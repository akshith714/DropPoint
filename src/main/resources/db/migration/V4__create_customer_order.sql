CREATE TABLE customer (
    id             BIGSERIAL PRIMARY KEY,
    branch_id      BIGINT           NOT NULL REFERENCES branch (id),
    name           VARCHAR(150)     NOT NULL,
    phone          VARCHAR(15)      NOT NULL,
    customer_type  VARCHAR(20)      NOT NULL CHECK (customer_type IN ('DOMESTIC', 'COMMERCIAL')),
    address_line   VARCHAR(250)     NOT NULL,
    latitude       DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude      DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    building_type  VARCHAR(20)      NOT NULL DEFAULT 'INDEPENDENT'
                   CHECK (building_type IN ('INDEPENDENT', 'APARTMENT', 'GATED', 'COMMERCIAL')),
    floor_no       SMALLINT         NOT NULL DEFAULT 0,
    has_lift       BOOLEAN          NOT NULL DEFAULT FALSE,
    active         BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id                 BIGSERIAL PRIMARY KEY,
    order_no           VARCHAR(30)  NOT NULL UNIQUE,
    customer_id        BIGINT       NOT NULL REFERENCES customer (id),
    branch_id          BIGINT       NOT NULL REFERENCES branch (id),
    cylinder_count     SMALLINT     NOT NULL DEFAULT 1 CHECK (cylinder_count > 0),
    status             VARCHAR(20)  NOT NULL DEFAULT 'BOOKED'
                       CHECK (status IN ('BOOKED', 'ASSIGNED', 'DELIVERED', 'FAILED', 'CANCELLED')),
    assigned_agent_id  BIGINT       REFERENCES app_user (id),
    assigned_at        TIMESTAMPTZ,
    delivery_code      VARCHAR(4)   NOT NULL,
    booked_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at       TIMESTAMPTZ
);

CREATE INDEX idx_orders_branch_status ON orders (branch_id, status);
CREATE INDEX idx_orders_agent_status ON orders (assigned_agent_id, status);

INSERT INTO customer (branch_id, name, phone, customer_type, address_line, latitude, longitude, building_type, floor_no, has_lift) VALUES
 (1, 'Lakshmi Devi',      '9100000001', 'DOMESTIC',   'Kothapet, near bus stop',        17.3689, 78.5247, 'APARTMENT',  3, FALSE),
 (1, 'Hotel Sri Krishna', '9100000002', 'COMMERCIAL', 'Dilsukhnagar main road',         17.3687, 78.5247, 'COMMERCIAL', 0, FALSE),
 (1, 'Venkat Rao',        '9100000003', 'DOMESTIC',   'Vanasthalipuram, phase 2',       17.3305, 78.5789, 'INDEPENDENT',0, FALSE),
 (1, 'Priya Sharma',      '9100000004', 'DOMESTIC',   'Nagole, Sai Enclave',            17.3712, 78.5580, 'GATED',      5, TRUE),
 (1, 'Anand Tiffins',     '9100000005', 'COMMERCIAL', 'L.B. Nagar circle',              17.3480, 78.5540, 'COMMERCIAL', 0, FALSE);
