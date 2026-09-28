-- how many stops an agent can do in a day; editable per agent, no names in code
ALTER TABLE app_user
    ADD COLUMN daily_order_cap INTEGER NOT NULL DEFAULT 40 CHECK (daily_order_cap > 0);

-- who is in today; set by the manager each morning, before check-in happens
CREATE TABLE agent_availability (
    work_date  DATE    NOT NULL,
    agent_id   BIGINT  NOT NULL REFERENCES app_user (id),
    available  BOOLEAN NOT NULL DEFAULT TRUE,
    note       VARCHAR(100),
    PRIMARY KEY (work_date, agent_id)
);

-- one plan per day
CREATE TABLE day_plan (
    id           BIGSERIAL PRIMARY KEY,
    plan_date    DATE        NOT NULL UNIQUE,
    status       VARCHAR(12) NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT', 'CONFIRMED')),
    summary      TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ
);

-- which agent takes which area today, and how much of it
CREATE TABLE plan_assignment (
    id             BIGSERIAL PRIMARY KEY,
    plan_id        BIGINT  NOT NULL REFERENCES day_plan (id) ON DELETE CASCADE,
    agent_id       BIGINT  REFERENCES app_user (id),      -- null means nobody could take it
    area_id        BIGINT  NOT NULL REFERENCES area (id),
    order_count    INTEGER NOT NULL,
    cylinder_count INTEGER NOT NULL,
    partial        BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_plan_assignment_plan ON plan_assignment (plan_id);

-- orders that did not fit today
ALTER TABLE orders ADD COLUMN carried_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN plan_id BIGINT REFERENCES day_plan (id);
