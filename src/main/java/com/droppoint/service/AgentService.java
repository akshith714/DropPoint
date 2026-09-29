package com.droppoint.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.GeoPoint;
import com.droppoint.core.Rules.CheckInPolicy;
import com.droppoint.repo.Workforce.AppUser;
import com.droppoint.repo.Workforce.AppUser.AppUserRepository;
import com.droppoint.repo.Workforce.Attendance;
import com.droppoint.repo.Workforce.Attendance.AttendanceRepository;
import com.droppoint.repo.Workforce.Branch;
import com.droppoint.repo.Workforce.Branch.BranchRepository;
import com.droppoint.repo.Workforce.CheckinAttempt;
import com.droppoint.repo.Workforce.CheckinAttempt.CheckinAttemptRepository;
import com.droppoint.repo.Workforce.LocationPing;
import com.droppoint.repo.Workforce.LocationPing.LocationPingRepository;
import com.droppoint.repo.Workforce.SosAlert;
import com.droppoint.repo.Workforce.SosAlert.SosAlertRepository;

/**
 * Everything about an agent's working day: checking in at the branch, checking
 * out, and the position pings recorded in between. Tracking only accepts a ping
 * from an agent who is on duty, which is why the two live together.
 */
@Service
public class AgentService {

    private static final double MAX_ACCURACY_METRES = 100;

    /** Above this speed between two pings, the movement is physically implausible. */
    private static final double MAX_PLAUSIBLE_KMPH = 150;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    public record CheckInResponse(boolean allowed, String status, String message,
                                  long distanceMetres, Long attendanceId, Instant checkInAt) { }

    public record CheckOutResponse(String status, String message, Long attendanceId,
                                   Instant checkInAt, Instant checkOutAt, long workedMinutes) { }

    public record PingResponse(boolean accepted, String status, Double speedKmph, boolean suspicious) { }

    public record SosResponse(Long id, String status, String message) { }

    public record SosAlertView(Long id, Long agentId, String agentName, String agentPhone,
                                Long branchId, String branchName,
                                Instant triggeredAt, Double latitude, Double longitude,
                                String status, Long acknowledgedBy, Instant acknowledgedAt) { }

    public record AgentLocationView(Long agentId, String agentName, double latitude, double longitude,
                                     Instant lastPingAt, Double speedKmph, boolean suspicious) { }

    public record AgentStatusView(boolean checkedIn, Instant checkInAt, Instant checkOutAt,
                                   Instant lastPingAt, boolean activeSos) { }

    private final AppUserRepository users;
    private final BranchRepository branches;
    private final AttendanceRepository attendances;
    private final CheckinAttemptRepository attempts;
    private final LocationPingRepository pings;
    private final SosAlertRepository sosAlerts;

    public AgentService(AppUserRepository users, BranchRepository branches,
                        AttendanceRepository attendances, CheckinAttemptRepository attempts,
                        LocationPingRepository pings, SosAlertRepository sosAlerts) {
        this.users = users;
        this.branches = branches;
        this.attendances = attendances;
        this.attempts = attempts;
        this.pings = pings;
        this.sosAlerts = sosAlerts;
    }

    // ---- attendance ----

    @Transactional
    public CheckInResponse checkIn(Long agentId, double latitude, double longitude,
                                   double accuracyMetres, boolean mockLocation) {

        AppUser agent = requireAgent(agentId);
        Branch branch = branches.findById(agent.getBranchId())
                .orElseThrow(() -> new NoSuchElementException("Branch " + agent.getBranchId() + " not found"));

        GeoPoint branchPoint = new GeoPoint(branch.getLatitude(), branch.getLongitude());
        GeoPoint agentPoint = new GeoPoint(latitude, longitude);
        CheckInPolicy policy = new CheckInPolicy(branch.getCheckinRadiusM(), MAX_ACCURACY_METRES);
        CheckInPolicy.Result result = policy.evaluate(branchPoint, agentPoint, accuracyMetres, mockLocation);

        Instant now = Instant.now();
        long distance = Math.round(result.distanceMetres());

        attempts.save(new CheckinAttempt(agent.getId(), branch.getId(), now, latitude, longitude,
                accuracyMetres, mockLocation, result.distanceMetres(), result.allowed(),
                result.reason().name()));

        if (!result.allowed()) {
            return new CheckInResponse(false, result.reason().name(),
                    messageFor(result.reason(), distance, branch.getCheckinRadiusM()),
                    distance, null, null);
        }

        LocalDate today = LocalDate.ofInstant(now, BUSINESS_ZONE);
        Optional<Attendance> existing = attendances.findByAgentIdAndWorkDate(agent.getId(), today);
        if (existing.isPresent()) {
            Attendance a = existing.get();
            return new CheckInResponse(true, "ALREADY_CHECKED_IN",
                    "You already checked in today.", distance, a.getId(), a.getCheckInAt());
        }

        Attendance saved = attendances.save(new Attendance(agent.getId(), branch.getId(), today, now,
                latitude, longitude, accuracyMetres, result.distanceMetres()));

        return new CheckInResponse(true, "CHECKED_IN", "Checked in. Have a safe day!",
                distance, saved.getId(), saved.getCheckInAt());
    }

