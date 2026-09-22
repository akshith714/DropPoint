INSERT INTO branch (name, latitude, longitude, checkin_radius_m)
VALUES ('L.B. Nagar Branch', 17.3457, 78.5522, 200);

INSERT INTO app_user (full_name, phone, role, branch_id) VALUES
    ('Owner Demo',   '9000000001', 'OWNER',   NULL),
    ('Manager Demo', '9000000002', 'MANAGER', 1),
    ('Ravi Kumar',   '9000000003', 'AGENT',   1),
    ('Suresh Reddy', '9000000004', 'AGENT',   1);
