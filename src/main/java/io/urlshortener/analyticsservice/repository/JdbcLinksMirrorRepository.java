package io.urlshortener.analyticsservice.repository;

import io.urlshortener.analyticsservice.model.domainObject.DeletionReason;
import io.urlshortener.analyticsservice.model.domainObject.MirroredLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * {@link LinksMirrorRepository} backed by Postgres through Spring's {@link JdbcClient}.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcLinksMirrorRepository implements LinksMirrorRepository {

	/**
	 * Every column is overwritten on conflict, not only the ones that changed, because the mirror always holds the
	 * latest full state of the link. That includes clearing {@code deleted_at} and {@code deletion_reason}.
	 */
	private static final String UPSERT_LINK_QUERY = """
			INSERT INTO links (short_code, long_url, owner_id, created_at, expires_at, status, deleted_at, deletion_reason)
			VALUES (:shortCode, :longUrl, :ownerId, :createdAt, :expiresAt, :status, :deletedAt, :deletionReason)
			ON CONFLICT (short_code) DO UPDATE SET
			long_url = EXCLUDED.long_url,
			owner_id = EXCLUDED.owner_id,
			created_at = EXCLUDED.created_at,
			expires_at = EXCLUDED.expires_at,
			status = EXCLUDED.status,
			deleted_at = EXCLUDED.deleted_at,
			deletion_reason = EXCLUDED.deletion_reason
			""";

	/**
	 * Checks for a row in the {@code links} mirror, soft-deleted or not.
	 */
	private static final String EXISTS_BY_SHORT_CODE_QUERY = """
		SELECT EXISTS (
			SELECT 1
			FROM links
			WHERE short_code = :shortCode
		)
		""";

	private final JdbcClient jdbcClient;

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 *     The pgjdbc driver cannot bind a raw {@link java.time.Instant} or an arbitrary enum, so timestamps are bound as
	 *     {@link OffsetDateTime} (UTC) and the deletion reason as its name.
	 * </p>
	 */
	@Override
	public void upsertLink(final MirroredLink link) {
		final OffsetDateTime createdAt = link.createdAt().atOffset(ZoneOffset.UTC);
		final OffsetDateTime expiresAt = Optional.ofNullable(link.expiresAt())
				.map(e -> e.atOffset(ZoneOffset.UTC))
				.orElse(null);
		final OffsetDateTime deletedAt = Optional.ofNullable(link.deletedAt())
				.map(d -> d.atOffset(ZoneOffset.UTC))
				.orElse(null);
		final String deletionReason = Optional.ofNullable(link.deletionReason())
				.map(DeletionReason::toString)
				.orElse(null);
		final JdbcClient.StatementSpec statementSpec = jdbcClient.sql(UPSERT_LINK_QUERY)
				.param("shortCode", link.shortCode())
				.param("longUrl", link.longUrl())
				.param("ownerId", link.ownerId())
				.param("createdAt", createdAt)
				.param("expiresAt", expiresAt)
				.param("status", link.status())
				.param("deletedAt", deletedAt)
				.param("deletionReason", deletionReason);
		final int rowsUpdated = statementSpec.update();
		log.atInfo()
				.addKeyValue("updated", rowsUpdated > 0)
				.log("Link upserted");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean existsByShortCode(final String shortCode) {
		return jdbcClient.sql(EXISTS_BY_SHORT_CODE_QUERY)
				.param("shortCode", shortCode)
				.query(Boolean.class)
				.single();
	}

}
