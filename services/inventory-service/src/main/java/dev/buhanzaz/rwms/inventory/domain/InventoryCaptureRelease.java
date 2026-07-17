package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "inventory_capture_release")
public class InventoryCaptureRelease {
  @Id
  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Column(name = "technical_attempt", nullable = false)
  private long technicalAttempt;

  @Column(name = "capture_id", nullable = false)
  private UUID captureId;

  @Column(name = "state", nullable = false, length = 16)
  private String state;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "lease_until")
  private OffsetDateTime leaseUntil;

  @Column(name = "last_failure_code", length = 64)
  private String lastFailureCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  protected InventoryCaptureRelease() {}

  public static InventoryCaptureRelease pending(
      UUID operationId, long technicalAttempt, UUID captureId, OffsetDateTime now) {
    if (operationId == null || technicalAttempt < 1 || captureId == null || now == null) {
      throw new IllegalArgumentException("Capture release is incomplete");
    }
    InventoryCaptureRelease value = new InventoryCaptureRelease();
    value.operationId = operationId;
    value.technicalAttempt = technicalAttempt;
    value.captureId = captureId;
    value.state = "HELD";
    value.attemptCount = 0;
    value.nextAttemptAt = now;
    value.createdAt = now;
    return value;
  }

  public void retarget(long technicalAttempt, UUID captureId, OffsetDateTime now) {
    if (technicalAttempt < 1 || captureId == null || now == null) {
      throw new IllegalStateException("Capture release cannot be retargeted");
    }
    this.technicalAttempt = technicalAttempt;
    this.captureId = captureId;
    state = "HELD";
    attemptCount = 0;
    nextAttemptAt = now;
    leaseOwner = null;
    leaseToken = null;
    leaseUntil = null;
    lastFailureCode = null;
    releasedAt = null;
    createdAt = now;
  }

  public void makeEligible(OffsetDateTime now) {
    if (!"HELD".equals(state) || now == null) {
      throw new IllegalStateException("Held capture cannot become release-eligible");
    }
    state = "PENDING";
    nextAttemptAt = now;
  }

  public UUID claim(String owner, OffsetDateTime now, OffsetDateTime until) {
    if (owner == null
        || owner.isBlank()
        || owner.length() > 128
        || now == null
        || until == null
        || !until.isAfter(now)
        || !("PENDING".equals(state) && !nextAttemptAt.isAfter(now))
            && !("IN_FLIGHT".equals(state) && leaseUntil != null && !leaseUntil.isAfter(now))) {
      throw new IllegalStateException("Capture release is not claimable");
    }
    state = "IN_FLIGHT";
    attemptCount = Math.addExact(attemptCount, 1);
    leaseOwner = owner;
    leaseToken = UUID.randomUUID();
    leaseUntil = until;
    return leaseToken;
  }

  public void fail(UUID expectedLeaseToken, String failureCode, OffsetDateTime retryAt) {
    if (!"IN_FLIGHT".equals(state)
        || expectedLeaseToken == null
        || !Objects.equals(expectedLeaseToken, leaseToken)
        || failureCode == null
        || failureCode.isBlank()
        || failureCode.length() > 64
        || retryAt == null) {
      throw new IllegalStateException("Capture release lease changed before failure recording");
    }
    state = "PENDING";
    nextAttemptAt = retryAt;
    leaseOwner = null;
    leaseToken = null;
    leaseUntil = null;
    lastFailureCode = failureCode;
  }

  public void failDirect(String failureCode, OffsetDateTime retryAt) {
    if ("RELEASED".equals(state)
        || failureCode == null
        || failureCode.isBlank()
        || failureCode.length() > 64
        || retryAt == null) {
      throw new IllegalStateException("Released capture cannot fail");
    }
    attemptCount = Math.addExact(attemptCount, 1);
    state = "PENDING";
    nextAttemptAt = retryAt;
    leaseOwner = null;
    leaseToken = null;
    leaseUntil = null;
    lastFailureCode = failureCode;
  }

  public void release(OffsetDateTime now) {
    if (now == null) {
      throw new IllegalArgumentException("Capture release time is required");
    }
    state = "RELEASED";
    leaseOwner = null;
    leaseToken = null;
    leaseUntil = null;
    lastFailureCode = null;
    releasedAt = now;
  }

  public UUID getOperationId() {
    return operationId;
  }

  public long getTechnicalAttempt() {
    return technicalAttempt;
  }

  public UUID getCaptureId() {
    return captureId;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public UUID getLeaseToken() {
    return leaseToken;
  }
}
