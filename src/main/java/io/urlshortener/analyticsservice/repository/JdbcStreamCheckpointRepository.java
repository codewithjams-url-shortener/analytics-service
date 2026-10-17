package io.urlshortener.analyticsservice.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link StreamCheckpointRepository} backed by Postgres through Spring's {@link JdbcClient}.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcStreamCheckpointRepository implements StreamCheckpointRepository {

	private static final String GET_SEQUENCE_NUMBER_QUERY = """
			SELECT sequence_number
			FROM stream_checkpoints
			WHERE shard_id = :shardId
			""";

	/**
	 * An upsert, since the first save for a shard has no row to update. {@code updated_at} is bumped explicitly because
	 * its column default only applies on insert.
	 */
	private static final String SAVE_SEQUENCE_NUMBER_QUERY = """
			INSERT INTO stream_checkpoints (shard_id, sequence_number)
			VALUES (:shardId, :sequenceNumber)
			ON CONFLICT (shard_id) DO UPDATE SET
			sequence_number = EXCLUDED.sequence_number,
			updated_at = now()
			""";

	private final JdbcClient jdbcClient;

	@Override
	public Optional<String> getSequenceNumber(final String shardId) {
		final JdbcClient.StatementSpec statementSpec = jdbcClient.sql(GET_SEQUENCE_NUMBER_QUERY)
				.param("shardId", shardId);
		final Optional<String> result = statementSpec.query(String.class).optional();
		log.atInfo()
				.addKeyValue("fetched", result.isPresent())
				.log("Fetch Sequence Number");
		return result;
	}

	@Override
	public void saveSequenceNumber(final String shardId, final String sequenceNumber) {
		final JdbcClient.StatementSpec statementSpec = jdbcClient.sql(SAVE_SEQUENCE_NUMBER_QUERY)
				.param("shardId", shardId)
				.param("sequenceNumber", sequenceNumber);
		final int rowsUpdated = statementSpec.update();
		log.atInfo()
				.addKeyValue("updated", rowsUpdated > 0)
				.addKeyValue("rowsAffected", rowsUpdated)
				.log("Sequence Number saved");
	}

}
