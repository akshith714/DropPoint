CREATE TABLE branch (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(100)     NOT NULL,
    latitude          DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude         DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    checkin_radius_m  INTEGER          NOT NULL DEFAULT 200 CHECK (checkin_radius_m > 0),
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id          BIGSERIAL PRIMARY KEY,
    full_name   VARCHAR(100) NOT NULL,
    phone       VARCHAR(15)  NOT NULL UNIQUE,
    role        VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER', 'MANAGER', 'AGENT')),
    branch_id   BIGINT       REFERENCES branch (id),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE attendance (
    id                   BIGSERIAL PRIMARY KEY,
    agent_id             BIGINT           NOT NULL REFERENCES app_user (id),
    branch_id            BIGINT           NOT NULL REFERENCES branch (id),
    work_date            DATE             NOT NULL,
    check_in_at          TIMESTAMPTZ      NOT NULL,
    check_in_latitude    DOUBLE PRECISION NOT NULL,
    check_in_longitude   DOUBLE PRECISION NOT NULL,
    check_in_accuracy_m  DOUBLE PRECISION NOT NULL,
    check_in_distance_m  DOUBLE PRECISION NOT NULL,
    check_out_at         TIMESTAMPTZ,
    UNIQUE (agent_id, work_date)
);

CREATE TABLE checkin_attempt (
    id            BIGSERIAL PRIMARY KEY,
    agent_id      BIGINT           NOT NULL REFERENCES app_user (id),
    branch_id     BIGINT           NOT NULL REFERENCES branch (id),
    attempted_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    latitude      DOUBLE PRECISION NOT NULL,
    longitude     DOUBLE PRECISION NOT NULL,
    accuracy_m    DOUBLE PRECISION NOT NULL,
    mock_location BOOLEAN          NOT NULL,
    distance_m    DOUBLE PRECISION NOT NULL,
    allowed       BOOLEAN          NOT NULL,
    reason        VARCHAR(20)      NOT NULL
);

CREATE INDEX idx_checkin_attempt_agent_time ON checkin_attempt (agent_id, attempted_at DESC);
