package com.droppoint.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages agents in the system: adding new agents, editing their details, multiple preferred regular areas,
 * daily order cap, toggling availability, and deactivating agents. No agent names are hardcoded.
 */
@Service
public class AgentAdminService {

    private static final Logger log = LoggerFactory.getLogger(AgentAdminService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public record AgentItem(Long id, String name, String phone, int cap, boolean available, int assignedToday,
                            Long preferredAreaId, String preferredAreaName,
                            List<Long> preferredAreaIds, List<String> preferredAreaNames) { }

    public record CreateAgentRequest(String name, String phone, Integer cap, Long preferredAreaId, List<Long> preferredAreaIds) { }

    public record UpdateAgentRequest(String name, String phone, Integer cap, Boolean available, Long preferredAreaId, List<Long> preferredAreaIds) { }

    private final JdbcTemplate jdbc;

    public AgentAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AgentItem> listAgents() {
        LocalDate today = LocalDate.now(ZONE);
        List<Long> agentIds = jdbc.queryForList("SELECT id FROM app_user WHERE role = 'AGENT' AND active ORDER BY full_name", Long.class);
        List<AgentItem> items = new ArrayList<>();
        for (Long id : agentIds) {
            items.add(getAgent(id, today));
        }
        return items;
    }

    @Transactional
    public AgentItem addAgent(String name, String phone, Integer cap, Long preferredAreaId, List<Long> preferredAreaIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent name is required");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Phone number is required");
        }
        if (!phone.trim().matches("\\d{10}")) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits");
        }
        int dailyCap = (cap != null && cap > 0) ? cap : 40;

        Long firstArea = (preferredAreaIds != null && !preferredAreaIds.isEmpty()) ? preferredAreaIds.get(0) : preferredAreaId;

        Long id = jdbc.queryForObject("""
                INSERT INTO app_user (full_name, phone, role, branch_id, active, daily_order_cap, preferred_area_id, created_at)
                VALUES (?, ?, 'AGENT', 1, TRUE, ?, ?, now())
                RETURNING id
                """, Long.class, name.trim(), phone.trim(), dailyCap, firstArea);

        if (preferredAreaIds != null && !preferredAreaIds.isEmpty()) {
            savePreferredAreas(id, preferredAreaIds);
        } else if (preferredAreaId != null && preferredAreaId > 0) {
            savePreferredAreas(id, List.of(preferredAreaId));
        }

        log.info("Added agent {}: {} ({}) cap {}", id, name, phone, dailyCap);
        return getAgent(id);
    }

    @Transactional
    public AgentItem addAgent(String name, String phone, Integer cap, Long preferredAreaId) {
        return addAgent(name, phone, cap, preferredAreaId, preferredAreaId != null ? List.of(preferredAreaId) : null);
    }

    @Transactional
    public AgentItem updateAgent(Long id, String name, String phone, Integer cap, Boolean available, Long preferredAreaId, List<Long> preferredAreaIds) {
        if (name != null && !name.isBlank()) {
            jdbc.update("UPDATE app_user SET full_name = ? WHERE id = ?", name.trim(), id);
        }
        if (phone != null && !phone.isBlank()) {
            if (!phone.trim().matches("\\d{10}")) {
                throw new IllegalArgumentException("Phone number must be exactly 10 digits");
            }
            jdbc.update("UPDATE app_user SET phone = ? WHERE id = ?", phone.trim(), id);
        }
        if (cap != null && cap > 0) {
            jdbc.update("UPDATE app_user SET daily_order_cap = ? WHERE id = ?", cap, id);
        }

        if (preferredAreaIds != null) {
            savePreferredAreas(id, preferredAreaIds);
            Long firstArea = preferredAreaIds.isEmpty() ? null : preferredAreaIds.get(0);
            jdbc.update("UPDATE app_user SET preferred_area_id = ? WHERE id = ?", firstArea, id);
        } else if (preferredAreaId != null) {
            Long firstArea = preferredAreaId <= 0 ? null : preferredAreaId;
            savePreferredAreas(id, firstArea != null ? List.of(firstArea) : List.of());
            jdbc.update("UPDATE app_user SET preferred_area_id = ? WHERE id = ?", firstArea, id);
        }

        if (available != null) {
            LocalDate today = LocalDate.now(ZONE);
            jdbc.update("""
                    INSERT INTO agent_availability (work_date, agent_id, available)
                    VALUES (?, ?, ?)
                    ON CONFLICT (work_date, agent_id)
                    DO UPDATE SET available = EXCLUDED.available
                    """, today, id, available);
        }
        log.info("Updated agent {}", id);
        return getAgent(id);
    }

    @Transactional
    public AgentItem updateAgent(Long id, String name, String phone, Integer cap, Boolean available, Long preferredAreaId) {
        return updateAgent(id, name, phone, cap, available, preferredAreaId, null);
    }

    @Transactional
    public void deleteAgent(Long id) {
        jdbc.update("UPDATE app_user SET active = FALSE WHERE id = ?", id);
        log.info("Deactivated agent {}", id);
    }

    @Transactional(readOnly = true)
    public AgentItem getAgent(Long id) {
        return getAgent(id, LocalDate.now(ZONE));
    }

    private AgentItem getAgent(Long id, LocalDate today) {
        List<Long> areaIds = jdbc.queryForList("""
                SELECT area_id FROM agent_preferred_area WHERE agent_id = ?
                """, Long.class, id);

        List<String> areaNames = new ArrayList<>();
        if (!areaIds.isEmpty()) {
            for (Long aId : areaIds) {
                try {
                    String name = jdbc.queryForObject("SELECT name FROM area WHERE id = ?", String.class, aId);
                    areaNames.add(name);
                } catch (Exception e) {}
            }
        }

        return jdbc.queryForObject("""
                SELECT u.id, u.full_name, u.phone, u.daily_order_cap,
                       COALESCE(av.available, TRUE) AS available,
                       (SELECT count(*) FROM orders o
                         WHERE o.assigned_agent_id = u.id AND o.status = 'ASSIGNED') AS assigned_today,
                       u.preferred_area_id,
                       pa.name AS preferred_area_name
                  FROM app_user u
                  LEFT JOIN agent_availability av ON av.agent_id = u.id AND av.work_date = ?
                  LEFT JOIN area pa ON pa.id = u.preferred_area_id
                 WHERE u.id = ?
                """,
                (rs, i) -> {
                    Long singlePrefId = rs.getObject(7) == null ? null : rs.getLong(7);
                    String singlePrefName = rs.getString(8);
                    
                    List<Long> finalIds = areaIds.isEmpty() && singlePrefId != null ? List.of(singlePrefId) : areaIds;
                    List<String> finalNames = areaNames.isEmpty() && singlePrefName != null ? List.of(singlePrefName) : areaNames;

                    return new AgentItem(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getBoolean(5), rs.getInt(6),
                            singlePrefId, singlePrefName, finalIds, finalNames);
                },
                today, id);
    }

    private void savePreferredAreas(Long agentId, List<Long> areaIds) {
        jdbc.update("DELETE FROM agent_preferred_area WHERE agent_id = ?", agentId);
        if (areaIds != null) {
            for (Long areaId : areaIds) {
                if (areaId != null && areaId > 0) {
                    jdbc.update("INSERT INTO agent_preferred_area (agent_id, area_id) VALUES (?, ?) ON CONFLICT DO NOTHING", agentId, areaId);
                }
            }
        }
    }
}
