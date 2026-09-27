package com.droppoint.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

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

    public record LivePosition(Long agentId, String agentName, double latitude, double longitude,
                               Instant recordedAt, Double speedKmph, boolean suspicious, long secondsAgo) { }

    private final AppUserRepository users;
    private final BranchRepository branches;
    private final AttendanceRepository attendances;
    private final CheckinAttemptRepository attempts;
    private final LocationPingRepository pings;

    public AgentService(AppUserRepository users, BranchRepository branches,
                        AttendanceRepository attendances, CheckinAttemptRepository attempts,
                        LocationPingRepository pings) {
        this.users = users;
        this.branches = branches;
        this.attendances = attendances;
        this.attempts = attempts;
        this.pings = pings;
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

    @Transactional(readOnly = true)
    public List<LivePosition> livePositions() {
        Instant now = Instant.now();
        List<LivePosition> out = new ArrayList<>();
        for (Object[] r : pings.findLivePositions()) {
            // Hibernate returns Instant for timestamptz; older versions hand back a Timestamp.
            Instant recordedAt = r[4] instanceof java.sql.Timestamp ts ? ts.toInstant() : (Instant) r[4];
            out.add(new LivePosition(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    ((Number) r[2]).doubleValue(),
                    ((Number) r[3]).doubleValue(),
                    recordedAt,
                    r[5] == null ? null : ((Number) r[5]).doubleValue(),
                    (Boolean) r[6],
                    Duration.between(recordedAt, now).getSeconds()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<LocationPing> todaysRoute(Long agentId) {
        Instant startOfDay = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant();
        return pings.findByAgentIdAndRecordedAtAfterOrderByRecordedAtAsc(agentId, startOfDay);
    }

    /**
     * Demo helper: records a straight-line route of simulated pings between two points,
     * spaced a few seconds apart, so tracking can be shown indoors.
     */
    @Transactional
    public int simulateRoute(Long agentId, double fromLat, double fromLon,
                             double toLat, double toLon, int steps, int secondsPerStep) {
        if (steps < 2 || steps > 200) {
            throw new IllegalArgumentException("Steps must be between 2 and 200");
        }
        LocalDate today = LocalDate.ofInstant(Instant.now(), BUSINESS_ZONE);
        Attendance duty = attendances.findByAgentIdAndWorkDate(agentId, today)
                .orElseThrow(() -> new NoSuchElementException("Agent " + agentId + " is not checked in today"));
        if (duty.getCheckOutAt() != null) {
            throw new IllegalArgumentException("Agent " + agentId + " has already checked out");
        }

        Instant start = Instant.now().minusSeconds((long) steps * secondsPerStep);
        List<LocationPing> batch = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            double fraction = i / (double) (steps - 1);
            double lat = fromLat + (toLat - fromLat) * fraction;
            double lon = fromLon + (toLon - fromLon) * fraction;
            batch.add(new LocationPing(agentId, start.plusSeconds((long) i * secondsPerStep),
                    lat, lon, 8, i == 0 ? null : 24.0, false, true));
        }
        pings.saveAll(batch);
        return batch.size();
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
