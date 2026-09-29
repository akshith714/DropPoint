package com.droppoint.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.droppoint.core.Rules;

/**
 * Builds the day's plan: which agent covers which area, within each agent's
 * daily cap. Nothing is hardcoded - agents, areas and availability all come
 * from the database.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public record AreaLine(Long assignmentId, Long areaId, String areaName,
                           int orders, int cylinders, boolean partial) { }

    public record AgentPlan(Long agentId, String agentName, int cap, int orders, int cylinders,
                            List<AreaLine> areas) { }

    public record PlanView(Long planId, LocalDate date, String status, String summary,
                           List<AgentPlan> agents, List<AreaLine> unassigned,
                           List<String> notes, int totalOrders, int totalCapacity,
                           int carriedIn) { }

    /** A saved plan and the result it was built from, before the summary is written. */
    private record Planned(Long planId, Rules.DayPlan.Result result) { }

    private final JdbcTemplate jdbc;
    private final AssistantService assistant;
    private final TransactionTemplate tx;

    public PlanService(JdbcTemplate jdbc, AssistantService assistant, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.assistant = assistant;
        this.tx = tx;
    }

    /* ---------------- planning ---------------- */

    /**
     * Saves the plan in one transaction, then asks the LLM for a summary outside it:
     * a slow or failing LLM call must not hold the transaction open or roll the plan back.
     */
    public PlanView plan() {
        Planned planned = tx.execute(status -> buildPlan());
        String summary = writeSummary(planned.result());
        jdbc.update("UPDATE day_plan SET summary = ? WHERE id = ?", summary, planned.planId());
        return view(planned.planId());
    }

    private Planned buildPlan() {
        LocalDate today = LocalDate.now(ZONE);

        Long existing = jdbc.query("SELECT id, status FROM day_plan WHERE plan_date = ?",
                rs -> rs.next() ? ("CONFIRMED".equals(rs.getString(2)) ? -rs.getLong(1) : rs.getLong(1)) : null,
                today);
        if (existing != null && existing < 0) {
            throw new IllegalStateException("Today's plan is already confirmed. Reopen it to re-plan.");
        }

        Long planId;
        if (existing != null) {
            planId = existing;
            jdbc.update("DELETE FROM plan_assignment WHERE plan_id = ?", planId);
        } else {
            planId = jdbc.queryForObject(
                    "INSERT INTO day_plan (plan_date, created_at) VALUES (?, now()) RETURNING id",
                    Long.class, today);
        }

        // what is waiting, by area
        List<Rules.DayPlan.AreaLoad> areaLoads = jdbc.query("""
                SELECT a.id, a.name, count(o.id), COALESCE(sum(o.cylinder_count), 0)
                  FROM orders o JOIN area a ON a.id = o.area_id
                 WHERE o.status = 'BOOKED'
                 GROUP BY a.id, a.name
                """, (rs, i) -> new Rules.DayPlan.AreaLoad(
                        rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));

        // who is in, with how much room left, and the area they covered last
        List<Rules.DayPlan.AgentSlot> slots = jdbc.query("""
                SELECT u.id, u.full_name,
                       GREATEST(u.daily_order_cap -
                           (SELECT count(*) FROM orders o
                             WHERE o.assigned_agent_id = u.id AND o.status = 'ASSIGNED'), 0) AS room,
                       COALESCE((SELECT pa.area_id FROM plan_assignment pa
                          JOIN day_plan dp ON dp.id = pa.plan_id
                         WHERE pa.agent_id = u.id AND dp.status = 'CONFIRMED'
                         ORDER BY dp.plan_date DESC LIMIT 1), u.preferred_area_id) AS last_area
                  FROM app_user u
                  LEFT JOIN agent_availability av ON av.agent_id = u.id AND av.work_date = ?
                 WHERE u.role = 'AGENT' AND u.active AND COALESCE(av.available, TRUE)
                 ORDER BY u.full_name
                """, (rs, i) -> new Rules.DayPlan.AgentSlot(
                        rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getObject(4) == null ? null : rs.getLong(4)),
                today);

        Rules.DayPlan.Result result = Rules.DayPlan.plan(areaLoads, slots);

        // cylinders per order, per area, so counts stay proportional on a split
        Map<Long, Double> cylPerOrder = new HashMap<>();
        for (Rules.DayPlan.AreaLoad a : areaLoads) {
            cylPerOrder.put(a.areaId(), a.orders() == 0 ? 1.0 : (double) a.cylinders() / a.orders());
        }

        for (Rules.DayPlan.Assignment a : result.assignments()) {
            jdbc.update("""
                    INSERT INTO plan_assignment (plan_id, agent_id, area_id, order_count, cylinder_count, partial)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, planId, a.agentId(), a.areaId(), a.orders(),
                    (int) Math.round(a.orders() * cylPerOrder.getOrDefault(a.areaId(), 1.0)), a.partial());
        }
        for (Rules.DayPlan.Leftover l : result.leftovers()) {
            jdbc.update("""
                    INSERT INTO plan_assignment (plan_id, agent_id, area_id, order_count, cylinder_count, partial)
                    VALUES (?, NULL, ?, ?, ?, FALSE)
                    """, planId, l.areaId(), l.orders(),
                    (int) Math.round(l.orders() * cylPerOrder.getOrDefault(l.areaId(), 1.0)));
        }

        log.info("Planned {}: {} assignments, {} leftovers",
                today, result.assignments().size(), result.leftovers().size());
        return new Planned(planId, result);
    }

    /** Manager moves one area to a different agent before confirming. */
    @Transactional
    public PlanView move(Long assignmentId, Long agentId) {
        Long planId = jdbc.queryForObject(
                "SELECT plan_id FROM plan_assignment WHERE id = ?", Long.class, assignmentId);
        String status = jdbc.queryForObject(
                "SELECT status FROM day_plan WHERE id = ?", String.class, planId);
        if ("CONFIRMED".equals(status)) {
            throw new IllegalStateException("This plan is confirmed and cannot be changed");
        }
        jdbc.update("UPDATE plan_assignment SET agent_id = ? WHERE id = ?", agentId, assignmentId);
        return view(planId);
    }

    /** Locks the plan in and assigns the orders. */
    @Transactional
    public Map<String, Object> confirm() {
        LocalDate today = LocalDate.now(ZONE);
        Long planId = jdbc.query(
                "SELECT id FROM day_plan WHERE plan_date = ? AND status = 'DRAFT'",
                rs -> rs.next() ? rs.getLong(1) : null, today);
        if (planId == null) {
            throw new NoSuchElementException("There is no draft plan for today");
        }

        int assigned = 0;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, agent_id, area_id, order_count FROM plan_assignment
                 WHERE plan_id = ? AND agent_id IS NOT NULL
                 ORDER BY id
                """, planId);

        for (Map<String, Object> r : rows) {
            Long agentId = ((Number) r.get("agent_id")).longValue();
            Long areaId = ((Number) r.get("area_id")).longValue();
            int count = ((Number) r.get("order_count")).intValue();

            assigned += jdbc.update("""
                    UPDATE orders SET assigned_agent_id = ?, assigned_at = now(),
                                      status = 'ASSIGNED', plan_id = ?
                     WHERE id IN (SELECT id FROM orders
                                   WHERE status = 'BOOKED' AND area_id = ?
                                   ORDER BY carried_days DESC, booked_at
                                   LIMIT ?)
                    """, agentId, planId, areaId, count);
        }

        int carried = jdbc.update(
                "UPDATE orders SET carried_days = carried_days + 1 WHERE status = 'BOOKED'");

        jdbc.update("UPDATE day_plan SET status = 'CONFIRMED', confirmed_at = now() WHERE id = ?", planId);

        log.info("Confirmed plan {}: {} orders assigned, {} carried over", planId, assigned, carried);
        return Map.of("status", "confirmed", "ordersAssigned", assigned, "carriedOver", carried);
    }

    /**
     * Undoes a confirm: orders not yet delivered or failed go back to BOOKED, and the
     * carry-over counted at confirm is taken back, so confirming again gives the same numbers.
     */
    @Transactional
    public void reopen() {
        Map<String, Object> plan = jdbc.query(
                "SELECT id, confirmed_at FROM day_plan WHERE plan_date = ? AND status = 'CONFIRMED'",
                rs -> rs.next() ? Map.of("id", rs.getLong(1), "confirmedAt", rs.getTimestamp(2)) : null,
                LocalDate.now(ZONE));
        if (plan == null) {
            throw new NoSuchElementException("There is no confirmed plan for today");
        }
        Long planId = (Long) plan.get("id");

        // orders that were waiting at confirm time and were counted as carried over
        jdbc.update("""
                UPDATE orders SET carried_days = carried_days - 1
                 WHERE status = 'BOOKED' AND carried_days > 0 AND booked_at < ?
                """, plan.get("confirmedAt"));

        int released = jdbc.update("""
                UPDATE orders SET status = 'BOOKED', assigned_agent_id = NULL,
                                  assigned_at = NULL, plan_id = NULL
                 WHERE plan_id = ? AND status = 'ASSIGNED'
                """, planId);

        jdbc.update("UPDATE day_plan SET status = 'DRAFT', confirmed_at = NULL WHERE id = ?", planId);
        log.info("Reopened plan {}: {} orders released", planId, released);
    }

    /* ---------------- reading ---------------- */

    @Transactional(readOnly = true)
    public PlanView today() {
        LocalDate today = LocalDate.now(ZONE);
        Long planId = jdbc.query("SELECT id FROM day_plan WHERE plan_date = ?",
                rs -> rs.next() ? rs.getLong(1) : null, today);
        return planId == null ? null : view(planId);
    }

    private PlanView view(Long planId) {
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT plan_date, status, summary FROM day_plan WHERE id = ?", planId);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pa.id, pa.agent_id, u.full_name, u.daily_order_cap,
                       pa.area_id, a.name AS area_name, pa.order_count, pa.cylinder_count, pa.partial
                  FROM plan_assignment pa
                  JOIN area a ON a.id = pa.area_id
                  LEFT JOIN app_user u ON u.id = pa.agent_id
                 WHERE pa.plan_id = ?
                 ORDER BY u.full_name NULLS LAST, pa.order_count DESC
                """, planId);

        Map<Long, AgentPlan> byAgent = new LinkedHashMap<>();
        List<AreaLine> unassigned = new ArrayList<>();
        int totalOrders = 0;

        for (Map<String, Object> r : rows) {
            int orders = ((Number) r.get("order_count")).intValue();
            int cyl = ((Number) r.get("cylinder_count")).intValue();
            totalOrders += orders;
            AreaLine line = new AreaLine(
                    ((Number) r.get("id")).longValue(),
                    ((Number) r.get("area_id")).longValue(),
                    (String) r.get("area_name"), orders, cyl, (Boolean) r.get("partial"));

            if (r.get("agent_id") == null) {
                unassigned.add(line);
                continue;
            }
            Long agentId = ((Number) r.get("agent_id")).longValue();
            AgentPlan existing = byAgent.get(agentId);
            List<AreaLine> areas = existing == null ? new ArrayList<>() : new ArrayList<>(existing.areas());
            areas.add(line);
            byAgent.put(agentId, new AgentPlan(agentId, (String) r.get("full_name"),
                    ((Number) r.get("daily_order_cap")).intValue(),
                    (existing == null ? 0 : existing.orders()) + orders,
                    (existing == null ? 0 : existing.cylinders()) + cyl, areas));
        }

        int capacity = byAgent.values().stream().mapToInt(AgentPlan::cap).sum();
        int carriedIn = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE status = 'BOOKED' AND carried_days > 0", Integer.class);

        return new PlanView(planId, ((java.sql.Date) head.get("plan_date")).toLocalDate(),
                (String) head.get("status"), (String) head.get("summary"),
                new ArrayList<>(byAgent.values()), unassigned, List.of(),
                totalOrders, capacity, carriedIn);
    }

    /** One paragraph from the LLM; falls back to plain text if it is unavailable. */
    private String writeSummary(Rules.DayPlan.Result result) {
        StringBuilder facts = new StringBuilder();
        facts.append("Total orders: ").append(result.totalOrders())
             .append(". Total capacity: ").append(result.totalCapacity()).append(".\n");
        result.assignments().forEach(a -> facts.append(a.agentName()).append(" takes ")
                .append(a.orders()).append(" in ").append(a.areaName())
                .append(a.partial() ? " (part of the area)" : "").append(".\n"));
        result.leftovers().forEach(l -> facts.append(l.orders()).append(" orders in ")
                .append(l.areaName()).append(" do not fit: ").append(l.reason()).append(".\n"));
        result.notes().forEach(n -> facts.append(n).append("\n"));

        try {
            return assistant.ask("""
                    Write two or three sentences for the branch manager summarising today's delivery plan.
                    Use only these facts. Name the agents and the numbers. Mention anything that needs
                    a decision. Do not add advice beyond the facts.

                    """ + facts).answer();
        } catch (Exception e) {
            log.warn("Summary unavailable: {}", e.getMessage());
            return facts.toString();
        }
    }

    public record AgentProgressItem(Long agentId, String agentName, int assigned, int delivered,
                                    int failed, int cylindersDelivered, Instant checkInAt, Instant checkOutAt) { }

    public record ProgressReport(LocalDate date, String planStatus, int totalOrders, int assignedOrders,
                                 int deliveredOrders, int failedOrders, int carriedOver,
                                 int deliveredCylinders, double completionRate,
                                 List<AgentProgressItem> agentProgress) { }

    @Transactional(readOnly = true)
    public ProgressReport progress(LocalDate date) {
        if (date == null) {
            date = LocalDate.now(ZONE);
        }

        String planStatus = jdbc.query("SELECT status FROM day_plan WHERE plan_date = ?",
                rs -> rs.next() ? rs.getString(1) : "NO_PLAN", date);

        int totalOrders = jdbc.queryForObject("""
                SELECT count(*) FROM orders
                 WHERE booked_at::date = ?
                """, Integer.class, date);

        int assignedOrders = jdbc.queryForObject("""
                SELECT count(*) FROM orders
                 WHERE assigned_at::date = ?
                """, Integer.class, date);

        int deliveredOrders = jdbc.queryForObject("""
                SELECT count(*) FROM orders
                 WHERE delivered_at::date = ? AND status = 'DELIVERED'
                """, Integer.class, date);

        int failedOrders = jdbc.queryForObject("""
                SELECT count(*) FROM orders
                 WHERE delivered_at::date = ? AND status = 'FAILED'
                """, Integer.class, date);

        int carriedOver = jdbc.queryForObject("""
                SELECT count(*) FROM orders
                 WHERE status = 'BOOKED' AND booked_at::date <= ?
                """, Integer.class, date);

        int deliveredCylinders = jdbc.queryForObject("""
                SELECT COALESCE(sum(cylinder_count), 0) FROM orders
                 WHERE delivered_at::date = ? AND status = 'DELIVERED'
                """, Integer.class, date);

        double completionRate = assignedOrders == 0 ? 0.0 :
                Math.round((double) deliveredOrders / assignedOrders * 1000.0) / 10.0;

        List<AgentProgressItem> agentProgress = jdbc.query("""
                SELECT u.id, u.full_name,
                       (SELECT count(*) FROM orders o WHERE o.assigned_agent_id = u.id AND o.assigned_at::date = ?) AS assigned,
                       (SELECT count(*) FROM orders o WHERE o.assigned_agent_id = u.id AND o.delivered_at::date = ? AND o.status = 'DELIVERED') AS delivered,
                       (SELECT count(*) FROM orders o WHERE o.assigned_agent_id = u.id AND o.delivered_at::date = ? AND o.status = 'FAILED') AS failed,
                       (SELECT COALESCE(sum(o.cylinder_count), 0) FROM orders o WHERE o.assigned_agent_id = u.id AND o.delivered_at::date = ? AND o.status = 'DELIVERED') AS cylinders,
                       att.check_in_at, att.check_out_at
                  FROM app_user u
                  LEFT JOIN attendance att ON att.agent_id = u.id AND att.work_date = ?
                 WHERE u.role = 'AGENT' AND u.active
                 ORDER BY u.full_name
                """,
                (rs, i) -> new AgentProgressItem(
                        rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()),
                date, date, date, date, date);

        return new ProgressReport(date, planStatus, totalOrders, assignedOrders, deliveredOrders,
                failedOrders, carriedOver, deliveredCylinders, completionRate, agentProgress);
    }
}
