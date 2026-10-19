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

/**
 * Authorization aggregate for either an interactive user or a worker application identity.
 *
 * <p>The aggregate owns credentials and authorization attributes that are persisted in the
 * {@code auth_subject} table. Its transition methods preserve the distinction between users and
 * workers and enforce that mobile access is granted only to eligible user roles.
 */
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

    /** Initializes audit timestamps and normalizes textual values before the aggregate is stored. */
    @PrePersist
    void beforeInsert() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
        normalize();
    }

    /** Refreshes the update timestamp and normalizes textual values before an update is stored. */
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

    /**
     * Initializes this aggregate as an interactive user without mobile-app access.
     *
     * @param username login name for the user
     * @param passwordHash encoded password to persist for authentication
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param timeZoneId optional IANA time-zone identifier
     * @param globalRole global role assigned to the user
     * @param active whether the user may initially authenticate
     * @throws IllegalStateException if the aggregate has already been initialized
     */
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

    /**
     * Initializes this aggregate as an interactive user and derives rental access from the role.
     *
     * @param username login name for the user
     * @param passwordHash encoded password to persist for authentication
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param timeZoneId optional IANA time-zone identifier
     * @param globalRole global role assigned to the user
     * @param active whether the user may initially authenticate
     * @param mobileAppAccess requested manager-mobile entitlement
     * @throws IllegalStateException if the aggregate has already been initialized
     * @throws IllegalArgumentException if mobile access is requested for an ineligible role
     */
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

    /**
     * Initializes this aggregate as an interactive user with explicit application entitlements.
     *
     * @param username login name for the user
     * @param passwordHash encoded password to persist for authentication
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param timeZoneId optional IANA time-zone identifier
     * @param globalRole global role assigned to the user
     * @param active whether the user may initially authenticate
     * @param mobileAppAccess requested manager-mobile entitlement
     * @param rentalAccess persisted rental entitlement
     * @throws IllegalStateException if the aggregate has already been initialized
     * @throws IllegalArgumentException if mobile access is requested for an ineligible role
     */
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

    /**
     * Initializes this aggregate as an active worker identity bound to one worker and warehouse.
     *
     * <p>Worker identities never retain interactive-user role or application entitlements.
     *
     * @param externalWorkerId identifier assigned by the worker-owning service
     * @param warehouseId warehouse to which the worker is bound
     * @param username canonical worker-application login
     * @param passwordHash encoded password to persist for authentication
     * @throws IllegalStateException if the aggregate has already been initialized
     */
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

    /**
     * Replaces the profile fields of an interactive user.
     *
     * @param username replacement login name
     * @param firstName replacement optional given name
     * @param lastName replacement optional family name
     * @param email replacement optional email address
     * @param timeZoneId replacement optional IANA time-zone identifier
     * @throws IllegalStateException if this aggregate is not a user
     */
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

    /**
     * Changes a user's role and active state while retaining mobile access only when it remains
     * eligible for the new role.
     *
     * @param globalRole replacement global role
     * @param active replacement authentication state
     * @throws IllegalStateException if this aggregate is not a user
     */
    public void changeUserAuthorization(UserGlobalRole globalRole, boolean active) {
        changeUserAuthorization(
                globalRole,
                active,
                mobileAppAccess && globalRole != null && globalRole.isManagerAppEligible());
    }

    /**
     * Changes a user's role, active state, and mobile entitlement while retaining rental access.
     *
     * @param globalRole replacement global role
     * @param active replacement authentication state
     * @param mobileAppAccess replacement manager-mobile entitlement
     * @throws IllegalStateException if this aggregate is not a user
     * @throws IllegalArgumentException if mobile access is requested for an ineligible role
     */
    public void changeUserAuthorization(
            UserGlobalRole globalRole, boolean active, boolean mobileAppAccess) {
        changeUserAuthorization(globalRole, active, mobileAppAccess, rentalAccess);
    }

    /**
     * Changes all mutable authorization attributes of an interactive user.
     *
     * @param globalRole replacement global role
     * @param active replacement authentication state
     * @param mobileAppAccess replacement manager-mobile entitlement
     * @param rentalAccess replacement rental entitlement
     * @throws IllegalStateException if this aggregate is not a user
     * @throws IllegalArgumentException if mobile access is requested for an ineligible role
     */
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

    /**
     * Rebinds an existing worker identity to its worker data and resets worker-only authorization.
     *
     * @param externalWorkerId replacement worker identifier
     * @param warehouseId replacement bound warehouse identifier
     * @param username replacement worker-application login
     * @param passwordHash replacement encoded password
     * @throws IllegalStateException if this aggregate is not a worker
     */
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

    /**
     * Replaces the persisted password hash without exposing a plaintext password.
     *
     * @param passwordHash replacement encoded password
     */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Disables authentication for this subject. */
    public void disable() {
        active = false;
    }

    /**
     * Enables a worker identity after checking that the subject is a worker.
     *
     * @throws IllegalStateException if this aggregate is not a worker
     */
    public void enableWorkerAccess() {
        requireType(PrincipalType.WORKER);
        active = true;
    }

    /** Updates the modification timestamp for an event that changes a related authorization view. */
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
