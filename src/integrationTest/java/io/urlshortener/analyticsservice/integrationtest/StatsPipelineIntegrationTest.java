package io.urlshortener.analyticsservice.integrationtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.urlshortener.analyticsservice.integrationtest.config.DeployedEnvironmentConfig;
import io.urlshortener.analyticsservice.integrationtest.config.IntegrationTestBootstrap;
import io.urlshortener.analyticsservice.integrationtest.config.LocalEnvironmentConfig;
import io.urlshortener.analyticsservice.integrationtest.constant.LinksApiConstants;
import io.urlshortener.analyticsservice.integrationtest.constant.StatsApiConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the whole analytics pipeline as seen through {@code GET /links/{shortCode}/stats}, shared
 * between two flavors selected via {@code spring.profiles.active}:
 *
 * <ul>
 *     <li>
 *         {@code local} ({@link LocalEnvironmentConfig}) - launches the built {@code analytics-service},
 *         {@code url-service} and {@code redirect-service} jars as separate processes against Testcontainers-managed
 *         floci, PostgreSQL and Redis.
 *     </li>
 *     <li>
 *         {@code deployed} ({@link DeployedEnvironmentConfig}) - hits already-deployed instances of the three services.
 *     </li>
 * </ul>
 *
 * <p>
 *     Traffic is generated only through url-service (links) and redirect-service (clicks), so these tests exercise the
 *     real redirect-service to SNS to SQS to analytics-service path and the real DynamoDB Streams change data capture,
 *     and read the outcome only from the stats endpoint. A link that was created and never clicked answering
 *     {@code 200} with zeros is how the change data capture is observed: the endpoint knows the link only from the
 *     mirror the stream consumer fills.
 * </p>
 *
 * <p>
 *     What needs direct database, S3 or process access (fault injection, restart, archive and mirror contents) is in
 *     {@link FaultToleranceIntegrationTest}, which runs under the {@code local} profile only.
 * </p>
 */
@SpringBootTest(
		classes = IntegrationTestBootstrap.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE
)
public class StatsPipelineIntegrationTest extends IntegrationTestSupport {

