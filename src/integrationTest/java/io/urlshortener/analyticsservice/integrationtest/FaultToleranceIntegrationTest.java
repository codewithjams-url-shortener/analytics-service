package io.urlshortener.analyticsservice.integrationtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.urlshortener.analyticsservice.integrationtest.config.IntegrationTestBootstrap;
import io.urlshortener.analyticsservice.integrationtest.config.LocalAnalyticsService;
import io.urlshortener.analyticsservice.integrationtest.config.LocalEnvironmentConfig;
import io.urlshortener.analyticsservice.integrationtest.constant.LinksApiConstants;
import io.urlshortener.analyticsservice.integrationtest.constant.StatsApiConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for what only the {@code local} profile can reach: the database, S3, and the analytics-service
 * process itself. They cover the guarantees that need a fault to be injected or the service to be restarted - that a
 * failed batch commits nothing and is counted exactly once on redelivery, that the stream consumer resumes from its
 * checkpoint instead of replaying, and what the mirror and the archives actually contain.
 *
 * <p>
 *     Skipped under the {@code deployed} profile, where none of that is available.
 *     The business flow itself is covered for both profiles by {@link StatsPipelineIntegrationTest}.
 * </p>
 *
 * @see LocalEnvironmentConfig
 */
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = "local")
@SpringBootTest(
		classes = IntegrationTestBootstrap.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE
)
public class FaultToleranceIntegrationTest extends IntegrationTestSupport {

	private static final String CLICK_EVENTS_PREFIX = "click-events/";

	private static final String LINK_LIFECYCLE_PREFIX = "links-lifecycle/";

	@Autowired
	private JdbcTemplate analyticsJdbcTemplate;

	@Autowired
	private S3Client archiveS3Client;

	@Autowired
	private LocalAnalyticsService analyticsService;

	@Value("${integration-test.local.bucket-name}")
	private String bucketName;