    @Transactional
    public CheckOutResponse checkOut(Long agentId) {
        AppUser agent = requireAgent(agentId);
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, BUSINESS_ZONE);

        Optional<Attendance> found = attendances.findByAgentIdAndWorkDate(agent.getId(), today);
        if (found.isEmpty()) {
            return new CheckOutResponse("NOT_CHECKED_IN", "You have not checked in today.",
                    null, null, null, 0);
        }

        Attendance a = found.get();
        if (a.getCheckOutAt() != null) {
            return new CheckOutResponse("ALREADY_CHECKED_OUT", "You already checked out today.",
                    a.getId(), a.getCheckInAt(), a.getCheckOutAt(),
                    Duration.between(a.getCheckInAt(), a.getCheckOutAt()).toMinutes());
        }

        a.checkOut(now);   // saved automatically when the transaction ends (dirty checking)

        return new CheckOutResponse("CHECKED_OUT", "Checked out. Thank you!",
                a.getId(), a.getCheckInAt(), a.getCheckOutAt(),
                Duration.between(a.getCheckInAt(), now).toMinutes());
    }

    // ---- tracking ----

    @Transactional
    public PingResponse recordPing(Long agentId, double latitude, double longitude,
                                   double accuracyMetres, boolean simulated) {

        GeoPoint here = new GeoPoint(latitude, longitude);   // validates the coordinates
        Instant now = Instant.now();

        Optional<Attendance> duty = attendances.findByAgentIdAndWorkDate(
                agentId, LocalDate.ofInstant(now, BUSINESS_ZONE));
        if (duty.isEmpty()) {
            return new PingResponse(false, "NOT_ON_DUTY", null, false);
        }
        if (duty.get().getCheckOutAt() != null) {
            return new PingResponse(false, "CHECKED_OUT", null, false);
        }

        LocationPing previous = pings.findTopByAgentIdOrderByRecordedAtDesc(agentId);
        Double speed = null;
        boolean suspicious = false;

        if (previous != null) {
            double metres = new GeoPoint(previous.getLatitude(), previous.getLongitude()).distanceTo(here);
            long seconds = Duration.between(previous.getRecordedAt(), now).getSeconds();
            if (seconds > 0) {
                speed = Math.round((metres / seconds) * 3.6 * 10) / 10.0;   // m/s to km/h, 1 decimal
                suspicious = speed > MAX_PLAUSIBLE_KMPH;
            }
        }

        pings.save(new LocationPing(agentId, now, latitude, longitude, accuracyMetres,
                speed, suspicious, simulated));

        return new PingResponse(true, suspicious ? "ACCEPTED_SUSPICIOUS" : "ACCEPTED", speed, suspicious);
    }

    // ---- SOS ----

    @Transactional
    public SosResponse triggerSos(Long agentId, Double latitude, Double longitude) {
        AppUser agent = requireAgent(agentId);

        // Check if there's already an active SOS for this agent
        List<SosAlert> active = sosAlerts.findByStatusOrderByTriggeredAtDesc(SosAlert.Status.ACTIVE);
        for (SosAlert a : active) {
            if (a.getAgentId().equals(agentId)) {
                return new SosResponse(a.getId(), "ALREADY_ACTIVE",
                        "You already have an active SOS alert. Your manager has been notified.");
            }
        }

        SosAlert alert = sosAlerts.save(
                new SosAlert(agentId, agent.getBranchId(), Instant.now(), latitude, longitude));

        return new SosResponse(alert.getId(), "TRIGGERED",
                "SOS alert sent. Your manager has been notified.");
    }

    @Transactional(readOnly = true)
    public List<SosAlertView> getActiveAlerts() {
        List<SosAlert> alerts = sosAlerts.findByStatusOrderByTriggeredAtDesc(SosAlert.Status.ACTIVE);
        Map<Long, AppUser> userCache = users.findAllById(
                alerts.stream().map(SosAlert::getAgentId).distinct().toList()
        ).stream().collect(Collectors.toMap(AppUser::getId, u -> u));

        Map<Long, Branch> branchCache = branches.findAllById(
                alerts.stream().map(SosAlert::getBranchId).filter(b -> b != null).distinct().toList()
        ).stream().collect(Collectors.toMap(Branch::getId, b -> b));

        return alerts.stream().map(a -> {
            AppUser user = userCache.get(a.getAgentId());
            Branch branch = a.getBranchId() != null ? branchCache.get(a.getBranchId()) : null;
            return new SosAlertView(a.getId(), a.getAgentId(),
                    user != null ? user.getFullName() : "Unknown",
                    user != null ? user.getPhone() : "",
                    a.getBranchId(), branch != null ? branch.getName() : "",
                    a.getTriggeredAt(), a.getLatitude(), a.getLongitude(),
                    a.getStatus().name(), a.getAcknowledgedBy(), a.getAcknowledgedAt());
        }).toList();
    }

    @Transactional
    public SosAlertView acknowledgeSos(Long alertId, Long managerId) {
        SosAlert alert = sosAlerts.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("SOS alert " + alertId + " not found"));

        if (alert.getStatus() != SosAlert.Status.ACTIVE) {
            throw new IllegalStateException("SOS alert is already " + alert.getStatus());
        }

        alert.acknowledge(managerId, Instant.now());

        AppUser agent = users.findById(alert.getAgentId()).orElse(null);
        Branch branch = alert.getBranchId() != null ? branches.findById(alert.getBranchId()).orElse(null) : null;

        return new SosAlertView(alert.getId(), alert.getAgentId(),
                agent != null ? agent.getFullName() : "Unknown",
                agent != null ? agent.getPhone() : "",
                alert.getBranchId(), branch != null ? branch.getName() : "",
                alert.getTriggeredAt(), alert.getLatitude(), alert.getLongitude(),
                alert.getStatus().name(), alert.getAcknowledgedBy(), alert.getAcknowledgedAt());
    }

    // ---- live location for dashboard ----

    @Transactional(readOnly = true)
    public List<AgentLocationView> getAgentLocations() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<LocationPing> latestPings = pings.findLatestPingsForOnDutyAgents(today);

        Map<Long, AppUser> userCache = users.findAllById(
                latestPings.stream().map(LocationPing::getAgentId).distinct().toList()
        ).stream().collect(Collectors.toMap(AppUser::getId, u -> u));

        return latestPings.stream().map(p -> {
            AppUser user = userCache.get(p.getAgentId());
            return new AgentLocationView(p.getAgentId(),
                    user != null ? user.getFullName() : "Agent " + p.getAgentId(),
                    p.getLatitude(), p.getLongitude(), p.getRecordedAt(),
                    p.getSpeedKmph(), p.isSuspicious());
        }).toList();
    }

    // ---- agent status (for mobile app) ----

    @Transactional(readOnly = true)
    public AgentStatusView getAgentStatus(Long agentId) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Optional<Attendance> att = attendances.findByAgentIdAndWorkDate(agentId, today);

        boolean checkedIn = att.isPresent() && att.get().getCheckOutAt() == null;
        Instant checkInAt = att.map(Attendance::getCheckInAt).orElse(null);
        Instant checkOutAt = att.map(Attendance::getCheckOutAt).orElse(null);

        LocationPing lastPing = pings.findTopByAgentIdOrderByRecordedAtDesc(agentId);
        Instant lastPingAt = lastPing != null ? lastPing.getRecordedAt() : null;

        boolean activeSos = sosAlerts.findByStatusOrderByTriggeredAtDesc(SosAlert.Status.ACTIVE)
                .stream().anyMatch(a -> a.getAgentId().equals(agentId));

        return new AgentStatusView(checkedIn, checkInAt, checkOutAt, lastPingAt, activeSos);
    }

    // ---- shared ----

    private AppUser requireAgent(Long agentId) {
        AppUser agent = users.findById(agentId)
                .orElseThrow(() -> new NoSuchElementException("Agent " + agentId + " not found"));
        if (agent.getRole() != AppUser.Role.AGENT || !agent.isActive() || agent.getBranchId() == null) {
            throw new IllegalArgumentException("User " + agentId + " is not an active agent with a branch");
        }
        return agent;
    }

    private static String messageFor(CheckInPolicy.Reason reason, long distance, int radius) {
        return switch (reason) {
            case MOCK_LOCATION -> "Fake location detected. Turn off location-changing apps and try again.";
            case LOW_ACCURACY -> "GPS signal is weak. Step outside and try again.";
            case TOO_FAR -> "You are " + distance + " m from the branch. Check-in works within " + radius + " m.";
            case ALLOWED -> "Allowed.";
        };
    }
}

