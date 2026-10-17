package io.urlshortener.analyticsservice.mapper.dbMapper;

import io.urlshortener.eventcontracts.LinkLifecycleEvent;
import io.urlshortener.eventcontracts.LinkLifecycleEventType;
import io.urlshortener.linkscontract.Link;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Identity;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class LinkLifecycleEventMapperTest {

	private static final String SEQUENCE_NUMBER = "100000000000000000001";

	private static final Map<String, AttributeValue> OLD_IMAGE = Map.of(
			"shortCode",
			AttributeValue.builder().s("old").build()
	);

	private static final Map<String, AttributeValue> NEW_IMAGE = Map.of(
			"shortCode",
			AttributeValue.builder().s("new").build()
	);

	private static final Link OLD_LINK = new Link(
			"old",
			"https://old.example.com",
			null,
			1L,
			null,
			"h",
			null
	);

	private static final Link NEW_LINK = new Link(
			"new",
			"https://new.example.com",
			null,
			2L,
			null,
			"h",
			null
	);

	@Mock
	private LinkAttributeMapper linkAttributeMapper;

	private LinkLifecycleEventMapper mapper;

	@BeforeEach
	void setUp() {
		mapper = new LinkLifecycleEventMapper(linkAttributeMapper);
	}

	@Test
	void toDomain_shouldReturnCreatedWithOnlyNewLink_whenOperationIsInsert() {
		// Arrange
		given(linkAttributeMapper.toObject(NEW_IMAGE)).willReturn(NEW_LINK);
		final Record record = record(OperationType.INSERT, null, NEW_IMAGE, null);

		// Act
		final LinkLifecycleEvent event = mapper.toDomain(record);

		// Assert
		assertThat(event.eventType()).isEqualTo(LinkLifecycleEventType.CREATED);
		assertThat(event.oldLink()).isNull();
		assertThat(event.newLink()).isEqualTo(NEW_LINK);
		assertThat(event.sequenceNumber()).isEqualTo(SEQUENCE_NUMBER);
		verify(linkAttributeMapper).toObject(NEW_IMAGE);
		verifyNoMoreInteractions(linkAttributeMapper);
	}

	@Test
	void toDomain_shouldReturnUpdatedWithBothLinks_whenOperationIsModify() {
		// Arrange
		given(linkAttributeMapper.toObject(OLD_IMAGE)).willReturn(OLD_LINK);
		given(linkAttributeMapper.toObject(NEW_IMAGE)).willReturn(NEW_LINK);
		final Record record = record(OperationType.MODIFY, OLD_IMAGE, NEW_IMAGE, null);

		// Act
		final LinkLifecycleEvent event = mapper.toDomain(record);

		// Assert
		assertThat(event.eventType()).isEqualTo(LinkLifecycleEventType.UPDATED);
		assertThat(event.oldLink()).isEqualTo(OLD_LINK);
		assertThat(event.newLink()).isEqualTo(NEW_LINK);
	}

	@Test
	void toDomain_shouldReturnExpiredWithOnlyOldLink_whenRemoveWasPerformedByDynamoDbItself() {
		// Arrange
		given(linkAttributeMapper.toObject(OLD_IMAGE)).willReturn(OLD_LINK);
		final Record record = record(OperationType.REMOVE, OLD_IMAGE, null,
				Identity.builder().type("Service").principalId("dynamodb.amazonaws.com").build());

		// Act
		final LinkLifecycleEvent event = mapper.toDomain(record);

		// Assert
		assertThat(event.eventType()).isEqualTo(LinkLifecycleEventType.EXPIRED);
		assertThat(event.oldLink()).isEqualTo(OLD_LINK);
		assertThat(event.newLink()).isNull();
	}

	@Test
	void toDomain_shouldReturnDeleted_whenRemoveHasNoUserIdentity() {
		// Arrange
		given(linkAttributeMapper.toObject(OLD_IMAGE)).willReturn(OLD_LINK);
		final Record record = record(OperationType.REMOVE, OLD_IMAGE, null, null);

		// Act
		final LinkLifecycleEvent event = mapper.toDomain(record);

		// Assert
		assertThat(event.eventType()).isEqualTo(LinkLifecycleEventType.DELETED);
		assertThat(event.oldLink()).isEqualTo(OLD_LINK);
		assertThat(event.newLink()).isNull();
	}

	@Test
	void toDomain_shouldReturnDeleted_whenRemoveWasPerformedByADifferentPrincipal() {
		// Arrange
		given(linkAttributeMapper.toObject(OLD_IMAGE)).willReturn(OLD_LINK);
		final Record record = record(OperationType.REMOVE, OLD_IMAGE, null,
				Identity.builder().type("Service").principalId("some-other-service.amazonaws.com").build());

		// Act
		final LinkLifecycleEvent event = mapper.toDomain(record);

		// Assert
		assertThat(event.eventType()).isEqualTo(LinkLifecycleEventType.DELETED);
	}

	@Test
	void toDomain_shouldThrowIllegalStateException_whenOperationIsNotRecognised() {
		// Arrange
		final Record record = Record.builder()
				.eventName("SOMETHING_NEW")
				.dynamodb(StreamRecord.builder().sequenceNumber(SEQUENCE_NUMBER).build())
				.build();

		// Act & Assert
		assertThatThrownBy(() -> mapper.toDomain(record))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Unexpected DynamoDB Streams event");
	}

	private Record record(final OperationType operation, final Map<String, AttributeValue> oldImage,
						  final Map<String, AttributeValue> newImage, final Identity userIdentity) {
		final StreamRecord.Builder streamRecord = StreamRecord.builder().sequenceNumber(SEQUENCE_NUMBER);
		if (oldImage != null) {
			streamRecord.oldImage(oldImage);
		}
		if (newImage != null) {
			streamRecord.newImage(newImage);
		}
		return Record.builder()
				.eventName(operation)
				.userIdentity(userIdentity)
				.dynamodb(streamRecord.build())
				.build();
	}

}
