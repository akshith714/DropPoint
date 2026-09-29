package com.droppoint.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The locality, the customer, the order placed for them, and the proof that
 * closed it. The order holds its own state machine: only an open order can be
 * assigned, and only an assigned one can be delivered or failed. Each entity
 * keeps its own repository beside it.
 */
public final class Deliveries {

    private Deliveries() {
        // namespace only
    }

    @Entity
    @Table(name = "area")
    public static class Area {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "branch_id", nullable = false)
        private Long branchId;

        @Column(nullable = false)
        private String name;

        @Column(nullable = false)
        private double latitude;

        @Column(nullable = false)
        private double longitude;

        @Column(nullable = false)
        private boolean active;

        protected Area() {
            // required by JPA
        }

        public Long getId() { return id; }
        public Long getBranchId() { return branchId; }
        public String getName() { return name; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public boolean isActive() { return active; }

        public interface AreaRepository extends JpaRepository<Area, Long> {
            List<Area> findByBranchIdAndActiveTrue(Long branchId);
        }
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

        @Column(name = "area_id")
        private Long areaId;

        /** EXACT from a real delivery, GEOCODED from the address, AREA from the locality centre. */
        @Column(name = "location_source", nullable = false)
        private String locationSource;

        protected Customer() {
            // required by JPA
        }

        public Customer(Long branchId, String name, String phone, CustomerType customerType,
                        String addressLine, double latitude, double longitude,
                        BuildingType buildingType, short floorNo, boolean hasLift,
                        boolean active) {
            this(branchId, name, phone, customerType, addressLine, latitude, longitude,
                    buildingType, floorNo, hasLift, active, null, "AREA");
        }

        public Customer(Long branchId, String name, String phone, CustomerType customerType,
                        String addressLine, double latitude, double longitude,
                        BuildingType buildingType, short floorNo, boolean hasLift,
                        boolean active, Long areaId, String locationSource) {
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
            this.areaId = areaId;
            this.locationSource = locationSource;
        }

        /** Called when a delivery gives us the customer's real position. */
        public void locateExactly(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.locationSource = "EXACT";
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
        public Long getAreaId() { return areaId; }
        public String getLocationSource() { return locationSource; }

        public interface CustomerRepository extends JpaRepository<Customer, Long> {
            List<Customer> findByBranchIdAndActiveTrue(Long branchId);

            /** The phone column is not unique, so take the oldest match rather than failing. */
            Optional<Customer> findFirstByPhoneOrderByIdAsc(String phone);
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

        @Column(name = "area_id")
        private Long areaId;

        /** Set when the order arrived through a bulk import, so a day's load can be traced. */
        @Column(name = "import_batch")
        private String importBatch;

        protected DeliveryOrder() {
            // required by JPA
        }

        public DeliveryOrder(String orderNo, Long customerId, Long branchId, short cylinderCount,
                             String deliveryCode, Instant bookedAt) {
            this(orderNo, customerId, branchId, cylinderCount, deliveryCode, bookedAt, null, null);
        }

        public DeliveryOrder(String orderNo, Long customerId, Long branchId, short cylinderCount,
                             String deliveryCode, Instant bookedAt, Long areaId, String importBatch) {
            this.orderNo = orderNo;
            this.customerId = customerId;
            this.branchId = branchId;
            this.cylinderCount = cylinderCount;
            this.deliveryCode = deliveryCode;
            this.bookedAt = bookedAt;
            this.status = Status.BOOKED;
            this.areaId = areaId;
            this.importBatch = importBatch;
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
        public Long getAreaId() { return areaId; }
        public String getImportBatch() { return importBatch; }

        public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {

            boolean existsByOrderNo(String orderNo);

            List<DeliveryOrder> findByBranchIdAndStatusInOrderByBookedAtDesc(Long branchId, List<Status> statuses);

            List<DeliveryOrder> findByAssignedAgentIdAndStatusOrderByAssignedAtAsc(Long agentId, Status status);

            List<DeliveryOrder> findByAssignedAgentId(Long agentId);
        }
    }



    @Entity
    @Table(name = "day_plan")
    public static class DayPlanRow {

        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "plan_date", nullable = false)
        private LocalDate planDate;

        @Column(nullable = false)
        private String status;

        @Column(columnDefinition = "text")
        private String summary;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        @Column(name = "confirmed_at")
        private Instant confirmedAt;

        protected DayPlanRow() { }

        public DayPlanRow(LocalDate planDate, Instant createdAt) {
            this.planDate = planDate;
            this.createdAt = createdAt;
            this.status = "DRAFT";
        }

        public void setSummary(String summary) { this.summary = summary; }

        public void confirm(Instant at) {
            if ("CONFIRMED".equals(status)) {
                throw new IllegalStateException("Plan for " + planDate + " is already confirmed");
            }
            this.status = "CONFIRMED";
            this.confirmedAt = at;
        }

        public Long getId() { return id; }
        public LocalDate getPlanDate() { return planDate; }
        public String getStatus() { return status; }
        public String getSummary() { return summary; }
        public Instant getConfirmedAt() { return confirmedAt; }

        public interface DayPlanRepository extends JpaRepository<DayPlanRow, Long> {
            Optional<DayPlanRow> findByPlanDate(LocalDate planDate);
        }
    }

    @Entity
    @Table(name = "plan_assignment")
    public static class PlanAssignment {

        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "plan_id", nullable = false)
        private Long planId;

        @Column(name = "agent_id")
        private Long agentId;

        @Column(name = "area_id", nullable = false)
        private Long areaId;

        @Column(name = "order_count", nullable = false)
        private int orderCount;

        @Column(name = "cylinder_count", nullable = false)
        private int cylinderCount;

        @Column(nullable = false)
        private boolean partial;

        protected PlanAssignment() { }

        public PlanAssignment(Long planId, Long agentId, Long areaId,
                              int orderCount, int cylinderCount, boolean partial) {
            this.planId = planId;
            this.agentId = agentId;
            this.areaId = areaId;
            this.orderCount = orderCount;
            this.cylinderCount = cylinderCount;
            this.partial = partial;
        }

        /** The manager moved this area to a different agent before confirming. */
        public void moveTo(Long agentId) { this.agentId = agentId; }

        public Long getId() { return id; }
        public Long getAgentId() { return agentId; }
        public Long getAreaId() { return areaId; }
        public int getOrderCount() { return orderCount; }
        public int getCylinderCount() { return cylinderCount; }
        public boolean isPartial() { return partial; }

        public interface PlanAssignmentRepository extends JpaRepository<PlanAssignment, Long> {
            List<PlanAssignment> findByPlanId(Long planId);
            void deleteByPlanId(Long planId);
        }
    }

    @Entity
    @Table(name = "agent_availability")
    @IdClass(AgentAvailability.Key.class)
    public static class AgentAvailability {

        public static class Key implements java.io.Serializable {
            private LocalDate workDate;
            private Long agentId;
            public Key() { }
            public Key(LocalDate workDate, Long agentId) { this.workDate = workDate; this.agentId = agentId; }
            @Override public boolean equals(Object o) {
                return o instanceof Key k && k.workDate.equals(workDate) && k.agentId.equals(agentId);
            }
            @Override public int hashCode() { return workDate.hashCode() * 31 + agentId.hashCode(); }
        }

        @Id
        @Column(name = "work_date")
        private LocalDate workDate;

        @Id
        @Column(name = "agent_id")
        private Long agentId;

        @Column(nullable = false)
        private boolean available;

        @Column(length = 100)
        private String note;

        protected AgentAvailability() { }

        public AgentAvailability(LocalDate workDate, Long agentId, boolean available, String note) {
            this.workDate = workDate;
            this.agentId = agentId;
            this.available = available;
            this.note = note;
        }

        public void set(boolean available, String note) {
            this.available = available;
            this.note = note;
        }

        public Long getAgentId() { return agentId; }
        public boolean isAvailable() { return available; }
        public String getNote() { return note; }

        public interface AgentAvailabilityRepository extends JpaRepository<AgentAvailability, Key> {
            List<AgentAvailability> findByWorkDate(LocalDate workDate);
        }
    }
}