	@Test
	void record_shouldCommitNothingThenCountOnceOnRedelivery_whenTheDailyRollupFails() {

		// Arrange
		final String shortCode = uniqueAlias("fault");
		createLinkWithAlias("https://example.com/fault", shortCode);
		awaitStats(shortCode, body -> true, "the link to be mirrored from the stream");
		final String constraint = "it_fail_" + shortCode;
		// The daily rollup is the last write of the recording transaction. Failing it for this one short code means the
		// click_events insert and the link_stats upsert have already run when the transaction aborts.
		analyticsJdbcTemplate.execute(
				"ALTER TABLE link_daily_stats ADD CONSTRAINT %s CHECK (short_code <> '%s')".formatted(constraint, shortCode));

		try {

			// Act
			assertThat(click(shortCode)).isEqualTo(HttpStatus.FOUND);

			// Assert
			// Held for several delivery attempts (the queue's visibility timeout is a few seconds): nothing may be
			// committed, not even the writes that ran before the failing one.
			awaiting().during(Duration.ofSeconds(20)).atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
				assertThat(rowCount("click_events", shortCode)).as("click_events rows while the rollup fails").isZero();
				assertThat(rowCount("link_stats", shortCode)).as("link_stats rows while the rollup fails").isZero();
			});
			// The failing batches are visible to an operator, not just in the logs.
			assertThat(metricValue(getPrometheus(), "sqs_consumer_batches_total", "outcome=\"failure\"").orElse(0))
					.as("failed batches reported while the rollup fails").isGreaterThanOrEqualTo(1.0);

		} finally {
			analyticsJdbcTemplate.execute("ALTER TABLE link_daily_stats DROP CONSTRAINT IF EXISTS " + constraint);
		}

		// With the fault gone, the message that stayed on the queue is redelivered and counted.
		final JsonNode stats = awaitStats(shortCode,
				body -> count(body, StatsApiConstants.FIELD_RESOLVED) == 1, "the redelivered click to be counted");
		assertThat(count(stats, StatsApiConstants.FIELD_TOTAL_CLICKS)).isEqualTo(1);
		// And exactly once: no further redelivery may add to it.
		awaiting().during(Duration.ofSeconds(12)).atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
			assertThat(rowCount("click_events", shortCode)).isEqualTo(1);
			assertThat(count(getStats(shortCode).getBody(), StatsApiConstants.FIELD_RESOLVED)).isEqualTo(1);
		});

	}

	@Test
	void consumers_shouldResumeWithoutReplayingAnything_whenAnalyticsServiceIsRestarted() {

		// Arrange
		final String linkBeforeRestart = createLink("https://example.com/before-restart")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		click(linkBeforeRestart);
		awaitStats(linkBeforeRestart, body -> count(body, StatsApiConstants.FIELD_RESOLVED) == 1,
				"the click before the restart to be counted");
		awaiting().untilAsserted(() ->
				assertThat(archivedLines(LINK_LIFECYCLE_PREFIX, linkBeforeRestart))
						.as("lifecycle events archived for the link before the restart").isEqualTo(1));
		analyticsService.stop();
		final String linkWhileDown = createLink("https://example.com/while-down")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		click(linkWhileDown);

		// Act
		analyticsService.start();
		awaitStats(linkWhileDown, body -> count(body, StatsApiConstants.FIELD_RESOLVED) == 1,
				"the click made while the service was down to be counted");

		// Assert
		// The change made while the service was down is picked up...
		assertThat(archivedLines(LINK_LIFECYCLE_PREFIX, linkWhileDown)).isEqualTo(1);
		// ...and nothing from before the restart is read again: the stream resumed after its checkpoint (a replay from
		// the start of the stream would archive the first link's creation a second time) and the queue message was deleted.
		assertThat(archivedLines(LINK_LIFECYCLE_PREFIX, linkBeforeRestart)).isEqualTo(1);
		assertThat(archivedLines(CLICK_EVENTS_PREFIX, linkBeforeRestart)).isEqualTo(1);
		assertThat(rowCount("click_events", linkBeforeRestart)).isEqualTo(1);
		assertThat(count(getStats(linkBeforeRestart).getBody(), StatsApiConstants.FIELD_RESOLVED)).isEqualTo(1);

	}

	@Test
	void mirror_shouldRecordTheExplicitDeleteReason_whenLinkIsDeleted() {

		// Arrange
		final JsonNode created = createLink("https://example.com/mirror-delete");
		final String shortCode = created.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		final String managementToken = created.get(LinksApiConstants.FIELD_MANAGEMENT_TOKEN).asText();
		awaiting().untilAsserted(() -> assertThat(mirroredAndLive(shortCode))
				.as("the created link to be mirrored as live").isEqualTo(1));

		// Act
		deleteLink(shortCode, managementToken);

		// Assert
		// Soft-deleted rather than removed, with the reason that tells an explicit delete from a TTL expiry.
		awaiting().untilAsserted(() -> assertThat(deletionReasons(shortCode))
				.as("deletion reason of the mirrored link").containsExactly("EXPLICIT_DELETE"));

	}

	@Test
	void archives_shouldHoldTheClickAndLifecycleEvents_whenALinkIsCreatedAndClicked() {

		// Arrange
		final String shortCode = createLink("https://example.com/archived")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();

		// Act
		click(shortCode);

		// Assert
		awaiting().untilAsserted(() -> {
			assertThat(archivedLines(CLICK_EVENTS_PREFIX, shortCode))
					.as("click events archived to S3").isEqualTo(1);
			assertThat(archivedLines(LINK_LIFECYCLE_PREFIX, shortCode))
					.as("link lifecycle events archived to S3").isEqualTo(1);
		});

	}

	private long rowCount(final String table, final String shortCode) {
		return analyticsJdbcTemplate.queryForObject(
				"SELECT count(*) FROM %s WHERE short_code = ?".formatted(table), Long.class, shortCode);
	}

	private long mirroredAndLive(final String shortCode) {
		return analyticsJdbcTemplate.queryForObject(
				"SELECT count(*) FROM links WHERE short_code = ? AND deleted_at IS NULL", Long.class, shortCode);
	}

	private List<String> deletionReasons(final String shortCode) {
		return analyticsJdbcTemplate.queryForList(
				"SELECT deletion_reason FROM links WHERE short_code = ? AND deleted_at IS NOT NULL",
				String.class, shortCode);
	}

	/**
	 * Counts the archived NDJSON lines under a prefix that mention a short code, across every object there.
	 */
	private long archivedLines(final String prefix, final String shortCode) {
		final String needle = "\"shortCode\":\"%s\"".formatted(shortCode);
		long lines = 0;
		final List<S3Object> objects = archiveS3Client.listObjectsV2Paginator(
						ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).build())
				.contents()
				.stream()
				.toList();
		for (final S3Object object : objects) {
			final String body = archiveS3Client.getObject(
							GetObjectRequest.builder().bucket(bucketName).key(object.key()).build(),
							ResponseTransformer.toBytes())
					.asUtf8String();
			lines += body.lines().filter(line -> line.contains(needle)).count();
		}
		return lines;
	}

}
