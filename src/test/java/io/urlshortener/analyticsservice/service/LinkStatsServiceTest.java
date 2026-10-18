package io.urlshortener.analyticsservice.service;

import io.urlshortener.analyticsservice.exception.InvalidDateRangeException;
import io.urlshortener.analyticsservice.exception.ShortLinkNotFoundException;
import io.urlshortener.analyticsservice.model.domainObject.DailyStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStatsReport;
import io.urlshortener.analyticsservice.repository.LinkStatsRepository;
import io.urlshortener.analyticsservice.repository.LinksMirrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LinkStatsServiceTest {

	// A fixed, neutral "today" supplied through the Clock: the default date range depends on it.
	private static final LocalDate TODAY = LocalDate.of(2024, 3, 20);

	private static final String SHORT_CODE = "abc1234";

	private static final Instant LAST_CLICKED_AT = Instant.parse("2024-03-19T08:30:00Z");

	private static final LinkStats TOTALS = new LinkStats(5, 2, 1, LAST_CLICKED_AT);

	@Mock
	private LinkStatsRepository linkStatsRepository;

	@Mock
	private LinksMirrorRepository linksMirrorRepository;

	private LinkStatsService service;

	@BeforeEach
	void setUp() {
		final Clock clock = Clock.fixed(TODAY.atTime(15, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		service = new LinkStatsService(linkStatsRepository, linksMirrorRepository, clock);
	}

	@Test
	void getStats_shouldDefaultToThirtyDaysEndingToday_whenNoDatesAreGiven() {
		// Arrange
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, TODAY.minusDays(29), TODAY)).willReturn(List.of());

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, null, null);

		// Assert
		// Regression: the unresolved (null) parameters used to be used for the zero-fill, so a request with no dates
		// threw a NullPointerException.
		assertThat(report.from()).isEqualTo(TODAY.minusDays(29));
		assertThat(report.to()).isEqualTo(TODAY);
		assertThat(report.daily()).hasSize(30);
		assertThat(report.daily().getFirst().day()).isEqualTo(TODAY.minusDays(29));
		assertThat(report.daily().getLast().day()).isEqualTo(TODAY);
	}

	@Test
	void getStats_shouldDefaultFromToTwentyNineDaysBeforeTo_whenOnlyToIsGiven() {
		// Arrange
		final LocalDate to = LocalDate.of(2024, 1, 31);
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, to.minusDays(29), to)).willReturn(List.of());

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, null, to);

		// Assert
		assertThat(report.from()).isEqualTo(to.minusDays(29));
		assertThat(report.to()).isEqualTo(to);
		assertThat(report.daily()).hasSize(30);
	}

	@Test
	void getStats_shouldDefaultToToToday_whenOnlyFromIsGiven() {
		// Arrange
		final LocalDate from = TODAY.minusDays(5);
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, from, TODAY)).willReturn(List.of());

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, from, null);

		// Assert
		assertThat(report.from()).isEqualTo(from);
		assertThat(report.to()).isEqualTo(TODAY);
		assertThat(report.daily()).hasSize(6);
	}

	@Test
	void getStats_shouldReturnTotalsAndDailyRows_whenLinkHasClicks() {
		// Arrange
		final LocalDate from = LocalDate.of(2024, 3, 18);
		final LocalDate to = LocalDate.of(2024, 3, 19);
		final DailyStats first = new DailyStats(from, 3, 1, 0);
		final DailyStats second = new DailyStats(to, 2, 1, 1);
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, from, to)).willReturn(List.of(first, second));

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, from, to);

		// Assert
		assertThat(report.shortCode()).isEqualTo(SHORT_CODE);
		assertThat(report.totals()).isEqualTo(TOTALS);
		assertThat(report.daily()).containsExactly(first, second);
	}

	@Test
	void getStats_shouldZeroFillDaysWithoutClicks_whenOnlySomeDaysHaveRows() {
		// Arrange
		final LocalDate from = LocalDate.of(2024, 3, 17);
		final LocalDate to = LocalDate.of(2024, 3, 21);
		final DailyStats middle = new DailyStats(
				LocalDate.of(2024, 3, 19),
				4,
				0,
				2
		);
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, from, to)).willReturn(List.of(middle));

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, from, to);

		// Assert
		assertThat(report.daily()).containsExactly(
				new DailyStats(LocalDate.of(2024, 3, 17), 0, 0, 0),
				new DailyStats(LocalDate.of(2024, 3, 18), 0, 0, 0),
				middle,
				new DailyStats(LocalDate.of(2024, 3, 20), 0, 0, 0),
				new DailyStats(LocalDate.of(2024, 3, 21), 0, 0, 0)
		);
	}

	@Test
	void getStats_shouldReturnASingleDay_whenFromEqualsTo() {
		// Arrange
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, TODAY, TODAY)).willReturn(List.of());

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, TODAY, TODAY);

		// Assert
		assertThat(report.daily()).containsExactly(new DailyStats(TODAY, 0, 0, 0));
	}

	@Test
	void getStats_shouldReturnZeroTotals_whenLinkIsInTheMirrorButHasNeverBeenClicked() {
		// Arrange
		given(linkStatsRepository.findTotals(SHORT_CODE)).willReturn(Optional.empty());
		given(linksMirrorRepository.existsByShortCode(SHORT_CODE)).willReturn(true);
		given(linkStatsRepository.findDaily(SHORT_CODE, TODAY.minusDays(29), TODAY)).willReturn(List.of());

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, null, null);

		// Assert
		assertThat(report.totals()).isEqualTo(new LinkStats(0, 0, 0, null));
		assertThat(report.daily()).hasSize(30).allMatch(day -> day.resolved() == 0);
	}

	@Test
	void getStats_shouldNotQueryTheMirror_whenTotalsExist() {
		// Arrange
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, TODAY.minusDays(29), TODAY)).willReturn(List.of());

		// Act
		service.getStats(SHORT_CODE, null, null);

		// Assert
		// A short code with clicks is known without asking the mirror, which may not hold an older, unmodified link.
		verify(linksMirrorRepository, never()).existsByShortCode(any());
	}

	@Test
	void getStats_shouldThrowShortLinkNotFoundException_whenThereAreNeitherTotalsNorAMirrorRow() {
		// Arrange
		given(linkStatsRepository.findTotals(SHORT_CODE)).willReturn(Optional.empty());
		given(linksMirrorRepository.existsByShortCode(SHORT_CODE)).willReturn(false);

		// Act & Assert
		assertThatThrownBy(() -> service.getStats(SHORT_CODE, null, null))
				.isInstanceOf(ShortLinkNotFoundException.class)
				.hasMessageContaining(SHORT_CODE);
		verify(linkStatsRepository, never()).findDaily(any(), any(), any());
	}

	@Test
	void getStats_shouldThrowInvalidDateRangeExceptionBeforeAnyQuery_whenFromIsAfterTo() {
		// Act & Assert
		assertThatThrownBy(() -> service.getStats(SHORT_CODE, TODAY, TODAY.minusDays(1)))
				.isInstanceOf(InvalidDateRangeException.class)
				.hasMessageContaining("after");
		verifyNoInteractions(linkStatsRepository, linksMirrorRepository);
	}

	@Test
	void getStats_shouldThrowInvalidDateRangeExceptionBeforeAnyQuery_whenRangeExceeds366Days() {
		// Arrange
		final LocalDate to = TODAY;
		final LocalDate from = to.minusDays(366);

		// Act & Assert
		// from..to inclusive is 367 days here.
		assertThatThrownBy(() -> service.getStats(SHORT_CODE, from, to))
				.isInstanceOf(InvalidDateRangeException.class)
				.hasMessageContaining("366");
		verifyNoInteractions(linkStatsRepository, linksMirrorRepository);
	}

	@Test
	void getStats_shouldAcceptARangeOfExactly366Days_whenAtTheLimit() {
		// Arrange
		final LocalDate to = TODAY;
		final LocalDate from = to.minusDays(365);
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(SHORT_CODE, from, to)).willReturn(List.of());

		// Act
		final LinkStatsReport report = service.getStats(SHORT_CODE, from, to);

		// Assert
		assertThat(report.daily()).hasSize(366);
	}

	@Test
	void getStats_shouldRethrowAndSkipOtherQueries_whenFindingTotalsFails() {
		// Arrange
		given(linkStatsRepository.findTotals(SHORT_CODE)).willThrow(new IllegalStateException("DB unavailable"));

		// Act & Assert
		assertThatThrownBy(() -> service.getStats(SHORT_CODE, null, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("DB unavailable");
		verifyNoInteractions(linksMirrorRepository);
		verify(linkStatsRepository, never()).findDaily(any(), any(), any());
	}

	@Test
	void getStats_shouldRethrow_whenCheckingTheMirrorFails() {
		// Arrange
		given(linkStatsRepository.findTotals(SHORT_CODE)).willReturn(Optional.empty());
		given(linksMirrorRepository.existsByShortCode(SHORT_CODE)).willThrow(new IllegalStateException("mirror down"));

		// Act & Assert
		assertThatThrownBy(() -> service.getStats(SHORT_CODE, null, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("mirror down");
	}

	@Test
	void getStats_shouldRethrow_whenFindingDailyStatsFails() {
		// Arrange
		givenTotals(TOTALS);
		given(linkStatsRepository.findDaily(any(), any(), any())).willThrow(new IllegalStateException("daily down"));

		// Act & Assert
		assertThatThrownBy(() -> service.getStats(SHORT_CODE, null, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("daily down");
	}

	private void givenTotals(final LinkStats totals) {
		given(linkStatsRepository.findTotals(SHORT_CODE)).willReturn(Optional.of(totals));
	}

}
