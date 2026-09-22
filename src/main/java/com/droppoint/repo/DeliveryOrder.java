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

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

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

    public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {

        List<DeliveryOrder> findByBranchIdAndStatusInOrderByBookedAtDesc(Long branchId, List<Status> statuses);

        long countByAssignedAgentIdAndStatus(Long agentId, Status status);

        List<DeliveryOrder> findByAssignedAgentIdAndStatusOrderByAssignedAtAsc(Long agentId, Status status);
    }
}
