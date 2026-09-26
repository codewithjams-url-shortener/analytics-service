package io.urlshortener.analyticsservice.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.analyticsservice.repository.ClickEventArchiver;
import io.urlshortener.analyticsservice.repository.ClickEventRepository;
import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.eventcontracts.ClickOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Polls the {@code click-events} SQS queue, aggregates same-{@code shortCode} click counts in memory before writing,
 * records each event idempotently in Postgres, archives the batch to S3, and deletes the processed messages.
 *
 * <p>
 *     A batch that fails partway (a Postgres or S3 error) is abandoned wholesale - none of its messages are deleted,
 *     so SQS redelivers the entire batch. That's safe rather than wasteful: {@link ClickEventRepository#recordIfNew}
 *     is idempotent on {@code eventId}, so re-processing a batch that partially succeeded before failing never
 *     double-counts anything.
 * </p>
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
	 * Records events idempotently and rolls them into {@code link_stats}.
	 */
	private final ClickEventRepository clickEventRepository;

	/**
	 * Archives each processed batch to S3.
	 */
	private final ClickEventArchiver clickEventArchiver;

	/**
	 * Runs one poll-and-process cycle: receive, parse, record (idempotently), aggregate into {@code
	 * link_stats}, archive, then delete only the messages that made it all the way through.
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
			final List<ClickEvent> newEvents = filterNewClickEvents(parsed.values().stream().toList());
			incrementStatsPerShortCode(newEvents);
			clickEventArchiver.archive(newEvents);
			deleteMessages(parsed.keySet());
			log.atInfo()
					.addKeyValue("status", "SUCCESS")
					.addKeyValue("messagesReceived", messages.size())
					.addKeyValue("messagesParsed", parsed.size())
					.addKeyValue("newEvents", newEvents.size())
					.addKeyValue("duplicateEvents", parsed.size() - newEvents.size())
					.log("ClickEvent batch processed successfully");
		} catch (RuntimeException e) {
			log.atError()
					.setCause(e)
					.addKeyValue("status", "FAILED")
					.addKeyValue("messagesReceived", messages.size())
					.addKeyValue("messagesParsed", parsed.size())
					.log("Failed to process ClickEvent batch, leaving message for redelivery/DLQ");
		}
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
			log.atWarn()
					.setCause(e)
					.addKeyValue("stage", "1")
					.addKeyValue("status", "FAILED")
					.log("Failed to receive Message from SQS");
			throw e;
		}
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
	 * Records every event via {@link ClickEventRepository#recordIfNew}, keeping only the ones that weren't
	 * already-processed duplicates.
	 *
	 * @param events the parsed events to record.
	 * @return the subset of {@code events} that were newly recorded.
	 */
	private List<ClickEvent> filterNewClickEvents(final List<ClickEvent> events) {
		log.atDebug()
				.addKeyValue("stage", "3")
				.addKeyValue("status", "STARTED")
				.log("Filter New Click Events from SQS Message");
		final List<ClickEvent> newEvents = new ArrayList<>();
		for (final ClickEvent event : events) {
			final boolean isNew;
			try {
				isNew = clickEventRepository.recordIfNew(event);
			} catch (Exception e) {
				log.atWarn()
						.setCause(e)
						.addKeyValue("stage", "3")
						.addKeyValue("status", "FAILED")
						.addKeyValue("event", event)
						.log("Failed to Record the event");
				throw e;
			}
			if (isNew) {
				newEvents.add(event);
			}
		}
		log.atDebug()
				.addKeyValue("stage", "3")
				.addKeyValue("status", "SUCCESS")
				.addKeyValue("newEventsCount", newEvents.size())
				.log("New Click Events filtered from SQS Message");
		return newEvents;
	}

	/**
	 * Groups the batch's new events by short code and applies each group's aggregated counts to {@code link_stats}
	 * in one call per short code, rather than one write per event.
	 *
	 * @param events the newly recorded events for this batch.
	 */
	private void incrementStatsPerShortCode(final List<ClickEvent> events) {
		log.atDebug()
				.addKeyValue("stage", "4")
				.addKeyValue("status", "STARTED")
				.addKeyValue("newEvents", events.size())
				.log("Incrementing stats per Short Code");
		final Map<String, List<ClickEvent>> eventsGroupedByShortCode = events.stream()
				.collect(Collectors.groupingBy(ClickEvent::shortCode));
		eventsGroupedByShortCode.forEach(this::processClickEventsByShortCode);
		log.atDebug()
				.addKeyValue("stage", "4")
				.addKeyValue("status", "SUCCESS")
				.addKeyValue("newEvents", events.size())
				.log("Incremented stats per Short Code");
	}

	/**
	 * Counts one short code's events by outcome and applies the aggregated deltas to {@code link_stats}.
	 *
	 * @param shortCode   the short code this group of events belongs to.
	 * @param eventsGroup this short code's events from the current batch.
	 */
	private void processClickEventsByShortCode(final String shortCode, final List<ClickEvent> eventsGroup) {
		log.atDebug()
				.addKeyValue("stage", "4.1")
				.addKeyValue("status", "STARTED")
				.addKeyValue("shortCode", shortCode)
				.log("Incrementing stats for a Short Code");
		final long resolved = countByOutcome(eventsGroup, ClickOutcome.RESOLVED);
		final long expired = countByOutcome(eventsGroup, ClickOutcome.EXPIRED);
		final long notFound = countByOutcome(eventsGroup, ClickOutcome.NOT_FOUND);
		final Instant lastClickedAt = eventsGroup.stream()
				.map(e -> Instant.ofEpochMilli(e.timestamp()))
				.max(Instant::compareTo)
				.orElseThrow();
		try {
			clickEventRepository.incrementStats(shortCode, resolved, expired, notFound, lastClickedAt);
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("stage", "4.1")
					.addKeyValue("status", "FAILED")
					.addKeyValue("shortCode", shortCode)
					.log("Something went wrong while incrementing the Short Code stats");
			throw e;
		}
		log.atDebug()
				.addKeyValue("stage", "4.1")
				.addKeyValue("status", "SUCCESS")
				.addKeyValue("shortCode", shortCode)
				.log("Incremented stats for a Short Code");
	}

	/**
	 * Counts how many of the given events have the given outcome.
	 *
	 * @param events  the events to count over.
	 * @param outcome the outcome to count.
	 * @return the number of matching events.
	 */
	private long countByOutcome(final List<ClickEvent> events, final ClickOutcome outcome) {
		return events.stream()
				.filter(e -> e.outcome() == outcome)
				.count();
	}

	/**
	 * Deletes a fully-processed batch's messages from SQS in one call. Any individual entries that fail to delete are
	 * logged (not thrown) - they'll simply be redelivered and safely reprocessed thanks to
	 * {@link ClickEventRepository#recordIfNew}'s idempotency.
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
