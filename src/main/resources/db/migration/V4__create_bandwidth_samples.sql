CREATE TABLE bandwidth_samples (
    sampled_at  TIMESTAMPTZ NOT NULL,
    node_id     TEXT NOT NULL REFERENCES nodes(node_id),
    speed_bps   BIGINT NOT NULL
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('bandwidth_samples', 'sampled_at',
            chunk_time_interval => INTERVAL '6 hours');
        PERFORM add_retention_policy('bandwidth_samples', INTERVAL '48 hours');
    END IF;
END $$;

CREATE INDEX idx_bandwidth_samples_node ON bandwidth_samples(node_id, sampled_at DESC);
