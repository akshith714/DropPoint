package com.droppoint.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class DeliveryOrder {

    public enum Status { BOOKED, ASSIGNED, DELIVERED, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "cylinder_count", nullable = false)
    private short cylinderCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "assigned_agent_id")
    private Long assignedAgentId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "delivery_code", nullable = false)
    private String deliveryCode;

    @Column(name = "travel_minutes")
    private Double travelMinutes;

    @Column(name = "handover_minutes")
    private Double handoverMinutes;

    @Column(name = "eta_minutes")
    private Double etaMinutes;

    @Column(name = "eta_at")
    private Instant etaAt;

    @Column(name = "eta_source")
    private String etaSource;

    @Column(name = "eta_explanation")
    private String etaExplanation;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(nullable = false)
    private boolean seeded;

    protected DeliveryOrder() {
        // required by JPA
    }

    public DeliveryOrder(String orderNo, Long customerId, Long branchId, short cylinderCount,
                         String deliveryCode, Instant bookedAt) {
        this.orderNo = orderNo;
        this.customerId = customerId;
        this.branchId = branchId;
        this.cylinderCount = cylinderCount;
        this.deliveryCode = deliveryCode;
        this.bookedAt = bookedAt;
        this.status = Status.BOOKED;
    }

    /** Assigns or reassigns the order to an agent. Only open orders can be assigned. */
    public void assignTo(Long agentId, Instant at) {
        if (status != Status.BOOKED && status != Status.ASSIGNED) {
            throw new IllegalStateException("Order " + orderNo + " is " + status + " and cannot be assigned");
        }
        this.assignedAgentId = agentId;
        this.assignedAt = at;
        this.status = Status.ASSIGNED;
    }

    /** Marks this order as generated demo data, so the seed can be cleared again. */
    public void markSeeded() {
        this.seeded = true;
    }

    public void markDelivered(Instant at) {
        if (status != Status.ASSIGNED) {
            throw new IllegalStateException("Order " + orderNo + " is " + status + " and cannot be delivered");
        }
        this.status = Status.DELIVERED;
        this.deliveredAt = at;
    }

    public void markFailed(String reason, Instant at) {
        if (status != Status.ASSIGNED) {
            throw new IllegalStateException("Order " + orderNo + " is " + status + " and cannot be failed");
        }
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.deliveredAt = at;
    }

    public void setEta(double travelMinutes, double handoverMinutes, double totalMinutes,
                       Instant etaAt, String source, String explanation) {
        this.travelMinutes = travelMinutes;
        this.handoverMinutes = handoverMinutes;
        this.etaMinutes = totalMinutes;
        this.etaAt = etaAt;
        this.etaSource = source;
        this.etaExplanation = explanation;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public Long getCustomerId() { return customerId; }
    public Long getBranchId() { return branchId; }
    public short getCylinderCount() { return cylinderCount; }
    public Status getStatus() { return status; }
    public Long getAssignedAgentId() { return assignedAgentId; }
    public Instant getAssignedAt() { return assignedAt; }
    public String getDeliveryCode() { return deliveryCode; }
    public Instant getBookedAt() { return bookedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Double getTravelMinutes() { return travelMinutes; }
    public Double getHandoverMinutes() { return handoverMinutes; }
    public Double getEtaMinutes() { return etaMinutes; }
    public Instant getEtaAt() { return etaAt; }
    public String getEtaSource() { return etaSource; }
    public String getEtaExplanation() { return etaExplanation; }
    public String getFailureReason() { return failureReason; }
    public boolean isSeeded() { return seeded; }

    public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {

        List<DeliveryOrder> findByBranchIdAndStatusInOrderByBookedAtDesc(Long branchId, List<Status> statuses);

        long countByAssignedAgentIdAndStatus(Long agentId, Status status);

        List<DeliveryOrder> findByAssignedAgentIdAndStatusOrderByAssignedAtAsc(Long agentId, Status status);

        List<DeliveryOrder> findByBranchIdAndDeliveredAtAfterOrderByDeliveredAtDesc(Long branchId, Instant after);

        List<DeliveryOrder> findBySeededTrue();
    }
}
