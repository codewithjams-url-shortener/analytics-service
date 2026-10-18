package io.urlshortener.analyticsservice.mapper.responseMapper;

import io.urlshortener.analyticsservice.model.dataTransferObject.DailyLinkStats;
import io.urlshortener.analyticsservice.model.dataTransferObject.LinkStatsResponse;
import io.urlshortener.analyticsservice.model.domainObject.DailyStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStatsReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LinkStatsResponseMapperTest {

	private static final LocalDate FROM = LocalDate.of(2024, 3, 18);

	private static final LocalDate TO = LocalDate.of(2024, 3, 19);

	private final LinkStatsResponseMapper mapper = new LinkStatsResponseMapper();

	@Test
	void toDto_shouldMapShortCodeAndTheRequestedRange_whenReportIsGiven() {
		// Arrange
		final LinkStatsReport report = report(new LinkStats(5, 2, 1, Instant.parse("2024-03-19T08:30:00Z")));

		// Act
		final LinkStatsResponse response = mapper.toDto(report);

		// Assert
		// Regression: from and to were left unset although the contract marks them required.
		assertThat(response.getShortCode()).isEqualTo("abc1234");
		assertThat(response.getFrom()).isEqualTo(FROM);
		assertThat(response.getTo()).isEqualTo(TO);
	}

	@Test
	void toDto_shouldUseResolvedCountForTotalClicks_whenTotalsAreGiven() {
		// Arrange
		final LinkStatsReport report = report(new LinkStats(5, 2, 1, Instant.parse("2024-03-19T08:30:00Z")));

		// Act
		final LinkStatsResponse response = mapper.toDto(report);

		// Assert
		assertThat(response.getTotalClicks()).isEqualTo(5L);
		assertThat(response.getResolved()).isEqualTo(5L);
		assertThat(response.getExpired()).isEqualTo(2L);
		assertThat(response.getNotFound()).isEqualTo(1L);
	}

	@Test
	void toDto_shouldConvertLastClickedAtToUtc_whenLinkHasBeenClicked() {
		// Arrange
		final LinkStatsReport report = report(new LinkStats(1, 0, 0, Instant.parse("2024-03-19T08:30:00Z")));

		// Act
		final LinkStatsResponse response = mapper.toDto(report);

		// Assert
		assertThat(response.getLastClickedAt())
				.isEqualTo(OffsetDateTime.of(2024, 3, 19, 8, 30, 0, 0, ZoneOffset.UTC));
	}

	@Test
	void toDto_shouldLeaveLastClickedAtNull_whenLinkHasNeverBeenClicked() {
		// Arrange
		final LinkStatsReport report = report(new LinkStats(0, 0, 0, null));

		// Act
		final LinkStatsResponse response = mapper.toDto(report);

		// Assert
		// Regression: a known link with no clicks used to fail here with a NullPointerException, returning a 500.
		assertThat(response.getLastClickedAt()).isNull();
		assertThat(response.getTotalClicks()).isZero();
	}

	@Test
	void toDto_shouldMapEveryDailyEntryInOrder_whenDailyListIsGiven() {
		// Arrange
		final LinkStatsReport report = new LinkStatsReport(
				"abc1234",
				new LinkStats(5, 2, 1, null),
				FROM,
				TO,
				List.of(new DailyStats(FROM, 3, 1, 0), new DailyStats(TO, 0, 0, 0))
		);

		// Act
		final LinkStatsResponse response = mapper.toDto(report);

		// Assert
		assertThat(response.getDaily()).hasSize(2);
		final DailyLinkStats first = response.getDaily().get(0);
		assertThat(first.getDate()).isEqualTo(FROM);
		assertThat(first.getClicks()).isEqualTo(3L);
		assertThat(first.getResolved()).isEqualTo(3L);
		assertThat(first.getExpired()).isEqualTo(1L);
		assertThat(first.getNotFound()).isZero();
		assertThat(response.getDaily().get(1).getDate()).isEqualTo(TO);
	}

	private LinkStatsReport report(final LinkStats totals) {
		return new LinkStatsReport("abc1234", totals, FROM, TO, List.of());
	}

}
