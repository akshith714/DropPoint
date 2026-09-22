CREATE TABLE location_ping (
    id           BIGSERIAL PRIMARY KEY,
    agent_id     BIGINT           NOT NULL REFERENCES app_user (id),
    recorded_at  TIMESTAMPTZ      NOT NULL,
    latitude     DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude    DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy_m   DOUBLE PRECISION NOT NULL,
    speed_kmph   DOUBLE PRECISION,
    suspicious   BOOLEAN          NOT NULL DEFAULT FALSE,
    simulated    BOOLEAN          NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_ping_agent_time ON location_ping (agent_id, recorded_at DESC);
