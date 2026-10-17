package io.urlshortener.analyticsservice.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import io.urlshortener.eventcontracts.LinkLifecycleEventType;
import io.urlshortener.linkscontract.Link;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LinksStreamConsumerTest {

	private static final String STREAM_ARN =
			"arn:aws:dynamodb:us-west-2:000000000000:table/Links/stream/2026-01-01T00:00:00.000";

	private static final String SHARD_ID = "shard-1";

	private static final String SEQUENCE_NUMBER_1 = "100000000000000000001";

	private static final String SEQUENCE_NUMBER_2 = "100000000000000000002";

	@Mock
	private DynamoDbClient dynamoDbClient;

	@Mock
	private DynamoDbStreamsClient dynamoDbStreamsClient;

	@Mock
	private LinkLifecycleEventMapper linkLifecycleEventMapper;

	@Mock
	private LinksMirrorRepository linksMirrorRepository;

	@Mock
	private StreamCheckpointRepository streamCheckpointRepository;

	@Mock
	private LinkLifecycleEventArchiver linkLifecycleEventArchiver;

	private SimpleMeterRegistry meterRegistry;

	private LinksStreamConsumer consumer;

	@BeforeEach
	void setUp() {
		final AwsProperties.DynamoDb dynamoDb = new AwsProperties.DynamoDb();
		dynamoDb.setTables(Map.of(AwsConstants.TABLE_LINKS, "Links"));
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setDynamoDb(dynamoDb);

		meterRegistry = new SimpleMeterRegistry();
		// LinkMirrorMapper is a pure function, so the real one is used and the MirroredLink handed to the repository
		// can be asserted on directly.
		consumer = new LinksStreamConsumer(dynamoDbClient, dynamoDbStreamsClient, awsProperties,
				linkLifecycleEventMapper, new LinkMirrorMapper(), linksMirrorRepository, streamCheckpointRepository,
				linkLifecycleEventArchiver, meterRegistry);
		consumer.streamArn = STREAM_ARN;
	}

	private double errorCount() {
		return meterRegistry.counter("streams.consumer.errors").count();
	}

	@Test
	void start_shouldNotThrowAndShouldIncrementErrorCounter_whenResolveStreamArnFails() {
		// Arrange
		given(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
				.willThrow(new RuntimeException("DynamoDB unreachable"));

		// Act & Assert
		assertThatCode(() -> consumer.start()).doesNotThrowAnyException();
		assertThat(errorCount()).isEqualTo(1.0);
	}

	@Test
	void stop_shouldNotThrow_whenConsumerWasNeverStarted() {
		// Act & Assert
		assertThatCode(() -> consumer.stop()).doesNotThrowAnyException();
	}

	@Test
	void resolveStreamArn_shouldReturnLatestStreamArn_whenTableIsDescribed() {
		// Arrange
		final DescribeTableResponse response = DescribeTableResponse.builder()
				.table(TableDescription.builder().latestStreamArn(STREAM_ARN).build())
				.build();
		given(dynamoDbClient.describeTable(any(DescribeTableRequest.class))).willReturn(response);

		// Act
		final String result = consumer.resolveStreamArn();

		// Assert
		assertThat(result).isEqualTo(STREAM_ARN);
	}

	@Test
	void resolveStreamArn_shouldThrowIllegalStateException_whenTableHasNoStream() {
		// Arrange
		final DescribeTableResponse response = DescribeTableResponse.builder()
				.table(TableDescription.builder().build())
				.build();
		given(dynamoDbClient.describeTable(any(DescribeTableRequest.class))).willReturn(response);

		// Act & Assert
		assertThatThrownBy(() -> consumer.resolveStreamArn())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Links");
	}

	@Test
	void discoverShards_shouldStartFromTrimHorizon_whenShardHasNoCheckpoint() {
		// Arrange
		givenStreamHasShard();
		given(streamCheckpointRepository.getSequenceNumber(SHARD_ID)).willReturn(Optional.empty());
		given(dynamoDbStreamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
				.willReturn(GetShardIteratorResponse.builder().shardIterator("iterator-1").build());

		// Act
		consumer.discoverShards();

		// Assert
		final ArgumentCaptor<GetShardIteratorRequest> captor = ArgumentCaptor.forClass(GetShardIteratorRequest.class);
		verify(dynamoDbStreamsClient).getShardIterator(captor.capture());
		// LATEST would silently skip whatever a newly rolled-over shard received before it was discovered.
		assertThat(captor.getValue().shardIteratorType()).isEqualTo(ShardIteratorType.TRIM_HORIZON);
		assertThat(captor.getValue().sequenceNumber()).isNull();
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "iterator-1");
	}

	@Test
	void discoverShards_shouldResumeAfterCheckpoint_whenShardHasCheckpoint() {
		// Arrange
		givenStreamHasShard();
		given(streamCheckpointRepository.getSequenceNumber(SHARD_ID)).willReturn(Optional.of(SEQUENCE_NUMBER_1));
		given(dynamoDbStreamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
				.willReturn(GetShardIteratorResponse.builder().shardIterator("iterator-1").build());

		// Act
		consumer.discoverShards();

		// Assert
		final ArgumentCaptor<GetShardIteratorRequest> captor = ArgumentCaptor.forClass(GetShardIteratorRequest.class);
		verify(dynamoDbStreamsClient).getShardIterator(captor.capture());
		assertThat(captor.getValue().shardIteratorType()).isEqualTo(ShardIteratorType.AFTER_SEQUENCE_NUMBER);
		assertThat(captor.getValue().sequenceNumber()).isEqualTo(SEQUENCE_NUMBER_1);
	}

	@Test
	void discoverShards_shouldFallBackToTrimHorizon_whenCheckpointIsOlderThanStreamRetention() {
		// Arrange
		givenStreamHasShard();
		given(streamCheckpointRepository.getSequenceNumber(SHARD_ID)).willReturn(Optional.of(SEQUENCE_NUMBER_1));
		given(dynamoDbStreamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
				.willThrow(TrimmedDataAccessException.builder().message("trimmed").build())
				.willReturn(GetShardIteratorResponse.builder().shardIterator("iterator-trim").build());

		// Act
		consumer.discoverShards();

		// Assert
		final ArgumentCaptor<GetShardIteratorRequest> captor = ArgumentCaptor.forClass(GetShardIteratorRequest.class);
		verify(dynamoDbStreamsClient, times(2)).getShardIterator(captor.capture());
		assertThat(captor.getAllValues())
				.extracting(GetShardIteratorRequest::shardIteratorType)
				.containsExactly(ShardIteratorType.AFTER_SEQUENCE_NUMBER, ShardIteratorType.TRIM_HORIZON);
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "iterator-trim");
	}

	@Test
	void discoverShards_shouldNotOverwriteExistingIterator_whenShardIsAlreadyTracked() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "existing-iterator");
		givenStreamHasShard();

		// Act
		consumer.discoverShards();

		// Assert
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "existing-iterator");
		verify(dynamoDbStreamsClient, never()).getShardIterator(any(GetShardIteratorRequest.class));
		verifyNoInteractions(streamCheckpointRepository);
	}

	@Test
	void pollAllShards_shouldOnlyAdvanceIterator_whenBatchIsEmpty() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(GetRecordsResponse.builder().records(List.of()).nextShardIterator("iterator-2").build());

		// Act
		consumer.pollAllShards();

		// Assert
		// Most polls are empty. Regression coverage: reading the last record of an empty batch used to throw, which
		// dropped the shard on every idle poll.
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "iterator-2");
		assertThat(errorCount()).isZero();
		verifyNoInteractions(linkLifecycleEventMapper, linksMirrorRepository, linkLifecycleEventArchiver,
				streamCheckpointRepository);
	}

	@Test
	void pollAllShards_shouldMirrorThenArchiveThenCheckpoint_whenRecordsArePresent() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		final Record first = record(SEQUENCE_NUMBER_1);
		final Record second = record(SEQUENCE_NUMBER_2);
		final LinkLifecycleEvent firstEvent = event(LinkLifecycleEventType.CREATED, null,
				link("aaa1111"), SEQUENCE_NUMBER_1);
		final LinkLifecycleEvent secondEvent = event(LinkLifecycleEventType.UPDATED, link("bbb2222"),
				link("bbb2222"), SEQUENCE_NUMBER_2);
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(
						GetRecordsResponse.builder()
								.records(first, second)
								.nextShardIterator("iterator-2")
								.build()
				);
		given(linkLifecycleEventMapper.toDomain(first)).willReturn(firstEvent);
		given(linkLifecycleEventMapper.toDomain(second)).willReturn(secondEvent);

		// Act
		consumer.pollAllShards();

		// Assert
		// The checkpoint must come last: if anything earlier throws, the batch is read again.
		final InOrder inOrder = inOrder(linksMirrorRepository, linkLifecycleEventArchiver, streamCheckpointRepository);
		inOrder.verify(linksMirrorRepository, times(2)).upsertLink(any(MirroredLink.class));
		inOrder.verify(linkLifecycleEventArchiver).archive(List.of(firstEvent, secondEvent));
		inOrder.verify(streamCheckpointRepository).saveSequenceNumber(SHARD_ID, SEQUENCE_NUMBER_2);
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "iterator-2");
		assertThat(errorCount()).isZero();
	}

	@ParameterizedTest
	@EnumSource(value = LinkLifecycleEventType.class, names = {"CREATED", "UPDATED"})
	void pollAllShards_shouldUpsertNewLinkAsLive_whenEventIsCreatedOrUpdated(final LinkLifecycleEventType type) {
		// Arrange
		final Link newLink = link("abc1234");
		final Link oldLink = type == LinkLifecycleEventType.UPDATED ? link("abc1234") : null;

		// Act
		final MirroredLink mirrored = pollSingleEvent(event(type, oldLink, newLink, SEQUENCE_NUMBER_1));

		// Assert
		assertThat(mirrored.shortCode()).isEqualTo("abc1234");
		assertThat(mirrored.createdAt()).isEqualTo(Instant.ofEpochMilli(newLink.createdAt()));
		assertThat(mirrored.deletedAt()).isNull();
		assertThat(mirrored.deletionReason()).isNull();
	}

	@Test
	void pollAllShards_shouldUpsertOldLinkAsExplicitDelete_whenEventIsDeleted() {
		// Arrange
		final Link oldLink = link("abc1234");
		final Instant before = Instant.now();

		// Act
		final MirroredLink mirrored = pollSingleEvent(
				event(LinkLifecycleEventType.DELETED, oldLink, null, SEQUENCE_NUMBER_1));

		// Assert
		// A delete has no new image, so the mirrored state comes from the old one.
		assertThat(mirrored.shortCode()).isEqualTo("abc1234");
		assertThat(mirrored.deletionReason()).isEqualTo(DeletionReason.EXPLICIT_DELETE);
		assertThat(mirrored.deletedAt()).isBetween(before, Instant.now());
	}

	@Test
	void pollAllShards_shouldUpsertOldLinkAsTtlExpiry_whenEventIsExpired() {
		// Arrange
		final Link oldLink = link("abc1234");
		final Instant before = Instant.now();

		// Act
		final MirroredLink mirrored = pollSingleEvent(
				event(LinkLifecycleEventType.EXPIRED, oldLink, null, SEQUENCE_NUMBER_1));

		// Assert
		assertThat(mirrored.shortCode()).isEqualTo("abc1234");
		assertThat(mirrored.deletionReason()).isEqualTo(DeletionReason.TTL_EXPIRY);
		assertThat(mirrored.deletedAt()).isBetween(before, Instant.now());
	}

	@Test
	void pollAllShards_shouldNotArchiveOrCheckpointAndShouldDropShard_whenUpsertFails() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		final Record record = record(SEQUENCE_NUMBER_1);
		final LinkLifecycleEvent event = event(LinkLifecycleEventType.CREATED, null, link("abc1234"),
				SEQUENCE_NUMBER_1);
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(GetRecordsResponse.builder().records(record).nextShardIterator("iterator-2").build());
		given(linkLifecycleEventMapper.toDomain(record)).willReturn(event);
		org.mockito.Mockito.doThrow(new RuntimeException("Postgres unavailable"))
				.when(linksMirrorRepository).upsertLink(any(MirroredLink.class));

		// Act
		consumer.pollAllShards();

		// Assert
		// Swallowing the failure would let the checkpoint move past a link that never reached Postgres.
		verify(linkLifecycleEventArchiver, never()).archive(anyList());
		verify(streamCheckpointRepository, never()).saveSequenceNumber(anyString(), anyString());
		assertThat(consumer.shardIterators).doesNotContainKey(SHARD_ID);
		assertThat(errorCount()).isEqualTo(1.0);
	}

	@Test
	void pollAllShards_shouldNotCheckpointAndShouldDropShard_whenArchiveFails() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		final Record record = record(SEQUENCE_NUMBER_1);
		final LinkLifecycleEvent event = event(LinkLifecycleEventType.CREATED, null, link("abc1234"),
				SEQUENCE_NUMBER_1);
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(GetRecordsResponse.builder().records(record).nextShardIterator("iterator-2").build());
		given(linkLifecycleEventMapper.toDomain(record)).willReturn(event);
		org.mockito.Mockito.doThrow(new RuntimeException("S3 unavailable"))
				.when(linkLifecycleEventArchiver).archive(anyList());

		// Act
		consumer.pollAllShards();

		// Assert
		verify(streamCheckpointRepository, never()).saveSequenceNumber(anyString(), anyString());
		assertThat(consumer.shardIterators).doesNotContainKey(SHARD_ID);
		assertThat(errorCount()).isEqualTo(1.0);
	}

	@Test
	void pollAllShards_shouldNotTouchMirrorOrCheckpointAndShouldDropShard_whenMappingFails() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		final Record record = record(SEQUENCE_NUMBER_1);
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(GetRecordsResponse.builder().records(record).nextShardIterator("iterator-2").build());
		given(linkLifecycleEventMapper.toDomain(record)).willThrow(new IllegalStateException("bad record"));

		// Act
		consumer.pollAllShards();

		// Assert
		verifyNoInteractions(linksMirrorRepository, linkLifecycleEventArchiver, streamCheckpointRepository);
		assertThat(consumer.shardIterators).doesNotContainKey(SHARD_ID);
		assertThat(errorCount()).isEqualTo(1.0);
	}

	@Test
	void pollAllShards_shouldRemoveShard_whenNextShardIteratorIsNull() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(GetRecordsResponse.builder().records(List.of()).nextShardIterator((String) null).build());

		// Act
		consumer.pollAllShards();

		// Assert
		assertThat(consumer.shardIterators).doesNotContainKey(SHARD_ID);
		assertThat(errorCount()).isZero();
	}

	@Test
	void pollAllShards_shouldRemoveShardAndIncrementErrorCounter_whenGetRecordsThrows() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class))).willThrow(new RuntimeException("boom"));

		// Act
		consumer.pollAllShards();

		// Assert
		assertThat(consumer.shardIterators).doesNotContainKey(SHARD_ID);
		assertThat(errorCount()).isEqualTo(1.0);
	}

	/**
	 * Drives one event through {@link LinksStreamConsumer#pollAllShards()} and returns what reached the mirror.
	 */
	private MirroredLink pollSingleEvent(final LinkLifecycleEvent event) {
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		final Record record = record(event.sequenceNumber());
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(GetRecordsResponse.builder().records(record).nextShardIterator("iterator-2").build());
		given(linkLifecycleEventMapper.toDomain(record)).willReturn(event);

		consumer.pollAllShards();

		final ArgumentCaptor<MirroredLink> captor = ArgumentCaptor.forClass(MirroredLink.class);
		verify(linksMirrorRepository).upsertLink(captor.capture());
		return captor.getValue();
	}

	private void givenStreamHasShard() {
		given(dynamoDbStreamsClient.describeStream(any(DescribeStreamRequest.class)))
				.willReturn(DescribeStreamResponse.builder()
						.streamDescription(StreamDescription.builder()
								.shards(Shard.builder().shardId(SHARD_ID).build())
								.build())
						.build());
	}

	private Record record(final String sequenceNumber) {
		return Record.builder()
				.eventName(OperationType.INSERT)
				.dynamodb(StreamRecord.builder().sequenceNumber(sequenceNumber).build())
				.build();
	}

	private LinkLifecycleEvent event(final LinkLifecycleEventType type, final Link oldLink, final Link newLink,
									 final String sequenceNumber) {
		return new LinkLifecycleEvent(type, oldLink, newLink, sequenceNumber);
	}

	private Link link(final String shortCode) {
		return new Link(
				shortCode,
				"https://example.com",
				"owner-1",
				1_700_000_000_000L,
				1_800_000_000L,
				"hash",
				null
		);
	}

}
