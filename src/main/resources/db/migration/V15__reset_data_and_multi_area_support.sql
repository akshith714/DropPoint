-- Multi-area support for agents
CREATE TABLE IF NOT EXISTS agent_preferred_area (
    agent_id  BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    area_id   BIGINT NOT NULL REFERENCES area(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, area_id)
);

-- Clear old runtime data & users
TRUNCATE TABLE sos_alert, location_ping, checkin_attempt, attendance, plan_assignment, day_plan, agent_availability, orders, customer, area, app_user CASCADE;

-- Ensure branch 1 (Main Branch)
INSERT INTO branch (id, name, latitude, longitude, checkin_radius_m)
VALUES (1, 'Main Branch', 17.3850, 78.4867, 10000)
ON CONFLICT (id) DO UPDATE SET checkin_radius_m = 10000;

-- Refresh System Users
INSERT INTO app_user (id, full_name, phone, role, branch_id, pin, active, daily_order_cap)
VALUES
  (1, 'System Admin',       '0000000000', 'ADMIN',   1, '1234', TRUE, 40),
  (2, 'Agency Owner',       '1111111111', 'OWNER',   1, '1234', TRUE, 40),
  (3, 'Operations Manager', '2222222222', 'MANAGER', 1, '1234', TRUE, 40),
  (4, 'Ramesh Kumar',       '3333333333', 'AGENT',   1, '1234', TRUE, 40),
  (5, 'Suresh Varma',       '3333333334', 'AGENT',   1, '1234', TRUE, 40),
  (6, 'Vikram Singh',       '3333333335', 'AGENT',   1, '1234', TRUE, 35);

SELECT setval('app_user_id_seq', 10, true);

-- Refresh Areas
INSERT INTO area (id, branch_id, name, latitude, longitude, active) VALUES
  (1, 1, 'Kothapet',       17.3695, 78.5447, TRUE),
  (2, 1, 'Dilsukhnagar',   17.3688, 78.5247, TRUE),
  (3, 1, 'L.B. Nagar',     17.3457, 78.5522, TRUE),
  (4, 1, 'Malakpet',       17.3753, 78.4983, TRUE),
  (5, 1, 'Santoshnagar',   17.3498, 78.5085, TRUE),
  (6, 1, 'Vanasthalipuram',17.3325, 78.5833, TRUE);

SELECT setval('area_id_seq', 10, true);

-- Assign Multiple Preferred Areas to Agents
INSERT INTO agent_preferred_area (agent_id, area_id) VALUES
  (4, 1), (4, 2), -- Ramesh covers Kothapet & Dilsukhnagar
  (5, 3), (5, 6), -- Suresh covers LB Nagar & Vanasthalipuram
  (6, 4), (6, 5)  -- Vikram covers Malakpet & Santoshnagar
ON CONFLICT DO NOTHING;

-- Seed Sample Customers
INSERT INTO customer (id, branch_id, name, phone, customer_type, address_line, latitude, longitude, building_type, floor_no, has_lift, active, area_id, location_source) VALUES
  (1,  1, 'K. Rajeshwar Rao',  '9849011111', 'DOMESTIC',   'H.No 12-45/1, Mohan Nagar, Kothapet',        17.3695, 78.5447, 'INDEPENDENT', 0, FALSE, TRUE, 1, 'AREA'),
  (2,  1, 'P. Sunitha Reddy',  '9849022222', 'DOMESTIC',   'Flat 302, Sri Sai Nivas, Dilsukhnagar',      17.3688, 78.5247, 'APARTMENT',   3, TRUE,  TRUE, 2, 'AREA'),
  (3,  1, 'M/s Paradise Hotel','9849033333', 'COMMERCIAL', 'Opp. Metro Pillar 14, L.B. Nagar X Roads',  17.3457, 78.5522, 'COMMERCIAL',  0, FALSE, TRUE, 3, 'AREA'),
  (4,  1, 'V. Srinivas',       '9849044444', 'DOMESTIC',   'Plot 88, Doctors Colony, Malakpet',          17.3753, 78.4983, 'INDEPENDENT', 1, FALSE, TRUE, 4, 'AREA'),
  (5,  1, 'Dr. A. Madhavi',    '9849055555', 'DOMESTIC',   'Flat 101, Green Meadows, Santoshnagar',      17.3498, 78.5085, 'GATED',       1, TRUE,  TRUE, 5, 'AREA'),
  (6,  1, 'G. Naresh Kumar',   '9849066666', 'DOMESTIC',   'H.No 4-12, NGO Colony, Vanasthalipuram',     17.3325, 78.5833, 'INDEPENDENT', 0, FALSE, TRUE, 6, 'AREA'),
  (7,  1, 'Hotel Bawarchi Sub','9849077777', 'COMMERCIAL', 'Main Road, Kothapet',                         17.3695, 78.5447, 'COMMERCIAL',  0, FALSE, TRUE, 1, 'AREA'),
  (8,  1, 'S. Anitha',         '9849088888', 'DOMESTIC',   'Flat 405, Vasavi Towers, Dilsukhnagar',      17.3688, 78.5247, 'APARTMENT',   4, TRUE,  TRUE, 2, 'AREA');

SELECT setval('customer_id_seq', 15, true);

-- Seed Sample Delivery Orders
INSERT INTO orders (id, order_no, customer_id, branch_id, cylinder_count, status, delivery_code, booked_at, area_id) VALUES
  (1,  'ORD-1001', 1, 1, 1, 'BOOKED', '4821', NOW() - INTERVAL '2 hours', 1),
  (2,  'ORD-1002', 2, 1, 1, 'BOOKED', '7193', NOW() - INTERVAL '2 hours', 2),
  (3,  'ORD-1003', 3, 1, 3, 'BOOKED', '1584', NOW() - INTERVAL '1 hour',  3),
  (4,  'ORD-1004', 4, 1, 1, 'BOOKED', '9240', NOW() - INTERVAL '1 hour',  4),
  (5,  'ORD-1005', 5, 1, 1, 'BOOKED', '3617', NOW() - INTERVAL '45 mins', 5),
  (6,  'ORD-1006', 6, 1, 2, 'BOOKED', '8402', NOW() - INTERVAL '30 mins', 6),
  (7,  'ORD-1007', 7, 1, 4, 'BOOKED', '6529', NOW() - INTERVAL '20 mins', 1),
  (8,  'ORD-1008', 8, 1, 1, 'BOOKED', '1947', NOW() - INTERVAL '10 mins', 2);

SELECT setval('orders_id_seq', 15, true);
