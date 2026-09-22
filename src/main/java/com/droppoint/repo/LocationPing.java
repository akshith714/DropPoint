package com.droppoint.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "location_ping")
public class LocationPing {

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

        List<LocationPing> findByAgentIdAndRecordedAtAfterOrderByRecordedAtAsc(Long agentId, Instant after);

        /** Latest ping per agent, for agents on duty today (checked in, not checked out). */
        @Query(value = """
                SELECT DISTINCT ON (p.agent_id)
                       p.agent_id, u.full_name, p.latitude, p.longitude,
                       p.recorded_at, p.speed_kmph, p.suspicious
                FROM location_ping p
                JOIN app_user u ON u.id = p.agent_id
                JOIN attendance a ON a.agent_id = p.agent_id
                                 AND a.work_date = CURRENT_DATE
                                 AND a.check_out_at IS NULL
                ORDER BY p.agent_id, p.recorded_at DESC
                """, nativeQuery = true)
        List<Object[]> findLivePositions();
    }
}
