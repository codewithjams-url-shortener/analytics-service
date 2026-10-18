package io.urlshortener.analyticsservice.repository;

import io.urlshortener.analyticsservice.model.domainObject.DailyStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JdbcLinkStatsRepositoryTest {

	private static final String SHORT_CODE = "abc1234";

	private static final LocalDate FROM = LocalDate.of(2024, 3, 18);

	private static final LocalDate TO = LocalDate.of(2024, 3, 19);

	@Mock
	private JdbcClient jdbcClient;

	@Mock(answer = Answers.RETURNS_SELF)
	private JdbcClient.StatementSpec statementSpec;

	@Mock
	private JdbcClient.MappedQuerySpec<LinkStats> totalsQuery;

	@Mock
	private JdbcClient.MappedQuerySpec<DailyStats> dailyQuery;

	@Mock
	private ResultSet resultSet;

	@Captor
	private ArgumentCaptor<RowMapper<LinkStats>> totalsMapperCaptor;

	@Captor
	private ArgumentCaptor<RowMapper<DailyStats>> dailyMapperCaptor;

	private JdbcLinkStatsRepository repository;

	@BeforeEach
	void setUp() {
		repository = new JdbcLinkStatsRepository(jdbcClient);
		given(jdbcClient.sql(anyString())).willReturn(statementSpec);
	}

	@Test
	void findTotals_shouldReturnTheRowAndBindTheShortCode_whenTheShortCodeHasBeenClicked() {
		// Arrange
		final LinkStats stats = new LinkStats(
				5,
				2,
				1,
				Instant.parse("2024-03-19T08:30:00Z")
		);
		given(statementSpec.query(ArgumentMatchers.<RowMapper<LinkStats>>any())).willReturn(totalsQuery);
		given(totalsQuery.optional()).willReturn(Optional.of(stats));

		// Act
		final Optional<LinkStats> result = repository.findTotals(SHORT_CODE);

		// Assert
		assertThat(result).contains(stats);
		verify(statementSpec).param("shortCode", SHORT_CODE);
	}

	@Test
	void findTotals_shouldReturnEmpty_whenTheShortCodeHasNeverBeenClicked() {
		// Arrange
		given(statementSpec.query(ArgumentMatchers.<RowMapper<LinkStats>>any())).willReturn(totalsQuery);
		given(totalsQuery.optional()).willReturn(Optional.empty());

		// Act
		final Optional<LinkStats> result = repository.findTotals(SHORT_CODE);

		// Assert
		assertThat(result).isEmpty();
	}

	@Test
	void findTotals_shouldMapEveryColumnToLinkStats_whenARowIsRead() throws SQLException {
		// Arrange
		given(statementSpec.query(ArgumentMatchers.<RowMapper<LinkStats>>any())).willReturn(totalsQuery);
		given(totalsQuery.optional()).willReturn(Optional.empty());
		given(resultSet.getLong("resolved_count")).willReturn(5L);
		given(resultSet.getLong("expired_count")).willReturn(2L);
		given(resultSet.getLong("not_found_count")).willReturn(1L);
		given(resultSet.getObject("last_clicked_at", OffsetDateTime.class))
				.willReturn(
						OffsetDateTime.of(
								2024,
								3,
								19,
								14,
								0,
								0,
								0,
								ZoneOffset.ofHoursMinutes(5, 30)
						)
				);
		repository.findTotals(SHORT_CODE);
		verify(statementSpec).query(totalsMapperCaptor.capture());

		// Act
		final LinkStats result = totalsMapperCaptor.getValue().mapRow(resultSet, 0);

		// Assert
		assertThat(result).isEqualTo(
				new LinkStats(5, 2, 1, Instant.parse("2024-03-19T08:30:00Z"))
		);
	}

	@Test
	void findTotals_shouldMapANullLastClickedAtToNull_whenTheColumnIsNull() throws SQLException {
		// Arrange
		given(statementSpec.query(ArgumentMatchers.<RowMapper<LinkStats>>any())).willReturn(totalsQuery);
		given(totalsQuery.optional()).willReturn(Optional.empty());
		given(resultSet.getObject("last_clicked_at", OffsetDateTime.class)).willReturn(null);
		repository.findTotals(SHORT_CODE);
		verify(statementSpec).query(totalsMapperCaptor.capture());

		// Act
		final LinkStats result = totalsMapperCaptor.getValue().mapRow(resultSet, 0);

		// Assert
		// The column is nullable in the schema, so a null must not raise a NullPointerException.
		assertThat(result).isNotNull();
		assertThat(result.lastClickedAt()).isNull();
	}

	@Test
	void findDaily_shouldBindShortCodeAndRangeAndReturnTheRows_whenCalled() {
		// Arrange
		final List<DailyStats> rows = List.of(
				new DailyStats(FROM, 3, 1, 0),
				new DailyStats(TO, 2, 1, 1)
		);
		given(statementSpec.query(ArgumentMatchers.<RowMapper<DailyStats>>any())).willReturn(dailyQuery);
		given(dailyQuery.list()).willReturn(rows);

		// Act
		final List<DailyStats> result = repository.findDaily(SHORT_CODE, FROM, TO);

		// Assert
		assertThat(result).isEqualTo(rows);
		verify(statementSpec).param("shortCode", SHORT_CODE);
		verify(statementSpec).param("from", FROM);
		verify(statementSpec).param("to", TO);
	}

	@Test
	void findDaily_shouldQueryAnInclusiveRangeOldestFirst_whenCalled() {
		// Arrange
		given(statementSpec.query(ArgumentMatchers.<RowMapper<DailyStats>>any())).willReturn(dailyQuery);
		given(dailyQuery.list()).willReturn(List.of());

		// Act
		repository.findDaily(SHORT_CODE, FROM, TO);

		// Assert
		final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcClient).sql(sqlCaptor.capture());
		assertThat(sqlCaptor.getValue())
				.contains("FROM link_daily_stats")
				.contains("day BETWEEN :from AND :to")
				.contains("ORDER BY day");
	}

	@Test
	void findDaily_shouldMapEveryColumnToDailyStats_whenARowIsRead() throws SQLException {
		// Arrange
		given(statementSpec.query(ArgumentMatchers.<RowMapper<DailyStats>>any())).willReturn(dailyQuery);
		given(dailyQuery.list()).willReturn(List.of());
		given(resultSet.getObject("day", LocalDate.class)).willReturn(FROM);
		given(resultSet.getLong("resolved_count")).willReturn(3L);
		given(resultSet.getLong("expired_count")).willReturn(1L);
		given(resultSet.getLong("not_found_count")).willReturn(0L);
		repository.findDaily(SHORT_CODE, FROM, TO);
		verify(statementSpec).query(dailyMapperCaptor.capture());

		// Act
		final DailyStats result = dailyMapperCaptor.getValue().mapRow(resultSet, 0);

		// Assert
		assertThat(result).isEqualTo(new DailyStats(FROM, 3, 1, 0));
	}

}
