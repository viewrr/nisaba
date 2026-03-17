CREATE TABLE nodes (
    node_id        TEXT PRIMARY KEY,
    base_url       TEXT NOT NULL,
    label          TEXT,
    client_type    TEXT NOT NULL DEFAULT 'qbittorrent',
    healthy        BOOLEAN NOT NULL DEFAULT false,
    ema_weight     REAL NOT NULL DEFAULT 0.5,
    last_speed_bps BIGINT,
    last_seen_at   TIMESTAMPTZ
);
