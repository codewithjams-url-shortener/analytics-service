package io.urlshortener.analyticsservice.repository;

import io.urlshortener.eventcontracts.ClickEvent;

import java.time.Instant;

/**
 * Persists {@code ClickEvent}s idempotently and rolls their outcomes into the {@code link_stats} rollup table.
 */
public interface ClickEventRepository {

	/**
	 * Inserts a click event, and idempotently rolls its outcome into {@code link_stats}.<br/>
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

}
