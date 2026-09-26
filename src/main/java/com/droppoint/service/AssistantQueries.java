package com.droppoint.service;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only data the AI assistant can reach. Every method is a fixed, read-only
 * query. The model chooses which one to call; it never writes SQL.
 */
@Service
@Transactional(readOnly = true)
public class AssistantQueries {

    private final JdbcTemplate jdbc;

    public AssistantQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Per-agent punctuality over the last N days. */
    public List<Map<String, Object>> agentPunctuality(int days) {
        return jdbc.queryForList("""
                SELECT u.full_name AS agent,
                       count(p.id) AS deliveries,
                       count(*) FILTER (WHERE p.late_minutes > 5) AS late_deliveries,
                       round(avg(p.late_minutes)::numeric, 1) AS avg_minutes_vs_eta,
                       round(max(p.late_minutes)::numeric, 1) AS worst_minutes_late,
                       (SELECT round(avg(EXTRACT(HOUR FROM a.check_in_at AT TIME ZONE 'Asia/Kolkata')
                                        + EXTRACT(MINUTE FROM a.check_in_at AT TIME ZONE 'Asia/Kolkata') / 60.0)::numeric, 2)
                          FROM attendance a
                         WHERE a.agent_id = u.id
                           AND a.work_date >= CURRENT_DATE - ?::int) AS avg_checkin_hour,
                       (SELECT count(*) FROM attendance a
                         WHERE a.agent_id = u.id
                           AND a.work_date >= CURRENT_DATE - ?::int
                           AND EXTRACT(HOUR FROM a.check_in_at AT TIME ZONE 'Asia/Kolkata') >= 9) AS late_checkins
                  FROM app_user u
                  LEFT JOIN delivery_proof p ON p.agent_id = u.id
                       AND p.recorded_at >= now() - make_interval(days => ?)
                 WHERE u.role = 'AGENT'
                 GROUP BY u.id, u.full_name
                 ORDER BY late_deliveries DESC
                """, days, days, days);
    }

