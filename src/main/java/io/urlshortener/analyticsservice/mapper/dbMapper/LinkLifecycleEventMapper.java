package io.urlshortener.analyticsservice.mapper.dbMapper;

import io.urlshortener.eventcontracts.LinkLifecycleEvent;
import io.urlshortener.eventcontracts.LinkLifecycleEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.Record;

import java.util.Objects;

/**
 * Converts a raw DynamoDB Streams {@link Record} on the Links table into a {@link LinkLifecycleEvent}.
 *
 * <p>
 *     This is where a TTL expiry is told apart from an explicit delete. Both arrive as a
 *     {@link software.amazon.awssdk.services.dynamodb.model.OperationType#REMOVE REMOVE} record, but DynamoDB
 *     performs a TTL deletion itself, as the service principal {@value #TTL_PRINCIPAL_ID}, which it stamps on the
 *     record's {@code userIdentity}. An explicit delete carries no such identity.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class LinkLifecycleEventMapper {

	/**
	 * The {@code userIdentity.principalId} DynamoDB sets on records it deleted itself because an item's TTL elapsed.
	 */
	private static final String TTL_PRINCIPAL_ID = "dynamodb.amazonaws.com";

	/**
	 * Converts the record's before/after images into {@link io.urlshortener.linkscontract.Link Link}s.
	 */
	private final LinkAttributeMapper linkAttributeMapper;

	/**
	 * Converts a stream record into a lifecycle event.
	 *
	 * <p>
	 *     Which images a record carries depends on its operation: an
	 *     {@link software.amazon.awssdk.services.dynamodb.model.OperationType#INSERT INSERT} has only a new image,
	 *     a {@link software.amazon.awssdk.services.dynamodb.model.OperationType#REMOVE REMOVE} only an old image,
	 *     and a {@link software.amazon.awssdk.services.dynamodb.model.OperationType#MODIFY MODIFY} both.
	 *     This relies on the stream view type being {@code NEW_AND_OLD_IMAGES}.
	 * </p>
	 *
	 * @param record the raw stream record.
	 * @return the event, carrying the record's sequence number so consumers can dedupe on it.
	 * @throws IllegalStateException if the record's operation is not
	 * {@link software.amazon.awssdk.services.dynamodb.model.OperationType#INSERT INSERT},
	 * {@link software.amazon.awssdk.services.dynamodb.model.OperationType#MODIFY MODIFY} or
	 * {@link software.amazon.awssdk.services.dynamodb.model.OperationType#REMOVE REMOVE}.
	 * @throws io.urlshortener.analyticsservice.exception.CorruptedDataException if an image is missing a required
	 *                                                                           attribute.
	 */
	public LinkLifecycleEvent toDomain(final Record record) {
		final String sequenceNumber = record.dynamodb().sequenceNumber();
		return switch (record.eventName()) {
			case INSERT -> new LinkLifecycleEvent(
					LinkLifecycleEventType.CREATED,
					null,
					linkAttributeMapper.toObject(record.dynamodb().newImage()),
					sequenceNumber
			);
			case MODIFY -> new LinkLifecycleEvent(
					LinkLifecycleEventType.UPDATED,
					linkAttributeMapper.toObject(record.dynamodb().oldImage()),
					linkAttributeMapper.toObject(record.dynamodb().newImage()),
					sequenceNumber
			);
			case REMOVE -> new LinkLifecycleEvent(
					isTtlDriven(record) ? LinkLifecycleEventType.EXPIRED : LinkLifecycleEventType.DELETED,
					linkAttributeMapper.toObject(record.dynamodb().oldImage()),
					null,
					sequenceNumber
			);
			default -> throw new IllegalStateException("Unexpected DynamoDB Streams event: %s".formatted(record.eventName()));
		};
	}

	/**
	 * @param record a {@link software.amazon.awssdk.services.dynamodb.model.OperationType#REMOVE REMOVE} record.
	 * @return {@code true} if DynamoDB deleted the item itself because its TTL elapsed, {@code false} if a client
	 * deleted it. {@code userIdentity} is absent on records not produced by a service principal, hence the null check.
	 */
	private boolean isTtlDriven(final Record record) {
		return Objects.nonNull(record.userIdentity()) && TTL_PRINCIPAL_ID.equals(record.userIdentity().principalId());
	}

}
