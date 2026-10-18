package io.urlshortener.analyticsservice.tracing;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A real Micrometer tracer and propagator on the OpenTelemetry SDK - the same bridge classes Spring Boot wires in
 * production - with an in-memory collector for the spans that finish.
 *
 * <p>
 *     Used instead of Micrometer's {@code SimpleTracer}, which hands out empty trace and span IDs and so cannot show
 *     what actually travels in the message attributes: the W3C {@code traceparent} format, and which span's context it
 *     carries.
 * </p>
 */
public final class OtelTracingFixture {

	private final List<SpanData> finishedSpans = Collections.synchronizedList(new ArrayList<>());

	private final Tracer tracer;

	private final Propagator propagator;

	/**
	 * @param extraPropagators propagators written alongside the W3C trace context, to prove that only the keys the
	 *                         shared shape defines are sent.
	 */
	public OtelTracingFixture(final TextMapPropagator... extraPropagators) {
		final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
				.addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter()))
				.build();
		final io.opentelemetry.api.trace.Tracer otelTracer = tracerProvider.get("test");
		final List<TextMapPropagator> propagators = new ArrayList<>();
		propagators.add(W3CTraceContextPropagator.getInstance());
		propagators.addAll(List.of(extraPropagators));
		this.tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
		this.propagator = new OtelPropagator(
				ContextPropagators.create(TextMapPropagator.composite(propagators)), otelTracer);
	}

	public Tracer tracer() {
		return tracer;
	}

	public Propagator propagator() {
		return propagator;
	}

	/**
	 * @return the spans that have ended so far, oldest first.
	 */
	public List<SpanData> finishedSpans() {
		synchronized (finishedSpans) {
			return List.copyOf(finishedSpans);
		}
	}

	private final class CollectingExporter implements SpanExporter {

		@Override
		public CompletableResultCode export(final Collection<SpanData> spans) {
			finishedSpans.addAll(spans);
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode flush() {
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode shutdown() {
			return CompletableResultCode.ofSuccess();
		}

	}

}
