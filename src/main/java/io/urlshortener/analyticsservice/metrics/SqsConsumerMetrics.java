package io.urlshortener.analyticsservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Micrometer meters describing how far behind the {@code click-events} SQS consumer is, and how healthy it is.
 *
 * <p>
 *     "Lag" is deliberately reported from several angles, because each catches a failure the others miss:
 * </p>
 * <ul>
 *     <li>
 *         {@code sqs.consumer.event.age} - the age of a click when its message was acknowledged: the real
 *         click-to-stats delay, as a percentile histogram so p50/p95/p99 and the max can be charted. SQS itself cannot
 *         report the age of its oldest message (only CloudWatch can), so the consumer measures it.
 *     </li>
 *     <li>
 *         {@code sqs.consumer.queue.depth} - the backlog, by message state. It grows when events are not being picked up
 *         at all, which event age cannot show because nothing is being acknowledged to record.
 *     </li>
 *     <li>
 *         {@code sqs.consumer.since.last.poll} - seconds since the last successful receive. A dead or wedged consumer
 *         shows as a value that only climbs.
 *     </li>
 *     <li>
 *         {@code sqs.consumer.batches} and {@code sqs.consumer.errors} - what to alert on beside them.
 *     </li>
 * </ul>
 *
 * <p>
 *     Example PromQL:
 * </p>
 * <pre>
 * # p95 click-to-stats delay over the last 5 minutes
 * histogram_quantile(0.95, sum by (le) (rate(sqs_consumer_event_age_seconds_bucket[5m])))
 * # backlog waiting to be picked up
 * sqs_consumer_queue_depth{state="visible"}
 * # consumer not reaching SQS for over a minute
 * sqs_consumer_since_last_poll_seconds &gt; 60
 * # batches failing and being redelivered
 * rate(sqs_consumer_batches_total{outcome="failure"}[5m]) &gt; 0
 * </pre>
 *
 * <p>
 *     This class only owns the meters. The consumer decides when each thing happens and calls the matching method, and
 *     {@code SqsQueueDepthMonitor} supplies the queue depth, so neither needs to know about Micrometer.
 * </p>
 */
@Component
public class SqsConsumerMetrics {

	static final String EVENT_AGE = "sqs.consumer.event.age";

	static final String QUEUE_DEPTH = "sqs.consumer.queue.depth";

	static final String SINCE_LAST_POLL = "sqs.consumer.since.last.poll";

	static final String BATCHES = "sqs.consumer.batches";

	static final String ERRORS = "sqs.consumer.errors";

	static final String TAG_STATE = "state";

	static final String TAG_OUTCOME = "outcome";

	static final String TAG_STAGE = "stage";

	/**
	 * Smallest event age the histogram resolves. Below this, the buckets would only add series for ages that are normal
	 * and uninteresting.
	 */
	private static final Duration EVENT_AGE_MINIMUM = Duration.ofMillis(500);

	/**
	 * Largest event age the histogram resolves. Micrometer's default tops out at 30 seconds, which would put every real
	 * incident (a backlog measured in minutes) into the last bucket and make the percentiles meaningless.
	 */
	private static final Duration EVENT_AGE_MAXIMUM = Duration.ofHours(1);

	/**
	 * Source of "now" for ages and the poll gauge, injected so tests can pin it.
	 */
	private final Clock clock;

	/**
	 * How old a click was when its message was acknowledged.
	 */
	private final Timer eventAgeTimer;

	private final Counter batchSuccessCounter;

	private final Counter batchFailureCounter;

	private final Counter receiveErrorCounter;

	private final Counter parseErrorCounter;

	private final Counter queueDepthErrorCounter;

	/**
	 * When the consumer last received successfully, whether or not the queue had anything in it. Backs the
	 * since-last-poll gauge.
	 */
	private final AtomicReference<Instant> lastSuccessfulPoll;

	/**
	 * Messages waiting to be received. Held in a field because a gauge only keeps a weak reference to what it reads.
	 */
	private final AtomicLong visibleMessages = new AtomicLong();

	/**
	 * Messages received but not yet deleted, i.e. being processed or awaiting their visibility timeout.
	 */
	private final AtomicLong inFlightMessages = new AtomicLong();

	/**
	 * Messages held back by a delivery delay.
	 */
	private final AtomicLong delayedMessages = new AtomicLong();

