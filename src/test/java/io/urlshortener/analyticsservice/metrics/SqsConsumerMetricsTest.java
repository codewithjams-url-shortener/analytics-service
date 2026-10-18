package io.urlshortener.analyticsservice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SqsConsumerMetricsTest {

	// A fixed, neutral "now": ages and the poll gauge depend on it.
	private static final Instant NOW = Instant.parse("2024-03-20T12:00:00Z");

	private MutableClock clock;

	private MeterRegistry registry;

	private SqsConsumerMetrics metrics;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(NOW);
		registry = new SimpleMeterRegistry();
		metrics = new SqsConsumerMetrics(registry, clock);
	}

	@Test
	void recordEventAge_shouldRecordTheElapsedTime_whenTheEventIsInThePast() {
		// Act
		metrics.recordEventAge(NOW.minusSeconds(12));

		// Assert
		assertThat(
				registry.get(SqsConsumerMetrics.EVENT_AGE)
						.timer()
						.count()
		).isEqualTo(1);
		assertThat(
				registry.get(SqsConsumerMetrics.EVENT_AGE)
						.timer()
						.totalTime(TimeUnit.SECONDS)
		).isEqualTo(12.0);
	}

	@Test
	void recordEventAge_shouldRecordZero_whenTheEventIsInTheFuture() {
		// Act
		metrics.recordEventAge(NOW.plusSeconds(30));

		// Assert
		// Clock skew between redirect-service and this service can put a click slightly in the future; a negative
		// duration would be rejected by the timer, so it is clamped to zero.
		assertThat(
				registry.get(SqsConsumerMetrics.EVENT_AGE)
						.timer()
						.count()
		).isEqualTo(1);
		assertThat(
				registry.get(SqsConsumerMetrics.EVENT_AGE)
						.timer()
						.totalTime(TimeUnit.SECONDS)
		).isZero();
	}

	@Test
	void sinceLastPoll_shouldGrowWithTime_whenNoPollSucceeds() {
		// Act
		clock.advance(Duration.ofSeconds(30));

		// Assert
		assertThat(
				registry.get(SqsConsumerMetrics.SINCE_LAST_POLL)
						.gauge()
						.value()
		).isEqualTo(30.0);
	}

	@Test
	void sinceLastPoll_shouldRestartFromZero_whenAPollSucceeds() {
		// Arrange
		clock.advance(Duration.ofSeconds(30));

		// Act
		metrics.pollSucceeded();
		clock.advance(Duration.ofSeconds(5));

		// Assert
		assertThat(
				registry.get(SqsConsumerMetrics.SINCE_LAST_POLL)
						.gauge()
						.value()
		).isEqualTo(5.0);
	}

	@Test
	void batchCounters_shouldCountSuccessAndFailureSeparately_whenBatchesFinish() {
		// Act
		metrics.batchSucceeded();
		metrics.batchSucceeded();
		metrics.batchFailed();

		// Assert
		assertThat(
				registry.get(SqsConsumerMetrics.BATCHES)
						.tag(SqsConsumerMetrics.TAG_OUTCOME, "success")
						.counter()
						.count()
		).isEqualTo(2.0);
		assertThat(
				registry.get(SqsConsumerMetrics.BATCHES)
						.tag(SqsConsumerMetrics.TAG_OUTCOME, "failure")
						.counter()
						.count()
		).isEqualTo(1.0);
	}

	@Test
	void errorCounters_shouldCountEachStageSeparately_whenTheyFail() {
		// Act
		metrics.receiveFailed();
		metrics.parseFailed();
		metrics.parseFailed();
		metrics.queueDepthRefreshFailed();

		// Assert
		assertThat(
				registry.get(SqsConsumerMetrics.ERRORS)
						.tag(SqsConsumerMetrics.TAG_STAGE, "receive")
						.counter()
						.count()
		).isEqualTo(1.0);
		assertThat(
				registry.get(SqsConsumerMetrics.ERRORS)
						.tag(SqsConsumerMetrics.TAG_STAGE, "parse")
						.counter()
						.count()
		).isEqualTo(2.0);
		assertThat(
				registry.get(SqsConsumerMetrics.ERRORS)
						.tag(SqsConsumerMetrics.TAG_STAGE, "queue_depth")
						.counter()
						.count()
		).isEqualTo(1.0);
	}

	@Test
	void updateQueueDepth_shouldPublishEachState_whenTheBacklogIsRefreshed() {
		// Act
		metrics.updateQueueDepth(7, 3, 1);

		// Assert
		assertThat(
				registry.get(SqsConsumerMetrics.QUEUE_DEPTH)
						.tag(SqsConsumerMetrics.TAG_STATE, "visible")
						.gauge()
						.value()
		).isEqualTo(7.0);
		assertThat(
				registry.get(SqsConsumerMetrics.QUEUE_DEPTH)
						.tag(SqsConsumerMetrics.TAG_STATE, "in_flight")
						.gauge()
						.value()
		).isEqualTo(3.0);
		assertThat(
				registry.get(SqsConsumerMetrics.QUEUE_DEPTH)
						.tag(SqsConsumerMetrics.TAG_STATE, "delayed")
						.gauge().value()
		).isEqualTo(1.0);
	}

	@Test
	void queueDepth_shouldStartAtZero_whenNothingHasBeenPublished() {
		// Assert
		assertThat(registry.get(SqsConsumerMetrics.QUEUE_DEPTH).gauges())
				.hasSize(3)
				.allMatch(gauge -> gauge.value() == 0.0);
	}

	@Test
	void scrape_shouldExposeThePrometheusNamesDashboardsWillQuery_whenRegisteredWithPrometheus() {
		// Arrange
		final PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		final SqsConsumerMetrics prometheusMetrics = new SqsConsumerMetrics(prometheus, clock);
		prometheusMetrics.recordEventAge(NOW.minusSeconds(3));

		// Act
		final String scrape = prometheus.scrape();

		// Assert
		// These names are what dashboards and alerts will query, so a rename must be a deliberate decision.
		assertThat(scrape)
				.contains("sqs_consumer_event_age_seconds_bucket")
				.contains("sqs_consumer_event_age_seconds_count")
				.contains("sqs_consumer_event_age_seconds_sum")
				.contains("sqs_consumer_event_age_seconds_max")
				.contains("sqs_consumer_queue_depth{state=\"visible\"")
				.contains("sqs_consumer_queue_depth{state=\"in_flight\"")
				.contains("sqs_consumer_queue_depth{state=\"delayed\"")
				.contains("sqs_consumer_since_last_poll_seconds")
				.contains("sqs_consumer_batches_total{outcome=\"success\"")
				.contains("sqs_consumer_batches_total{outcome=\"failure\"")
				.contains("sqs_consumer_errors_total{stage=\"receive\"")
				.contains("sqs_consumer_errors_total{stage=\"parse\"")
				.contains("sqs_consumer_errors_total{stage=\"queue_depth\"");
	}

	@Test
	void scrape_shouldResolveEventAgesBeyondTheDefaultThirtySeconds_whenAnIncidentBuildsABacklog() {
		// Arrange
		final PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		final SqsConsumerMetrics prometheusMetrics = new SqsConsumerMetrics(prometheus, clock);
		prometheusMetrics.recordEventAge(NOW.minusSeconds(3));

		// Act
		final double largestFiniteBucket = prometheus.scrape().lines()
				.filter(line -> line.startsWith("sqs_consumer_event_age_seconds_bucket"))
				.map(line -> line.replaceAll(".*le=\"([^\"]+)\".*", "$1"))
				.filter(boundary -> !boundary.equals("+Inf"))
				.mapToDouble(Double::parseDouble)
				.max()
				.orElseThrow();

		// Assert
		// Micrometer's default tops out at 30 seconds, which would put every real backlog (minutes old) into the last
		// bucket and make the percentiles meaningless.
		assertThat(largestFiniteBucket).isGreaterThan(1800.0);

	}

	/**
	 * A clock the test can move forward, standing in for time passing.
	 */
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(final Instant now) {
			this.now = now;
		}

		private void advance(final Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(final ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
