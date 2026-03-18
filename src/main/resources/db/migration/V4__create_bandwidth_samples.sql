CREATE TABLE bandwidth_samples (
    sampled_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    node_id     TEXT NOT NULL REFERENCES nodes(node_id),
    speed_bps   BIGINT NOT NULL
);

CREATE INDEX idx_bandwidth_samples_node ON bandwidth_samples(node_id, sampled_at DESC);
