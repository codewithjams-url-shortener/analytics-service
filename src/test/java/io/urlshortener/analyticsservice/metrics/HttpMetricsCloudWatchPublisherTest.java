package io.urlshortener.analyticsservice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.urlshortener.servicecommon.HttpMetricContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HttpMetricsCloudWatchPublisherTest {

	private static final String SERVICE_NAME = "analytics-service";

	private static final Instant NOW = Instant.parse("2024-03-20T15:30:00Z");

	@Mock
	private CloudWatchClient cloudWatchClient;

	private MeterRegistry meterRegistry;

	private HttpMetricsCloudWatchPublisher publisher;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		publisher = new HttpMetricsCloudWatchPublisher(meterRegistry, cloudWatchClient, clock, SERVICE_NAME);
	}

	@Test
	void publish_shouldSendTheServerErrorAndRequestCountsUnderTheSharedContract_whenResponsesWereServed() {
		// Arrange
		record("/links/{shortCode}/stats", "SUCCESS", "200", 3);
		record("/links/{shortCode}/stats", "SERVER_ERROR", "500", 2);

		// Act
		publisher.publish();

		// Assert
		final PutMetricDataRequest request = capturePublishedRequest(1).getFirst();
		assertThat(request.namespace()).isEqualTo(HttpMetricContract.NAMESPACE);
		final Map<String, MetricDatum> data = byMetricName(request);
		assertThat(data).containsOnlyKeys(HttpMetricContract.HTTP_5XX_COUNT, HttpMetricContract.HTTP_REQUEST_COUNT);
		assertThat(data.get(HttpMetricContract.HTTP_5XX_COUNT).value()).isEqualTo(2.0);
		assertThat(data.get(HttpMetricContract.HTTP_REQUEST_COUNT).value()).isEqualTo(5.0);
	}

	@Test
	void publish_shouldDimensionEveryDatumByTheServiceNameAndStampItWithTheClock_whenPublishing() {
		// Arrange
		record("/links/{shortCode}/stats", "SUCCESS", "200", 1);

		// Act
		publisher.publish();

		// Assert
		final PutMetricDataRequest request = capturePublishedRequest(1).getFirst();
		assertThat(request.metricData())
				.hasSize(2)
				.allSatisfy(
						datum -> {
							assertThat(datum.dimensions())
									.hasSize(1);
							assertThat(datum.dimensions().getFirst().name())
									.isEqualTo(HttpMetricContract.SERVICE_DIMENSION);
							assertThat(datum.dimensions().getFirst().value())
									.isEqualTo(SERVICE_NAME);
							assertThat(datum.unit())
									.isEqualTo(StandardUnit.COUNT);
							assertThat(datum.timestamp())
									.isEqualTo(NOW);
						}
				);
	}

	@Test
	void publish_shouldCountOnlyServerErrorOutcomesAsErrors_whenOtherOutcomesAreMixedIn() {
		// Arrange
		record("/links/{shortCode}/stats", "SUCCESS", "201", 4);
		record("/links/{shortCode}/stats", "CLIENT_ERROR", "404", 3);
		record("/links/{shortCode}/stats", "REDIRECTION", "302", 2);
		record("/links/{shortCode}/stats", "SERVER_ERROR", "503", 1);

		// Act
		publisher.publish();

		// Assert
		final Map<String, MetricDatum> data = byMetricName(capturePublishedRequest(1).getFirst());
		assertThat(data.get(HttpMetricContract.HTTP_5XX_COUNT).value()).isEqualTo(1.0);
		assertThat(data.get(HttpMetricContract.HTTP_REQUEST_COUNT).value()).isEqualTo(10.0);
	}

	@Test
	void publish_shouldIgnoreActuatorRequests_whenProbesAndScrapesWereServed() {
		// Arrange
		record("/links/{shortCode}/stats", "SUCCESS", "200", 2);
		record("/actuator/health/liveness", "SUCCESS", "200", 500);
		record("/actuator/prometheus", "SUCCESS", "200", 100);
		record("/actuator/health", "SERVER_ERROR", "503", 7);

		// Act
		publisher.publish();

		// Assert
		// Probes would dilute the 5xx rate and meet the alarm's minimum request count on their own.
		final Map<String, MetricDatum> data = byMetricName(capturePublishedRequest(1).getFirst());
		assertThat(data.get(HttpMetricContract.HTTP_REQUEST_COUNT).value()).isEqualTo(2.0);
		assertThat(data.get(HttpMetricContract.HTTP_5XX_COUNT).value()).isZero();
	}

	@Test
	void publish_shouldSendZeroCounts_whenNoRequestsWereServed() {
		// Act
		publisher.publish();

		// Assert
		// A quiet service keeps reporting, so the metric does not go missing.
		final Map<String, MetricDatum> data = byMetricName(capturePublishedRequest(1).getFirst());
		assertThat(data.get(HttpMetricContract.HTTP_5XX_COUNT).value()).isZero();
		assertThat(data.get(HttpMetricContract.HTTP_REQUEST_COUNT).value()).isZero();
	}

	@Test
	void publish_shouldSendOnlyTheCountsSinceTheLastPublish_whenPublishedBefore() {
		// Arrange
		record("/links/{shortCode}/stats", "SUCCESS", "200", 5);
		record("/links/{shortCode}/stats", "SERVER_ERROR", "500", 1);
		publisher.publish();
		record("/links/{shortCode}/stats", "SUCCESS", "200", 2);
		record("/links/{shortCode}/stats", "SERVER_ERROR", "500", 3);

		// Act
		publisher.publish();

		// Assert
		final Map<String, MetricDatum> second = byMetricName(capturePublishedRequest(2).get(1));
		assertThat(second.get(HttpMetricContract.HTTP_5XX_COUNT).value()).isEqualTo(3.0);
		assertThat(second.get(HttpMetricContract.HTTP_REQUEST_COUNT).value()).isEqualTo(5.0);
	}

	@Test
	void publish_shouldCarryTheMissedCountsForward_whenAPublishFails() {
		// Arrange
		record("/links/{shortCode}/stats", "SUCCESS", "200", 4);
		record("/links/{shortCode}/stats", "SERVER_ERROR", "500", 2);
		given(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
				.willThrow(SdkClientException.create("CloudWatch unreachable"))
				.willReturn(PutMetricDataResponse.builder().build());
		publisher.publish();
		record("/links/{shortCode}/stats", "SUCCESS", "200", 1);

		// Act
		publisher.publish();

		// Assert
		// The failed publish must not lose the 5xx evidence: the next one carries everything since the last success.
		final Map<String, MetricDatum> retried = byMetricName(capturePublishedRequest(2).get(1));
		assertThat(retried.get(HttpMetricContract.HTTP_5XX_COUNT).value()).isEqualTo(2.0);
		assertThat(retried.get(HttpMetricContract.HTTP_REQUEST_COUNT).value()).isEqualTo(7.0);
	}

	@Test
	void publish_shouldNotThrow_whenCloudWatchIsUnavailable() {
		// Arrange
		doThrow(SdkClientException.create("CloudWatch unreachable"))
				.when(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));

		// Act & Assert
		// A CloudWatch outage must not take the scheduler, let alone the service, down.
		assertThatCode(() -> publisher.publish()).doesNotThrowAnyException();
	}

	@Test
	void publish_shouldTreatTheCurrentCountsAsNew_whenTheTimersWereRecreated() {
		// Arrange
		record("/links/{shortCode}/stats", "SUCCESS", "200", 10);
		publisher.publish();
		meterRegistry.clear();
		record("/links/{shortCode}/stats", "SUCCESS", "200", 3);

		// Act
		publisher.publish();

		// Assert
		// Counts that went backwards mean the timers were reset, not that -7 requests were served.
		final Map<String, MetricDatum> second = byMetricName(capturePublishedRequest(2).get(1));
		assertThat(second.get(HttpMetricContract.HTTP_REQUEST_COUNT).value()).isEqualTo(3.0);
	}

	private void record(final String uri, final String outcome, final String status, final int count) {
		final Timer timer = Timer.builder(HttpMetricsCloudWatchPublisher.METER_NAME)
				.tag(HttpMetricsCloudWatchPublisher.URI_TAG, uri)
				.tag(HttpMetricsCloudWatchPublisher.OUTCOME_TAG, outcome)
				.tag("status", status)
				.register(meterRegistry);
		for (int i = 0; i < count; i++) {
			timer.record(Duration.ofMillis(5));
		}
	}

	private List<PutMetricDataRequest> capturePublishedRequest(final int expectedPublishes) {
		final ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
		verify(cloudWatchClient, times(expectedPublishes)).putMetricData(captor.capture());
		return captor.getAllValues();
	}

	private Map<String, MetricDatum> byMetricName(final PutMetricDataRequest request) {
		return request.metricData()
				.stream()
				.collect(Collectors.toMap(MetricDatum::metricName, Function.identity()));
	}

}
