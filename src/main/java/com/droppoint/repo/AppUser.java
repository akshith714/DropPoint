package com.droppoint.repo;

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

@Entity
@Table(name = "app_user")
public class AppUser {

    public enum Role { OWNER, MANAGER, AGENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(nullable = false)
    private boolean active;

    public interface AppUserRepository extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByPhone(String phone);
    }

    protected AppUser() {
        // required by JPA
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public Role getRole() { return role; }
    public Long getBranchId() { return branchId; }
    public boolean isActive() { return active; }
}
