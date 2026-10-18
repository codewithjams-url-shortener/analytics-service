package io.urlshortener.analyticsservice.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.servicecommon.MessageAttributeKeys;
import io.urlshortener.servicecommon.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Continues the trace redirect-service started, for each click event the SQS consumer processes (ADR-0006).
 *
 * <p>
 *     Trace context does not cross a queue hop on its own: redirect-service sends it as SNS message attributes, and
 *     this class reads it back and starts a {@code CONSUMER} span as a child of redirect-service's publish span, so one
 *     trace runs from the redirect request through to the processing here.
 * </p>
 *
 * <p>
 *     The real {@code sns-sqs} Terraform module does not enable raw message delivery, so SNS puts the message
 *     attributes inside the <em>envelope</em> in the SQS message body ({@code MessageAttributes.<name>.Value}), not on the
 *     SQS message's own attributes. The envelope is already being parsed for the event itself, so the context is read
 *     from there.
 * </p>
 *
 * <p>
 *     Tracing must never stop an event being counted: a message with missing or malformed context simply starts its own
 *     trace.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventTracing {

	private static final String SPAN_NAME = "click-events process";

	/**
	 * Starts the consumer span, and ends it again.
	 */
	private final Tracer tracer;

	/**
	 * Turns the context carried in the message back into a span builder with the remote parent set.
	 */
	private final Propagator propagator;

	/**
	 * Source of the configured queue URL, whose last segment names the queue on the span.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Reads the trace context redirect-service sent from an SNS envelope.
	 *
	 * @param envelope the parsed SNS notification envelope that is the SQS message body.
	 * @return the context; any field the message does not carry is {@code null}. Never {@code null} itself.
	 */
	public TraceContext contextOf(final JsonNode envelope) {
		final JsonNode attributes = envelope.path("MessageAttributes");
		return new TraceContext(
				attributeValue(attributes, MessageAttributeKeys.TRACE_PARENT),
				attributeValue(attributes, MessageAttributeKeys.TRACE_STATE),
				attributeValue(attributes, MessageAttributeKeys.CORRELATION_ID)
		);
	}

	/**
	 * Starts the span for processing one click event, as a child of the span that published it.
	 *
	 * <p>
	 *     Parented to the message's own context, not to whatever span is current on the polling thread, so each event
	 *     joins the trace of the redirect that produced it. A message with no usable context starts a new trace.
	 * </p>
	 *
	 * @param context the context read from the message.
	 * @param event   the click event being processed.
	 * @return the started span, which the caller must end with {@link #endAcknowledged} or {@link #endFailed}.
	 */
	public Span startConsumerSpan(final TraceContext context, final ClickEvent event) {
		final Span.Builder builder = remoteParent(context)
				.name(SPAN_NAME)
				.kind(Span.Kind.CONSUMER)
				.tag("messaging.system", "aws_sqs")
				.tag("messaging.destination.name", queueName())
				.tag("messaging.operation", "process")
				.tag("shortCode", event.shortCode());
		return builder.start();
	}

	/**
	 * Ends the span of an event whose message was acknowledged, logging that with the span current so the log line
	 * carries the trace's {@code traceId}.
	 *
	 * @param span    the span from {@link #startConsumerSpan}.
	 * @param event   the click event.
	 * @param context the context the message carried, whose correlation ID is logged beside the trace's own.
	 */
	public void endAcknowledged(final Span span, final ClickEvent event, final TraceContext context) {
		try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
			log.atDebug()
					.addKeyValue("eventId", event.eventId())
					.addKeyValue("shortCode", event.shortCode())
					.addKeyValue("outcome", event.outcome().toString())
					.addKeyValue("correlationId", context.correlationId())
					.log("Click event acknowledged");
		} finally {
			span.end();
		}
	}

	/**
	 * Ends the span of an event whose batch failed, marking it as an error and logging that with the span current.
	 *
	 * @param span  the span from {@link #startConsumerSpan}.
	 * @param event the click event.
	 * @param error why the batch failed.
	 */
	public void endFailed(final Span span, final ClickEvent event, final Throwable error) {
		span.error(error);
		try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
			log.atWarn()
					.addKeyValue("eventId", event.eventId())
					.addKeyValue("shortCode", event.shortCode())
					.log("Click event not acknowledged, its message stays on the queue to be redelivered");
		} finally {
			span.end();
		}
	}

	/**
	 * @param context the context read from the message.
	 * @return a span builder whose parent is the remote span in the context, or a builder for a new trace if there is
	 * none or it cannot be used.
	 */
	private Span.Builder remoteParent(final TraceContext context) {
		final Map<String, String> carrier = new HashMap<>();
		putIfPresent(carrier, MessageAttributeKeys.TRACE_PARENT, context.traceParent());
		putIfPresent(carrier, MessageAttributeKeys.TRACE_STATE, context.traceState());
		try {
			return propagator.extract(carrier, Map::get);
		} catch (RuntimeException e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("traceParent", context.traceParent())
					.log("Could not use the trace context carried by the message, starting a new trace");
			return tracer.spanBuilder();
		}
	}

	private void putIfPresent(final Map<String, String> carrier, final String key, final String value) {
		if (StringUtils.hasText(value)) {
			carrier.put(key, value);
		}
	}

	/**
	 * @param attributes the envelope's {@code MessageAttributes} node, possibly missing.
	 * @param key        the attribute name.
	 * @return its {@code Value}, or {@code null} if the attribute is absent, not text, or blank.
	 */
	private String attributeValue(final JsonNode attributes, final String key) {
		final JsonNode value = attributes.path(key).path("Value");
		return value.isTextual() && StringUtils.hasText(value.asText()) ? value.asText() : null;
	}

	/**
	 * @return the name of the click events queue, taken from the end of its configured URL.
	 */
	private String queueName() {
		final String queueUrl = awsProperties.getSqs().getQueueUrl();
		return queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
	}

}
