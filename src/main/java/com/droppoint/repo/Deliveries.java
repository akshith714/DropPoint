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

/**
 * The customer, the order placed for them, and the proof that closed it. The
 * order holds its own state machine: only an open order can be assigned, and
 * only an assigned one can be delivered or failed. Each entity keeps its own
 * repository beside it.
 */
public final class Deliveries {

    private Deliveries() {
        // namespace only
    }

    @Entity
    @Table(name = "customer")
    public static class Customer {

        public enum CustomerType { DOMESTIC, COMMERCIAL }
        public enum BuildingType { INDEPENDENT, APARTMENT, GATED, COMMERCIAL }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "branch_id", nullable = false)
        private Long branchId;

        @Column(nullable = false)
        private String name;

        @Column(nullable = false)
        private String phone;

        @Enumerated(EnumType.STRING)
        @Column(name = "customer_type", nullable = false)
        private CustomerType customerType;

        @Column(name = "address_line", nullable = false)
        private String addressLine;

        @Column(nullable = false)
        private double latitude;

        @Column(nullable = false)
        private double longitude;

        @Enumerated(EnumType.STRING)
        @Column(name = "building_type", nullable = false)
        private BuildingType buildingType;

        @Column(name = "floor_no", nullable = false)
        private short floorNo;

        @Column(name = "has_lift", nullable = false)
        private boolean hasLift;

        @Column(nullable = false)
        private boolean active;

        @Column(nullable = false)
        private boolean seeded;

        protected Customer() {
            // required by JPA
        }

        public Customer(Long branchId, String name, String phone, CustomerType customerType,
                        String addressLine, double latitude, double longitude,
                        BuildingType buildingType, short floorNo, boolean hasLift,
                        boolean active, boolean seeded) {
            this.branchId = branchId;
            this.name = name;
            this.phone = phone;
            this.customerType = customerType;
            this.addressLine = addressLine;
            this.latitude = latitude;
            this.longitude = longitude;
            this.buildingType = buildingType;
            this.floorNo = floorNo;
            this.hasLift = hasLift;
            this.active = active;
            this.seeded = seeded;
        }

        public Long getId() { return id; }
        public Long getBranchId() { return branchId; }
        public String getName() { return name; }
        public String getPhone() { return phone; }
        public CustomerType getCustomerType() { return customerType; }
        public String getAddressLine() { return addressLine; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public BuildingType getBuildingType() { return buildingType; }
        public short getFloorNo() { return floorNo; }
        public boolean isHasLift() { return hasLift; }
        public boolean isActive() { return active; }
        public boolean isSeeded() { return seeded; }

        public interface CustomerRepository extends JpaRepository<Customer, Long> {
            List<Customer> findByBranchIdAndActiveTrue(Long branchId);

            List<Customer> findBySeededTrue();
        }
    }

    @Entity
    @Table(name = "orders")
    public static class DeliveryOrder {

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

    @Entity
    @Table(name = "delivery_proof")
    public static class DeliveryProof {

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
}
