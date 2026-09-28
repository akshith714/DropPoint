-- Localities, so an order can be placed without anyone typing coordinates.
CREATE TABLE area (
    id         BIGSERIAL PRIMARY KEY,
    branch_id  BIGINT           NOT NULL REFERENCES branch (id),
    name       VARCHAR(60)      NOT NULL,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    active     BOOLEAN          NOT NULL DEFAULT TRUE,
    UNIQUE (branch_id, name)
);

-- How much we trust a customer's position: EXACT came from a real delivery,
-- GEOCODED from the address, AREA is only the locality centre.
ALTER TABLE customer
    ADD COLUMN area_id         BIGINT REFERENCES area (id),
    ADD COLUMN location_source VARCHAR(12) NOT NULL DEFAULT 'AREA'
        CHECK (location_source IN ('EXACT', 'GEOCODED', 'AREA'));

ALTER TABLE orders
    ADD COLUMN area_id      BIGINT REFERENCES area (id),
    ADD COLUMN import_batch VARCHAR(40);

CREATE INDEX idx_orders_batch ON orders (import_batch);

-- localities around the L.B. Nagar branch
INSERT INTO area (branch_id, name, latitude, longitude) VALUES
 (1, 'L.B. Nagar',       17.3457, 78.5522),
 (1, 'Kothapet',         17.3689, 78.5247),
 (1, 'Dilsukhnagar',     17.3687, 78.5247),
 (1, 'Nagole',           17.3712, 78.5580),
 (1, 'Vanasthalipuram',  17.3305, 78.5789),
 (1, 'Chaitanyapuri',    17.3620, 78.5310),
 (1, 'Saroornagar',      17.3520, 78.5350),
 (1, 'Hayathnagar',      17.3300, 78.6050),
 (1, 'Uppal',            17.4010, 78.5590),
 (1, 'Malakpet',         17.3750, 78.5030),
 (1, 'Champapet',        17.3420, 78.5310),
 (1, 'Karmanghat',       17.3360, 78.5400),
 (1, 'Meerpet',          17.3200, 78.5470),
 (1, 'Almasguda',        17.3150, 78.5650),
 (1, 'Kachiguda',        17.3830, 78.4950);
