CREATE TABLE state_transitions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    infohash    TEXT NOT NULL REFERENCES torrents(infohash) ON DELETE CASCADE,
    from_state  VARCHAR(20),
    to_state    VARCHAR(20) NOT NULL,
    node_id     TEXT,
    reason      TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_state_transitions_infohash ON state_transitions(infohash);
