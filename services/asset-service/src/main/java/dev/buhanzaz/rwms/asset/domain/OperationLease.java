package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.proxy.HibernateProxy;

/**
 * JPA entity that persists operation lease in the asset-owned database.
 */
@Entity
@Table(name = "operation_lease")
public class OperationLease {
  private static final Pattern OWNER_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "owner_type", nullable = false, length = 64)
  private String ownerType;

  @Column(name = "owner_id", nullable = false, length = 128)
  private String ownerId;

  @Column(name = "fencing_token", nullable = false)
  private long fencingToken;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private OperationLeaseState state;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected OperationLease() {}

  public static OperationLease acquire(
      UUID rentalItemId,
      String ownerType,
      String ownerId,
      long fencingToken,
      UUID idempotencyKey,
      OffsetDateTime acquiredAt,
      OffsetDateTime expiresAt) {
    if (rentalItemId == null) throw new IllegalArgumentException("rentalItemId is required");
    if (idempotencyKey == null) throw new IllegalArgumentException("idempotencyKey is required");
    if (fencingToken <= 0) throw new IllegalArgumentException("fencingToken must be positive");
    OffsetDateTime acquired = requireTime(acquiredAt, "acquiredAt");
    OffsetDateTime expiry = requireTime(expiresAt, "expiresAt");
    if (!expiry.isAfter(acquired)) throw new IllegalArgumentException("expiresAt must be after acquiredAt");

    OperationLease lease = new OperationLease();
    lease.rentalItemId = rentalItemId;
    lease.ownerType = canonicalOwnerType(ownerType);
    lease.ownerId = canonicalOwnerId(ownerId);
    lease.fencingToken = fencingToken;
    lease.state = OperationLeaseState.ACTIVE;
    lease.idempotencyKey = idempotencyKey;
    lease.expiresAt = expiry;
    lease.createdAt = acquired;
    lease.updatedAt = acquired;
    return lease;
  }

  public void renew(OffsetDateTime renewedAt, OffsetDateTime nextExpiresAt) {
    OffsetDateTime renewed = requireTime(renewedAt, "renewedAt");
    OffsetDateTime expiry = requireTime(nextExpiresAt, "nextExpiresAt");
    requireActiveAt(renewed);
    if (!expiry.isAfter(renewed)) throw new IllegalArgumentException("nextExpiresAt must be after renewedAt");
    expiresAt = expiry;
    updatedAt = renewed;
  }

  public void release(OffsetDateTime releasedAt) {
    OffsetDateTime released = requireTime(releasedAt, "releasedAt");
    requireActiveAt(released);
    state = OperationLeaseState.RELEASED;
    this.releasedAt = released;
    updatedAt = released;
  }

  public boolean expire(OffsetDateTime expiredAt) {
    OffsetDateTime expired = requireTime(expiredAt, "expiredAt");
    if (state != OperationLeaseState.ACTIVE || expiresAt.isAfter(expired)) return false;
    state = OperationLeaseState.EXPIRED;
    releasedAt = expired;
    updatedAt = expired;
    return true;
  }

  public boolean isActiveAt(OffsetDateTime instant) {
    return state == OperationLeaseState.ACTIVE && expiresAt.isAfter(requireTime(instant, "instant"));
  }

  public boolean isOwnedBy(String expectedOwnerType, String expectedOwnerId) {
    return ownerType.equals(expectedOwnerType) && ownerId.equals(expectedOwnerId);
  }

  private void requireActiveAt(OffsetDateTime instant) {
    if (!isActiveAt(instant)) throw new IllegalStateException("Operation lease is stale or fenced");
  }

  private static String canonicalOwnerType(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!OWNER_TYPE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("ownerType has invalid format");
    }
    return normalized;
  }

  private static String canonicalOwnerId(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 128) {
      throw new IllegalArgumentException("ownerId is required and must be at most 128 characters");
    }
    return normalized;
  }

  private static OffsetDateTime requireTime(OffsetDateTime value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
    if (createdAt == null) createdAt = timestamp;
    if (updatedAt == null) updatedAt = timestamp;
  }

  @PreUpdate
  void preUpdate() {
    if (updatedAt == null) updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getRentalItemId() { return rentalItemId; }
  public String getOwnerType() { return ownerType; }
  public String getOwnerId() { return ownerId; }
  public long getFencingToken() { return fencingToken; }
  public OperationLeaseState getState() { return state; }
  public UUID getIdempotencyKey() { return idempotencyKey; }
  public OffsetDateTime getExpiresAt() { return expiresAt; }
  public OffsetDateTime getReleasedAt() { return releasedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : getClass();
    return thisClass == otherClass && id != null && Objects.equals(id, ((OperationLease) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
