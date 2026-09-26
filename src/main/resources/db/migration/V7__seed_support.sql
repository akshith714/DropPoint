-- two more agents so the history has four
INSERT INTO app_user (full_name, phone, role, branch_id) VALUES
    ('Mahesh Babu', '9000000005', 'AGENT', 1),
    ('Imran Khan',  '9000000006', 'AGENT', 1);

-- marks rows created by the generator, so demo data can be told apart
ALTER TABLE customer ADD COLUMN seeded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders   ADD COLUMN seeded BOOLEAN NOT NULL DEFAULT FALSE;

-- records that the generator has run, so it cannot run twice
CREATE TABLE seed_run (
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary    VARCHAR(300) NOT NULL
);
