package io.urlshortener.analyticsservice.repository;

import java.util.Optional;

/**
 * Durable record of how far the Links stream consumer has read each shard, so a restart resumes where the last run
 * stopped instead of replaying from the beginning or missing events.
 */
public interface StreamCheckpointRepository {

	/**
	 * Looks up how far a shard has been read.
	 *
	 * @param shardId the shard to look up.
	 * @return the last successfully processed sequence number for this shard, or empty if this shard has never been
	 * checkpointed.
	 */
	Optional<String> getSequenceNumber(String shardId);

	/**
	 * Records the last successfully processed sequence number for a shard.
	 *
	 * @param shardId        the shard being checkpointed.
	 * @param sequenceNumber the sequence number of the last record successfully processed.
	 */
	void saveSequenceNumber(String shardId, String sequenceNumber);

}
