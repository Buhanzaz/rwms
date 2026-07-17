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

@Entity
@Table(name = "inventory_start_capture_attempt")
@IdClass(InventoryStartCaptureAttempt.Key.class)
public class InventoryStartCaptureAttempt {
  @Id
  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Id
  @Column(name = "technical_attempt", nullable = false)
  private long technicalAttempt;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "requested_at", nullable = false)
  private OffsetDateTime requestedAt;

  protected InventoryStartCaptureAttempt() {}

  public static InventoryStartCaptureAttempt request(
      UUID operationId,
      long technicalAttempt,
      String requestFingerprint,
      OffsetDateTime requestedAt) {
    if (operationId == null
        || technicalAttempt < 1
        || !sha256(requestFingerprint)
        || requestedAt == null) {
      throw new IllegalArgumentException("Capture attempt is incomplete");
    }
    InventoryStartCaptureAttempt value = new InventoryStartCaptureAttempt();
    value.operationId = operationId;
    value.technicalAttempt = technicalAttempt;
    value.requestFingerprint = requestFingerprint;
    value.requestedAt = requestedAt;
    return value;
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

  public String getRequestFingerprint() {
    return requestFingerprint;
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
