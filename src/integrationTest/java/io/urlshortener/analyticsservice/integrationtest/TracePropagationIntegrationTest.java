package io.urlshortener.analyticsservice.integrationtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.urlshortener.analyticsservice.integrationtest.config.IntegrationTestBootstrap;
import io.urlshortener.analyticsservice.integrationtest.config.LocalEnvironmentConfig;
import io.urlshortener.analyticsservice.integrationtest.constant.LinksApiConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that one trace crosses Redirect -> SNS -> SQS -> Analytics (ADR-0006), as Jaeger sees it.
 *
 * <p>
 *     A link is clicked through redirect-service, and the spans the three launched services exported to a real Jaeger
 *     are read back through its query API. The consumer span in analytics-service must sit in the same trace as the
 *     redirect, as a child of redirect-service's publish span: proof that the context travelled in the SNS message
 *     attributes and was restored from inside the SNS envelope on the other side.
 * </p>
 *
 * <p>
 *     Runs under the {@code local} profile only, since it needs the Jaeger container and the analytics-service log
 *     file ({@link LocalEnvironmentConfig}).
 * </p>
 */
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = "local")
@SpringBootTest(
		classes = IntegrationTestBootstrap.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE
)
public class TracePropagationIntegrationTest extends IntegrationTestSupport {

	private static final String REDIRECT_SERVICE = "redirect-service";

	private static final String ANALYTICS_SERVICE = "analytics-service";

	private static final String PUBLISH_OPERATION = "click-events publish";

	private static final String PROCESS_OPERATION = "click-events process";

	private static final Path ANALYTICS_LOG = Path.of("build/integration-test-logs/analytics-service.log");

	@Autowired
	private RestClient jaegerApiClient;

	@Test
	void trace_shouldCrossRedirectSnsSqsAndAnalytics_whenALinkIsClicked() {

		// Arrange
		final String shortCode = createLink("https://example.com/traced")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();

		// Act
		assertThat(click(shortCode)).isEqualTo(HttpStatus.FOUND);

		// Assert
		// Spans are exported in batches every few seconds, so the trace takes a moment to appear in Jaeger.
		final JsonNode trace = awaitTrace(shortCode);
		final List<Span> spans = spansOf(trace);

		final Span consumer = onlySpan(spans, ANALYTICS_SERVICE, PROCESS_OPERATION);
		final Span publish = onlySpan(spans, REDIRECT_SERVICE, PUBLISH_OPERATION);
		assertThat(consumer.parentSpanId())
				.as("the analytics consumer span is a child of redirect-service's publish span")
				.isEqualTo(publish.spanId());
		assertThat(publish.parentSpanId())
				.as("the publish span is itself inside the redirect request's span, in the same trace")
				.isNotNull();
		assertThat(spans.stream().filter(span -> span.spanId().equals(publish.parentSpanId())).findFirst())
				.hasValueSatisfying(requestSpan -> assertThat(requestSpan.service()).isEqualTo(REDIRECT_SERVICE));
		assertThat(servicesOf(spans)).contains(REDIRECT_SERVICE, ANALYTICS_SERVICE);

	}

	@Test
	void consumerLogs_shouldCarryTheTraceIdOfTheRedirect_whenALinkIsClicked() {

		// Arrange
		final String shortCode = createLink("https://example.com/traced-logs")
				.get(LinksApiConstants.FIELD_SHORT_CODE).asText();

		// Act
		click(shortCode);
		final String traceId = awaitTrace(shortCode).get("traceID").asText();

		// Assert
		// The point of continuing the trace: a log search by the trace ID now finds the analytics side too.
		awaiting().untilAsserted(() -> assertThat(readLines(ANALYTICS_LOG))
				.as("analytics-service log lines carrying trace %s", traceId)
				.anyMatch(line -> line.contains("\"traceId\":\"" + traceId + "\"")
						&& line.contains("Click event acknowledged")
						&& line.contains("\"shortCode\":\"" + shortCode + "\"")));

	}

	/**
	 * Waits for the trace of a click to appear in Jaeger, found by the short code tag on the analytics consumer span.
	 */
	private JsonNode awaitTrace(final String shortCode) {
		final JsonNode[] found = new JsonNode[1];
		awaiting().alias("a trace of %s in Jaeger".formatted(shortCode)).untilAsserted(() -> {
			final JsonNode response = jaegerApiClient.get()
					.uri(uriBuilder -> uriBuilder.path("/api/traces")
							.queryParam("service", ANALYTICS_SERVICE)
							.queryParam("operation", PROCESS_OPERATION)
							// A template variable, because the JSON's braces would otherwise be read as one.
							.queryParam("tags", "{tags}")
							.queryParam("limit", 5)
							.build("{\"shortCode\":\"%s\"}".formatted(shortCode)))
					.retrieve()
					.body(JsonNode.class);
			assertThat(response.path("data").size())
					.as("traces in Jaeger for short code %s", shortCode)
					.isGreaterThanOrEqualTo(1);
			final JsonNode candidate = response.path("data").get(0);
			// The consumer span exports from analytics-service and the publish span from redirect-service, separately.
			assertThat(servicesOf(spansOf(candidate)))
					.as("services seen in the trace so far")
					.contains(REDIRECT_SERVICE, ANALYTICS_SERVICE);
			found[0] = candidate;
		});
		return found[0];
	}

	private List<Span> spansOf(final JsonNode trace) {
		final List<Span> spans = new ArrayList<>();
		for (final JsonNode span : trace.path("spans")) {
			final String service = trace.path("processes").path(span.path("processID").asText())
					.path("serviceName").asText();
			String parent = null;
			for (final JsonNode reference : span.path("references")) {
				if ("CHILD_OF".equals(reference.path("refType").asText())) {
					parent = reference.path("spanID").asText();
				}
			}
			spans.add(new Span(span.path("spanID").asText(), parent, service, span.path("operationName").asText()));
		}
		return spans;
	}

	private Set<String> servicesOf(final List<Span> spans) {
		final Set<String> services = new HashSet<>();
		spans.forEach(span -> services.add(span.service()));
		return services;
	}

	private Span onlySpan(final List<Span> spans, final String service, final String operation) {
		final List<Span> matching = spans.stream()
				.filter(span -> span.service().equals(service) && span.operation().equals(operation))
				.toList();
		assertThat(matching).as("spans named '%s' from %s in the trace", operation, service).hasSize(1);
		return matching.getFirst();
	}

	private List<String> readLines(final Path file) {
		try {
			return Files.readAllLines(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read %s".formatted(file), e);
		}
	}

	/**
	 * One span as Jaeger returns it, reduced to what the assertions need.
	 */
	private record Span(String spanId, String parentSpanId, String service, String operation) {
	}

}
