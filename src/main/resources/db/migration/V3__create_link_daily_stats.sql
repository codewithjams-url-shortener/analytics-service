CREATE TABLE link_daily_stats
(
    short_code      VARCHAR(32) NOT NULL,
    day             DATE        NOT NULL,
    resolved_count  BIGINT      NOT NULL DEFAULT 0,
    expired_count   BIGINT      NOT NULL DEFAULT 0,
    not_found_count BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (short_code, day)
);

-- Backfill from the source of truth so existing click history is not lost.
INSERT INTO link_daily_stats (short_code, day, resolved_count, expired_count, not_found_count)
SELECT short_code,
       (occurred_at AT TIME ZONE 'UTC')::date,
    COUNT(*) FILTER (WHERE outcome = 'RESOLVED'),
    COUNT(*) FILTER (WHERE outcome = 'EXPIRED'),
    COUNT(*) FILTER (WHERE outcome = 'NOT_FOUND')
FROM click_events
GROUP BY short_code, (occurred_at AT TIME ZONE 'UTC')::date;
