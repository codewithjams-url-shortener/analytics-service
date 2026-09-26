CREATE TABLE click_events
(
    event_id       TEXT PRIMARY KEY,
    short_code     VARCHAR(32) NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    outcome        VARCHAR(16) NOT NULL,
    referer_domain VARCHAR(255),
    user_agent_raw TEXT,
    ip_hash        VARCHAR(64) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE link_stats
(
    short_code      VARCHAR(32) PRIMARY KEY,
    resolved_count  BIGINT NOT NULL DEFAULT 0,
    expired_count   BIGINT NOT NULL DEFAULT 0,
    not_found_count BIGINT NOT NULL DEFAULT 0,
    last_clicked_at TIMESTAMPTZ
);
