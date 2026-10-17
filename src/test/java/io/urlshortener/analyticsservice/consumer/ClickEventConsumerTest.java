package io.urlshortener.analyticsservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.analyticsservice.repository.ClickEventArchiver;
import io.urlshortener.analyticsservice.service.ClickEventRecorder;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ClickEventConsumerTest {

	private static final String QUEUE_URL = "http://localhost:4566/000000000000/click-events-queue";

	@Mock
	private SqsClient sqsClient;

	@Mock
	private ClickEventRecorder clickEventRecorder;

	@Mock
	private ClickEventArchiver clickEventArchiver;

	private ClickEventConsumer consumer;

	@BeforeEach
	void setUp() {
		final AwsProperties.Sqs sqs = new AwsProperties.Sqs();
		sqs.setQueueUrl(QUEUE_URL);
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSqs(sqs);

		consumer = new ClickEventConsumer(sqsClient, awsProperties, new ObjectMapper(), clickEventRecorder,
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
		verifyNoInteractions(clickEventRecorder, clickEventArchiver);
	}

	@Test
	void pollAndProcess_shouldRecordArchiveAndDelete_whenAnEventIsReceived() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willReturn(1);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		final ArgumentCaptor<List<ClickEvent>> recordedCaptor = eventListCaptor();
		verify(clickEventRecorder).record(recordedCaptor.capture());
		assertThat(recordedCaptor.getValue()).hasSize(1);
		assertThat(recordedCaptor.getValue().getFirst().eventId()).isEqualTo("event-1");
		assertThat(recordedCaptor.getValue().getFirst().shortCode()).isEqualTo("abc1234");
		verify(clickEventArchiver).archive(recordedCaptor.getValue());

		final ArgumentCaptor<DeleteMessageBatchRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
		verify(sqsClient).deleteMessageBatch(deleteCaptor.capture());
		assertThat(deleteCaptor.getValue().entries()).extracting("id").containsExactly("msg-1");
	}

	@Test
	void pollAndProcess_shouldStillArchiveAndDelete_whenEveryEventIsADuplicate() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willReturn(0);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		// The recorder reports a redelivered batch as all duplicates, but the archive must still receive it: the previous
		// attempt may have committed to Postgres and then failed to archive. A duplicate is also still successfully
		// processed, so the message is deleted.
		final ArgumentCaptor<List<ClickEvent>> archivedCaptor = eventListCaptor();
		verify(clickEventArchiver).archive(archivedCaptor.capture());
		assertThat(archivedCaptor.getValue()).extracting(ClickEvent::eventId).containsExactly("event-1");
		verify(sqsClient).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
	}

	@Test
	void pollAndProcess_shouldHandTheSameEventsToRecorderAndArchiver_whenMultipleEventsAreReceived() {
		// Arrange
		final Message first = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		final Message second = sqsMessage("msg-2", envelope("event-2", "xyz7890", "EXPIRED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(first, second).build());
		given(clickEventRecorder.record(anyList())).willReturn(2);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		final ArgumentCaptor<List<ClickEvent>> recordedCaptor = eventListCaptor();
		verify(clickEventRecorder).record(recordedCaptor.capture());
		assertThat(recordedCaptor.getValue()).extracting(ClickEvent::eventId)
				.containsExactlyInAnyOrder("event-1", "event-2");
		verify(clickEventArchiver).archive(recordedCaptor.getValue());
	}

	@Test
	void pollAndProcess_shouldSkipMessage_whenBodyIsMalformedJson() {
		// Arrange
		final Message goodMessage = sqsMessage("msg-good", envelope("event-1", "abc1234", "RESOLVED"));
		final Message badMessage = sqsMessage("msg-bad", "not-valid-json");
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(goodMessage, badMessage).build());
		given(clickEventRecorder.record(anyList())).willReturn(1);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		final ArgumentCaptor<List<ClickEvent>> recordedCaptor = eventListCaptor();
		verify(clickEventRecorder).record(recordedCaptor.capture());
		assertThat(recordedCaptor.getValue()).extracting(ClickEvent::eventId).containsExactly("event-1");
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
		// The bad message is excluded from the batch entirely, but a batch reduced to zero parsed events isn't itself a
		// failure, so the recorder and archiver still run (as harmless no-ops for an empty batch). deleteMessageBatch is
		// skipped entirely since a real DeleteMessageBatch request with zero entries would just be rejected by AWS.
		verify(clickEventRecorder).record(List.of());
		verify(clickEventArchiver).archive(List.of());
		verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
	}

	@Test
	void pollAndProcess_shouldNotArchiveOrDelete_whenRecordingFails() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willThrow(new RuntimeException("DB unavailable"));

		// Act
		consumer.pollAndProcess();

		// Assert
		verifyNoInteractions(clickEventArchiver);
		verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
	}

	@Test
	void pollAndProcess_shouldNotDelete_whenArchivingFailsAfterRecordingSucceeds() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willReturn(1);
		doThrow(new RuntimeException("S3 unavailable")).when(clickEventArchiver).archive(any());

		// Act
		consumer.pollAndProcess();

		// Assert
		// Recording has already committed here. The message must stay on the queue so the redelivery re-archives it.
		verify(clickEventRecorder).record(anyList());
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
		verifyNoInteractions(clickEventRecorder, clickEventArchiver);
	}

	@Test
	void pollAndProcess_shouldNotThrow_whenDeleteBatchPartiallyFails() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willReturn(1);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder()
						.successful(List.of())
						.failed(BatchResultErrorEntry.builder().id("msg-1").message("boom").build())
						.build());

		// Act & Assert
		assertThatCode(() -> consumer.pollAndProcess()).doesNotThrowAnyException();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private ArgumentCaptor<List<ClickEvent>> eventListCaptor() {
		return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
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
