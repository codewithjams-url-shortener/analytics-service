package io.urlshortener.analyticsservice.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.eventcontracts.ClickOutcome;
import io.urlshortener.servicecommon.MessageAttributeKeys;
import io.urlshortener.servicecommon.TraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClickEventTracingTest {

	private static final String QUEUE_URL = "http://localhost:4566/000000000000/click-events-queue";

	private static final ClickEvent EVENT = new ClickEvent("event-1", "abc1234", 1_700_000_000_000L,
			ClickOutcome.RESOLVED, "google.com", "Mozilla/5.0", "hash123");

	private final ObjectMapper objectMapper = new ObjectMapper();

	private OtelTracingFixture tracing;

	private ClickEventTracing clickEventTracing;

	@BeforeEach
	void setUp() {
		tracing = new OtelTracingFixture();
		final AwsProperties.Sqs sqs = new AwsProperties.Sqs();
		sqs.setQueueUrl(QUEUE_URL);
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSqs(sqs);
		clickEventTracing = new ClickEventTracing(tracing.tracer(), tracing.propagator(), awsProperties);
	}

	@Test
	void contextOf_shouldReadAllThreeAttributesFromTheEnvelope_whenTheyArePresent() throws Exception {
		// Arrange
		final JsonNode envelope = objectMapper.readTree("""
				{"Type":"Notification","MessageAttributes":{
				  "traceparent":{"Type":"String","Value":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"},
				  "tracestate":{"Type":"String","Value":"vendor=value"},
				  "correlationId":{"Type":"String","Value":"4bf92f3577b34da6a3ce929d0e0e4736"}}}
				""");

		// Act
		final TraceContext context = clickEventTracing.contextOf(envelope);

		// Assert
		assertThat(context.traceParent()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
		assertThat(context.traceState()).isEqualTo("vendor=value");
		assertThat(context.correlationId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
	}

	@Test
	void contextOf_shouldReturnNullFields_whenTheEnvelopeCarriesNoAttributes() throws Exception {
		// Arrange
		final JsonNode envelope = objectMapper.readTree("{\"Type\":\"Notification\",\"Message\":\"{}\"}");

		// Act
		final TraceContext context = clickEventTracing.contextOf(envelope);

		// Assert
		assertThat(context).isEqualTo(new TraceContext(null, null, null));
	}

	@Test
	void contextOf_shouldIgnoreAnAttributeThatIsBlankOrNotText_whenTheEnvelopeIsOddlyShaped() throws Exception {
		// Arrange
		final JsonNode envelope = objectMapper.readTree("""
				{"MessageAttributes":{
				  "traceparent":{"Type":"String","Value":"   "},
				  "tracestate":{"Type":"Number","Value":42},
				  "correlationId":"not-an-object"}}
				""");

		// Act
		final TraceContext context = clickEventTracing.contextOf(envelope);

		// Assert
		assertThat(context).isEqualTo(new TraceContext(null, null, null));
	}

	@Test
	void startConsumerSpan_shouldContinueTheProducersTrace_whenTheMessageCarriesItsContext() {
		// Arrange
		final SpanData producer = finishedProducerSpan();
		final TraceContext context = contextOf(producer);

		// Act
		clickEventTracing.endAcknowledged(clickEventTracing.startConsumerSpan(context, EVENT), EVENT, context);

		// Assert
		final SpanData consumer = consumerSpan();
		assertThat(consumer.getTraceId()).isEqualTo(producer.getTraceId());
		assertThat(consumer.getParentSpanId()).isEqualTo(producer.getSpanId());
		assertThat(consumer.getParentSpanContext().isRemote()).isTrue();
	}

	@Test
	void startConsumerSpan_shouldStartAConsumerSpanDescribingTheQueue_whenStarted() {
		// Arrange
		final TraceContext context = contextOf(finishedProducerSpan());

		// Act
		clickEventTracing.endAcknowledged(clickEventTracing.startConsumerSpan(context, EVENT), EVENT, context);

		// Assert
		final SpanData consumer = consumerSpan();
		assertThat(consumer.getKind()).isEqualTo(SpanKind.CONSUMER);
		assertThat(consumer.getAttributes().get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws_sqs");
		assertThat(consumer.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
				.isEqualTo("click-events-queue");
		assertThat(consumer.getAttributes().get(AttributeKey.stringKey("messaging.operation"))).isEqualTo("process");
		assertThat(consumer.getAttributes().get(AttributeKey.stringKey("shortCode"))).isEqualTo("abc1234");
	}

	@Test
	void startConsumerSpan_shouldParentToTheMessagesContext_notToTheCurrentSpan() {
		// Arrange
		final SpanData producer = finishedProducerSpan();
		final TraceContext context = contextOf(producer);
		final Span pollingThreadSpan = tracing.tracer().nextSpan().name("scheduled poll").start();

		// Act
		try (Tracer.SpanInScope ignored = tracing.tracer().withSpan(pollingThreadSpan)) {
			clickEventTracing.endAcknowledged(clickEventTracing.startConsumerSpan(context, EVENT), EVENT, context);
		}

		// Assert
		// The span that happens to be current on the polling thread is unrelated to this event's trace.
		assertThat(consumerSpan().getTraceId()).isEqualTo(producer.getTraceId());
		assertThat(consumerSpan().getParentSpanId()).isEqualTo(producer.getSpanId());
	}

	@Test
	void startConsumerSpan_shouldStartANewTrace_whenTheMessageCarriesNoContext() {
		// Arrange
		final TraceContext none = new TraceContext(null, null, null);

		// Act
		clickEventTracing.endAcknowledged(clickEventTracing.startConsumerSpan(none, EVENT), EVENT, none);

		// Assert
		// An older message, or one sent without tracing, must still be processed - just not stitched to a trace.
		final SpanData consumer = consumerSpan();
		assertThat(consumer.getParentSpanContext().isValid()).isFalse();
		assertThat(consumer.getTraceId()).hasSize(32);
	}

	@Test
	void startConsumerSpan_shouldStartANewTraceInsteadOfFailing_whenTheTraceparentIsMalformed() {
		// Arrange
		final TraceContext garbage = new TraceContext("not-a-traceparent", "also=bad", null);

		// Act
		clickEventTracing.endAcknowledged(clickEventTracing.startConsumerSpan(garbage, EVENT), EVENT, garbage);

		// Assert
		assertThat(consumerSpan().getParentSpanContext().isValid()).isFalse();
	}

	@Test
	void endAcknowledged_shouldEndTheSpanWithoutAnError_whenTheMessageWasAcknowledged() {
		// Arrange
		final TraceContext context = contextOf(finishedProducerSpan());
		final Span span = clickEventTracing.startConsumerSpan(context, EVENT);

		// Act
		clickEventTracing.endAcknowledged(span, EVENT, context);

		// Assert
		// Only an ended span reaches the collector, so finding it at all proves it ended.
		assertThat(consumerSpan().getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
	}

	@Test
	void endFailed_shouldEndTheSpanAsAnError_whenTheBatchFailed() {
		// Arrange
		final TraceContext context = contextOf(finishedProducerSpan());
		final Span span = clickEventTracing.startConsumerSpan(context, EVENT);

		// Act
		clickEventTracing.endFailed(span, EVENT, new IllegalStateException("DB unavailable"));

		// Assert
		final SpanData consumer = consumerSpan();
		assertThat(consumer.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
		assertThat(consumer.getEvents()).anyMatch(event -> event.getName().equals("exception"));
	}

	/**
	 * Stands in for redirect-service's publish: a finished producer span on its own trace.
	 */
	private SpanData finishedProducerSpan() {
		final Span producer = tracing.tracer().spanBuilder()
				.name("click-events publish")
				.kind(Span.Kind.PRODUCER)
				.start();
		producer.end();
		return tracing.finishedSpans().getFirst();
	}

	/**
	 * What redirect-service would have put in the message attributes for that producer span.
	 */
	private TraceContext contextOf(final SpanData producer) {
		final Map<String, String> carrier = new HashMap<>();
		carrier.put(MessageAttributeKeys.TRACE_PARENT,
				"00-%s-%s-01".formatted(producer.getTraceId(), producer.getSpanId()));
		return new TraceContext(carrier.get(MessageAttributeKeys.TRACE_PARENT), null, producer.getTraceId());
	}

	private SpanData consumerSpan() {
		final List<SpanData> consumerSpans = tracing.finishedSpans().stream()
				.filter(span -> "click-events process".equals(span.getName()))
				.toList();
		assertThat(consumerSpans).hasSize(1);
		return consumerSpans.getFirst();
	}

}
