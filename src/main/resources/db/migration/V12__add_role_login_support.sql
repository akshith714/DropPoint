-- Add ADMIN role, pin column for auth, and seed initial test accounts for each role

ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK (role IN ('ADMIN', 'OWNER', 'MANAGER', 'AGENT'));

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS pin VARCHAR(100) NOT NULL DEFAULT '1234';

-- Ensure branch 1 exists for user foreign keys
INSERT INTO branch (id, name, latitude, longitude)
VALUES (1, 'Main Branch', 17.3850, 78.4867)
ON CONFLICT (id) DO NOTHING;

-- Seed initial account for each role (Default PIN: 1234)
INSERT INTO app_user (full_name, phone, role, branch_id, pin)
VALUES 
  ('System Admin', '0000000000', 'ADMIN', 1, '1234'),
  ('Agency Owner', '1111111111', 'OWNER', 1, '1234'),
  ('Operations Manager', '2222222222', 'MANAGER', 1, '1234'),
  ('Delivery Agent', '3333333333', 'AGENT', 1, '1234')
ON CONFLICT (phone) DO UPDATE 
  SET role = EXCLUDED.role, pin = EXCLUDED.pin, full_name = EXCLUDED.full_name;
