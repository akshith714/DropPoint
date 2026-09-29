package com.droppoint.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The branch and the people who work out of it: users, the attendance row that
 * opens and closes a working day, every check-in attempt including the refused
 * ones, and the position pings recorded while on duty. Each entity keeps its own
 * repository beside it.
 */
public final class Workforce {

    private Workforce() {
        // namespace only
    }

    @Entity
    @Table(name = "branch")
    public static class Branch {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String name;

        @Column(nullable = false)
        private double latitude;

        @Column(nullable = false)
        private double longitude;

        @Column(name = "checkin_radius_m", nullable = false)
        private int checkinRadiusM;

        public interface BranchRepository extends JpaRepository<Branch, Long> { }

        protected Branch() {
            // required by JPA
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public int getCheckinRadiusM() { return checkinRadiusM; }
    }

    @Entity
    @Table(name = "app_user")
    public static class AppUser {

        public enum Role { OWNER, MANAGER, AGENT }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "full_name", nullable = false)
        private String fullName;

        @Column(nullable = false, unique = true)
        private String phone;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private Role role;

        @Column(name = "branch_id")
        private Long branchId;

        @Column(nullable = false)
        private boolean active;

        public interface AppUserRepository extends JpaRepository<AppUser, Long> {
            Optional<AppUser> findByPhone(String phone);
        }

        protected AppUser() {
            // required by JPA
        }

        public Long getId() { return id; }
        public String getFullName() { return fullName; }
        public String getPhone() { return phone; }
        public Role getRole() { return role; }
        public Long getBranchId() { return branchId; }
        public boolean isActive() { return active; }
    }

    @Entity
    @Table(name = "attendance")
    public static class Attendance {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "agent_id", nullable = false)
        private Long agentId;

        @Column(name = "branch_id", nullable = false)
        private Long branchId;

        @Column(name = "work_date", nullable = false)
        private LocalDate workDate;

        @Column(name = "check_in_at", nullable = false)
        private Instant checkInAt;

        @Column(name = "check_in_latitude", nullable = false)
        private double checkInLatitude;

        @Column(name = "check_in_longitude", nullable = false)
        private double checkInLongitude;

        @Column(name = "check_in_accuracy_m", nullable = false)
        private double checkInAccuracyM;

        @Column(name = "check_in_distance_m", nullable = false)
        private double checkInDistanceM;

        @Column(name = "check_out_at")
        private Instant checkOutAt;

