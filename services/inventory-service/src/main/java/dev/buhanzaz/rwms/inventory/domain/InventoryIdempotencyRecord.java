package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity that persists inventory idempotency record in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_idempotency_record")
@IdClass(InventoryIdempotencyRecord.Key.class)
public class InventoryIdempotencyRecord {
  @Id
  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Id
  @Column(name = "command_scope", nullable = false, length = 96)
  private String commandScope;

  @Id
  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "state", nullable = false, length = 16)
  private String state;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "lease_until")
  private OffsetDateTime leaseUntil;

  @Column(name = "response_status")
  private Integer responseStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", columnDefinition = "jsonb")
  private String responseBody;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  protected InventoryIdempotencyRecord() {}

  public static InventoryIdempotencyRecord reserve(
      UUID subjectId,
      String commandScope,
      UUID idempotencyKey,
      String requestSha256,
      UUID leaseToken,
      OffsetDateTime now,
      OffsetDateTime leaseUntil) {
    if (subjectId == null
        || commandScope == null
        || commandScope.isBlank()
        || commandScope.length() > 96
        || idempotencyKey == null
        || !sha256(requestSha256)
        || leaseToken == null
        || now == null
        || leaseUntil == null
        || !leaseUntil.isAfter(now)) {
      throw new IllegalArgumentException("Idempotency reservation is incomplete");
    }
    InventoryIdempotencyRecord value = new InventoryIdempotencyRecord();
    value.subjectId = subjectId;
    value.commandScope = commandScope;
    value.idempotencyKey = idempotencyKey;
    value.requestSha256 = requestSha256;
    value.state = "IN_PROGRESS";
    value.attemptCount = 1;
    value.leaseToken = leaseToken;
    value.leaseUntil = leaseUntil;
    value.createdAt = now;
    value.updatedAt = now;
    value.expiresAt = now.plusDays(7);
    return value;
  }

  public void reclaim(UUID newLeaseToken, OffsetDateTime now, OffsetDateTime newLeaseUntil) {
    if (!"IN_PROGRESS".equals(state)
        || newLeaseToken == null
        || now == null
        || newLeaseUntil == null
        || !newLeaseUntil.isAfter(now)
        || (leaseUntil != null && leaseUntil.isAfter(now))) {
      throw new IllegalStateException("Idempotency reservation cannot be reclaimed");
    }
    attemptCount = Math.addExact(attemptCount, 1);
    leaseToken = newLeaseToken;
    leaseUntil = newLeaseUntil;
    updatedAt = now;
  }

  public void complete(
      UUID expectedLeaseToken,
      String expectedRequestSha256,
      int status,
      String body,
      OffsetDateTime now) {
    if (!"IN_PROGRESS".equals(state)
        || expectedLeaseToken == null
        || !expectedLeaseToken.equals(leaseToken)
        || !Objects.equals(expectedRequestSha256, requestSha256)
        || status < 200
        || status > 299
        || body == null
        || now == null) {
      throw new IllegalStateException("Idempotency lease changed before completion");
    }
    state = "COMPLETED";
    leaseToken = null;
    leaseUntil = null;
    responseStatus = status;
    responseBody = body;
    updatedAt = now;
  }

  /**
   * Makes this reservation reclaimable after its owning command rolled back.
   *
   * <p>The schema intentionally keeps failed attempts as {@code IN_PROGRESS}: only successful
   * responses are durable replays. An abandoned reservation therefore keeps the in-progress
   * shape, but receives an already-expired, retired lease. A later identical request must still
   * reclaim it under the row lock before executing.
   */
  public boolean abandon(
      UUID expectedLeaseToken, String expectedRequestSha256, OffsetDateTime now) {
    if (!ownsLease(expectedLeaseToken, expectedRequestSha256) || now == null) {
      return false;
    }
    leaseToken = UUID.randomUUID();
    leaseUntil = now;
    updatedAt = now;
    return true;
  }

  public boolean hasRequestHash(String value) {
    return requestSha256.equals(value);
  }

  public boolean isExpired(OffsetDateTime now) {
    return !expiresAt.isAfter(now);
  }

  public boolean isCompleted() {
    return "COMPLETED".equals(state);
  }

  public boolean hasActiveLease(OffsetDateTime now) {
    return "IN_PROGRESS".equals(state) && leaseUntil != null && leaseUntil.isAfter(now);
  }

  public boolean ownsLease(UUID expectedLeaseToken, String expectedRequestSha256) {
    return "IN_PROGRESS".equals(state)
        && expectedLeaseToken != null
        && expectedLeaseToken.equals(leaseToken)
        && Objects.equals(expectedRequestSha256, requestSha256);
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public UUID getSubjectId() {
    return subjectId;
  }

  public String getCommandScope() {
    return commandScope;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public UUID getLeaseToken() {
    return leaseToken;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public static final class Key implements Serializable {
    private UUID subjectId;
    private String commandScope;
    private UUID idempotencyKey;

    public Key() {}

    public Key(UUID subjectId, String commandScope, UUID idempotencyKey) {
      this.subjectId = subjectId;
      this.commandScope = commandScope;
      this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key value)) {
        return false;
      }
      return Objects.equals(subjectId, value.subjectId)
          && Objects.equals(commandScope, value.commandScope)
          && Objects.equals(idempotencyKey, value.idempotencyKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(subjectId, commandScope, idempotencyKey);
    }
  }
}
