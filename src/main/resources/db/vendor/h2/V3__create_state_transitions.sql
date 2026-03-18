CREATE TABLE state_transitions (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    infohash    VARCHAR NOT NULL REFERENCES torrents(infohash) ON DELETE CASCADE,
    from_state  VARCHAR,
    to_state    VARCHAR NOT NULL,
    node_id     VARCHAR,
    reason      VARCHAR,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_state_transitions_infohash ON state_transitions(infohash);
