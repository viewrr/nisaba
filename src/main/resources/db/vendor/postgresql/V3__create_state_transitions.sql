CREATE TABLE state_transitions (
    id          BIGSERIAL PRIMARY KEY,
    infohash    TEXT NOT NULL REFERENCES torrents(infohash) ON DELETE CASCADE,
    from_state  torrent_state,
    to_state    torrent_state NOT NULL,
    node_id     TEXT,
    reason      TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_state_transitions_infohash ON state_transitions(infohash);
