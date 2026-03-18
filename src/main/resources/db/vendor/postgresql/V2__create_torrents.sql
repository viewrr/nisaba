CREATE TYPE torrent_state AS ENUM (
    'queued', 'assigning', 'downloading',
    'stalled', 'reassigning', 'paused', 'done', 'failed'
);

CREATE TABLE torrents (
    infohash         TEXT PRIMARY KEY,
    name             TEXT,
    magnet_uri       TEXT NOT NULL,
    category         TEXT,
    save_path        TEXT NOT NULL,
    state            torrent_state NOT NULL DEFAULT 'queued',
    assigned_node_id TEXT REFERENCES nodes(node_id),
    progress_pct     REAL DEFAULT 0,
    total_size       BIGINT,
    content_path     TEXT,
    eta              BIGINT,
    ratio            REAL,
    seeding_time     BIGINT,
    pieces_bitmap    BYTEA,
    last_synced_at   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_torrents_state ON torrents(state);
CREATE INDEX idx_torrents_assigned_node ON torrents(assigned_node_id);
