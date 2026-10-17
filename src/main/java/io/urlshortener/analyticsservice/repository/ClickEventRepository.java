package io.urlshortener.analyticsservice.repository;

import io.urlshortener.eventcontracts.ClickEvent;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Persists {@code ClickEvent}s idempotently and rolls their outcomes into the {@code link_stats} (all-time) and
 * {@code link_daily_stats} (per UTC day) rollup tables.
 *
 * <p>
 *     None of these methods is transactional on its own. The caller ({@code ClickEventRecorder}) wraps them in one
 *     transaction so that an event and its rollups are recorded together or not at all.
 * </p>
 */
public interface ClickEventRepository {

	/**
	 * Inserts a click event.<br/>
	 * A duplicate {@code eventId} (already-processed message redelivered by SQS) is a no-op.
	 *
	 * @param event the event to record.
	 * @return {@code true} if this was a new event actually recorded; {@code false} if it was
	 * already processed (duplicate eventId).
	 */
	boolean recordIfNew(final ClickEvent event);

	/**
	 * Applies aggregated per-outcome click counts to {@code link_stats}, incrementing existing counters or creating the
	 * row if this is the short code's first recorded click.
	 *
	 * @param shortCode     the short code the counts belong to.
	 * @param resolvedDelta additional RESOLVED clicks to add.
	 * @param expiredDelta  additional EXPIRED clicks to add.
	 * @param notFoundDelta additional NOT_FOUND clicks to add.
	 * @param lastClickedAt the timestamp of the most recent click in this batch for this short code.
	 */
	void incrementStats(final String shortCode, final long resolvedDelta, final long expiredDelta,
						final long notFoundDelta, final Instant lastClickedAt);

	/**
	 * Applies aggregated per-outcome click counts for one UTC day to {@code link_daily_stats}, incrementing the existing
	 * counters or creating the row if this is the short code's first recorded click that day.
	 *
	 * @param shortCode     the short code the counts belong to.
	 * @param day           the UTC day the clicks occurred on.
	 * @param resolvedDelta additional RESOLVED clicks to add.
	 * @param expiredDelta  additional EXPIRED clicks to add.
	 * @param notFoundDelta additional NOT_FOUND clicks to add.
	 */
	void incrementDailyStats(final String shortCode, final LocalDate day, final long resolvedDelta,
							 final long expiredDelta, final long notFoundDelta);

}