	@Test
	void getLinkStats_shouldReturn404_whenShortCodeNeverExisted() {

		// Arrange
		final String shortCode = uniqueAlias("missing");

		// Act
		final ResponseEntity<JsonNode> response = getStats(shortCode);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody().get(StatsApiConstants.FIELD_MESSAGE).asText()).contains(shortCode);

	}

	@Test
	void getLinkStats_shouldReturn200WithZeroTotals_whenLinkWasCreatedButNeverClicked() {

		// Arrange
		final String shortCode = createLink("https://example.com/never-clicked")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();

		// Act
		// The link is known to analytics-service only once the stream consumer has mirrored its creation.
		final JsonNode stats = awaitStats(shortCode, body -> true, "the created link to be mirrored from the stream");

		// Assert
		assertThat(count(stats, StatsApiConstants.FIELD_TOTAL_CLICKS)).isZero();
		assertThat(count(stats, StatsApiConstants.FIELD_RESOLVED)).isZero();
		assertThat(count(stats, StatsApiConstants.FIELD_EXPIRED)).isZero();
		assertThat(count(stats, StatsApiConstants.FIELD_NOT_FOUND)).isZero();
		assertThat(stats.get(StatsApiConstants.FIELD_LAST_CLICKED_AT).isNull())
				.as("lastClickedAt of a link that was never clicked")
				.isTrue();
		assertThat(stats.get(StatsApiConstants.FIELD_DAILY)).hasSize(30);
		assertThat(sumOfDailyClicks(stats)).isZero();

	}

	@Test
	void getLinkStats_shouldCountResolvedClicks_whenLinkIsClicked() {

		// Arrange
		final String shortCode = createLink("https://example.com/clicked")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();

		// Act
		for (int i = 0; i < 3; i++) {
			assertThat(click(shortCode)).isEqualTo(HttpStatus.FOUND);
		}
		final JsonNode stats = awaitStats(shortCode,
				body -> count(body, StatsApiConstants.FIELD_RESOLVED) == 3, "three resolved clicks to be counted");

		// Assert
		assertThat(count(stats, StatsApiConstants.FIELD_TOTAL_CLICKS)).isEqualTo(3);
		assertThat(count(stats, StatsApiConstants.FIELD_EXPIRED)).isZero();
		assertThat(count(stats, StatsApiConstants.FIELD_NOT_FOUND)).isZero();
		assertThat(stats.get(StatsApiConstants.FIELD_LAST_CLICKED_AT).isNull()).isFalse();
		// Summed over the breakdown rather than read from "today", so a run that straddles midnight UTC still passes.
		assertThat(sumOfDailyClicks(stats)).isEqualTo(3);

	}

	@Test
	void getLinkStats_shouldCountNotFoundClicks_whenShortCodeDoesNotExist() {

		// Arrange
		final String shortCode = uniqueAlias("ghost");

		// Act
		assertThat(click(shortCode)).isEqualTo(HttpStatus.NOT_FOUND);
		final JsonNode stats = awaitStats(shortCode,
				body -> count(body, StatsApiConstants.FIELD_NOT_FOUND) == 1, "the not-found click to be counted");

		// Assert
		// A short code with click totals is known even though no link was ever created for it.
		assertThat(count(stats, StatsApiConstants.FIELD_RESOLVED)).isZero();
		assertThat(count(stats, StatsApiConstants.FIELD_EXPIRED)).isZero();
		assertThat(stats.get(StatsApiConstants.FIELD_LAST_CLICKED_AT).isNull()).isFalse();

	}

	@Test
	void getLinkStats_shouldCountExpiredClicks_whenLinkHasExpired() {

		// Arrange
		final String shortCode = createLinkExpiringAt("https://example.com/will-expire",
				Instant.now().plus(3, ChronoUnit.SECONDS))
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		pauseFor(Duration.ofSeconds(5));

		// Act
		assertThat(click(shortCode)).isEqualTo(HttpStatus.NOT_FOUND);
		final JsonNode stats = awaitStats(shortCode,
				body -> count(body, StatsApiConstants.FIELD_EXPIRED) == 1, "the expired click to be counted");

		// Assert
		assertThat(count(stats, StatsApiConstants.FIELD_RESOLVED)).isZero();
		assertThat(count(stats, StatsApiConstants.FIELD_NOT_FOUND)).isZero();

	}

	@Test
	void getLinkStats_shouldKeepTheClickHistory_whenLinkIsDeletedAfterBeingClicked() {

		// Arrange
		final JsonNode created = createLink("https://example.com/deleted-later");
		final String shortCode = created.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		final String managementToken = created.get(LinksApiConstants.FIELD_MANAGEMENT_TOKEN).asText();
		click(shortCode);
		click(shortCode);
		awaitStats(shortCode, body -> count(body, StatsApiConstants.FIELD_RESOLVED) == 2, "two clicks to be counted");

		// Act
		deleteLink(shortCode, managementToken);
		pauseFor(Duration.ofSeconds(4));
		final ResponseEntity<JsonNode> response = getStats(shortCode);

		// Assert
		// A deleted link is soft-deleted in the mirror and its click history is untouched.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(count(response.getBody(), StatsApiConstants.FIELD_RESOLVED)).isEqualTo(2);

	}

	@Test
	void getLinkStats_shouldHonourTheRequestedRange_whenFromAndToAreGiven() {

		// Arrange
		final String shortCode = createLink("https://example.com/ranged")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		click(shortCode);
		awaitStats(shortCode, body -> count(body, StatsApiConstants.FIELD_RESOLVED) == 1, "the click to be counted");
		final LocalDate today = LocalDate.now(ZoneOffset.UTC);
		final LocalDate from = today.minusDays(9);
		final LocalDate to = today.plusDays(1);

		// Act
		final ResponseEntity<JsonNode> response = getStats(shortCode, from.toString(), to.toString());

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		final JsonNode stats = response.getBody();
		assertThat(date(stats, StatsApiConstants.FIELD_FROM)).isEqualTo(from);
		assertThat(date(stats, StatsApiConstants.FIELD_TO)).isEqualTo(to);
		assertThat(stats.get(StatsApiConstants.FIELD_DAILY)).hasSize(11);
		assertThat(sumOfDailyClicks(stats)).isEqualTo(1);

	}

	@Test
	void getLinkStats_shouldReturn400_whenFromIsAfterTo() {

		// Act
		final ResponseEntity<JsonNode> response = getStats("abc1234", "2024-03-20", "2024-03-19");

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().get(StatsApiConstants.FIELD_MESSAGE).asText()).contains("after");

	}

	@Test
	void getLinkStats_shouldReturn400_whenRangeExceeds366Days() {

		// Act
		final ResponseEntity<JsonNode> response = getStats("abc1234", "2020-01-01", "2024-03-19");

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().get(StatsApiConstants.FIELD_MESSAGE).asText()).contains("366");

	}

	@Test
	void getLinkStats_shouldReturn400WithAnErrorBody_whenADateIsMalformed() {

		// Act
		final ResponseEntity<JsonNode> response = getStats("abc1234", "abc", null);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().get(StatsApiConstants.FIELD_MESSAGE).asText())
				.isEqualTo("from: must be a date in yyyy-MM-dd format");

	}

	@Test
	void getLinkStats_shouldReturn400_whenShortCodeIsTooShort() {

		// Act
		final ResponseEntity<JsonNode> response = getStats("ab");

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().get(StatsApiConstants.FIELD_MESSAGE).asText()).startsWith("shortCode");

	}

}
