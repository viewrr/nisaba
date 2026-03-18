-- H2 doesn't support CREATE TYPE AS ENUM; use VARCHAR with a CHECK constraint
CREATE TABLE torrents (
    infohash         VARCHAR PRIMARY KEY,
    name             VARCHAR,
    magnet_uri       VARCHAR NOT NULL,
    category         VARCHAR,
    save_path        VARCHAR NOT NULL,
    state            VARCHAR NOT NULL DEFAULT 'queued'
        CHECK (state IN ('queued', 'assigning', 'downloading',
                         'stalled', 'reassigning', 'paused', 'done', 'failed')),
    assigned_node_id VARCHAR REFERENCES nodes(node_id),
    progress_pct     REAL DEFAULT 0,
    total_size       BIGINT,
    content_path     VARCHAR,
    eta              BIGINT,
    ratio            REAL,
    seeding_time     BIGINT,
    pieces_bitmap    BINARY,
    last_synced_at   TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_torrents_state ON torrents(state);
CREATE INDEX idx_torrents_assigned_node ON torrents(assigned_node_id);
