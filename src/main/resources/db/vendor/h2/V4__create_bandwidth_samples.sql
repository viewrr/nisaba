CREATE TABLE bandwidth_samples (
    sampled_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    node_id     VARCHAR NOT NULL REFERENCES nodes(node_id),
    speed_bps   BIGINT NOT NULL
);

-- Skip TimescaleDB hypertable/retention (not available in H2)

CREATE INDEX idx_bandwidth_samples_node ON bandwidth_samples(node_id, sampled_at DESC);
