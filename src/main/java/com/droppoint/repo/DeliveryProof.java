package com.droppoint.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_proof")
public class DeliveryProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(name = "distance_m", nullable = false)
    private double distanceM;

    @Column(name = "code_matched", nullable = false)
    private boolean codeMatched;

    @Column(nullable = false)
    private String verdict;

    @Column(name = "late_minutes")
    private Double lateMinutes;

    protected DeliveryProof() {
        // required by JPA
    }

    public DeliveryProof(Long orderId, Long agentId, Instant recordedAt, double latitude,
                         double longitude, double distanceM, boolean codeMatched,
                         String verdict, Double lateMinutes) {
        this.orderId = orderId;
        this.agentId = agentId;
        this.recordedAt = recordedAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distanceM = distanceM;
        this.codeMatched = codeMatched;
        this.verdict = verdict;
        this.lateMinutes = lateMinutes;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getAgentId() { return agentId; }
    public Instant getRecordedAt() { return recordedAt; }
    public double getDistanceM() { return distanceM; }
    public String getVerdict() { return verdict; }
    public Double getLateMinutes() { return lateMinutes; }

    public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, Long> {
        List<DeliveryProof> findByVerdictOrderByRecordedAtDesc(String verdict);
        long countByAgentIdAndVerdict(Long agentId, String verdict);

        void deleteByOrderIdIn(java.util.Collection<Long> orderIds);
    }
}
