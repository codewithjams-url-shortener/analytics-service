package io.urlshortener.analyticsservice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.urlshortener.servicecommon.HttpMetricContract;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Publishes how many responses this service sent, and how many of them were 5xx, to CloudWatch once per interval, so
 * the per-service 5xx-rate alarm can roll back a bad AppConfig deployment. The metric names, namespace and dimension
 * are the shared contract in {@link HttpMetricContract}, which the alarm in {@code url-shortener-infra} reads.
 *
 * <p>
 *     The counts come from Micrometer's {@code http.server.requests} timers rather than a servlet filter of its own, so
 *     they cover every response Spring MVC recorded, including ones produced by exception handlers. Each timer is a
 *     cumulative count, so the publisher remembers what it last sent and sends the difference. Errors and the total are
 *     summed in the same pass over the timers, so the errors can never exceed the total.
 * </p>
 *
 * <p>
 *     Requests under {@code /actuator} are left out. Kubernetes probes and Prometheus scrapes hit them constantly;
 *     they would dilute the 5xx rate and, on their own, meet the alarm's minimum request count on a service nobody is
 *     using.
 * </p>
 *
 * <p>
 *     Publishing runs on the scheduler's thread, never on a request thread, and never throws: a CloudWatch outage must
 *     not take the service down. When a publish fails the remembered counts are not advanced, so the next publish
 *     carries what was missed rather than losing the 5xx evidence.
 * </p>
 */
@Slf4j
public class HttpMetricsCloudWatchPublisher {

	/**
	 * Micrometer's timer for every server request Spring MVC handles.
	 */
	static final String METER_NAME = "http.server.requests";

	static final String URI_TAG = "uri";

	static final String OUTCOME_TAG = "outcome";

	/**
	 * The {@code outcome} tag value Micrometer gives every 5xx response.
	 */
	private static final String SERVER_ERROR_OUTCOME = "SERVER_ERROR";

	private static final String ACTUATOR_URI_PREFIX = "/actuator";

	private final MeterRegistry meterRegistry;

	private final CloudWatchClient cloudWatchClient;

	private final Clock clock;

	private final String serviceName;

	/**
	 * The cumulative counts at the last successful publish.
	 */
	private Counts published = new Counts(0, 0);

	/**
	 * Wires the publisher.
	 *
	 * @param meterRegistry    the registry holding the {@code http.server.requests} timers.
	 * @param cloudWatchClient the client the counts are sent with.
	 * @param clock            the clock the Datapoint's timestamps are taken from.
	 * @param serviceName      this service's name, the value of the metrics' service dimension.
	 */
	public HttpMetricsCloudWatchPublisher(final MeterRegistry meterRegistry, final CloudWatchClient cloudWatchClient,
										  final Clock clock, final String serviceName) {
		this.meterRegistry = meterRegistry;
		this.cloudWatchClient = cloudWatchClient;
		this.clock = clock;
		this.serviceName = serviceName;
	}

	/**
	 * Sends the counts accumulated since the last successful publish, including zeros, so a quiet service keeps
	 * reporting. Runs every {@code url-shortener.http-metrics.publish-interval} (an ISO-8601 duration, one minute
	 * unless configured otherwise).
	 */
	@Scheduled(fixedRateString = "${url-shortener.http-metrics.publish-interval:PT1M}")
	public synchronized void publish() {
		final Counts current = currentCounts();
		final Counts delta = current.minus(published);
		try {
			cloudWatchClient.putMetricData(request(delta, clock.instant()));
			published = current;
			log.atDebug()
					.addKeyValue("requests", delta.requests())
					.addKeyValue("serverErrors", delta.serverErrors())
					.log("Published HTTP response counts to CloudWatch");
		} catch (RuntimeException e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("requests", delta.requests())
					.addKeyValue("serverErrors", delta.serverErrors())
					.log("Could not publish HTTP response counts to CloudWatch, will retry with the next publish");
		}
	}

	private Counts currentCounts() {
		long requests = 0;
		long serverErrors = 0;
		for (final Timer timer : meterRegistry.find(METER_NAME).timers()) {
			if (isActuator(timer)) {
				continue;
			}
			final long count = timer.count();
			requests += count;
			if (SERVER_ERROR_OUTCOME.equals(timer.getId().getTag(OUTCOME_TAG))) {
				serverErrors += count;
			}
		}
		return new Counts(requests, serverErrors);
	}

	private boolean isActuator(final Timer timer) {
		final String uri = timer.getId().getTag(URI_TAG);
		return Objects.nonNull(uri) && uri.startsWith(ACTUATOR_URI_PREFIX);
	}

	private PutMetricDataRequest request(final Counts counts, final Instant timestamp) {
		return PutMetricDataRequest.builder()
				.namespace(HttpMetricContract.NAMESPACE)
				.metricData(
						datum(HttpMetricContract.HTTP_5XX_COUNT, counts.serverErrors(), timestamp),
						datum(HttpMetricContract.HTTP_REQUEST_COUNT, counts.requests(), timestamp)
				)
				.build();
	}

	private MetricDatum datum(final String metricName, final long value, final Instant timestamp) {
		return MetricDatum.builder()
				.metricName(metricName)
				.dimensions(Dimension.builder().name(HttpMetricContract.SERVICE_DIMENSION).value(serviceName).build())
				.value((double) value)
				.unit(StandardUnit.COUNT)
				.timestamp(timestamp)
				.build();
	}

	/**
	 * Cumulative response counts.
	 *
	 * @param requests     all the responses.
	 * @param serverErrors the 5xx ones among them.
	 */
	private record Counts(long requests, long serverErrors) {

		/**
		 * The counts since {@code earlier}. A count that went backwards means its timers were removed and re-created,
		 * so the current value is all that has happened since.
		 */
		Counts minus(final Counts earlier) {
			return new Counts(
					requests >= earlier.requests ? requests - earlier.requests : requests,
					serverErrors >= earlier.serverErrors ? serverErrors - earlier.serverErrors : serverErrors
			);
		}

	}

}