    /** Headline delivery numbers over the last N days. */
    public Map<String, Object> deliveryStats(int days) {
        return jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered,
                       count(*) FILTER (WHERE o.status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE o.status IN ('BOOKED','ASSIGNED')) AS still_open,
                       (SELECT count(*) FROM delivery_proof p
                         WHERE p.recorded_at >= now() - make_interval(days => ?)
                           AND p.late_minutes > 5) AS late_deliveries,
                       (SELECT round(avg(p.late_minutes)::numeric, 1) FROM delivery_proof p
                         WHERE p.recorded_at >= now() - make_interval(days => ?)) AS avg_minutes_vs_eta,
                       (SELECT count(*) FROM delivery_proof p
                         WHERE p.recorded_at >= now() - make_interval(days => ?)
                           AND p.verdict = 'SUSPICIOUS') AS flagged_deliveries
                  FROM orders o
                 WHERE o.booked_at >= now() - make_interval(days => ?)
                """, days, days, days, days);
    }

    /** Where late deliveries cluster: by hour of day and by building type. */
    public Map<String, Object> lateBreakdown(int days) {
        List<Map<String, Object>> byHour = jdbc.queryForList("""
                SELECT EXTRACT(HOUR FROM p.recorded_at AT TIME ZONE 'Asia/Kolkata') AS hour_of_day,
                       count(*) AS deliveries,
                       count(*) FILTER (WHERE p.late_minutes > 5) AS late,
                       round(avg(p.late_minutes)::numeric, 1) AS avg_minutes_vs_eta
                  FROM delivery_proof p
                 WHERE p.recorded_at >= now() - make_interval(days => ?)
                 GROUP BY 1 HAVING count(*) >= 3 ORDER BY 1
                """, days);

        List<Map<String, Object>> byBuilding = jdbc.queryForList("""
                SELECT c.building_type, c.customer_type,
                       count(*) AS deliveries,
                       count(*) FILTER (WHERE p.late_minutes > 5) AS late,
                       round(avg(p.late_minutes)::numeric, 1) AS avg_minutes_vs_eta,
                       round(avg(o.handover_minutes)::numeric, 1) AS avg_handover_minutes
                  FROM delivery_proof p
                  JOIN orders o ON o.id = p.order_id
                  JOIN customer c ON c.id = o.customer_id
                 WHERE p.recorded_at >= now() - make_interval(days => ?)
                 GROUP BY 1, 2 ORDER BY late DESC
                """, days);

        return Map.of("by_hour_of_day", byHour, "by_building_type", byBuilding);
    }

    /** Customers who have gone quiet compared with their own usual refill gap. */
    public List<Map<String, Object>> quietCustomers(int minDaysQuiet) {
        return jdbc.queryForList("""
                WITH gaps AS (
                    SELECT o.customer_id,
                           o.booked_at::date AS order_date,
                           o.booked_at::date - lag(o.booked_at::date)
                               OVER (PARTITION BY o.customer_id ORDER BY o.booked_at) AS gap_days
                      FROM orders o
                     WHERE o.status IN ('DELIVERED','FAILED')
                ),
                profile AS (
                    SELECT customer_id,
                           round(avg(gap_days)) AS usual_gap_days,
                           count(*) AS gaps_seen
                      FROM gaps WHERE gap_days IS NOT NULL
                     GROUP BY customer_id
                )
                SELECT c.id AS customer_id, c.name, c.customer_type, c.phone, c.active,
                       max(o.booked_at)::date AS last_order_date,
                       CURRENT_DATE - max(o.booked_at)::date AS days_quiet,
                       pr.usual_gap_days,
                       count(o.id) AS total_orders,
                       count(*) FILTER (WHERE o.status = 'FAILED') AS failed_deliveries,
                       (SELECT count(*) FROM delivery_proof p
                          JOIN orders o2 ON o2.id = p.order_id
                         WHERE o2.customer_id = c.id AND p.late_minutes > 5) AS late_deliveries,
                       (SELECT round(avg(p.late_minutes)::numeric, 1) FROM delivery_proof p
                          JOIN orders o3 ON o3.id = p.order_id
                         WHERE o3.customer_id = c.id
                           AND p.recorded_at >= max(o.booked_at) - INTERVAL '90 days') AS recent_avg_late
                  FROM customer c
                  JOIN orders o ON o.customer_id = c.id
                  LEFT JOIN profile pr ON pr.customer_id = c.id
                 GROUP BY c.id, c.name, c.customer_type, c.phone, c.active, pr.usual_gap_days
                HAVING CURRENT_DATE - max(o.booked_at)::date >= ?
                 ORDER BY days_quiet DESC
                 LIMIT 25
                """, minDaysQuiet);
    }

    /** Everything the owner dashboard shows, in one call. */
    public Map<String, Object> dashboard() {
        Map<String, Object> today = todaySnapshot();

        Map<String, Object> month = jdbc.queryForMap("""
                SELECT count(*) AS deliveries,
                       count(*) FILTER (WHERE late_minutes > 5) AS late,
                       round(100.0 * count(*) FILTER (WHERE late_minutes <= 5) / greatest(count(*), 1), 0) AS on_time_pct,
                       round(avg(late_minutes)::numeric, 1) AS avg_minutes_vs_eta,
                       count(*) FILTER (WHERE verdict = 'SUSPICIOUS') AS flagged
                  FROM delivery_proof
                 WHERE recorded_at >= now() - INTERVAL '30 days'
                """);

        List<Map<String, Object>> agents = jdbc.queryForList("""
                SELECT u.full_name AS agent,
                       count(p.id) AS deliveries,
                       count(*) FILTER (WHERE p.late_minutes > 5) AS late,
                       round(avg(p.late_minutes)::numeric, 1) AS avg_vs_eta,
                       (SELECT count(*) FROM attendance a
                         WHERE a.agent_id = u.id
                           AND a.work_date >= CURRENT_DATE - 30
                           AND EXTRACT(HOUR FROM a.check_in_at AT TIME ZONE 'Asia/Kolkata') >= 9)
                           AS late_checkins,
                       EXISTS (SELECT 1 FROM attendance a
                                WHERE a.agent_id = u.id AND a.work_date = CURRENT_DATE
                                  AND a.check_out_at IS NULL) AS on_duty
                  FROM app_user u
                  LEFT JOIN delivery_proof p ON p.agent_id = u.id
                       AND p.recorded_at >= now() - INTERVAL '30 days'
                 WHERE u.role = 'AGENT'
                 GROUP BY u.id, u.full_name
                 ORDER BY late_checkins DESC, late DESC
                """);

        List<Map<String, Object>> lateByHour = jdbc.queryForList("""
                SELECT EXTRACT(HOUR FROM recorded_at AT TIME ZONE 'Asia/Kolkata')::int AS hour,
                       count(*) AS deliveries,
                       count(*) FILTER (WHERE late_minutes > 5) AS late
                  FROM delivery_proof
                 WHERE recorded_at >= now() - INTERVAL '30 days'
                 GROUP BY 1 HAVING count(*) >= 2 ORDER BY 1
                """);

        return Map.of("today", today, "month", month, "agents", agents,
                "lateByHour", lateByHour, "atRisk", quietCustomers(30));
    }

    /** Everything about one customer, matched loosely by name. */
    public Map<String, Object> customerHistory(String nameFragment) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT id, name, customer_type, phone, address_line, building_type,
                       floor_no, has_lift, active
                  FROM customer WHERE name ILIKE ? ORDER BY id LIMIT 1
                """, "%" + nameFragment.trim() + "%");

        if (found.isEmpty()) {
            return Map.of("found", false, "searched_for", nameFragment);
        }
        Long id = ((Number) found.get(0).get("id")).longValue();

        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT o.order_no, o.booked_at::date AS booked_on, o.status,
                       o.cylinder_count, o.failure_reason,
                       round(o.eta_minutes::numeric, 1) AS eta_minutes,
                       round(p.late_minutes::numeric, 1) AS minutes_vs_eta,
                       p.verdict
                  FROM orders o
                  LEFT JOIN delivery_proof p ON p.order_id = o.id
                 WHERE o.customer_id = ?
                 ORDER BY o.booked_at DESC LIMIT 15
                """, id);

        return Map.of("found", true, "customer", found.get(0), "recent_orders", orders);
    }

    /** What is happening right now. */
    public Map<String, Object> todaySnapshot() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM attendance
                         WHERE work_date = CURRENT_DATE AND check_out_at IS NULL) AS agents_on_duty,
                       (SELECT count(*) FROM attendance WHERE work_date = CURRENT_DATE) AS agents_checked_in_today,
                       (SELECT count(*) FROM orders WHERE status = 'BOOKED') AS orders_waiting,
                       (SELECT count(*) FROM orders WHERE status = 'ASSIGNED') AS orders_in_progress,
                       (SELECT count(*) FROM orders
                         WHERE status = 'DELIVERED' AND delivered_at::date = CURRENT_DATE) AS delivered_today,
                       (SELECT count(*) FROM orders
                         WHERE status = 'FAILED' AND delivered_at::date = CURRENT_DATE) AS failed_today,
                       (SELECT count(*) FROM delivery_proof
                         WHERE verdict = 'SUSPICIOUS' AND recorded_at::date = CURRENT_DATE) AS flagged_today
                """);
    }
}
