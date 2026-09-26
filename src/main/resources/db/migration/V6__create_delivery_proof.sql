CREATE TABLE delivery_proof (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT           NOT NULL UNIQUE REFERENCES orders (id),
    agent_id      BIGINT           NOT NULL REFERENCES app_user (id),
    recorded_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    latitude      DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude     DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    distance_m    DOUBLE PRECISION NOT NULL,
    code_matched  BOOLEAN          NOT NULL,
    verdict       VARCHAR(20)      NOT NULL
                  CHECK (verdict IN ('VERIFIED', 'SUSPICIOUS')),
    late_minutes  DOUBLE PRECISION
);

ALTER TABLE orders
    ADD COLUMN failure_reason VARCHAR(30)
    CHECK (failure_reason IS NULL OR failure_reason IN
          ('NOBODY_HOME', 'WRONG_ADDRESS', 'CUSTOMER_REFUSED', 'NO_ACCESS', 'OTHER'));
