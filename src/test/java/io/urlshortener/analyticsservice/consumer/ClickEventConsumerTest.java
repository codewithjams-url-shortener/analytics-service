package io.urlshortener.analyticsservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.urlshortener.analyticsservice.metrics.SqsConsumerMetrics;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.analyticsservice.repository.ClickEventArchiver;
import io.urlshortener.analyticsservice.service.ClickEventRecorder;
import io.urlshortener.analyticsservice.tracing.ClickEventTracing;
import io.urlshortener.analyticsservice.tracing.OtelTracingFixture;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ClickEventConsumerTest {

	private static final String QUEUE_URL = "http://localhost:4566/000000000000/click-events-queue";

	@Mock
	private SqsClient sqsClient;

	@Mock
	private ClickEventRecorder clickEventRecorder;

	@Mock
	private ClickEventArchiver clickEventArchiver;

	@Mock
	private SqsConsumerMetrics sqsConsumerMetrics;

	private OtelTracingFixture tracing;

	private ClickEventConsumer consumer;

	@BeforeEach
	void setUp() {
		final AwsProperties.Sqs sqs = new AwsProperties.Sqs();
		sqs.setQueueUrl(QUEUE_URL);
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSqs(sqs);

		tracing = new OtelTracingFixture();
		consumer = new ClickEventConsumer(sqsClient, awsProperties, new ObjectMapper(), clickEventRecorder,
				clickEventArchiver, sqsConsumerMetrics,
				new ClickEventTracing(tracing.tracer(), tracing.propagator(), awsProperties));
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

	@Test
	void pollAndProcess_shouldReportThePollTheEventAgesAndTheBatch_whenABatchIsProcessed() {
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
		verify(sqsConsumerMetrics).pollSucceeded();
		verify(sqsConsumerMetrics, times(2)).recordEventAge(any(Instant.class));
		verify(sqsConsumerMetrics).batchSucceeded();
		verify(sqsConsumerMetrics, never()).batchFailed();
	}

	@Test
	void pollAndProcess_shouldOnlyReportThePoll_whenNoMessagesAreReceived() {
		// Arrange
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

		// Act
		consumer.pollAndProcess();

		// Assert
		// An empty receive still proves the consumer is alive and reaching SQS, but there is no batch to report.
		verify(sqsConsumerMetrics).pollSucceeded();
		verifyNoMoreInteractions(sqsConsumerMetrics);
	}

	@Test
	void pollAndProcess_shouldReportABatchFailureAndNoEventAge_whenRecordingFails() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willThrow(new RuntimeException("DB unavailable"));

		// Act
		consumer.pollAndProcess();

		// Assert
		// The message stays on the queue, so it has not been acknowledged and its age must not be recorded yet.
		verify(sqsConsumerMetrics).batchFailed();
		verify(sqsConsumerMetrics, never()).batchSucceeded();
		verify(sqsConsumerMetrics, never()).recordEventAge(any(Instant.class));
	}

	@Test
	void pollAndProcess_shouldReportABatchFailureAndNoEventAge_whenArchivingFailsAfterRecordingSucceeds() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willReturn(1);
		doThrow(new RuntimeException("S3 unavailable")).when(clickEventArchiver).archive(any());

		// Act
		consumer.pollAndProcess();

		// Assert
		verify(sqsConsumerMetrics).batchFailed();
		verify(sqsConsumerMetrics, never()).recordEventAge(any(Instant.class));
	}

	@Test
	void pollAndProcess_shouldCountTheParseFailureWithoutFailingTheBatch_whenOneMessageIsMalformed() {
		// Arrange
		final Message good = sqsMessage("msg-good", envelope("event-1", "abc1234", "RESOLVED"));
		final Message bad = sqsMessage("msg-bad", "not-valid-json");
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(good, bad).build());
		given(clickEventRecorder.record(anyList())).willReturn(1);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());

		// Act
		consumer.pollAndProcess();

		// Assert
		verify(sqsConsumerMetrics).parseFailed();
		verify(sqsConsumerMetrics).batchSucceeded();
		verify(sqsConsumerMetrics).recordEventAge(any(Instant.class));
		verify(sqsConsumerMetrics, never()).batchFailed();
	}

	@Test
	void pollAndProcess_shouldNotCountASuccessfulBatch_whenEveryMessageIsUnparseable() {
		// Arrange
		final Message bad = sqsMessage("msg-bad", "not-valid-json");
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(bad).build());

		// Act
		consumer.pollAndProcess();

		// Assert
		// Nothing was processed, so it is neither a success nor a failure: only the parse error is counted.
		verify(sqsConsumerMetrics).parseFailed();
		verify(sqsConsumerMetrics).pollSucceeded();
		verify(sqsConsumerMetrics, never()).batchSucceeded();
		verify(sqsConsumerMetrics, never()).batchFailed();
		verify(sqsConsumerMetrics, never()).recordEventAge(any(Instant.class));
	}

	@Test
	void pollAndProcess_shouldReportTheReceiveFailureAndNoPoll_whenReceivingFails() {
		// Arrange
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willThrow(new RuntimeException("SQS unavailable"));

		// Act & Assert
		assertThatThrownBy(() -> consumer.pollAndProcess()).isInstanceOf(RuntimeException.class);
		verify(sqsConsumerMetrics).receiveFailed();
		verify(sqsConsumerMetrics, never()).pollSucceeded();
	}

	@Test
	void pollAndProcess_shouldContinueTheProducersTrace_whenTheMessageCarriesATraceparent() {
		// Arrange
		final String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
		final String producerSpanId = "00f067aa0ba902b7";
		final Message message = sqsMessage("msg-1", envelopeWithTraceparent(
				"event-1", "abc1234", "RESOLVED", "00-%s-%s-01".formatted(traceId, producerSpanId)));
		givenBatchSucceeds(message);

		// Act
		consumer.pollAndProcess();

		// Assert
		// Parsed from inside the SNS envelope: with the real sns-sqs module the attributes are not on the SQS message.
		final SpanData span = onlyConsumerSpan();
		assertThat(span.getTraceId()).isEqualTo(traceId);
		assertThat(span.getParentSpanId()).isEqualTo(producerSpanId);
		assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
	}

	@Test
	void pollAndProcess_shouldStartOneSpanPerMessageEachInItsOwnTrace_whenMessagesCarryDifferentContexts() {
		// Arrange
		final Message first = sqsMessage("msg-1", envelopeWithTraceparent("event-1", "abc1234", "RESOLVED",
				"00-11111111111111111111111111111111-aaaaaaaaaaaaaaaa-01"));
		final Message second = sqsMessage("msg-2", envelopeWithTraceparent("event-2", "xyz7890", "EXPIRED",
				"00-22222222222222222222222222222222-bbbbbbbbbbbbbbbb-01"));
		givenBatchSucceeds(first, second);

		// Act
		consumer.pollAndProcess();

		// Assert
		assertThat(tracing.finishedSpans())
				.extracting(SpanData::getTraceId, SpanData::getParentSpanId)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("11111111111111111111111111111111", "aaaaaaaaaaaaaaaa"),
						org.assertj.core.groups.Tuple.tuple("22222222222222222222222222222222", "bbbbbbbbbbbbbbbb"));
	}

	@Test
	void pollAndProcess_shouldStillProcessTheEventInANewTrace_whenTheMessageCarriesNoContext() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		givenBatchSucceeds(message);

		// Act
		consumer.pollAndProcess();

		// Assert
		// A message sent without tracing (older, or from a service that does not propagate) must still be counted.
		verify(clickEventRecorder).record(anyList());
		verify(sqsClient).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
		assertThat(onlyConsumerSpan().getParentSpanContext().isValid()).isFalse();
	}

	@Test
	void pollAndProcess_shouldEndTheSpansAsErrors_whenRecordingFails() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelopeWithTraceparent("event-1", "abc1234", "RESOLVED",
				"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willThrow(new RuntimeException("DB unavailable"));

		// Act
		consumer.pollAndProcess();

		// Assert
		// The message stays on the queue, so the trace shows the attempt as a failure rather than a clean run.
		final SpanData span = onlyConsumerSpan();
		assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
		assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
	}

	@Test
	void pollAndProcess_shouldEndTheSpansAsErrors_whenArchivingFailsAfterRecordingSucceeds() {
		// Arrange
		final Message message = sqsMessage("msg-1", envelope("event-1", "abc1234", "RESOLVED"));
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(message).build());
		given(clickEventRecorder.record(anyList())).willReturn(1);
		doThrow(new RuntimeException("S3 unavailable")).when(clickEventArchiver).archive(any());

		// Act
		consumer.pollAndProcess();

		// Assert
		assertThat(onlyConsumerSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
	}

	@Test
	void pollAndProcess_shouldStartNoSpan_whenEveryMessageIsUnparseable() {
		// Arrange
		final Message bad = sqsMessage("msg-bad", "not-valid-json");
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(bad).build());

		// Act
		consumer.pollAndProcess();

		// Assert
		assertThat(tracing.finishedSpans()).isEmpty();
	}

	@Test
	void pollAndProcess_shouldStartNoSpan_whenNoMessagesAreReceived() {
		// Arrange
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

		// Act
		consumer.pollAndProcess();

		// Assert
		assertThat(tracing.finishedSpans()).isEmpty();
	}

	private void givenBatchSucceeds(final Message... messages) {
		given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.willReturn(ReceiveMessageResponse.builder().messages(messages).build());
		given(clickEventRecorder.record(anyList())).willReturn(messages.length);
		given(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
				.willReturn(DeleteMessageBatchResponse.builder().build());
	}

	private SpanData onlyConsumerSpan() {
		assertThat(tracing.finishedSpans()).hasSize(1);
		assertThat(tracing.finishedSpans().getFirst().getName()).isEqualTo("click-events process");
		return tracing.finishedSpans().getFirst();
	}

	/**
	 * The same SNS envelope, with the trace context redirect-service sends as a message attribute.
	 */
	private String envelopeWithTraceparent(final String eventId, final String shortCode, final String outcome,
										   final String traceparent) {
		final String envelope = envelope(eventId, shortCode, outcome);
		final String attributes = ",\"MessageAttributes\":{\"traceparent\":{\"Type\":\"String\",\"Value\":\"%s\"}}"
				.formatted(traceparent);
		return envelope.substring(0, envelope.lastIndexOf('}')) + attributes + "}";
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
