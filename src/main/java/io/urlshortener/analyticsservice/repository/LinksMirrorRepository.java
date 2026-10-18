package io.urlshortener.analyticsservice.repository;

import io.urlshortener.analyticsservice.model.domainObject.MirroredLink;

/**
 * Maintains analytics-service's Postgres mirror of the Links table, used to join click statistics to link details.
 */
public interface LinksMirrorRepository {

	/**
	 * Inserts the link, or overwrites it if its short code is already mirrored.
	 *
	 * <p>
	 *     Idempotent, so replaying a stream batch after a failure is safe. A deleted link is written as a soft delete
	 *     (its {@code deletedAt} and {@code deletionReason} set) rather than removed, so its click history stays joinable.
	 * </p>
	 *
	 * @param link the link's current state.
	 */
	void upsertLink(final MirroredLink link);

	/**
	 * Checks whether a short code is in the mirror.
	 *
	 * <p>
	 *     Soft-deleted links count as present, so a deleted link keeps its history. Absence does not prove a link never
	 *     existed: the mirror is fed from a stream that only retains 24 hours, so an older, unmodified link may be
	 *     missing from it.
	 * </p>
	 *
	 * @param shortCode the short code to look up.
	 * @return {@code true} if the mirror holds a row for it.
	 */
	boolean existsByShortCode(final String shortCode);

}
