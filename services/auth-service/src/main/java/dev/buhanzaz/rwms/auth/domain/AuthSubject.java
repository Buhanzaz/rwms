package dev.buhanzaz.rwms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Entity
@Table(
        name = "auth_subject",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_auth_subject_username", columnNames = "username"),
            @UniqueConstraint(name = "uk_auth_subject_external_worker", columnNames = "external_worker_id")
        },
        indexes = @Index(
                name = "idx_auth_subject_principal_active",
                columnList = "principal_type, active"))
public class AuthSubject {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 16)
    private PrincipalType principalType;

    @Column(name = "username", nullable = false, length = 128)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Column(name = "last_name", length = 128)
    private String lastName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "time_zone_id", length = 64)
    private String timeZoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "global_role", length = 32)
    private UserGlobalRole globalRole;

    @Column(name = "mobile_app_access", nullable = false)
    private boolean mobileAppAccess;

    @Column(name = "rental_access", nullable = false)
    private boolean rentalAccess;

    @Column(name = "external_worker_id", length = 128)
    private String externalWorkerId;

    @Column(name = "warehouse_id", length = 128)
    private String warehouseId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
        normalize();
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        normalize();
    }

    private void normalize() {
        username = trim(username);
        firstName = trim(firstName);
        lastName = trim(lastName);
        email = trim(email);
        timeZoneId = trim(timeZoneId);
        externalWorkerId = trim(externalWorkerId);
        warehouseId = trim(warehouseId);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public void registerUser(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            boolean active) {
        registerUser(
                username,
                passwordHash,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                false);
    }

    public void registerUser(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            boolean active,
            boolean mobileAppAccess) {
        registerUser(
                username,
                passwordHash,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                mobileAppAccess,
                globalRole != null && globalRole.hasRentalAccessByDefault());
    }

    public void registerUser(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            boolean active,
            boolean mobileAppAccess,
            boolean rentalAccess) {
        if (principalType != null) {
            throw new IllegalStateException("Auth subject is already initialized");
        }
        requireEligibleMobileAccess(globalRole, mobileAppAccess);
        principalType = PrincipalType.USER;
        this.username = username;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.timeZoneId = timeZoneId;
        this.globalRole = globalRole;
        this.active = active;
        this.mobileAppAccess = mobileAppAccess;
        this.rentalAccess = rentalAccess;
    }

    public void registerWorker(
            String externalWorkerId,
            String warehouseId,
            String username,
            String passwordHash) {
        if (principalType != null) {
            throw new IllegalStateException("Auth subject is already initialized");
        }
        principalType = PrincipalType.WORKER;
        this.externalWorkerId = externalWorkerId;
        this.warehouseId = warehouseId;
        this.username = username;
        this.passwordHash = passwordHash;
        globalRole = null;
        mobileAppAccess = false;
        rentalAccess = false;
        active = true;
    }

    public void changeUserProfile(
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId) {
        requireType(PrincipalType.USER);
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.timeZoneId = timeZoneId;
    }

    public void changeUserAuthorization(UserGlobalRole globalRole, boolean active) {
        changeUserAuthorization(
                globalRole,
                active,
                mobileAppAccess && globalRole != null && globalRole.isManagerAppEligible());
    }

    public void changeUserAuthorization(
            UserGlobalRole globalRole, boolean active, boolean mobileAppAccess) {
        changeUserAuthorization(globalRole, active, mobileAppAccess, rentalAccess);
    }

    public void changeUserAuthorization(
            UserGlobalRole globalRole,
            boolean active,
            boolean mobileAppAccess,
            boolean rentalAccess) {
        requireType(PrincipalType.USER);
        requireEligibleMobileAccess(globalRole, mobileAppAccess);
        this.globalRole = globalRole;
        this.active = active;
        this.mobileAppAccess = mobileAppAccess;
        this.rentalAccess = rentalAccess;
    }

    public void reconfigureWorker(
            String externalWorkerId,
            String warehouseId,
            String username,
            String passwordHash) {
        requireType(PrincipalType.WORKER);
        this.externalWorkerId = externalWorkerId;
        this.warehouseId = warehouseId;
        this.username = username;
        this.passwordHash = passwordHash;
        globalRole = null;
        mobileAppAccess = false;
        rentalAccess = false;
        active = true;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void disable() {
        active = false;
    }

    public void enableWorkerAccess() {
        requireType(PrincipalType.WORKER);
        active = true;
    }

    public void touch() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    private void requireType(PrincipalType required) {
        if (principalType != required) {
            throw new IllegalStateException("Auth subject principal type does not allow this transition");
        }
    }

    private void requireEligibleMobileAccess(
            UserGlobalRole role, boolean requestedMobileAppAccess) {
        if (requestedMobileAppAccess && (role == null || !role.isManagerAppEligible())) {
            throw new IllegalArgumentException(
                    "Mobile app access requires an eligible user role");
        }
    }
}
