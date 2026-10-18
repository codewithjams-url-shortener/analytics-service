package io.urlshortener.analyticsservice.model.domainObject;

import java.time.Instant;

/**
 * A short code's all-time click totals, as held in the {@code link_stats} rollup.
 *
 * @param resolved      clicks that were redirected to the destination.
 * @param expired       clicks on a link that had expired.
 * @param notFound      clicks on a short code that did not exist.
 * @param lastClickedAt when the link was last clicked, or {@code null} if it never has been.
 */
public record LinkStats(
		long resolved,
		long expired,
		long notFound,
		Instant lastClickedAt
) {
}
