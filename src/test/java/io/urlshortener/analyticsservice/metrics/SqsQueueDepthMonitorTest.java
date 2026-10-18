package io.urlshortener.analyticsservice.metrics;

import io.urlshortener.analyticsservice.property.AwsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SqsQueueDepthMonitorTest {

	private static final String QUEUE_URL = "http://localhost:4566/000000000000/click-events-queue";

	@Mock
	private SqsClient sqsClient;

	@Mock
	private SqsConsumerMetrics sqsConsumerMetrics;

	private SqsQueueDepthMonitor monitor;

	@BeforeEach
	void setUp() {
		final AwsProperties.Sqs sqs = new AwsProperties.Sqs();
		sqs.setQueueUrl(QUEUE_URL);
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSqs(sqs);

		monitor = new SqsQueueDepthMonitor(sqsClient, awsProperties, sqsConsumerMetrics);
	}

	@Test
	void refresh_shouldPublishTheThreeCounts_whenSqsReturnsThem() {
		// Arrange
		given(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class))).willReturn(response(
				Map.of(
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "7",
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "3",
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "1"
				)));

		// Act
		monitor.refresh();

		// Assert
		verify(sqsConsumerMetrics).updateQueueDepth(7, 3, 1);
		verify(sqsConsumerMetrics, never()).queueDepthRefreshFailed();
	}

	@Test
	void refresh_shouldAskForTheConfiguredQueueAndTheThreeCounts_whenCalled() {
		// Arrange
		given(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class))).willReturn(response(allCounts()));
		final ArgumentCaptor<GetQueueAttributesRequest> captor = ArgumentCaptor.forClass(GetQueueAttributesRequest.class);

		// Act
		monitor.refresh();

		// Assert
		verify(sqsClient).getQueueAttributes(captor.capture());
		assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
		assertThat(captor.getValue().attributeNames()).containsExactlyInAnyOrder(
				QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
				QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
				QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED);
	}

	@Test
	void refresh_shouldCountTheFailureAndKeepTheLastValues_whenSqsThrows() {
		// Arrange
		given(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
				.willThrow(new IllegalStateException("SQS unavailable"));

		// Act & Assert
		// Not thrown: one bad read must not blank the backlog or log twice. The counter is what shows it has gone stale.
		assertThatCode(() -> monitor.refresh()).doesNotThrowAnyException();
		verify(sqsConsumerMetrics).queueDepthRefreshFailed();
		verify(sqsConsumerMetrics, never()).updateQueueDepth(anyLong(), anyLong(), anyLong());
	}

	@Test
	void refresh_shouldCountTheFailureInsteadOfPublishingZero_whenAnAttributeIsMissing() {
		// Arrange
		given(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class))).willReturn(response(
				Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "7")));

		// Act
		monitor.refresh();

		// Assert
		verify(sqsConsumerMetrics).queueDepthRefreshFailed();
		verify(sqsConsumerMetrics, never()).updateQueueDepth(anyLong(), anyLong(), anyLong());
	}

	@Test
	void refresh_shouldCountTheFailure_whenAnAttributeIsNotANumber() {
		// Arrange
		given(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class))).willReturn(response(
				Map.of(
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "many",
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "3",
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "1"
				)));

		// Act
		monitor.refresh();

		// Assert
		verify(sqsConsumerMetrics).queueDepthRefreshFailed();
		verify(sqsConsumerMetrics, never()).updateQueueDepth(anyLong(), anyLong(), anyLong());
	}

	private Map<QueueAttributeName, String> allCounts() {
		return Map.of(
				QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0",
				QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0",
				QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "0"
		);
	}

	private GetQueueAttributesResponse response(final Map<QueueAttributeName, String> attributes) {
		return GetQueueAttributesResponse.builder().attributes(attributes).build();
	}

}
