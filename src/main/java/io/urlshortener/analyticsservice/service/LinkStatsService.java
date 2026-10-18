package io.urlshortener.analyticsservice.service;

import io.urlshortener.analyticsservice.exception.InvalidDateRangeException;
import io.urlshortener.analyticsservice.exception.ShortLinkNotFoundException;
import io.urlshortener.analyticsservice.model.domainObject.DailyStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStatsReport;
import io.urlshortener.analyticsservice.repository.LinkStatsRepository;
import io.urlshortener.analyticsservice.repository.LinksMirrorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the click statistics of a short link: its all-time totals and a zero-filled daily breakdown over a date range.
 *
 * <p>
 *     A short code is known if it has click totals <em>or</em> is in the links mirror. Checking the mirror alone would
 *     wrongly reject an older link that has clicks but was never modified, because the mirror is fed from a stream that
 *     only retains 24 hours. A link in neither place is unknown.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkStatsService {

	/**
	 * Length of the daily breakdown when the caller gives no {@code from}: the {@code to} day and the 29 before it.
	 */
	private static final int DEFAULT_RANGE_DAYS = 30;

	/**
	 * Longest daily breakdown allowed, so a response can never grow without bound.
	 */
	private static final int MAX_RANGE_DAYS = 366;

	/**
	 * Reads the totals and the daily rollup.
	 */
	private final LinkStatsRepository linkStatsRepository;

	/**
	 * Tells a link that exists but has no clicks apart from one that never existed.
	 */
	private final LinksMirrorRepository linksMirrorRepository;

	/**
	 * Source of "today" for the default range, injected so tests can pin the date.
	 */
	private final Clock clock;

	/**
	 * Returns the statistics of a short code.
	 *
	 * <p>
	 *     {@code to} defaults to today (UTC) and {@code from} to 29 days before {@code to}. The range is validated
	 *     before any database access. The daily list has one entry for every day in the range, with zeros for days
	 *     without clicks.
	 * </p>
	 *
	 * @param shortCode the short code to report on.
	 * @param from      the first UTC day, inclusive, or {@code null} for the default.
	 * @param to        the last UTC day, inclusive, or {@code null} for today.
	 * @return the report.
	 * @throws InvalidDateRangeException if {@code from} is after {@code to}, or the range exceeds 366 days.
	 * @throws ShortLinkNotFoundException if the short code has neither click totals nor a place in the links mirror.
	 */
	public LinkStatsReport getStats(final String shortCode, final LocalDate from, final LocalDate to) {
		final LocalDate toFinal = Objects.nonNull(to) ? to : LocalDate.now(clock);
		final LocalDate fromFinal = Objects.nonNull(from) ? from : toFinal.minusDays(DEFAULT_RANGE_DAYS - 1);
		validateDateRange(shortCode, fromFinal, toFinal);
		final Optional<LinkStats> allTimeTotal = getAllTimeTotal(shortCode);
		final LinkStats stats = getShortLinkAllTimeStats(shortCode, allTimeTotal);
		final List<DailyStats> dailyStats = getDailyStats(shortCode, fromFinal, toFinal);
		final Map<LocalDate, DailyStats> dailyStatsMap = dailyStats.stream()
				.collect(Collectors.toMap(DailyStats::day, Function.identity()));
		final List<DailyStats> daily = fromFinal.datesUntil(toFinal.plusDays(1))
				.map(day -> dailyStatsMap.getOrDefault(day, new DailyStats(day, 0, 0, 0)))
				.toList();
		return new LinkStatsReport(shortCode, stats, fromFinal, toFinal, daily);
	}

	/**
	 * Rejects a range that is backwards or longer than {@value #MAX_RANGE_DAYS} days.
	 *
	 * @param shortCode the short code, for logging.
	 * @param from      the first day.
	 * @param to        the last day.
	 * @throws InvalidDateRangeException if the range is not acceptable.
	 */
	private void validateDateRange(final String shortCode, final LocalDate from, final LocalDate to) {
		if (from.isAfter(to)) {
			throw new InvalidDateRangeException("from date is after to date");
		}
		if ((ChronoUnit.DAYS.between(from, to) + 1) > MAX_RANGE_DAYS) {
			final String message = "No. of days between from and to exceeded %d days".formatted(MAX_RANGE_DAYS);
			throw new InvalidDateRangeException(message);
		}
		log.atDebug()
				.addKeyValue("shortCode", shortCode)
				.addKeyValue("from", from)
				.addKeyValue("to", to)
				.log("Date Range validated");
	}

	/**
	 * Reads a short code's all-time totals, logging the short code on failure.
	 *
	 * @param shortCode the short code to look up.
	 * @return its totals, or empty if it has never been clicked.
	 */
	private Optional<LinkStats> getAllTimeTotal(final String shortCode) {
		try {
			return linkStatsRepository.findTotals(shortCode);
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("shortCode", shortCode)
					.log("Error while fetching total stats for Short Code");
			throw e;
		}
	}

	/**
	 * Settles whether the short code is known and returns its totals.
	 *
	 * @param shortCode the short code being reported on.
	 * @param allTimeTotals    its totals, empty if it has never been clicked.
	 * @return the totals, or all zeros for a known link without clicks.
	 * @throws ShortLinkNotFoundException if there are no totals and the short code is not in the mirror.
	 */
	private LinkStats getShortLinkAllTimeStats(final String shortCode, final Optional<LinkStats> allTimeTotals) {
		if (allTimeTotals.isPresent() || existsInMirror(shortCode)) {
			return allTimeTotals.orElse(new LinkStats(0, 0, 0, null));
		} else {
			log.atWarn()
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("totalsExists", false)
					.addKeyValue("statsExistByShortCode", false)
					.log("Stats not found for Short Code");
			throw new ShortLinkNotFoundException(shortCode);
		}
	}

	/**
	 * Checks the links mirror, logging the short code on failure.
	 *
	 * @param shortCode the short code to look up.
	 * @return {@code true} if the mirror holds it.
	 */
	private boolean existsInMirror(final String shortCode) {
		try {
			return linksMirrorRepository.existsByShortCode(shortCode);
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("shortCode", shortCode)
					.log("Error while checking if Stats exists by Short Code");
			throw e;
		}
	}

	/**
	 * Reads the days with clicks in the range, logging the short code and range on failure.
	 *
	 * @param shortCode the short code to look up.
	 * @param from      the first day, inclusive.
	 * @param to        the last day, inclusive.
	 * @return the days that have clicks, in order.
	 */
	private List<DailyStats> getDailyStats(final String shortCode, final LocalDate from, final LocalDate to) {
		try {
			return linkStatsRepository.findDaily(shortCode, from, to);
		} catch (Exception e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("from", from)
					.addKeyValue("to", to)
					.log("Error while fetching Daily Stats for a Short Code within given time range");
			throw e;
		}
	}

}
