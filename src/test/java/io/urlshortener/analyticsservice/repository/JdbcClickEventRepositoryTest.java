package io.urlshortener.analyticsservice.repository;

import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.eventcontracts.ClickOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JdbcClickEventRepositoryTest {

	private static final String EVENT_ID = "e1a2b3c4-d5e6-7f89-0a1b-2c3d4e5f6789";

	private static final String SHORT_CODE = "abc1234";

	@Mock
	private JdbcClient jdbcClient;

	@Mock(answer = Answers.RETURNS_SELF)
	private JdbcClient.StatementSpec statementSpec;

	private JdbcClickEventRepository repository;

	@BeforeEach
	void setUp() {
		repository = new JdbcClickEventRepository(jdbcClient);
		given(jdbcClient.sql(anyString())).willReturn(statementSpec);
	}

	@Test
	void recordIfNew_shouldReturnTrue_whenRowWasActuallyInserted() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final ClickEvent event = clickEvent(ClickOutcome.RESOLVED);

		// Act
		final boolean result = repository.recordIfNew(event);

		// Assert
		assertThat(result).isTrue();
	}

	@Test
	void recordIfNew_shouldReturnFalse_whenRowWasADuplicate() {
		// Arrange
		given(statementSpec.update()).willReturn(0);
		final ClickEvent event = clickEvent(ClickOutcome.RESOLVED);

		// Act
		final boolean result = repository.recordIfNew(event);

		// Assert
		assertThat(result).isFalse();
	}

	@Test
	void recordIfNew_shouldBindOccurredAtAsOffsetDateTime_notRawInstant() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final ClickEvent event = clickEvent(ClickOutcome.RESOLVED);

		// Act
		repository.recordIfNew(event);

		// Assert
		// A raw java.time.Instant can't be bound by the PostgreSQL JDBC driver - regression coverage for
		// that exact bug, caught only by the end-to-end test until now.
		final OffsetDateTime expected = Instant.ofEpochMilli(event.timestamp()).atOffset(ZoneOffset.UTC);
		verify(statementSpec).param("occurredAt", expected);
	}

	@Test
	void recordIfNew_shouldBindOutcomeAsString_notRawEnum() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final ClickEvent event = clickEvent(ClickOutcome.EXPIRED);

		// Act
		repository.recordIfNew(event);

		// Assert
		// A raw ClickOutcome enum can't be bound by the PostgreSQL JDBC driver either - regression
		// coverage for that bug too.
		verify(statementSpec).param("outcome", "EXPIRED");
	}

	@Test
	void recordIfNew_shouldBindAllRemainingFields_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final ClickEvent event = clickEvent(ClickOutcome.RESOLVED);

		// Act
		repository.recordIfNew(event);

		// Assert
		verify(statementSpec).param("eventId", EVENT_ID);
		verify(statementSpec).param("shortCode", SHORT_CODE);
		verify(statementSpec).param("refererDomain", "google.com");
		verify(statementSpec).param("userAgentRaw", "Mozilla/5.0");
		verify(statementSpec).param("ipHash", "hash123");
	}

	@Test
	void incrementStats_shouldBindLastClickedAtAsOffsetDateTime_notRawInstant() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final Instant lastClickedAt = Instant.now();

		// Act
		repository.incrementStats(SHORT_CODE, 2L, 1L, 0L, lastClickedAt);

		// Assert
		verify(statementSpec).param("lastClickedAt", lastClickedAt.atOffset(ZoneOffset.UTC));
	}

	@Test
	void incrementStats_shouldBindShortCodeAndDeltas_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);

		// Act
		repository.incrementStats(SHORT_CODE, 2L, 1L, 3L, Instant.now());

		// Assert
		verify(statementSpec).param("shortCode", SHORT_CODE);
		verify(statementSpec).param("resolvedCount", 2L);
		verify(statementSpec).param("expiredCount", 1L);
		verify(statementSpec).param("notFoundCount", 3L);
	}

	@Test
	void incrementStats_shouldKeepTheLatestClickTimestamp_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);

		// Act
		repository.incrementStats(SHORT_CODE, 1L, 0L, 0L, Instant.now());

		// Assert
		// A redelivered older batch must not move lastClickedAt backwards.
		final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcClient).sql(sqlCaptor.capture());
		assertThat(sqlCaptor.getValue()).contains("GREATEST(link_stats.last_clicked_at, EXCLUDED.last_clicked_at)");
	}

	@Test
	void incrementDailyStats_shouldBindShortCodeDayAndDeltas_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);
		final LocalDate day = LocalDate.of(2026, 10, 16);

		// Act
		repository.incrementDailyStats(SHORT_CODE, day, 2L, 1L, 3L);

		// Assert
		verify(statementSpec).param("shortCode", SHORT_CODE);
		verify(statementSpec).param("day", day);
		verify(statementSpec).param("resolvedCount", 2L);
		verify(statementSpec).param("expiredCount", 1L);
		verify(statementSpec).param("notFoundCount", 3L);
	}

	@Test
	void incrementDailyStats_shouldIssueAnAddingUpsertOnShortCodeAndDay_whenCalled() {
		// Arrange
		given(statementSpec.update()).willReturn(1);

		// Act
		repository.incrementDailyStats(SHORT_CODE, LocalDate.of(2026, 10, 16), 1L, 0L, 0L);

		// Assert
		final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcClient).sql(sqlCaptor.capture());
		assertThat(sqlCaptor.getValue())
				.contains("INSERT INTO link_daily_stats")
				.contains("ON CONFLICT (short_code, day) DO UPDATE SET")
				.contains("resolved_count = link_daily_stats.resolved_count + EXCLUDED.resolved_count")
				.contains("expired_count = link_daily_stats.expired_count + EXCLUDED.expired_count")
				.contains("not_found_count = link_daily_stats.not_found_count + EXCLUDED.not_found_count");
	}

	private ClickEvent clickEvent(final ClickOutcome outcome) {
		return new ClickEvent(EVENT_ID, SHORT_CODE, Instant.now().toEpochMilli(), outcome, "google.com",
				"Mozilla/5.0", "hash123");
	}

}
