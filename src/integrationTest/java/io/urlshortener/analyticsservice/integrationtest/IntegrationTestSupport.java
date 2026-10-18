package io.urlshortener.analyticsservice.integrationtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.urlshortener.analyticsservice.integrationtest.constant.LinksApiConstants;
import io.urlshortener.analyticsservice.integrationtest.constant.StatsApiConstants;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared plumbing for the integration tests: the three HTTP clients, helpers that generate traffic through
 * {@code url-service} and {@code redirect-service}, and polling helpers for reading the result from
 * {@code analytics-service}.
 *
 * <p>
 *     Everything here goes through HTTP, so it behaves identically under the {@code local} and {@code deployed}
 *     profiles. Requests and responses are handled as raw JSON ({@link JsonNode}) rather than any service's own
 *     generated DTOs, so this suite has no compile-time dependency on the applications it tests.
 * </p>
 *
 * <p>
 *     Clicks reach the stats asynchronously (the SQS consumer polls roughly every 5 seconds, then waits 5 seconds
 *     between polls), so assertions on counts use {@link #awaitStats} rather than a fixed sleep.
 * </p>
 */
public abstract class IntegrationTestSupport {

	private static final String LINK_WITH_LONG_URL_TEMPLATE = loadRequestTemplate("link-with-long-url.json");

	private static final String LINK_WITH_ALIAS_TEMPLATE = loadRequestTemplate("link-with-long-url-and-alias.json");

	private static final String LINK_WITH_EXPIRES_AT_TEMPLATE =
			loadRequestTemplate("link-with-long-url-and-expires-at.json");

	/**
	 * Client for analytics-service, the system under test.
	 */
	@Autowired
	protected RestClient analyticsApiClient;

	/**
	 * Client for url-service, used to create and delete links.
	 */
	@Autowired
	protected RestClient linksApiClient;

	/**
	 * Client for redirect-service, used to click links.
	 */
	@Autowired
	protected RestClient redirectApiClient;

	@Value("${integration-test.await.timeout}")
	private Duration awaitTimeout;

	@Value("${integration-test.await.poll-interval}")
	private Duration awaitPollInterval;

	/**
	 * Creates a link through url-service.
	 *
	 * @param longUrl the destination URL.
	 * @return the create response, holding the short code and management token.
	 */
	protected JsonNode createLink(final String longUrl) {
		return create(LINK_WITH_LONG_URL_TEMPLATE.formatted(longUrl));
	}

	/**
	 * Creates a link with a chosen alias through url-service.
	 *
	 * @param longUrl the destination URL.
	 * @param alias   the custom alias, which becomes the short code. Alphanumeric, 3 to 32 characters.
	 * @return the create response, holding the short code and management token.
	 */
	protected JsonNode createLinkWithAlias(final String longUrl, final String alias) {
		return create(LINK_WITH_ALIAS_TEMPLATE.formatted(longUrl, alias));
	}

	/**
	 * Creates a link that expires at the given time through url-service.
	 *
	 * @param longUrl   the destination URL.
	 * @param expiresAt when the link expires. Must be in the future at request time.
	 * @return the create response, holding the short code and management token.
	 */
	protected JsonNode createLinkExpiringAt(final String longUrl, final Instant expiresAt) {
		return create(LINK_WITH_EXPIRES_AT_TEMPLATE.formatted(longUrl, expiresAt));
	}

	/**
	 * Deletes a link through url-service.
	 *
	 * @param shortCode       the link's short code.
	 * @param managementToken the token returned when it was created.
	 */
	protected void deleteLink(final String shortCode, final String managementToken) {
		final ResponseEntity<Void> response = linksApiClient.delete()
				.uri("/links/{shortCode}", shortCode)
				.header(LinksApiConstants.HEADER_MANAGEMENT_TOKEN, managementToken)
				.retrieve()
				.toBodilessEntity();
		assertThat(response.getStatusCode().is2xxSuccessful())
				.as("DELETE /links/%s should succeed, was %s", shortCode, response.getStatusCode())
				.isTrue();
	}

	/**
	 * Clicks a short code through redirect-service, which publishes a click event whatever the outcome.
	 *
	 * @param shortCode the short code to click.
	 * @return the HTTP status redirect-service answered with.
	 */
	protected HttpStatusCode click(final String shortCode) {
		return redirectApiClient.get()
				.uri("/{shortCode}", shortCode)
				.retrieve()
				.toBodilessEntity()
				.getStatusCode();
	}

	/**
	 * Reads a short code's stats with the default date range.
	 *
	 * @param shortCode the short code.
	 * @return the response, whatever its status.
	 */
	protected ResponseEntity<JsonNode> getStats(final String shortCode) {
		return analyticsApiClient.get()
				.uri("/links/{shortCode}/stats", shortCode)
				.retrieve()
				.toEntity(JsonNode.class);
	}

	/**
	 * Reads a short code's stats with explicit query parameters, passed through exactly as given so malformed values
	 * can be tested.
	 *
	 * @param shortCode the short code.
	 * @param from      the {@code from} parameter, or {@code null} to omit it.
	 * @param to        the {@code to} parameter, or {@code null} to omit it.
	 * @return the response, whatever its status.
	 */
	protected ResponseEntity<JsonNode> getStats(final String shortCode, final String from, final String to) {
		return analyticsApiClient.get()
				.uri(uriBuilder -> {
					uriBuilder.path("/links/{shortCode}/stats");
					if (from != null) {
						uriBuilder.queryParam("from", from);
					}
					if (to != null) {
						uriBuilder.queryParam("to", to);
					}
					return uriBuilder.build(shortCode);
				})
				.retrieve()
				.toEntity(JsonNode.class);
	}

	/**
	 * Reads analytics-service's Prometheus scrape, as Prometheus itself would.
	 *
	 * @return the scrape in the Prometheus text format.
	 */
	protected String getPrometheus() {
		return analyticsApiClient.get()
				.uri("/actuator/prometheus")
				.retrieve()
				.body(String.class);
	}

	/**
	 * Finds one sample in a Prometheus scrape.
	 *
	 * @param scrape        the scrape text.
	 * @param name          the exact sample name, such as {@code sqs_consumer_batches_total}.
	 * @param labelFragment text the sample's labels must contain, such as {@code outcome="success"}, or {@code null} to
	 *                      take the first sample with that name.
	 * @return the sample's value, or empty if there is no such sample.
	 */
	protected static OptionalDouble metricValue(final String scrape, final String name, final String labelFragment) {
		return scrape.lines()
				.filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
				.filter(line -> labelFragment == null || line.contains(labelFragment))
				.findFirst()
				.map(line -> OptionalDouble.of(Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))))
				.orElse(OptionalDouble.empty());
	}

	/**
	 * Polls a short code's stats until they answer {@code 200} and satisfy the condition.
	 *
	 * @param shortCode   the short code.
	 * @param condition   what the stats body must satisfy.
	 * @param description what is being waited for, shown if it never happens.
	 * @return the first matching stats body.
	 */
	protected JsonNode awaitStats(final String shortCode, final Predicate<JsonNode> condition,
								  final String description) {
		final JsonNode[] matched = new JsonNode[1];
		awaiting()
				.alias("%s for %s".formatted(description, shortCode))
				.untilAsserted(() -> {
					final ResponseEntity<JsonNode> response = getStats(shortCode);
					assertThat(response.getStatusCode().value())
							.as("GET /links/%s/stats while waiting for: %s", shortCode, description)
							.isEqualTo(200);
					assertThat(condition.test(response.getBody()))
							.as("stats of %s while waiting for: %s, last seen: %s",
									shortCode, description, response.getBody())
							.isTrue();
					matched[0] = response.getBody();
				});
		return matched[0];
	}

	/**
	 * @return an Awaitility condition factory with the configured timeout and polling interval.
	 */
	protected ConditionFactory awaiting() {
		return Awaitility.await().atMost(awaitTimeout).pollInterval(awaitPollInterval);
	}

	/**
	 * Waits for a fixed time without blocking the thread on {@code Thread.sleep}, for the few cases where the passage of
	 * time itself is what is being tested, such as a link expiring.
	 *
	 * @param pause how long to wait.
	 */
	protected void pauseFor(final Duration pause) {
		Awaitility.await().pollDelay(pause).atMost(pause.plusSeconds(5)).until(() -> true);
	}

	/**
	 * @param stats a stats response body.
	 * @return the sum of the RESOLVED clicks across every day of the breakdown.
	 */
	protected static long sumOfDailyClicks(final JsonNode stats) {
		long sum = 0;
		final Iterator<JsonNode> days = stats.get(StatsApiConstants.FIELD_DAILY).elements();
		while (days.hasNext()) {
			sum += days.next().get(StatsApiConstants.FIELD_CLICKS).asLong();
		}
		return sum;
	}

	/**
	 * @param stats a stats response body.
	 * @param field one of the all-time count fields.
	 * @return its value.
	 */
	protected static long count(final JsonNode stats, final String field) {
		return stats.get(field).asLong();
	}

	/**
	 * @param prefix a readable prefix.
	 * @return a short code that is unique to this run, alphanumeric, within url-service's 3 to 32 character limit.
	 */
	protected static String uniqueAlias(final String prefix) {
		return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	/**
	 * @param field a date field of a stats response, such as {@code from}.
	 * @param stats the response body.
	 * @return the date.
	 */
	protected static LocalDate date(final JsonNode stats, final String field) {
		return LocalDate.parse(stats.get(field).asText());
	}

	private JsonNode create(final String body) {
		final ResponseEntity<JsonNode> response = linksApiClient.post()
				.uri("/links")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.toEntity(JsonNode.class);
		assertThat(response.getStatusCode().is2xxSuccessful())
				.as("POST /links with %s should succeed, was %s: %s", body, response.getStatusCode(), response.getBody())
				.isTrue();
		return response.getBody();
	}

	private static String loadRequestTemplate(final String fileName) {
		final ClassPathResource resource = new ClassPathResource("requests/" + fileName);
		try {
			return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load request template: %s".formatted(fileName), e);
		}
	}

}
