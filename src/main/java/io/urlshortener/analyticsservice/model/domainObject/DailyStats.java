package io.urlshortener.analyticsservice.model.domainObject;

import java.time.LocalDate;

/**
 * A short code's click counts for one UTC day, as held in the {@code link_daily_stats} rollup.
 *
 * @param day      the UTC day the clicks occurred on.
 * @param resolved clicks that were redirected to the destination that day.
 * @param expired  clicks on a link that had expired that day.
 * @param notFound clicks on a short code that did not exist that day.
 */
public record DailyStats(
		LocalDate day,
		long resolved,
		long expired,
		long notFound
) {
}
