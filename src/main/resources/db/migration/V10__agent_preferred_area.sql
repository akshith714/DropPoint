-- Add preferred/regular area column for agents
ALTER TABLE app_user
    ADD COLUMN preferred_area_id BIGINT REFERENCES area (id);
