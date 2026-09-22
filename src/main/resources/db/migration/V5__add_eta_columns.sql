ALTER TABLE orders
    ADD COLUMN travel_minutes   DOUBLE PRECISION,
    ADD COLUMN handover_minutes DOUBLE PRECISION,
    ADD COLUMN eta_minutes      DOUBLE PRECISION,
    ADD COLUMN eta_at           TIMESTAMPTZ,
    ADD COLUMN eta_source       VARCHAR(10),
    ADD COLUMN eta_explanation  VARCHAR(300);
