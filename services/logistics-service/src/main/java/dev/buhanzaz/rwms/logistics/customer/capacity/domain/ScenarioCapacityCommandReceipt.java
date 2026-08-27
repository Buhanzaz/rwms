package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Immutable result of one accepted capacity command, retained so a delayed retry cannot replace a
 * newer active warehouse snapshot.
 */
@Entity
@Table(name = "customer_scenario_capacity_command_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScenarioCapacityCommandReceipt {
  @Id
  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "source_scenario_id", nullable = false)
  private UUID sourceScenarioId;

  @Column(name = "source_generation", nullable = false)
  private long sourceGeneration;

  @Column(name = "source_revision", nullable = false, length = 64)
  private String sourceRevision;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "snapshot_version", nullable = false)
  private long snapshotVersion;

  @Column(name = "job_count", nullable = false)
  private int jobCount;

  @Column(name = "response_updated_at", nullable = false)
  private OffsetDateTime responseUpdatedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** Records the exact response facts returned by one newly applied command. */
  public static ScenarioCapacityCommandReceipt applied(
      UUID idempotencyKey,
      ScenarioCapacitySnapshot snapshot,
      String requestSha256,
      OffsetDateTime createdAt) {
    return create(
        idempotencyKey,
        snapshot.getWarehouseId(),
        snapshot.getSourceScenarioId(),
        snapshot.getSourceGeneration(),
        snapshot.getSourceRevision(),
        requestSha256,
        snapshot.getVersion(),
        snapshot.getJobs().size(),
        snapshot.getUpdatedAt(),
        createdAt);
  }

  /** Binds another accepted key to the same immutable revision result without changing capacity. */
  public static ScenarioCapacityCommandReceipt replayAlias(
      UUID idempotencyKey,
      ScenarioCapacityCommandReceipt original,
      OffsetDateTime createdAt) {
    Objects.requireNonNull(original, "original");
    return create(
        idempotencyKey,
        original.warehouseId,
        original.sourceScenarioId,
        original.sourceGeneration,
        original.sourceRevision,
        original.requestSha256,
        original.snapshotVersion,
        original.jobCount,
        original.responseUpdatedAt,
        createdAt);
  }

  /** Returns whether this successful key was accepted for the same canonical request facts. */
  public boolean matchesRequest(String requestSha256) {
    return this.requestSha256.equals(requestSha256);
  }

  private static ScenarioCapacityCommandReceipt create(
      UUID idempotencyKey,
      UUID warehouseId,
      UUID sourceScenarioId,
      long sourceGeneration,
      String sourceRevision,
      String requestSha256,
      long snapshotVersion,
      int jobCount,
      OffsetDateTime responseUpdatedAt,
      OffsetDateTime createdAt) {
    ScenarioCapacityCommandReceipt receipt = new ScenarioCapacityCommandReceipt();
    receipt.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    receipt.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    receipt.sourceScenarioId = Objects.requireNonNull(sourceScenarioId, "sourceScenarioId");
    if (sourceGeneration < 1) {
      throw new IllegalArgumentException("Capacity receipt generation is invalid");
    }
    receipt.sourceGeneration = sourceGeneration;
    receipt.sourceRevision = requireSha256(sourceRevision, "sourceRevision");
    receipt.requestSha256 = requireSha256(requestSha256, "requestSha256");
    if (snapshotVersion < 0 || jobCount < 0) {
      throw new IllegalArgumentException("Capacity receipt result is invalid");
    }
    receipt.snapshotVersion = snapshotVersion;
    receipt.jobCount = jobCount;
    receipt.responseUpdatedAt =
        Objects.requireNonNull(responseUpdatedAt, "responseUpdatedAt");
    receipt.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    return receipt;
  }

  private static String requireSha256(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
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
    return thisClass == otherClass
        && idempotencyKey != null
        && Objects.equals(
            idempotencyKey, ((ScenarioCapacityCommandReceipt) other).idempotencyKey);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
