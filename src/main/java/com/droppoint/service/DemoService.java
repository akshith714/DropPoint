package com.droppoint.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.GeoPoint;

/**
 * Demo support: reset the system to a known state, and move an agent along a
 * route in real time so live tracking can be shown indoors.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final double BRANCH_LAT = 17.3457;
    private static final double BRANCH_LON = 78.5522;

    private final AtomicBoolean driving = new AtomicBoolean(false);

    private final JdbcTemplate jdbc;
    private final CheckInService checkIns;
    private final TrackingService tracking;

    public DemoService(JdbcTemplate jdbc, CheckInService checkIns, TrackingService tracking) {
        this.jdbc = jdbc;
        this.checkIns = checkIns;
        this.tracking = tracking;
    }

    /**
     * Puts the system into the state the demo script expects:
     * today's live data cleared, agents 3 and 4 on duty at the branch,
     * one order waiting to be assigned. Seeded history is untouched.
     */
    @Transactional
    public Map<String, Object> reset() {
        LocalDate today = LocalDate.now(ZONE);

        // clear today's live activity only
        jdbc.update("DELETE FROM delivery_proof WHERE recorded_at::date = ?", today);
        jdbc.update("DELETE FROM orders WHERE NOT seeded AND booked_at::date = ?", today);
        jdbc.update("DELETE FROM location_ping WHERE recorded_at::date = ?", today);
        jdbc.update("DELETE FROM attendance WHERE work_date = ?", today);
        jdbc.update("DELETE FROM checkin_attempt WHERE attempted_at::date = ?", today);

        // agent 4 starts the day on duty; agent 3 stays out so the demo can check him in live
        checkIns.checkIn(4L, BRANCH_LAT + 0.0004, BRANCH_LON + 0.0003, 11, false);
        tracking.recordPing(4L, BRANCH_LAT + 0.0004, BRANCH_LON + 0.0003, 11, false);

        // one order waiting for the manager to assign on stage
        Long orderId = jdbc.queryForObject("""
                INSERT INTO orders (order_no, customer_id, branch_id, cylinder_count, status,
                                    delivery_code, booked_at, seeded)
                VALUES (?, 4, 1, 2, 'BOOKED', '4747', now(), FALSE)
                RETURNING id
                """, Long.class, "DEMO-" + System.currentTimeMillis() % 100000);

        log.info("Demo reset complete, waiting order {}", orderId);
        return Map.of(
                "status", "ready",
                "onDuty", "Suresh Reddy (agent 4)",
                "checkInLive", "Ravi Kumar (agent 3) is NOT checked in - do it on stage",
                "waitingOrderId", orderId,
                "waitingCustomer", "Priya Sharma, gated, floor 5, 2 cylinders");
    }

    /**
     * Drives an agent from the branch to a customer in real time, one ping every
     * few seconds, so the map visibly moves. Runs in the background.
     */
    @Async
    public void drive(Long agentId, double toLat, double toLon, int steps, int secondsPerStep) {
        if (!driving.compareAndSet(false, true)) {
            log.warn("A drive is already running");
            return;
        }
        try {
            GeoPoint from = new GeoPoint(BRANCH_LAT, BRANCH_LON);
            GeoPoint to = new GeoPoint(toLat, toLon);
            log.info("Driving agent {} over {} steps, {} m to cover",
                    agentId, steps, Math.round(from.distanceTo(to)));

            for (int i = 0; i < steps; i++) {
                double f = i / (double) (steps - 1);
                // a slight curve so it does not look like a ruler line
                double wobble = Math.sin(f * Math.PI) * 0.0012;
                double lat = BRANCH_LAT + (toLat - BRANCH_LAT) * f + wobble;
                double lon = BRANCH_LON + (toLon - BRANCH_LON) * f;

                tracking.recordPing(agentId, lat, lon, 9, true);
                Thread.sleep(secondsPerStep * 1000L);
            }
            log.info("Drive finished for agent {}", agentId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Drive failed", e);
        } finally {
            driving.set(false);
        }
    }

    public boolean isDriving() {
        return driving.get();
    }

    /** Quick check that everything the demo needs is working. */
    public Map<String, Object> healthCheck() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("database", jdbc.queryForObject("SELECT 'ok'", String.class));
        out.put("seededOrders", jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE seeded", Integer.class));
        out.put("deliveryProofs", jdbc.queryForObject(
                "SELECT count(*) FROM delivery_proof", Integer.class));
        out.put("quietCustomers", jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT customer_id FROM orders GROUP BY customer_id
                     HAVING CURRENT_DATE - max(booked_at)::date > 30) q
                """, Integer.class));
        out.put("agentsOnDutyNow", jdbc.queryForObject(
                "SELECT count(*) FROM attendance WHERE work_date = CURRENT_DATE AND check_out_at IS NULL",
                Integer.class));
        out.put("groqKeySet", System.getenv("GROQ_API_KEY") != null);
        return out;
    }
}
