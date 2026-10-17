package io.urlshortener.analyticsservice.mapper.dbMapper;

import io.urlshortener.analyticsservice.model.domainObject.DeletionReason;
import io.urlshortener.analyticsservice.model.domainObject.MirroredLink;
import io.urlshortener.linkscontract.Link;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Converts the shared {@link Link} wire shape into analytics-service's own {@link MirroredLink} domain object, keeping
 * the shared shape out of the repository layer.
 */
@Component
public class LinkMirrorMapper {

	/**
	 * Converts a link into its mirrored form.
	 *
	 * <p>
	 *     {@code Link} stores {@link Link#createdAt() createdAt} as epoch <em>milliseconds</em>
	 *     but {@link Link#expiresAt() expiresAt} as epoch <em>seconds</em> (it is the literal DynamoDB TTL attribute),
	 *     so the two are converted differently. The management token hash is deliberately not carried over:
	 *     analytics has no use for it.
	 * </p>
	 *
	 * @param link           the link's state to mirror.
	 * @param deletedAt      when the link was seen deleted, or {@code null} if it is live.
	 * @param deletionReason why it was deleted, or {@code null} if it is live.
	 * @return the mirrored link.
	 */
	public MirroredLink toDomain(final Link link, final Instant deletedAt, final DeletionReason deletionReason) {
		final Instant createdAt = Instant.ofEpochMilli(link.createdAt());
		final Instant expiresAt = Optional.ofNullable(link.expiresAt())
				.map(Instant::ofEpochSecond)
				.orElse(null);
		return MirroredLink.builder()
				.shortCode(link.shortCode())
				.longUrl(link.longUrl())
				.ownerId(link.ownerId())
				.createdAt(createdAt)
				.expiresAt(expiresAt)
				.status(link.status())
				.deletedAt(deletedAt)
				.deletionReason(deletionReason)
				.build();
	}

}
