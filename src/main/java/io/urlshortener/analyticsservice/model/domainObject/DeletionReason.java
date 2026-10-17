package io.urlshortener.analyticsservice.model.domainObject;

/**
 * Why a mirrored link was deleted, derived from how the DynamoDB Streams
 * {@link software.amazon.awssdk.services.dynamodb.model.OperationType#REMOVE REMOVE} record was produced.
 */
public enum DeletionReason {

	/**
	 * DynamoDB removed the item itself because its TTL ({@code expiresAt}) elapsed.
	 */
	TTL_EXPIRY,

	/**
	 * A client deleted the item.
	 */
	EXPLICIT_DELETE

}
