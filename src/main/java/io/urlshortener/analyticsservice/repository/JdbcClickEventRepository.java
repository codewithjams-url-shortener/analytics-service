package io.urlshortener.analyticsservice.repository;

import io.urlshortener.eventcontracts.ClickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * {@link ClickEventRepository} backed by Spring's {@link JdbcClient} against Postgres.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcClickEventRepository implements ClickEventRepository {

	/**
	 * Idempotently inserts a click event.<br/>
	 * A duplicate {@code event_id} is a no-op via {@code ON CONFLICT DO NOTHING}, which is what makes redelivery of an
	 * already-processed SQS message safe.
	 */
	private static final String INSERT_CLICK_EVENT_QUERY = """
			INSERT INTO click_events (event_id, short_code, occurred_at, outcome, referer_domain, user_agent_raw, ip_hash)
			VALUES (:eventId, :shortCode, :occurredAt, :outcome, :refererDomain, :userAgentRaw, :ipHash)
			ON CONFLICT (event_id) DO NOTHING
			""";

	/**
	 * Upserts {@code link_stats}.<br/>
	 * Creates the row on a short code's first recorded click, or increments its existing per-outcome counters
	 * otherwise.
	 */
	private static final String INCREMENT_STATS_QUERY = """
			INSERT INTO link_stats (short_code, resolved_count, expired_count, not_found_count, last_clicked_at)
			VALUES (:shortCode, :resolvedCount, :expiredCount, :notFoundCount, :lastClickedAt)
			ON CONFLICT (short_code) DO UPDATE SET
			resolved_count = link_stats.resolved_count + EXCLUDED.resolved_count,
			expired_count = link_stats.expired_count + EXCLUDED.expired_count,
			not_found_count = link_stats.not_found_count + EXCLUDED.not_found_count,
			last_clicked_at = EXCLUDED.last_clicked_at
			""";

	/**
	 * Executes the SQL above against Postgres.
	 */
	private final JdbcClient jdbcClient;

	/**
	 * Inserts a click event, and idempotently rolls its outcome into {@code link_stats}.<br/>
	 * A duplicate {@code eventId} (already-processed message redelivered by SQS) is a no-op.
	 *
	 * @param event the event to record.
	 * @return {@code true} if this was a new event actually recorded; {@code false} if it was
	 * already processed (duplicate eventId).
	 */
	@Override
	public boolean recordIfNew(final ClickEvent event) {
		final JdbcClient.StatementSpec statementSpec = jdbcClient.sql(INSERT_CLICK_EVENT_QUERY)
				.param("eventId", event.eventId())
				.param("shortCode", event.shortCode())
				// PostgreSQL's JDBC driver can't infer a SQL type for a raw java.time.Instant - it supports
				// OffsetDateTime/LocalDateTime/java.sql.Timestamp for a TIMESTAMPTZ column, but not Instant.
				.param("occurredAt", Instant.ofEpochMilli(event.timestamp()).atOffset(ZoneOffset.UTC))
				.param("outcome", event.outcome().toString())
				.param("refererDomain", event.refererDomain())
				.param("userAgentRaw", event.userAgentRaw())
				.param("ipHash", event.ipHash());
		final int insertedRows = statementSpec.update();
		log.atInfo()
				.addKeyValue("insertCount", insertedRows)
				.log("Click Events Inserted");
		return insertedRows > 0;
	}

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
	@Override
	public void incrementStats(final String shortCode, final long resolvedDelta, final long expiredDelta,
							   final long notFoundDelta, final Instant lastClickedAt) {
		final JdbcClient.StatementSpec statementSpec = jdbcClient.sql(INCREMENT_STATS_QUERY)
				.param("shortCode", shortCode)
				.param("resolvedCount", resolvedDelta)
				.param("expiredCount", expiredDelta)
				.param("notFoundCount", notFoundDelta)
				.param("lastClickedAt", lastClickedAt.atOffset(ZoneOffset.UTC));
		final int affectedRows = statementSpec.update();
		log.atInfo()
				.addKeyValue("affectedRows", affectedRows)
				.log("Increment Stats updated");
	}

}
