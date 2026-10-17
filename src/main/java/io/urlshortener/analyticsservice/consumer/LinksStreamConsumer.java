package io.urlshortener.analyticsservice.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.urlshortener.analyticsservice.constant.AwsConstants;
import io.urlshortener.analyticsservice.mapper.dbMapper.LinkLifecycleEventMapper;
import io.urlshortener.analyticsservice.mapper.dbMapper.LinkMirrorMapper;
import io.urlshortener.analyticsservice.model.domainObject.DeletionReason;
import io.urlshortener.analyticsservice.model.domainObject.MirroredLink;
import io.urlshortener.analyticsservice.property.AwsProperties;
import io.urlshortener.analyticsservice.repository.LinkLifecycleEventArchiver;
import io.urlshortener.analyticsservice.repository.LinksMirrorRepository;
import io.urlshortener.analyticsservice.repository.StreamCheckpointRepository;
import io.urlshortener.eventcontracts.LinkLifecycleEvent;
import io.urlshortener.linkscontract.Link;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tails the Links table's DynamoDB Stream (change data capture) and, for every change, upserts the Postgres
 * {@code links} mirror and archives the {@link LinkLifecycleEvent} to S3. TTL expiry and an explicit delete are told
 * apart by {@link LinkLifecycleEventMapper}, and recorded as a soft delete with a {@link DeletionReason}.
 *
 * <p>
 *     Mirrors redirect-service's stream consumer (single polling thread, periodic shard discovery) with one key
 *     difference: this consumer <em>checkpoints</em> the last processed sequence number of every shard in Postgres.
 *     Redirect-service can start each shard at {@link ShardIteratorType#LATEST LATEST} because a missed event only
 *     means a stale cache entry until its TTL.
 *     Here a missed event would be a permanent hole in the mirror and the archive.
 * </p>
 *
 * <p>
 *     Delivery is at-least-once. Within a batch the order is: mirror every event, archive the batch, and only then save
 *     the checkpoint. Any failure before the checkpoint is saved means the batch is read again, which is safe: the
 *     mirror upsert is idempotent. The archive may receive the same events twice, so consumers of the archive should
 *     dedupe on {@code sequenceNumber}. For the same reason, a shard with no checkpoint starts at
 *     {@link ShardIteratorType#TRIM_HORIZON TRIM_HORIZON} (everything the stream still holds, up to 24 hours) rather
 *     than {@link ShardIteratorType#LATEST LATEST}: a shard that rolled over is discovered up to a minute late,
 *     and {@link ShardIteratorType#LATEST LATEST} would silently skip what was written in that window.
 * </p>
 *
 * <p>
 *     A failure while polling one shard drops only that shard from the in-memory map. The next shard discovery
 *     (about a minute later) adds it back, resuming from its checkpoint. A record that can never be processed therefore
 *     blocks its own shard, retried about once a minute, rather than being skipped. It is surfaced through
 *     {@code streams.consumer.errors} and the per-record warn logs.
 * </p>
 */
@Slf4j
@Component
public class LinksStreamConsumer {

	/**
	 * Pause between polling cycles. DynamoDB Streams has no long-polling, so this bounds the request rate.
	 */
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

	/**
	 * Shards are rediscovered every this-many polling cycles, to pick up child shards created by shard rollover and
	 * shards dropped after an error.
	 */
	private static final int SHARD_DISCOVERY_INTERVAL_CYCLES = 60; // ~once a minute at a 1s poll interval

	/**
	 * How long {@link #stop()} waits for the polling thread to exit.
	 */
	private static final int EXECUTOR_SERVICE_TERMINATE_TIMEOUT_SECONDS = 10;

	/**
	 * Resolves the Links table's stream ARN.
	 */
	private final DynamoDbClient dynamoDbClient;

	/**
	 * Reads the stream: shard discovery, shard iterators and records.
	 */
	private final DynamoDbStreamsClient dynamoDbStreamsClient;

	/**
	 * Source of the configured Links table name.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Converts a raw stream record into a {@link LinkLifecycleEvent}, classifying TTL expiry vs. explicit delete.
	 */
	private final LinkLifecycleEventMapper linkLifecycleEventMapper;

	/**
	 * Converts a lifecycle event's link into the mirror's domain object.
	 */
	private final LinkMirrorMapper linkMirrorMapper;

	/**
	 * Writes the Postgres {@code links} mirror.
	 */
	private final LinksMirrorRepository linksMirrorRepository;

	/**
	 * Durable per-shard progress, so a restart resumes where the last run stopped.
	 */
	private final StreamCheckpointRepository streamCheckpointRepository;

	/**
	 * Archives each batch of lifecycle events to S3.
	 */
	private final LinkLifecycleEventArchiver linkLifecycleEventArchiver;

	/**
	 * Counts failures of the stream consumer: starting up, polling a shard, or the polling loop itself.
	 * Package-private so unit tests can assert on it.
	 */
	final Counter consumerErrorCounter;

	/**
	 * When the polling loop last completed a cycle without an exception. Backs the lag gauge.
	 */
	private final AtomicReference<Instant> lastSuccessfulPoll;

	/**
	 * The current iterator for every shard being tailed, keyed by shard id.
	 *
	 * <p>
	 *     Plain {@link HashMap}, deliberately: it is filled by {@link #start()} before the polling thread is submitted,
	 *     and only touched by that single thread afterwards. Package-private so unit tests can drive the polling
	 *     logic directly without going through {@link #start()}'s background thread.
	 * </p>
	 */
	final Map<String, String> shardIterators;

	/**
	 * ARN of the Links table's stream, resolved once in {@link #start()}. Package-private for unit tests.
	 */
	String streamArn;

	/**
	 * Runs the polling loop on a single background thread. {@code null} if {@link #start()} failed.
	 */
	private ExecutorService executorService;

	/**
	 * Builds the consumer and registers its metrics.
	 *
	 * <p>
	 *     Registers {@code streams.consumer.errors} (a counter) and {@code streams.consumer.lag.seconds} (a gauge of the
	 *     time since the polling loop last completed a cycle without an exception).
	 * </p>
	 *
	 * @param dynamoDbClient             resolves the Links table's stream ARN.
	 * @param dynamoDbStreamsClient      reads the stream.
	 * @param awsProperties              source of the configured Links table name.
	 * @param linkLifecycleEventMapper   converts stream records to lifecycle events.
	 * @param linkMirrorMapper           converts a lifecycle event's link to the mirror's domain object.
	 * @param linksMirrorRepository      writes the Postgres mirror.
	 * @param streamCheckpointRepository stores per-shard progress.
	 * @param linkLifecycleEventArchiver archives each batch to S3.
	 * @param meterRegistry              registry the consumer's metrics are registered with.
	 */
	public LinksStreamConsumer(final DynamoDbClient dynamoDbClient, final DynamoDbStreamsClient dynamoDbStreamsClient,
							   final AwsProperties awsProperties,
							   final LinkLifecycleEventMapper linkLifecycleEventMapper,
							   final LinkMirrorMapper linkMirrorMapper,
							   final LinksMirrorRepository linksMirrorRepository,
							   final StreamCheckpointRepository streamCheckpointRepository,
							   final LinkLifecycleEventArchiver linkLifecycleEventArchiver,
							   final MeterRegistry meterRegistry) {
		this.dynamoDbClient = dynamoDbClient;
		this.dynamoDbStreamsClient = dynamoDbStreamsClient;
		this.awsProperties = awsProperties;
		this.linkLifecycleEventMapper = linkLifecycleEventMapper;
		this.linkMirrorMapper = linkMirrorMapper;
		this.linksMirrorRepository = linksMirrorRepository;
		this.streamCheckpointRepository = streamCheckpointRepository;
		this.linkLifecycleEventArchiver = linkLifecycleEventArchiver;
		this.consumerErrorCounter = Counter.builder("streams.consumer.errors")
				.description("Number of consumer errors when performing GetRecords or DescribeStream")
				.register(meterRegistry);
		this.lastSuccessfulPoll = new AtomicReference<>(Instant.now());
		Gauge.builder(
						"streams.consumer.lag.seconds",
						lastSuccessfulPoll,
						ref -> Duration.between(ref.get(), Instant.now()).getSeconds()
				)
				.description("Seconds since the Links DynamoDB Stream consumer last completed a successful poll")
				.register(meterRegistry);
		this.shardIterators = new HashMap<>();
	}

	/**
	 * Resolves the stream, discovers its shards and starts the polling thread.
	 *
	 * <p>
	 *     A failure here is logged and counted rather than thrown, so a stream problem does not stop the rest of
	 *     analytics-service (the click-event pipeline) from starting. The consumer stays inactive until the service is
	 *     restarted.
	 * </p>
	 */
	@PostConstruct
	public void start() {
		try {
			streamArn = resolveStreamArn();
			discoverShards();
			log.atInfo()
					.addKeyValue("shardCount", shardIterators.size())
					.log("Links DynamoDB Stream consumer started");
			executorService = Executors.newSingleThreadExecutor();
			executorService.submit(this::poll);
		} catch (Exception e) {
			consumerErrorCounter.increment();
			log.atError()
					.setCause(e)
					.log("Failed to start Links DynamoDB Stream consumer - the links mirror and lifecycle archive "
							+ "will not be updated until this is resolved and the service is restarted");
		}
	}

	/**
	 * Interrupts the polling thread and waits up to {@value #EXECUTOR_SERVICE_TERMINATE_TIMEOUT_SECONDS} seconds for it
	 * to exit. A no-op if {@link #start()} never started the thread.
	 */
	@PreDestroy
	public void stop() {
		if (Objects.nonNull(executorService)) {
			log.atInfo()
					.log("Stopping Consumer");
			executorService.shutdownNow();
			final boolean terminated;
			try {
				terminated = executorService.awaitTermination(EXECUTOR_SERVICE_TERMINATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				if (terminated) {
					log.atDebug()
							.addKeyValue("terminated", true)
							.addKeyValue("timeoutSeconds", EXECUTOR_SERVICE_TERMINATE_TIMEOUT_SECONDS)
							.log("Executor Service terminated within timeoutSeconds");
				} else {
					log.atWarn()
							.addKeyValue("terminated", false)
							.addKeyValue("timeoutSeconds", EXECUTOR_SERVICE_TERMINATE_TIMEOUT_SECONDS)
							.log("Unable to terminate Executor Service post timeoutSeconds");
				}
			} catch (InterruptedException e) {
				log.atError()
						.setCause(e)
						.addKeyValue("timeoutSeconds", EXECUTOR_SERVICE_TERMINATE_TIMEOUT_SECONDS)
						.log("Interrupted while waiting to terminate the Executor Service within timeoutSeconds");
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Looks up the stream ARN of the configured Links table.
	 *
	 * @return the table's latest stream ARN.
	 * @throws IllegalStateException if the table has no stream, i.e. DynamoDB Streams is not enabled on it.
	 */
	String resolveStreamArn() {
		final String tableName = awsProperties.getDynamoDb()
				.getTables()
				.get(AwsConstants.TABLE_LINKS);
		final DescribeTableRequest request = DescribeTableRequest.builder()
				.tableName(tableName)
				.build();
		final DescribeTableResponse response = dynamoDbClient.describeTable(request);
		final String arn = response.table().latestStreamArn();
		if (!StringUtils.hasText(arn)) {
			throw new IllegalStateException(("Streams not enabled on table: %s. Enable DynamoDB Streams " +
					"(NEW_AND_OLD_IMAGES) on it and restart").formatted(tableName));
		}
		log.atInfo()
				.addKeyValue("tableName", tableName)
				.addKeyValue("streamArn", arn)
				.log("Resolved Links table stream ARN");
		return arn;
	}

	/**
	 * Adds an iterator for every shard the stream currently has that is not already being tailed.
	 *
	 * <p>
	 *     Closed (fully drained) shards stay visible in {@code DescribeStream} for 24 hours, so a shard removed from
	 *     {@link #shardIterators} by {@link #pollAllShards()} is re-added here. That is harmless: it resumes after its
	 *     checkpoint, reads nothing, and is dropped again as closed.
	 * </p>
	 */
	void discoverShards() {
		log.atDebug()
				.addKeyValue("shardIteratorSize", shardIterators.size())
				.log("Discovering Shards");
		final DescribeStreamRequest request = DescribeStreamRequest.builder()
				.streamArn(streamArn)
				.build();
		final DescribeStreamResponse response = dynamoDbStreamsClient.describeStream(request);
		final List<Shard> shards = response.streamDescription().shards();
		log.atDebug()
				.addKeyValue("shardCount", shards.size())
				.log("Shards Discovered");
		for (final Shard shard : shards) {
			if (!shardIterators.containsKey(shard.shardId())) {
				log.atInfo()
						.addKeyValue("shardId", shard.shardId())
						.log("Discovered new Shard");
			}
			shardIterators.computeIfAbsent(shard.shardId(), this::fetchShardIterator);
		}
		log.atDebug()
				.addKeyValue("shardIteratorSize", shardIterators.size())
				.log("Shard discovery completed");
	}

	/**
	 * Chooses where to start reading a shard.
	 *
	 * <ul>
	 *     <li>
	 *         Checkpoint present: resume right after it
	 *         ({@link ShardIteratorType#AFTER_SEQUENCE_NUMBER AFTER_SEQUENCE_NUMBER}).
	 *     </li>
	 *     <li>
	 *         Checkpoint present but older than the stream's 24 hour retention: events were lost, so restart from
	 *         {@link ShardIteratorType#TRIM_HORIZON TRIM_HORIZON}, the oldest record still available.
	 *     </li>
	 *     <li>
	 *         No checkpoint: {@link ShardIteratorType#TRIM_HORIZON TRIM_HORIZON}.
	 *         {@link ShardIteratorType#LATEST LATEST} would skip anything written to a newly rolled-over shard before
	 *         it was discovered. Re-reading is safe because the mirror upsert is idempotent.
	 *     </li>
	 * </ul>
	 *
	 * @param shardId the shard to read.
	 * @return the shard iterator to read from.
	 */
	private String fetchShardIterator(final String shardId) {
		final Optional<String> checkpoint = streamCheckpointRepository.getSequenceNumber(shardId);
		if (checkpoint.isPresent()) {
			final String sequenceNumber = checkpoint.get();
			log.atInfo()
					.addKeyValue("shardId", shardId)
					.addKeyValue("sequenceNumber", sequenceNumber)
					.log("Resuming Shard after checkpoint");
			try {
				return getShardIterator(shardId, ShardIteratorType.AFTER_SEQUENCE_NUMBER, sequenceNumber);
			} catch (TrimmedDataAccessException e) {
				log.atWarn()
						.setCause(e)
						.addKeyValue("shardId", shardId)
						.addKeyValue("sequenceNumber", sequenceNumber)
						.log("Checkpoint is older than the 24h stream retention. Some events are unrecoverable. " +
								"Starting from TRIM_HORIZON");
				return getShardIterator(shardId, ShardIteratorType.TRIM_HORIZON, null);
			}
		}
		log.atInfo()
				.addKeyValue("shardId", shardId)
				.log("No checkpoint, starting from TRIM_HORIZON");
		return getShardIterator(shardId, ShardIteratorType.TRIM_HORIZON, null);
	}

	/**
	 * Requests a shard iterator from DynamoDB Streams.
	 *
	 * @param shardId        the shard to read.
	 * @param iteratorType   where in the shard to start reading.
	 * @param sequenceNumber the position the iterator type refers to; only sent for
	 *                       {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER AFTER_SEQUENCE_NUMBER},
	 *                       and may be {@code null} otherwise.
	 * @return the shard iterator.
	 */
	private String getShardIterator(final String shardId, final ShardIteratorType iteratorType,
									final String sequenceNumber) {
		final GetShardIteratorRequest.Builder requestBuilder = GetShardIteratorRequest.builder()
				.streamArn(streamArn)
				.shardId(shardId)
				.shardIteratorType(iteratorType);
		if (StringUtils.hasText(sequenceNumber)) {
			requestBuilder.sequenceNumber(sequenceNumber);
		}
		final GetShardIteratorResponse response = dynamoDbStreamsClient.getShardIterator(requestBuilder.build());
		return response.shardIterator();
	}

	/**
	 * The polling loop, run on the single background thread until it is interrupted by {@link #stop()}.
	 *
	 * <p>
	 *     An exception from a cycle is counted and logged, never thrown, so one bad cycle cannot kill the thread.
	 * </p>
	 */
	private void poll() {
		int cyclesSinceLastDiscovery = 0;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				pollAllShards();
				lastSuccessfulPoll.set(Instant.now());
				cyclesSinceLastDiscovery++;
				if (cyclesSinceLastDiscovery >= SHARD_DISCOVERY_INTERVAL_CYCLES) {
					discoverShards();
					cyclesSinceLastDiscovery = 0;
				}
			} catch (Exception e) {
				consumerErrorCounter.increment();
				log.atWarn()
						.setCause(e)
						.log("Error while polling Links DynamoDB Stream");
			}
			sleep();
		}
		log.atInfo()
				.log("Polling loop stopped");
	}

	/**
	 * Polls every tracked shard once.
	 *
	 * <p>
	 *     A shard whose next iterator is {@code null} is closed and fully drained, so it is removed. A shard that
	 *     throws is also removed (and counted as an error) so that it cannot hold up the others. Discovery re-adds it and
	 *     it resumes from its checkpoint, so nothing is skipped.
	 * </p>
	 */
	void pollAllShards() {
		final Iterator<Map.Entry<String, String>> iterator = shardIterators.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<String, String> entry = iterator.next();
			try {
				final String nextIterator = pollShard(entry.getKey(), entry.getValue());
				if (Objects.isNull(nextIterator)) {
					log.atInfo()
							.addKeyValue("shardId", entry.getKey())
							.log("Shard closed and fully drained");
					iterator.remove(); // shard closed and fully drained
				} else {
					entry.setValue(nextIterator);
				}
			} catch (Exception e) {
				consumerErrorCounter.increment();
				log.atWarn()
						.setCause(e)
						.addKeyValue("shardId", entry.getKey())
						.log("Error polling shard, dropping it - will be rediscovered if still open");
				iterator.remove();
			}
		}
	}

	/**
	 * Reads up to 100 records from one shard and processes them as a batch.
	 *
	 * <p>
	 *     The order is deliberate: mirror every event, archive the batch, and only then save the checkpoint. If
	 *     anything throws before the checkpoint is saved, the batch is read again. An empty batch (the common case) does
	 *     nothing and does not touch the checkpoint.
	 * </p>
	 *
	 * @param shardId       the shard being read, used as the checkpoint key.
	 * @param shardIterator the iterator to read from.
	 * @return the iterator for the next read, or {@code null} if the shard is closed and fully drained.
	 */
	private String pollShard(final String shardId, final String shardIterator) {
		final GetRecordsRequest request = GetRecordsRequest.builder()
				.shardIterator(shardIterator)
				.limit(100)
				.build();
		final GetRecordsResponse response = dynamoDbStreamsClient.getRecords(request);
		final List<Record> records = response.records();
		if (!CollectionUtils.isEmpty(records)) {
			final List<LinkLifecycleEvent> events = records.stream()
					.map(this::toLifecycleEvent)
					.toList();
			events.forEach(this::mirrorEvent);
			linkLifecycleEventArchiver.archive(events);
			final String lastRecordSequenceNumber = records.getLast().dynamodb().sequenceNumber();
			streamCheckpointRepository.saveSequenceNumber(shardId, lastRecordSequenceNumber);
			log.atInfo()
					.addKeyValue("shardId",shardId)
					.addKeyValue("recordCount", records.size())
					.addKeyValue("lastSequenceNumber", lastRecordSequenceNumber)
					.log("Processed stream batch");
		}
		return response.nextShardIterator();
	}

	/**
	 * Maps a stream record to a {@link LinkLifecycleEvent}, logging the record's identity on failure since the mapper's
	 * own exception does not say which record it was processing. Rethrows so the shard is dropped and retried.
	 *
	 * @param record the raw stream record.
	 * @return the equivalent lifecycle event.
	 */
	private LinkLifecycleEvent toLifecycleEvent(final Record record) {
		try {
			return linkLifecycleEventMapper.toDomain(record);
		} catch (RuntimeException e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("eventName", record.eventNameAsString())
					.addKeyValue("sequenceNumber", record.dynamodb().sequenceNumber())
					.log("Error mapping stream record to Link Lifecycle Event");
			throw e;
		}
	}

	/**
	 * Applies one lifecycle event to the mirror.
	 *
	 * <p>
	 *     {@link io.urlshortener.eventcontracts.LinkLifecycleEventType#CREATED CREATED} and
	 *     {@link io.urlshortener.eventcontracts.LinkLifecycleEventType#UPDATED UPDATED} carry the link's new state.
	 *     A delete has no new state, so {@link io.urlshortener.eventcontracts.LinkLifecycleEventType#DELETED DELETED}
	 *     and {@link io.urlshortener.eventcontracts.LinkLifecycleEventType#EXPIRED EXPIRED} mirror the <em>old</em>
	 *     image as a soft delete, stamped with the time this consumer saw it (not the time of the delete itself, which
	 *     the event does not carry) and the reason that distinguishes the two.
	 * </p>
	 *
	 * <p>
	 *     Failures are logged with the link's identity and then rethrown. They must not be swallowed: the checkpoint is
	 *     saved after the batch, so swallowing would advance it past a link that never reached Postgres.
	 * </p>
	 *
	 * @param event the event to apply.
	 */
	private void mirrorEvent(final LinkLifecycleEvent event) {
		try {
			final MirroredLink mirroredLink = switch (event.eventType()) {
				case CREATED, UPDATED ->
						linkMirrorMapper.toDomain(event.newLink(), null, null);
				case DELETED ->
						linkMirrorMapper.toDomain(event.oldLink(), Instant.now(), DeletionReason.EXPLICIT_DELETE);
				case EXPIRED ->
						linkMirrorMapper.toDomain(event.oldLink(), Instant.now(), DeletionReason.TTL_EXPIRY);
			};
			linksMirrorRepository.upsertLink(mirroredLink);
			log.atDebug()
					.addKeyValue("shortCode", mirroredLink.shortCode())
					.addKeyValue("eventType", event.eventType())
					.addKeyValue("deletionReason", mirroredLink.deletionReason())
					.log("Event Upserted");
		} catch (RuntimeException e) {
			final Link link = Objects.nonNull(event.newLink()) ? event.newLink() : event.oldLink();
			log.atWarn()
					.setCause(e)
					.addKeyValue("shortCode", Objects.nonNull(link) ? link.shortCode() : null)
					.addKeyValue("eventType", event.eventType())
					.addKeyValue("sequenceNumber", event.sequenceNumber())
					.log("Error Upserting Event, the shard will be retried from its checkpoint");
			throw e;
		}
	}

	/**
	 * Waits for {@link #POLL_INTERVAL}, restoring the interrupt flag if interrupted so {@link #poll()} can exit.
	 */
	private void sleep() {
		try {
			Thread.sleep(POLL_INTERVAL.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
