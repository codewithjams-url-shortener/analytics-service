package io.urlshortener.analyticsservice.integrationtest;

import io.urlshortener.analyticsservice.integrationtest.config.IntegrationTestBootstrap;
import io.urlshortener.servicecommon.HttpMetricContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks, against a real analytics-service process and floci's CloudWatch, that the service publishes the response
 * counts the AppConfig rollback alarm reads (ADR-0011): requests are sent over HTTP, one of them answered with a
 * genuine 500, and the counts are read back from CloudWatch under the shared metric contract.
 *
 * <p>
 *     The 500 comes from a real fault, not a test-only hook: for a moment the table the stats are read from is renamed
 *     away, the way {@link FaultToleranceIntegrationTest} injects its faults, so the stats query fails with a database
 *     error. Runs only under the {@code local} profile, as it needs that database and the floci that profile launches.
 *     The launched service publishes every second rather than every minute so the test does not have to wait a minute.
 * </p>
 *
 * <p>
 *     The counts only ever grow and other tests share the running service, so the test compares what CloudWatch holds
 *     before and after its own requests and asserts at least what it sent.
 * </p>
 */
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = "local")
@SpringBootTest(
		classes = IntegrationTestBootstrap.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE
)
public class HttpMetricsIntegrationTest extends IntegrationTestSupport {

	/**
	 * The service's name, which it publishes its counts under: its {@code spring.application.name}.
	 */
	private static final String SERVICE_NAME = "analytics-service";

	private static final String STATS_TABLE = "link_stats";

	private static final String HIDDEN_STATS_TABLE = "link_stats_hidden_by_it";

	@Autowired
	private CloudWatchClient cloudWatchClient;

	@Autowired
	private JdbcTemplate analyticsJdbcTemplate;

	@Test
	void service_shouldPublishRequestAndServerErrorCountsToCloudWatch_whenItServedRequestsIncludingA500() {

		// Arrange
		final double errorsBefore = sum(HttpMetricContract.HTTP_5XX_COUNT);
		final double requestsBefore = sum(HttpMetricContract.HTTP_REQUEST_COUNT);

		// Act
		final HttpStatus firstMissing = HttpStatus.valueOf(getStats(uniqueAlias("missing")).getStatusCode().value());
		final HttpStatus secondMissing = HttpStatus.valueOf(getStats(uniqueAlias("missing")).getStatusCode().value());
		final HttpStatus whileTheStatsTableIsGone = statsStatusWithTheStatsTableRenamedAway();

		// Assert
		assertThat(firstMissing).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(secondMissing).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(whileTheStatsTableIsGone)
				.as("a failing stats query is a genuine server error")
				.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		awaiting().untilAsserted(() -> {
			final double errors = sum(HttpMetricContract.HTTP_5XX_COUNT) - errorsBefore;
			final double requests = sum(HttpMetricContract.HTTP_REQUEST_COUNT) - requestsBefore;
			assertThat(errors).as("5xx responses published").isGreaterThanOrEqualTo(1.0);
			assertThat(requests).as("responses published, 4xx and 5xx included").isGreaterThanOrEqualTo(3.0);
			assertThat(errors).as("5xx responses never exceed all responses").isLessThanOrEqualTo(requests);
		});

	}

	/**
	 * Asks for a short code's stats while the table they are read from is renamed away, and puts it back afterwards
	 * whatever happens.
	 */
	private HttpStatus statsStatusWithTheStatsTableRenamedAway() {
		analyticsJdbcTemplate.execute("ALTER TABLE %s RENAME TO %s".formatted(STATS_TABLE, HIDDEN_STATS_TABLE));
		try {
			return HttpStatus.valueOf(getStats(uniqueAlias("unreadable")).getStatusCode().value());
		} finally {
			analyticsJdbcTemplate.execute("ALTER TABLE %s RENAME TO %s".formatted(HIDDEN_STATS_TABLE, STATS_TABLE));
		}
	}

	/**
	 * Everything CloudWatch holds for the metric under this service's dimension, over a window wide enough to cover the
	 * whole test run.
	 */
	private double sum(final String metricName) {
		return cloudWatchClient.getMetricStatistics(GetMetricStatisticsRequest.builder()
						.namespace(HttpMetricContract.NAMESPACE)
						.metricName(metricName)
						.dimensions(Dimension.builder()
								.name(HttpMetricContract.SERVICE_DIMENSION)
								.value(SERVICE_NAME)
								.build())
						.startTime(Instant.now().minus(Duration.ofHours(1)))
						.endTime(Instant.now().plus(Duration.ofMinutes(1)))
						.period(60)
						.statistics(Statistic.SUM)
						.build())
				.datapoints().stream()
				.mapToDouble(Datapoint::sum)
				.sum();
	}

}
