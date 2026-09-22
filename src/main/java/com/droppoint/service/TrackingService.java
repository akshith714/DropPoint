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
import com.droppoint.repo.Attendance;
import com.droppoint.repo.Attendance.AttendanceRepository;
import com.droppoint.repo.LocationPing;
import com.droppoint.repo.LocationPing.LocationPingRepository;

@Service
public class TrackingService {

    /** Above this speed between two pings, the movement is physically implausible. */
    private static final double MAX_PLAUSIBLE_KMPH = 150;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    public record PingResponse(boolean accepted, String status, Double speedKmph, boolean suspicious) { }

    public record LivePosition(Long agentId, String agentName, double latitude, double longitude,
                               Instant recordedAt, Double speedKmph, boolean suspicious, long secondsAgo) { }

    private final LocationPingRepository pings;
    private final AttendanceRepository attendances;

    public TrackingService(LocationPingRepository pings, AttendanceRepository attendances) {
        this.pings = pings;
        this.attendances = attendances;
    }

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
}
