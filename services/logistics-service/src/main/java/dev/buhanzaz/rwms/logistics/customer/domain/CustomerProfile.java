package dev.buhanzaz.rwms.logistics.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned customer profile that binds one authenticated auth subject to one rental client.
 * Authentication data and password material never cross this aggregate.
 */
@Entity
@Table(
    name = "customer_profile",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_customer_profile_auth_subject",
          columnNames = "auth_subject_id"),
      @UniqueConstraint(
          name = "uk_customer_profile_client",
          columnNames = "client_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerProfile {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "auth_subject_id", nullable = false)
  private UUID authSubjectId;

  @Column(name = "client_id", nullable = false)
  private UUID clientId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 32)
  private CustomerEntityType entityType;

  @Column(name = "first_name", length = 255)
  private String firstName;

  @Column(name = "last_name", length = 255)
  private String lastName;

  @Column(name = "company_name", length = 512)
  private String companyName;

  @Column(name = "phone", nullable = false, length = 32)
  private String phone;

  @Column(name = "email", length = 320)
  private String email;

  @Column(name = "additional_info", length = 2_000)
  private String additionalInfo;

  @Column(name = "avatar_warehouse_id")
  private UUID avatarWarehouseId;

  @Column(name = "avatar_media_id")
  private UUID avatarMediaId;

  @Column(name = "avatar_generation")
  private Long avatarGeneration;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates the immutable auth/client binding and normalized profile snapshot. */
  public static CustomerProfile create(
      UUID authSubjectId,
      UUID clientId,
      CustomerEntityType entityType,
      String firstName,
      String lastName,
      String companyName,
      String phone,
      String email,
      String additionalInfo) {
    CustomerProfile profile = new CustomerProfile();
    profile.authSubjectId = Objects.requireNonNull(authSubjectId, "authSubjectId");
    profile.clientId = Objects.requireNonNull(clientId, "clientId");
    profile.entityType = Objects.requireNonNull(entityType, "entityType");
    profile.firstName = optional(firstName, 255, "firstName");
    profile.lastName = optional(lastName, 255, "lastName");
    profile.companyName = optional(companyName, 512, "companyName");
    if (entityType == CustomerEntityType.INDIVIDUAL
        && (profile.firstName == null || profile.lastName == null)) {
      throw new IllegalArgumentException("Individual first and last names are required");
    }
    if (entityType == CustomerEntityType.LEGAL && profile.companyName == null) {
      throw new IllegalArgumentException("Legal entity company name is required");
    }
    profile.phone = required(phone, 32, "phone");
    profile.email = optional(email, 320, "email");
    profile.additionalInfo = optional(additionalInfo, 2_000, "additionalInfo");
    profile.createdAt = now();
    profile.updatedAt = profile.createdAt;
    return profile;
  }

  /** Returns the client-facing display name used by the existing rental-order domain. */
  public String displayName() {
    return entityType == CustomerEntityType.LEGAL
        ? companyName
        : firstName + " " + lastName;
  }

  /** Returns a legal-entity contact without inventing a separate responsible person. */
  public String contactPerson() {
    if (entityType != CustomerEntityType.LEGAL) return null;
    if (firstName == null && lastName == null) return companyName;
    return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
  }

  /**
   * Replaces mutable profile details under the customer-visible optimistic version fence.
   * Authentication, rental-client identity and the individual/legal kind remain immutable.
   */
  public void updateDetails(
      long expectedVersion,
      String firstName,
      String lastName,
      String companyName,
      String phone,
      String email,
      String additionalInfo) {
    requireVersion(expectedVersion);
    String normalizedFirstName = optional(firstName, 255, "firstName");
    String normalizedLastName = optional(lastName, 255, "lastName");
    String normalizedCompanyName = optional(companyName, 512, "companyName");
    if (entityType == CustomerEntityType.INDIVIDUAL
        && (normalizedFirstName == null || normalizedLastName == null)) {
      throw new IllegalArgumentException("Individual first and last names are required");
    }
    if (entityType == CustomerEntityType.LEGAL && normalizedCompanyName == null) {
      throw new IllegalArgumentException("Legal entity company name is required");
    }
    this.firstName = normalizedFirstName;
    this.lastName = normalizedLastName;
    this.companyName = normalizedCompanyName;
    this.phone = required(phone, 32, "phone");
    this.email = optional(email, 320, "email");
    this.additionalInfo = optional(additionalInfo, 2_000, "additionalInfo");
    this.updatedAt = now();
  }

  /**
   * Fixes the first validated warehouse as this profile's media authorization scope.
   * Replays for the same scope do not mutate the profile or advance its version.
   */
  public boolean prepareAvatarScope(long expectedVersion, UUID warehouseId) {
    Objects.requireNonNull(warehouseId, "warehouseId");
    if (avatarWarehouseId != null) {
      if (!avatarWarehouseId.equals(warehouseId)) {
        throw new IllegalStateException("Avatar media warehouse is already fixed");
      }
      return false;
    }
    requireVersion(expectedVersion);
    avatarWarehouseId = warehouseId;
    updatedAt = now();
    return true;
  }

  /** Binds one media-service-validated READY avatar generation under the profile fence. */
  public void bindAvatar(long expectedVersion, UUID mediaId, long generation) {
    requireVersion(expectedVersion);
    if (avatarWarehouseId == null) {
      throw new IllegalStateException("Avatar media scope is not prepared");
    }
    avatarMediaId = Objects.requireNonNull(mediaId, "mediaId");
    if (generation < 1) throw new IllegalArgumentException("Avatar generation is invalid");
    avatarGeneration = generation;
    updatedAt = now();
  }

  private void requireVersion(long expectedVersion) {
    if (expectedVersion < 0 || version != expectedVersion) {
      throw new IllegalStateException("Customer profile version conflict");
    }
  }

  private static String required(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String optional(String value, int maximum, String field) {
    if (value == null || value.isBlank()) return null;
    return required(value, maximum, field);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass && id != null && Objects.equals(id, ((CustomerProfile) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
