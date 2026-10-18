package io.urlshortener.analyticsservice.repository;

import io.urlshortener.analyticsservice.model.domainObject.DailyStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStats;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the click rollups ({@code link_stats} and {@code link_daily_stats}) behind the stats endpoint.
 */
public interface LinkStatsRepository {

	/**
	 * Finds a short code's all-time totals.
	 *
	 * @param shortCode the short code to look up.
	 * @return its totals, or empty if it has no {@code link_stats} row, i.e. it has never been clicked.
	 */
	Optional<LinkStats> findTotals(final String shortCode);

	/**
	 * Finds a short code's daily click counts within a date range.
	 *
	 * @param shortCode the short code to look up.
	 * @param from      the first UTC day, inclusive.
	 * @param to        the last UTC day, inclusive.
	 * @return the days that have clicks, ordered by day. Days without clicks are not included; the caller fills them in.
	 */
	List<DailyStats> findDaily(final String shortCode, final LocalDate from, final LocalDate to);

}
