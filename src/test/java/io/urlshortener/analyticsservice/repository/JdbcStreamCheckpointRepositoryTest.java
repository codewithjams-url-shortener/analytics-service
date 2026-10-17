package io.urlshortener.analyticsservice.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JdbcStreamCheckpointRepositoryTest {

	private static final String SHARD_ID = "shardId-00000001-abcdef";

	private static final String SEQUENCE_NUMBER = "100000000000000000001";

	@Mock
	private JdbcClient jdbcClient;

	@Mock(answer = Answers.RETURNS_SELF)
	private JdbcClient.StatementSpec statementSpec;

	@Mock
	private JdbcClient.MappedQuerySpec<String> querySpec;

	private JdbcStreamCheckpointRepository repository;

	@BeforeEach
	void setUp() {
		repository = new JdbcStreamCheckpointRepository(jdbcClient);
		given(jdbcClient.sql(anyString())).willReturn(statementSpec);
	}

	@Test
	void getSequenceNumber_shouldReturnSequenceNumber_whenShardHasBeenCheckpointed() {
		// Arrange
		given(statementSpec.query(String.class)).willReturn(querySpec);
		given(querySpec.optional()).willReturn(Optional.of(SEQUENCE_NUMBER));

		// Act
		final Optional<String> result = repository.getSequenceNumber(SHARD_ID);

		// Assert
		assertThat(result).contains(SEQUENCE_NUMBER);
		verify(statementSpec).param("shardId", SHARD_ID);
	}

	@Test
	void getSequenceNumber_shouldReturnEmpty_whenShardHasNeverBeenCheckpointed() {
		// Arrange
		given(statementSpec.query(String.class)).willReturn(querySpec);
		given(querySpec.optional()).willReturn(Optional.empty());

		// Act
		final Optional<String> result = repository.getSequenceNumber(SHARD_ID);

		// Assert
		assertThat(result).isEmpty();
	}

	@Test
	void saveSequenceNumber_shouldBindShardIdAndSequenceNumber_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);

		// Act
		repository.saveSequenceNumber(SHARD_ID, SEQUENCE_NUMBER);

		// Assert
		verify(statementSpec).param("shardId", SHARD_ID);
		verify(statementSpec).param("sequenceNumber", SEQUENCE_NUMBER);
	}

	@Test
	void saveSequenceNumber_shouldIssueAnUpsertOnShardId_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);

		// Act
		repository.saveSequenceNumber(SHARD_ID, SEQUENCE_NUMBER);

		// Assert
		// An upsert, because a shard's first checkpoint has no row to update.
		final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcClient).sql(sqlCaptor.capture());
		assertThat(sqlCaptor.getValue())
				.contains("INSERT INTO stream_checkpoints")
				.contains("ON CONFLICT (shard_id) DO UPDATE")
				.contains("updated_at = now()");
	}

}
