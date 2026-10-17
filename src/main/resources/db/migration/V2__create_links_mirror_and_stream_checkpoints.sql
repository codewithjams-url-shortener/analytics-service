CREATE TABLE links
(
    short_code      VARCHAR(32) PRIMARY KEY,
    long_url        TEXT NOT NULL,
    owner_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ,
    status          VARCHAR(32),
    deleted_at      TIMESTAMPTZ,
    deletion_reason VARCHAR(20)
);

CREATE TABLE stream_checkpoints
(
    shard_id        VARCHAR(128) PRIMARY KEY,
    sequence_number VARCHAR(64) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
