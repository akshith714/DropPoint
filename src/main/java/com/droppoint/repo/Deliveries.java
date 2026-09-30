package com.droppoint.repo;

import java.time.Instant;
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
import jakarta.persistence.Table;

public final class Deliveries {
    private Deliveries() {
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

        @Column(name = "location_source", nullable = false)
        private String locationSource;

        protected Customer() {
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

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getPhone() { return phone; }
        public String getAddressLine() { return addressLine; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public Long getAreaId() { return areaId; }
        public String getLocationSource() { return locationSource; }

        public interface CustomerRepository extends JpaRepository<Customer, Long> {
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

        @Column(name = "delivery_code", nullable = false)
        private String deliveryCode;

        @Column(name = "booked_at", nullable = false)
        private Instant bookedAt;

        @Column(name = "delivered_at")
        private Instant deliveredAt;

        @Column(name = "failure_reason")
        private String failureReason;

        @Column(name = "area_id")
        private Long areaId;

        @Column(name = "import_batch")
        private String importBatch;

        protected DeliveryOrder() {
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

        public Long getId() { return id; }
        public String getOrderNo() { return orderNo; }
        public Long getCustomerId() { return customerId; }
        public short getCylinderCount() { return cylinderCount; }
        public Status getStatus() { return status; }
        public Long getAssignedAgentId() { return assignedAgentId; }
        public String getDeliveryCode() { return deliveryCode; }
        public Instant getBookedAt() { return bookedAt; }
        public Instant getDeliveredAt() { return deliveredAt; }

        public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {
            boolean existsByOrderNo(String orderNo);

            List<DeliveryOrder> findByBranchIdAndStatusInOrderByBookedAtDesc(Long branchId, List<Status> statuses);

            List<DeliveryOrder> findByAssignedAgentId(Long agentId);
        }
    }
}
