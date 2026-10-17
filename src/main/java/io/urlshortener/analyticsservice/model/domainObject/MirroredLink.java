package io.urlshortener.analyticsservice.model.domainObject;

import lombok.Builder;

import java.time.Instant;

/**
 * A link as held in analytics-service's Postgres mirror of the Links table.
 *
 * <p>
 *     analytics-service's own domain object, so the repository layer does not depend on the shared
 *     {@link io.urlshortener.linkscontract.Link Link} wire shape.
 *     Unlike {@link io.urlshortener.linkscontract.Link Link}, it holds {@link Instant}s rather than
 *     raw epoch numbers, and has no management token hash.
 * </p>
 *
 * @param shortCode      the unique short code identifying this link (the mirror's primary key).
 * @param longUrl        the destination URL the short code redirects to.
 * @param ownerId        the link's owner, or {@code null} if it was created anonymously.
 * @param createdAt      when the link was created.
 * @param expiresAt      when the link expires, or {@code null} if it never does.
 * @param status         the moderation status (e.g. {@code "FLAGGED"}), or {@code null} for a normal link.
 * @param deletedAt      when this consumer saw the link deleted, or {@code null} while it is live. Not the exact time of
 *                       the delete, which the stream event does not carry.
 * @param deletionReason why the link was deleted, or {@code null} while it is live.
 */
@Builder
public record MirroredLink(
		String shortCode,
		String longUrl,
		String ownerId,
		Instant createdAt,
		Instant expiresAt,
		String status,
		Instant deletedAt,
		DeletionReason deletionReason
) {
}
