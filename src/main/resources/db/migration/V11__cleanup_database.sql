-- Remove demo seed tracking table
DROP TABLE IF EXISTS seed_run CASCADE;

-- Clear operational tables so no data remains
TRUNCATE TABLE delivery_proof, location_ping, checkin_attempt, attendance, plan_assignment, day_plan, agent_availability, orders, customer CASCADE;

-- Remove seeded app users
DELETE FROM app_user;

-- Remove unwanted seeded columns
ALTER TABLE customer DROP COLUMN IF EXISTS seeded;
ALTER TABLE orders   DROP COLUMN IF EXISTS seeded;