        public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
            Optional<Attendance> findByAgentIdAndWorkDate(Long agentId, LocalDate workDate);
        }

        protected Attendance() {
            // required by JPA
        }

        public Attendance(Long agentId, Long branchId, LocalDate workDate, Instant checkInAt,
                          double latitude, double longitude, double accuracyM, double distanceM) {
            this.agentId = agentId;
            this.branchId = branchId;
            this.workDate = workDate;
            this.checkInAt = checkInAt;
            this.checkInLatitude = latitude;
            this.checkInLongitude = longitude;
            this.checkInAccuracyM = accuracyM;
            this.checkInDistanceM = distanceM;
        }

        public void checkOut(Instant at) {
            if (checkOutAt != null) {
                throw new IllegalStateException("Already checked out");
            }
            this.checkOutAt = at;
        }

        public Long getId() { return id; }
        public Long getAgentId() { return agentId; }
        public Long getBranchId() { return branchId; }
        public LocalDate getWorkDate() { return workDate; }
        public Instant getCheckInAt() { return checkInAt; }
        public double getCheckInDistanceM() { return checkInDistanceM; }
        public Instant getCheckOutAt() { return checkOutAt; }
    }

    @Entity
    @Table(name = "checkin_attempt")
    public static class CheckinAttempt {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "agent_id", nullable = false)
        private Long agentId;

        @Column(name = "branch_id", nullable = false)
        private Long branchId;

        @Column(name = "attempted_at", nullable = false)
        private Instant attemptedAt;

        @Column(nullable = false)
        private double latitude;

        @Column(nullable = false)
        private double longitude;

        @Column(name = "accuracy_m", nullable = false)
        private double accuracyM;

        @Column(name = "mock_location", nullable = false)
        private boolean mockLocation;

        @Column(name = "distance_m", nullable = false)
        private double distanceM;

        @Column(nullable = false)
        private boolean allowed;

        @Column(nullable = false)
        private String reason;

        public interface CheckinAttemptRepository extends JpaRepository<CheckinAttempt, Long> { }

        protected CheckinAttempt() {
            // required by JPA
        }

        public CheckinAttempt(Long agentId, Long branchId, Instant attemptedAt, double latitude,
                              double longitude, double accuracyM, boolean mockLocation,
                              double distanceM, boolean allowed, String reason) {
            this.agentId = agentId;
            this.branchId = branchId;
            this.attemptedAt = attemptedAt;
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyM = accuracyM;
            this.mockLocation = mockLocation;
            this.distanceM = distanceM;
            this.allowed = allowed;
            this.reason = reason;
        }

        public Long getId() { return id; }
        public Long getAgentId() { return agentId; }
        public Instant getAttemptedAt() { return attemptedAt; }
        public double getDistanceM() { return distanceM; }
        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }

    @Entity
    @Table(name = "location_ping")
    public static class LocationPing {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "agent_id", nullable = false)
        private Long agentId;

        @Column(name = "recorded_at", nullable = false)
        private Instant recordedAt;

        @Column(nullable = false)
        private double latitude;

        @Column(nullable = false)
        private double longitude;

        @Column(name = "accuracy_m", nullable = false)
        private double accuracyM;

        @Column(name = "speed_kmph")
        private Double speedKmph;

        @Column(nullable = false)
        private boolean suspicious;

        @Column(nullable = false)
        private boolean simulated;

        protected LocationPing() {
            // required by JPA
        }

        public LocationPing(Long agentId, Instant recordedAt, double latitude, double longitude,
                            double accuracyM, Double speedKmph, boolean suspicious, boolean simulated) {
            this.agentId = agentId;
            this.recordedAt = recordedAt;
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyM = accuracyM;
            this.speedKmph = speedKmph;
            this.suspicious = suspicious;
            this.simulated = simulated;
        }

        public Long getId() { return id; }
        public Long getAgentId() { return agentId; }
        public Instant getRecordedAt() { return recordedAt; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public Double getSpeedKmph() { return speedKmph; }
        public boolean isSuspicious() { return suspicious; }

        public interface LocationPingRepository extends JpaRepository<LocationPing, Long> {
            LocationPing findTopByAgentIdOrderByRecordedAtDesc(Long agentId);

            @Query("""
                    SELECT lp FROM Workforce$LocationPing lp
                    WHERE lp.recordedAt = (
                        SELECT MAX(lp2.recordedAt) FROM Workforce$LocationPing lp2
                        WHERE lp2.agentId = lp.agentId
                    )
                    AND lp.agentId IN (
                        SELECT a.agentId FROM Workforce$Attendance a
                        WHERE a.workDate = :today AND a.checkOutAt IS NULL
                    )
                    """)
            List<LocationPing> findLatestPingsForOnDutyAgents(@org.springframework.data.repository.query.Param("today") LocalDate today);
        }
    }

    @Entity
    @Table(name = "sos_alert")
    public static class SosAlert {

        public enum Status { ACTIVE, ACKNOWLEDGED, RESOLVED }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "agent_id", nullable = false)
        private Long agentId;

        @Column(name = "branch_id")
        private Long branchId;

        @Column(name = "triggered_at", nullable = false)
        private Instant triggeredAt;

        @Column
        private Double latitude;

        @Column
        private Double longitude;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private Status status;

        @Column(name = "acknowledged_by")
        private Long acknowledgedBy;

        @Column(name = "acknowledged_at")
        private Instant acknowledgedAt;

        @Column(name = "resolved_at")
        private Instant resolvedAt;

        @Column
        private String note;

        protected SosAlert() {
            // required by JPA
        }

        public SosAlert(Long agentId, Long branchId, Instant triggeredAt,
                         Double latitude, Double longitude) {
            this.agentId = agentId;
            this.branchId = branchId;
            this.triggeredAt = triggeredAt;
            this.latitude = latitude;
            this.longitude = longitude;
            this.status = Status.ACTIVE;
        }

        public void acknowledge(Long managerId, Instant at) {
            this.acknowledgedBy = managerId;
            this.acknowledgedAt = at;
            this.status = Status.ACKNOWLEDGED;
        }

        public Long getId() { return id; }
        public Long getAgentId() { return agentId; }
        public Long getBranchId() { return branchId; }
        public Instant getTriggeredAt() { return triggeredAt; }
        public Double getLatitude() { return latitude; }
        public Double getLongitude() { return longitude; }
        public Status getStatus() { return status; }
        public Long getAcknowledgedBy() { return acknowledgedBy; }
        public Instant getAcknowledgedAt() { return acknowledgedAt; }

        public interface SosAlertRepository extends JpaRepository<SosAlert, Long> {
            List<SosAlert> findByStatusOrderByTriggeredAtDesc(Status status);
        }
    }
}
