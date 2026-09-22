package com.droppoint.repo;

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
@Table(name = "customer")
public class Customer {

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

    protected Customer() {
        // required by JPA
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

    public interface CustomerRepository extends JpaRepository<Customer, Long> {
        List<Customer> findByBranchIdAndActiveTrue(Long branchId);
    }
}
