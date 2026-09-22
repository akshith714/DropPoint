package com.droppoint.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.CheckInPolicy;
import com.droppoint.core.GeoPoint;
import com.droppoint.repo.AppUser;
import com.droppoint.repo.AppUser.AppUserRepository;
import com.droppoint.repo.Attendance;
import com.droppoint.repo.Attendance.AttendanceRepository;
import com.droppoint.repo.Branch;
import com.droppoint.repo.Branch.BranchRepository;
import com.droppoint.repo.CheckinAttempt;
import com.droppoint.repo.CheckinAttempt.CheckinAttemptRepository;

@Service
public class CheckInService {

    private static final double MAX_ACCURACY_METRES = 100;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    public record CheckInResponse(boolean allowed, String status, String message,
                                  long distanceMetres, Long attendanceId, Instant checkInAt) { }

    private final AppUserRepository users;
    private final BranchRepository branches;
    private final AttendanceRepository attendances;
    private final CheckinAttemptRepository attempts;

    public CheckInService(AppUserRepository users, BranchRepository branches,
                          AttendanceRepository attendances, CheckinAttemptRepository attempts) {
        this.users = users;
        this.branches = branches;
        this.attendances = attendances;
        this.attempts = attempts;
    }

    @Transactional
    public CheckInResponse checkIn(Long agentId, double latitude, double longitude,
                                   double accuracyMetres, boolean mockLocation) {

        AppUser agent = users.findById(agentId)
                .orElseThrow(() -> new NoSuchElementException("Agent " + agentId + " not found"));
        if (agent.getRole() != AppUser.Role.AGENT || !agent.isActive() || agent.getBranchId() == null) {
            throw new IllegalArgumentException("User " + agentId + " is not an active agent with a branch");
        }
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

    private static String messageFor(CheckInPolicy.Reason reason, long distance, int radius) {
        return switch (reason) {
            case MOCK_LOCATION -> "Fake location detected. Turn off location-changing apps and try again.";
            case LOW_ACCURACY -> "GPS signal is weak. Step outside and try again.";
            case TOO_FAR -> "You are " + distance + " m from the branch. Check-in works within " + radius + " m.";
            case ALLOWED -> "Allowed.";
        };
    }
}