	/**
	 * Registers every meter.
	 *
	 * @param meterRegistry the registry the meters are registered with.
	 * @param clock         the clock "now" is read from.
	 */
	public SqsConsumerMetrics(final MeterRegistry meterRegistry, final Clock clock) {
		this.clock = clock;
		this.eventAgeTimer = Timer.builder(EVENT_AGE)
				.description("Age of a click event when its SQS message was acknowledged, i.e. the delay from the click "
						+ "to it being counted")
				.publishPercentileHistogram()
				.minimumExpectedValue(EVENT_AGE_MINIMUM)
				.maximumExpectedValue(EVENT_AGE_MAXIMUM)
				.register(meterRegistry);
		this.batchSuccessCounter = Counter.builder(BATCHES)
				.description("Number of click event batches the consumer finished, by outcome")
				.tag(TAG_OUTCOME, "success")
				.register(meterRegistry);
		this.batchFailureCounter = Counter.builder(BATCHES)
				.description("Number of click event batches the consumer finished, by outcome")
				.tag(TAG_OUTCOME, "failure")
				.register(meterRegistry);
		this.receiveErrorCounter = Counter.builder(ERRORS)
				.description("Number of click event consumer errors, by stage")
				.tag(TAG_STAGE, "receive")
				.register(meterRegistry);
		this.parseErrorCounter = Counter.builder(ERRORS)
				.description("Number of click event consumer errors, by stage")
				.tag(TAG_STAGE, "parse")
				.register(meterRegistry);
		this.queueDepthErrorCounter = Counter.builder(ERRORS)
				.description("Number of click event consumer errors, by stage")
				.tag(TAG_STAGE, "queue_depth")
				.register(meterRegistry);
		this.lastSuccessfulPoll = new AtomicReference<>(Instant.now(clock));
		Gauge.builder(
						SINCE_LAST_POLL,
						lastSuccessfulPoll,
						ref -> Duration.between(ref.get(), Instant.now(clock)).toMillis() / 1000.0
				)
				.description("Seconds since the click event consumer last received from SQS successfully")
				.baseUnit("seconds")
				.register(meterRegistry);
		registerQueueDepthGauge(meterRegistry, "visible", visibleMessages);
		registerQueueDepthGauge(meterRegistry, "in_flight", inFlightMessages);
		registerQueueDepthGauge(meterRegistry, "delayed", delayedMessages);
	}

	/**
	 * Records how old a click was when its message was acknowledged.
	 *
	 * <p>
	 *     The click's timestamp comes from redirect-service's clock and "now" from this service's, so skew between them
	 *     could make an age negative. It is recorded as zero instead, since a negative duration would be rejected.
	 * </p>
	 *
	 * @param eventTime when the click happened.
	 */
	public void recordEventAge(final Instant eventTime) {
		final Duration age = Duration.between(eventTime, Instant.now(clock));
		eventAgeTimer.record(age.isNegative() ? Duration.ZERO : age);
	}

	/**
	 * Notes a successful receive, even one that returned no messages: it proves the consumer is alive and reaching SQS.
	 */
	public void pollSucceeded() {
		lastSuccessfulPoll.set(Instant.now(clock));
	}

	/**
	 * Counts a batch that was recorded, archived and deleted.
	 */
	public void batchSucceeded() {
		batchSuccessCounter.increment();
	}

	/**
	 * Counts a batch that failed and was left on the queue for redelivery.
	 */
	public void batchFailed() {
		batchFailureCounter.increment();
	}

	/**
	 * Counts a failed attempt to receive from SQS.
	 */
	public void receiveFailed() {
		receiveErrorCounter.increment();
	}

	/**
	 * Counts a message whose body could not be parsed into a click event.
	 */
	public void parseFailed() {
		parseErrorCounter.increment();
	}

	/**
	 * Counts a failed attempt to read the queue's backlog. The depth gauges keep their last values meanwhile,
	 * so without this a refresh that keeps failing would leave them stale with nothing to show it.
	 */
	public void queueDepthRefreshFailed() {
		queueDepthErrorCounter.increment();
	}

	/**
	 * Publishes the latest queue backlog.
	 *
	 * @param visible  messages waiting to be received.
	 * @param inFlight messages received but not yet deleted.
	 * @param delayed  messages held back by a delivery delay.
	 */
	public void updateQueueDepth(final long visible, final long inFlight, final long delayed) {
		visibleMessages.set(visible);
		inFlightMessages.set(inFlight);
		delayedMessages.set(delayed);
	}

	private void registerQueueDepthGauge(final MeterRegistry meterRegistry, final String state,
										 final AtomicLong value) {
		Gauge.builder(QUEUE_DEPTH, value, AtomicLong::get)
				.description("Approximate number of messages in the click events queue, by state")
				.tag(TAG_STATE, state)
				.register(meterRegistry);
	}

}
