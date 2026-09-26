package com.droppoint.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "attendance")
public class Attendance {

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

        List<Attendance> findByWorkDateBefore(LocalDate date);
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
