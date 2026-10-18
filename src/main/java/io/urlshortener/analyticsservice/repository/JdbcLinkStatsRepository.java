package io.urlshortener.analyticsservice.repository;

import io.urlshortener.analyticsservice.model.domainObject.DailyStats;
import io.urlshortener.analyticsservice.model.domainObject.LinkStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link LinkStatsRepository} backed by Postgres through Spring's {@link JdbcClient}.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcLinkStatsRepository implements LinkStatsRepository {

	/**
	 * Reads a short code's all-time totals from {@code link_stats}.
	 */
	private static final String FIND_TOTAL_QUERY = """
			SELECT resolved_count, expired_count, not_found_count, last_clicked_at
			FROM link_stats
			WHERE short_code = :shortCode
			""";

	/**
	 * Reads a short code's rows from {@code link_daily_stats} for an inclusive range of UTC days, oldest first.
	 */
	private static final String FIND_DAILY_QUERY = """
			SELECT day, resolved_count, expired_count, not_found_count
			FROM link_daily_stats
			WHERE short_code = :shortCode
			AND day BETWEEN :from AND :to
			ORDER BY day
			""";

	/**
	 * Executes the SQL above against Postgres.
	 */
	private final JdbcClient jdbcClient;

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 *     {@code last_clicked_at} is nullable in the schema, so it is mapped to {@code null} rather than assumed present.
	 * </p>
	 */
	@Override
	public Optional<LinkStats> findTotals(final String shortCode) {
		return jdbcClient.sql(FIND_TOTAL_QUERY)
				.param("shortCode", shortCode)
				.query(
						(rs, rowNum) -> {
							final OffsetDateTime lastClickedAt =
									rs.getObject("last_clicked_at", OffsetDateTime.class);
							return new LinkStats(
									rs.getLong("resolved_count"),
									rs.getLong("expired_count"),
									rs.getLong("not_found_count"),
									Objects.nonNull(lastClickedAt) ? lastClickedAt.toInstant() : null
							);
						}
				).optional();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<DailyStats> findDaily(final String shortCode, final LocalDate from, final LocalDate to) {
		return jdbcClient.sql(FIND_DAILY_QUERY)
				.param("shortCode", shortCode)
				.param("from", from)
				.param("to", to)
				.query(
						(rs, rowNum) -> new DailyStats(
								rs.getObject("day", LocalDate.class),
								rs.getLong("resolved_count"),
								rs.getLong("expired_count"),
								rs.getLong("not_found_count")
						)
				)
				.list();
	}

}
