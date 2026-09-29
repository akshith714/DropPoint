-- SOS emergency alert table
CREATE TABLE sos_alert (
    id              BIGSERIAL PRIMARY KEY,
    agent_id        BIGINT NOT NULL REFERENCES app_user(id),
    branch_id       BIGINT REFERENCES branch(id),
    triggered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    acknowledged_by BIGINT REFERENCES app_user(id),
    acknowledged_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    note            TEXT,

    CONSTRAINT sos_alert_status_check CHECK (status IN ('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED'))
);

CREATE INDEX idx_sos_alert_status ON sos_alert(status);
CREATE INDEX idx_sos_alert_agent  ON sos_alert(agent_id);
