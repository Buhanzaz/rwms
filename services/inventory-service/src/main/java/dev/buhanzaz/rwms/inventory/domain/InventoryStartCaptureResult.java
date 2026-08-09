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

/**
 * JPA entity that persists inventory start capture result in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_start_capture_result")
@IdClass(InventoryStartCaptureResult.Key.class)
public class InventoryStartCaptureResult {
  @Id
  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Id
  @Column(name = "technical_attempt", nullable = false)
  private long technicalAttempt;

  @Column(name = "outcome", nullable = false, length = 24)
  private String outcome;

  @Column(name = "capture_id")
  private UUID captureId;

  @Column(name = "membership_digest", length = 64)
  private String membershipDigest;

  @Column(name = "total_count")
  private Integer totalCount;

  @Column(name = "expires_at")
  private OffsetDateTime expiresAt;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected InventoryStartCaptureResult() {}

  public static InventoryStartCaptureResult captured(
      UUID operationId,
      long technicalAttempt,
      UUID captureId,
      String membershipDigest,
      long totalCount,
      OffsetDateTime expiresAt,
      OffsetDateTime recordedAt) {
    if (operationId == null
        || technicalAttempt < 1
        || captureId == null
        || !sha256(membershipDigest)
        || totalCount < 0
        || totalCount > Integer.MAX_VALUE
        || expiresAt == null
        || recordedAt == null
        || !expiresAt.isAfter(recordedAt)) {
      throw new IllegalArgumentException("Captured result is incomplete");
    }
    InventoryStartCaptureResult value = new InventoryStartCaptureResult();
    value.operationId = operationId;
    value.technicalAttempt = technicalAttempt;
    value.outcome = "CAPTURED";
    value.captureId = captureId;
    value.membershipDigest = membershipDigest;
    value.totalCount = Math.toIntExact(totalCount);
    value.expiresAt = expiresAt;
    value.recordedAt = recordedAt;
    return value;
  }

  public static InventoryStartCaptureResult failed(
      UUID operationId,
      long technicalAttempt,
      boolean rejected,
      String failureCode,
      OffsetDateTime recordedAt) {
    if (operationId == null
        || technicalAttempt < 1
        || failureCode == null
        || failureCode.isBlank()
        || failureCode.length() > 64
        || recordedAt == null) {
      throw new IllegalArgumentException("Capture failure result is incomplete");
    }
    InventoryStartCaptureResult value = new InventoryStartCaptureResult();
    value.operationId = operationId;
    value.technicalAttempt = technicalAttempt;
    value.outcome = rejected ? "REJECTED" : "TRANSIENT_FAILED";
    value.failureCode = failureCode;
    value.recordedAt = recordedAt;
    return value;
  }

  public boolean isSameCapture(
      UUID captureId, String membershipDigest, long totalCount, OffsetDateTime expiresAt) {
    return "CAPTURED".equals(outcome)
        && Objects.equals(this.captureId, captureId)
        && Objects.equals(this.membershipDigest, membershipDigest)
        && this.totalCount != null
        && this.totalCount.longValue() == totalCount
        && Objects.equals(this.expiresAt, expiresAt);
  }

  public boolean isSameFailure(boolean rejected, String failureCode) {
    return Objects.equals(outcome, rejected ? "REJECTED" : "TRANSIENT_FAILED")
        && Objects.equals(this.failureCode, failureCode);
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public UUID getOperationId() {
    return operationId;
  }

  public long getTechnicalAttempt() {
    return technicalAttempt;
  }

  public String getOutcome() {
    return outcome;
  }

  public UUID getCaptureId() {
    return captureId;
  }

  public String getMembershipDigest() {
    return membershipDigest;
  }

  public Integer getTotalCount() {
    return totalCount;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public static final class Key implements Serializable {
    private UUID operationId;
    private long technicalAttempt;

    public Key() {}

    public Key(UUID operationId, long technicalAttempt) {
      this.operationId = operationId;
      this.technicalAttempt = technicalAttempt;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key value)) {
        return false;
      }
      return technicalAttempt == value.technicalAttempt
          && Objects.equals(operationId, value.operationId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(operationId, technicalAttempt);
    }
  }
}
