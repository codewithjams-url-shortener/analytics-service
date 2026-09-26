package io.urlshortener.analyticsservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.analyticsservice.repository.ClickEventArchiver;
import io.urlshortener.analyticsservice.repository.ClickEventRepository;
import io.urlshortener.eventcontracts.ClickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ClickEventConsumerTest {

	private static final String QUEUE_URL = "http://localhost:4566/000000000000/click-events-queue";

	@Mock
	private SqsClient sqsClient;

	@Mock
	private ClickEventRepository clickEventRepository;

	@Mock
	private ClickEventArchiver clickEventArchiver;

	private ClickEventConsumer consumer;

	@BeforeEach
	void setUp() {
		final AwsProperties.Sqs sqs = new AwsProperties.Sqs();
		sqs.setQueueUrl(QUEUE_URL);
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSqs(sqs);

		consumer = new ClickEventConsumer(sqsClient, awsProperties, new ObjectMapper(), clickEventRepository,
				clickEventArchiver);
	}

	@Test
	void pollAndProcess_shouldDoNothing_whenNoMessagesAreReceived() {
		// Arrange
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

		// Act
		consumer.pollAndProcess();

		// Assert
		verifyNoInteractions(clickEventRepository, clickEventArchiver);
	}

	@Test
	void pollAndProcess_shouldRecordArchiveAndDelete_whenANewEventIsReceived() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRepository.recordIfNew(any(ClickEvent.class))).willReturn(true);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		final ArgumentCaptor<ClickEvent> eventCaptor = ArgumentCaptor.forClass(ClickEvent.class);
		verify(clickEventRepository).recordIfNew(eventCaptor.capture());
		assertThat(eventCaptor.getValue().eventId()).isEqualTo("event-1");
		assertThat(eventCaptor.getValue().shortCode()).isEqualTo("abc1234");

		verify(clickEventRepository).incrementStats(eq("abc1234"), eq(1L), eq(0L), eq(0L), any(Instant.class));
		verify(clickEventArchiver).archive(List.of(eventCaptor.getValue()));

		final ArgumentCaptor<DeleteMessageBatchRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
		verify(sqsClient).deleteMessageBatch(deleteCaptor.capture());
		assertThat(deleteCaptor.getValue().entries()).extracting("id").containsExactly("msg-1");
	}

	@Test
	void pollAndProcess_shouldNotIncrementStats_whenEventIsADuplicate() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRepository.recordIfNew(any(ClickEvent.class))).willReturn(false);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		verify(clickEventRepository, never()).incrementStats(any(), anyLong(), anyLong(), anyLong(), any());
		verify(clickEventArchiver).archive(List.of());
		// A duplicate still counts as successfully processed - the message should still be deleted.
		verify(sqsClient).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
	}

	@Test
	void pollAndProcess_shouldSkipMessage_whenBodyIsMalformedJson() {
		// Arrange
		final Message goodMessage = sqsMessage("msg-good", envelope("event-1", "abc1234", "RESOLVED"));
		final Message badMessage = sqsMessage("msg-bad", "not-valid-json");
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(goodMessage, badMessage).build());
		given(clickEventRepository.recordIfNew(any(ClickEvent.class))).willReturn(true);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		verify(clickEventRepository, times(1)).recordIfNew(any(ClickEvent.class));
		final ArgumentCaptor<DeleteMessageBatchRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
		verify(sqsClient).deleteMessageBatch(deleteCaptor.capture());
		assertThat(deleteCaptor.getValue().entries())
				.as("only the successfully parsed message should be deleted")
				.extracting("id")
				.containsExactly("msg-good");
	}

	@Test
	void pollAndProcess_shouldSkipMessageAndNotCallDeleteBatch_whenEnvelopeHasNoMessageField() {
		// Arrange
		final Message badMessage = sqsMessage("msg-bad", "{\"Type\":\"Notification\",\"MessageId\":\"x\"}");
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(badMessage).build());

		// Act
		consumer.pollAndProcess();

		// Assert
		// The bad message is excluded from the batch entirely - nothing is ever recorded for it - but a
		// batch reduced to zero parsed events isn't itself a failure, so archive still runs (as a
		// harmless no-op for an empty batch); deleteMessageBatch is skipped entirely since a real
		// DeleteMessageBatch request with zero entries would just be rejected by AWS.
		verifyNoInteractions(clickEventRepository);
		verify(clickEventArchiver).archive(List.of());
		verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
	}

	@Test
	void pollAndProcess_shouldIncrementStatsOnce_whenMultipleEventsShareTheSameShortCode() {
		// Arrange
		final Message resolved = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		final Message expired = sqsMessage("msg-2", envelope("event-2", "abc1234", "EXPIRED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(resolved, expired).build());
		given(clickEventRepository.recordIfNew(any(ClickEvent.class))).willReturn(true);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		verify(clickEventRepository, times(1))
				.incrementStats(eq("abc1234"), eq(1L), eq(1L), eq(0L), any(Instant.class));
	}

	@Test
	void pollAndProcess_shouldNotArchiveOrDelete_whenRecordingAnEventFails() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRepository.recordIfNew(any(ClickEvent.class)))
				.willThrow(new RuntimeException("DB unavailable"));

		// Act
		consumer.pollAndProcess();

		// Assert
		verifyNoInteractions(clickEventArchiver);
		verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
	}

	@Test
	void pollAndProcess_shouldNotDelete_whenArchivingFails() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRepository.recordIfNew(any(ClickEvent.class))).willReturn(true);
		doThrow(new RuntimeException("S3 unavailable")).when(clickEventArchiver).archive(any());

		// Act
		consumer.pollAndProcess();

		// Assert
		verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
	}

	@Test
	void pollAndProcess_shouldPropagateAndSkipProcessing_whenReceivingMessagesFails() {
		// Arrange
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willThrow(new RuntimeException("SQS unavailable"));

		// Act & Assert
		assertThatThrownBy(() -> consumer.pollAndProcess())
				.isInstanceOf(RuntimeException.class)
				.hasMessage("SQS unavailable");
		verifyNoInteractions(clickEventRepository, clickEventArchiver);
	}

	@Test
	void pollAndProcess_shouldNotThrow_whenDeleteBatchPartiallyFails() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRepository.recordIfNew(any(ClickEvent.class))).willReturn(true);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder()
						.successful(List.of())
						.failed(BatchResultErrorEntry.builder().id("msg-1").message("boom").build())
						.build());

		// Act & Assert
		consumer.pollAndProcess();
	}

	private Message sqsMessage(final String id, final String body) {
		return Message.builder().messageId(id).receiptHandle("receipt-" + id).body(body).build();
	}

	private String envelope(final String eventId, final String shortCode, final String outcome) {
		final String clickEventJson = """
				{"eventId":"%s","shortCode":"%s","timestamp":%d,"outcome":"%s","refererDomain":"google.com",\
				"userAgentRaw":"Mozilla/5.0","ipHash":"hash123"}\
				""".formatted(eventId, shortCode, Instant.now().toEpochMilli(), outcome);
		final String escaped = clickEventJson.replace("\"", "\\\"");
		return """
				{"Type":"Notification","MessageId":"sns-%s",\
				"TopicArn":"arn:aws:sns:us-west-2:000000000000:click-events","Message":"%s","Timestamp":"%s"}\
				""".formatted(eventId, escaped, Instant.now());
	}

}
