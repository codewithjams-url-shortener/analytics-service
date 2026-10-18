package io.urlshortener.analyticsservice.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.metrics.SqsConsumerMetrics;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.analyticsservice.repository.ClickEventArchiver;
import io.urlshortener.analyticsservice.service.ClickEventRecorder;
import io.urlshortener.eventcontracts.ClickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.*;

/**
 * Polls the {@code click-events} SQS queue, hands each batch to {@link ClickEventRecorder} to record in Postgres,
 * archives the batch to S3, and deletes the processed messages.
 *
 * <p>
 *     A batch that fails partway (a Postgres or S3 error) is abandoned wholesale - none of its messages are deleted,
 *     so SQS redelivers the entire batch. Each half of that is safe:
 * </p>
 * <ul>
 *     <li>
 *         <b>Postgres:</b> {@link ClickEventRecorder#record} is one transaction covering the {@code click_events}
 *         insert and both rollups ({@code link_stats}, {@code link_daily_stats}). A failure rolls all of it back, so the
 *         redelivered events are new again and are counted exactly once. Nothing is ever recorded without its counts.
 *     </li>
 *     <li>
 *         <b>S3:</b> the archive runs after that transaction commits and cannot be part of it, and it receives <em>every
 *         parsed event</em> rather than only the newly recorded ones. So if the archive fails after the commit, the
 *         redelivery (where the recorder reports every event as a duplicate) still archives the batch. The cost is that
 *         a redelivered batch can be archived twice, so readers of the archive should dedupe on {@code eventId}.
 *     </li>
 * </ul>
 *
 * <p>
 *     A message that fails only to *parse* is treated differently from a batch processing failure: it's simply left off
 *     the batch (never deleted), redelivering on its own until the queue's {@code maxReceiveCount} is exceeded, and it
 *     lands in the DLQ - a genuinely poison message shouldn't block the rest of the batch from proceeding.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventConsumer {

	/**
	 * Polls for and deletes {@code click-events} messages.
	 */
	private final SqsClient sqsClient;

	/**
	 * Source of the configured queue URL.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Parses the SNS-enveloped message body into a {@link ClickEvent}.
	 */
	private final ObjectMapper objectMapper;

	/**
	 * Records each batch of events and their rollups atomically.
	 */
	private final ClickEventRecorder clickEventRecorder;

	/**
	 * Archives each processed batch to S3.
	 */
	private final ClickEventArchiver clickEventArchiver;

	/**
	 * Reports how far behind the consumer is and how its batches fare.
	 */
	private final SqsConsumerMetrics sqsConsumerMetrics;

	/**
	 * Runs one poll-and-process cycle: receive, parse, record (one transaction covering the events and both rollups),
	 * archive, then delete only the messages that made it all the way through.
	 */
	@Scheduled(fixedDelay = 5000)
	public void pollAndProcess() {
		log.atDebug()
				.addKeyValue("status", "STARTED")
				.log("Poll and Process started");

		final List<Message> messages = receiveMessages();

		if (CollectionUtils.isEmpty(messages)) {
			log.atDebug()
					.addKeyValue("status", "ABORTED")
					.addKeyValue("verdict", "Early exit due to no SQS Message")
					.log("Poll and Process finished");
			return;
		}

		final Map<Message, ClickEvent> parsed = createSqsMessageClickEventMap(messages);

		try {
			final List<ClickEvent> parsedEvents = parsed.values().stream().toList();
			final int newEventCount = clickEventRecorder.record(parsedEvents);
			clickEventArchiver.archive(parsedEvents);
			deleteMessages(parsed.keySet());
			recordBatchSuccess(parsedEvents);
			log.atInfo()
					.addKeyValue("status", "SUCCESS")
					.addKeyValue("messagesReceived", messages.size())
					.addKeyValue("messagesParsed", parsed.size())
					.addKeyValue("newEvents", newEventCount)
					.addKeyValue("duplicateEvents", parsed.size() - newEventCount)
					.log("ClickEvent batch processed successfully");
		} catch (RuntimeException e) {
			sqsConsumerMetrics.batchFailed();
			log.atError()
					.setCause(e)
					.addKeyValue("status", "FAILED")
					.addKeyValue("messagesReceived", messages.size())
					.addKeyValue("messagesParsed", parsed.size())
					.log("Failed to process ClickEvent batch, leaving message for redelivery/DLQ");
		}
	}

	/**
	 * Reports a batch that was recorded, archived and acknowledged: the age of each of its events, and the batch itself.
	 *
	 * <p>
	 *     A batch with no parsed events (every message was unparseable) is not counted as a success: nothing was
	 *     processed, and the unparseable messages are already counted as parse errors.
	 * </p>
	 *
	 * @param parsedEvents the events of the acknowledged batch.
	 */
	private void recordBatchSuccess(final List<ClickEvent> parsedEvents) {
		if (parsedEvents.isEmpty()) {
			return;
		}
		for (final ClickEvent event : parsedEvents) {
			sqsConsumerMetrics.recordEventAge(Instant.ofEpochMilli(event.timestamp()));
		}
		sqsConsumerMetrics.batchSucceeded();
	}

	/**
	 * Receives up to 10 messages from the {@code click-events} queue in one call.
	 *
	 * @return the received messages, possibly empty.
	 */
	private List<Message> receiveMessages() {
		final ReceiveMessageRequest request = ReceiveMessageRequest.builder()
				.queueUrl(awsProperties.getSqs().getQueueUrl())
				.maxNumberOfMessages(10)
				.waitTimeSeconds(5)
				.build();
		final ReceiveMessageResponse response;
		log.atDebug()
				.addKeyValue("stage", "1")
				.addKeyValue("status", "STARTED")
				.log("Receive Message from SQS");
		try {
			response = sqsClient.receiveMessage(request);
		} catch (Exception e) {
			sqsConsumerMetrics.receiveFailed();
			log.atWarn()
					.setCause(e)
					.addKeyValue("stage", "1")
					.addKeyValue("status", "FAILED")
					.log("Failed to receive Message from SQS");
			throw e;
		}
		// Even an empty receive proves the consumer is alive and reaching SQS.
		sqsConsumerMetrics.pollSucceeded();
		log.atDebug()
				.addKeyValue("stage", "1")
				.addKeyValue("status", "SUCCESS")
				.log("Message Received from SQS");
		return response.messages();
	}

	/**
	 * Parses each message into a {@link ClickEvent}, skipping (not deleting) any that fail to parse.
	 *
	 * @param messages the messages to parse.
	 * @return the successfully parsed messages, keyed by their originating SQS {@link Message} so the receipt handle is
	 * still available for deletion later.
	 */
	private Map<Message, ClickEvent> createSqsMessageClickEventMap(final List<Message> messages) {
		log.atDebug()
				.addKeyValue("stage", "2")
				.addKeyValue("status", "STARTED")
				.log("Create SQS Message - ClickEvent Map");
		final Map<Message, ClickEvent> parsed = new LinkedHashMap<>();
		for (final Message message : messages) {
			final ClickEvent event;
			try {
				event = parseClickEvent(message.body());
			} catch (Exception e) {
				sqsConsumerMetrics.parseFailed();
				log.atWarn()
						.setCause(e)
						.addKeyValue("stage", "2")
						.addKeyValue("status", "IN_PROGRESS")
						.addKeyValue("messageId", message.messageId())
						.log("Failed to parse ClickEvent message, leaving it for redelivery/DLQ");
				continue;
			}
			parsed.put(message, event);
		}
		log.atDebug()
				.addKeyValue("stage", "2")
				.addKeyValue("status", "SUCCESS")
				.addKeyValue("keyValueCount", parsed.size())
				.log("Created SQS Message - ClickEvent Map");
		return parsed;
	}

	/**
	 * Parses an SQS message body as an SNS notification envelope and extracts the {@code ClickEvent} from its
	 * {@code "Message"} field. The real {@code sns-sqs} Terraform module doesn't enable raw message delivery, so the
	 * message body is always the full SNS envelope, not the raw event JSON.
	 *
	 * @param messageBody the raw SQS message body.
	 * @return the parsed {@link ClickEvent}.
	 * @throws JsonProcessingException if the envelope or the inner event JSON is malformed.
	 */
	private ClickEvent parseClickEvent(final String messageBody) throws JsonProcessingException {
		log.atDebug()
				.addKeyValue("stage", "2.1")
				.addKeyValue("status", "STARTED")
				.log("Parsing ClickEvent from SQS Message");
		final JsonNode envelop;
		try {
			envelop = objectMapper.readTree(messageBody);
		} catch (JsonProcessingException e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("stage", "2.1")
					.addKeyValue("status", "FAILED")
					.log("Failed to parse the SQS message");
			throw e;
		}
		final String messageId = envelop.has("MessageId") ? envelop.get("MessageId").asText() : null;
		if (!envelop.has("Message")) {
			log.atWarn()
					.addKeyValue("stage", "2.1")
					.addKeyValue("status", "FAILED")
					.addKeyValue("messageId", messageId)
					.log("Received SQS Message does not contain ClickEvent");
			throw new RuntimeException("ClickEvent missing from SQS Message");
		}
		final String innerJson = envelop.get("Message").asText();
		final ClickEvent clickEvent;
		try {
			clickEvent = objectMapper.readValue(innerJson, ClickEvent.class);
		} catch (JsonProcessingException e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("stage", "2.1")
					.addKeyValue("status", "FAILED")
					.addKeyValue("messageId", messageId)
					.log("Failed to parse ClickEvent from SQS Message");
			throw e;
		}
		log.atDebug()
				.addKeyValue("stage", "2.1")
				.addKeyValue("status", "SUCCESS")
				.addKeyValue("messageId", messageId)
				.log("ClickEvent parsed from SQS Message");
		return clickEvent;
	}

	/**
	 * Deletes a fully-processed batch's messages from SQS in one call. Any individual entries that fail to delete are
	 * logged (not thrown) - they'll simply be redelivered and safely reprocessed: {@link ClickEventRecorder#record}
	 * reports them as duplicates and counts nothing twice.
	 *
	 * @param messages the messages whose processing completed successfully.
	 */
	private void deleteMessages(final Set<Message> messages) {
		if (messages.isEmpty()) {
			// A batch reduced to zero parsed messages (e.g. every message failed to parse) still reaches
			// here - real AWS rejects a DeleteMessageBatch request with no entries, so skip the call
			// entirely rather than making a request that can only fail.
			return;
		}
		log.atDebug()
				.addKeyValue("stage", "5")
				.addKeyValue("status", "STARTED")
				.log("Deleting SQS Messages");
		final List<DeleteMessageBatchRequestEntry> entries = messages.stream()
				.map(
						m -> DeleteMessageBatchRequestEntry.builder()
								.id(m.messageId())
								.receiptHandle(m.receiptHandle())
								.build()
				)
				.toList();
		final DeleteMessageBatchRequest request = DeleteMessageBatchRequest.builder()
				.queueUrl(awsProperties.getSqs().getQueueUrl())
				.entries(entries)
				.build();
		final DeleteMessageBatchResponse response;
		try {
			response = sqsClient.deleteMessageBatch(request);
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("stage", "5")
					.addKeyValue("status", "FAILED")
					.log("Failed to Delete SQS Messages");
			throw e;
		}
		if (!response.failed().isEmpty()) {
			log.atWarn()
					.addKeyValue("stage", "5")
					.addKeyValue("status", "PARTIAL_FAILURE")
					.addKeyValue("failedCount", response.failed().size())
					.log("Some SQS messages failed to delete and will be redelivered");
		}
		log.atDebug()
				.addKeyValue("stage", "5")
				.addKeyValue("status", "SUCCESS")
				.log("SQS Message Deleted");
	}

}
