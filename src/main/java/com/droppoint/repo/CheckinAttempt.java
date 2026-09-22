package com.droppoint.repo;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "checkin_attempt")
public class CheckinAttempt {

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
