CREATE TABLE nodes (
    node_id        VARCHAR PRIMARY KEY,
    base_url       VARCHAR NOT NULL,
    label          VARCHAR,
    client_type    VARCHAR NOT NULL DEFAULT 'qbittorrent',
    healthy        BOOLEAN NOT NULL DEFAULT false,
    ema_weight     REAL NOT NULL DEFAULT 0.5,
    last_speed_bps BIGINT,
    last_seen_at   TIMESTAMP WITH TIME ZONE
);
