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

}
