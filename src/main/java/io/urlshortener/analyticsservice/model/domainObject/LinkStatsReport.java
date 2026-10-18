package io.urlshortener.analyticsservice.model.domainObject;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the stats endpoint returns for one short code: its all-time totals and a daily breakdown over a date range.
 *
 * @param shortCode the short code the report is about.
 * @param totals    the all-time totals, all zero with no last click if the link has never been clicked.
 * @param from      the first UTC day of the daily breakdown, inclusive.
 * @param to        the last UTC day of the daily breakdown, inclusive.
 * @param daily     one entry per UTC day from {@code from} to {@code to}, in order, with zeros for days without clicks.
 */
public record LinkStatsReport(
		String shortCode,
		LinkStats totals,
		LocalDate from,
		LocalDate to,
		List<DailyStats> daily
) {
}
