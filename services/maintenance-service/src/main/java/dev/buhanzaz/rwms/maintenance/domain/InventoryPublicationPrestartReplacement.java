package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable, local coordinator state for a pre-start inventory replacement.
 *
 * <p>The owning source remains immutable and is created only after every externally visible
 * cancellation/release fact has been replay-safe. This row deliberately contains enough stable
 * command data to repeat the remote portion after a response is lost, without retaining a
 * database transaction or row lock across the callback-capable logistics call.
 */
@Entity
@Table(name = "inventory_publication_prestart_replacement")
public class InventoryPublicationPrestartReplacement {
  private static final Set<String> DRIVER_KINDS =
      Set.of("DELIVER_TO_REPAIR", "CAPITAL_TO_PRODUCTION");
  private static final Set<String> PREDECESSOR_MODES =
      Set.of("ORDINARY", "EXTERNAL_CAPITAL");

  @EmbeddedId private InventoryPublicationSourceId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "request_idempotency_key", nullable = false)
  private UUID requestIdempotencyKey;

  @Column(name = "request_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String requestSnapshot;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "predecessor_repair_id", nullable = false)
  private UUID predecessorRepairId;

  @Column(name = "predecessor_mode", nullable = false, length = 32)
  private String predecessorMode;

  @Column(name = "driver_kind", nullable = false, length = 32)
  private String driverKind;

  @Column(name = "task_external_id", nullable = false)
  private UUID taskExternalId;

  @Column(name = "task_expected_version")
  private Long taskExpectedVersion;

  @Column(name = "task_guard_required", nullable = false)
  private boolean taskGuardRequired;

  /**
   * Incremented and committed before each callback-capable remote compensation attempt. A
   * transport loss after that point can never be treated as a terminal source conflict.
   */
  @Column(name = "remote_attempt_count", nullable = false)
  private long remoteAttemptCount;

  @Column(name = "lease_id")
  private UUID leaseId;

  @Column(name = "lease_version")
  private Long leaseVersion;

  @Column(name = "fencing_token")
  private Long fencingToken;

  @Column(name = "lease_owner_type", length = 64)
  private String leaseOwnerType;

  @Column(name = "lease_owner_id")
  private UUID leaseOwnerId;

  @Column(name = "phase", nullable = false, length = 32)
  private String phase;

  @Column(name = "driver_outcome", length = 32)
  private String driverOutcome;

  @Column(name = "task_outcome", length = 32)
  private String taskOutcome;

  @Column(name = "occupancy_reassignment_required", nullable = false)
  private boolean occupancyReassignmentRequired;

  @Column(name = "repair_place_allocation_id")
  private UUID repairPlaceAllocationId;

  @Column(name = "repair_place_allocation_version")
  private Long repairPlaceAllocationVersion;

  @Column(name = "successor_repair_id")
  private UUID successorRepairId;

  @Column(name = "compensated_at")
  private OffsetDateTime compensatedAt;

  @Column(name = "lease_released_at")
  private OffsetDateTime leaseReleasedAt;

  @Column(name = "applied_at")
  private OffsetDateTime appliedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryPublicationPrestartReplacement() {}

  public static InventoryPublicationPrestartReplacement prepare(
      InventoryPublicationSourceId id,
      String requestSha256,
      UUID requestIdempotencyKey,
      String requestSnapshot,
      UUID warehouseId,
      UUID assetId,
      UUID predecessorRepairId,
      String predecessorMode,
      String driverKind,
      UUID taskExternalId,
      Long taskExpectedVersion,
      boolean taskGuardRequired,
      LeaseIdentity lease) {
    if (id == null
        || !sha256(requestSha256)
        || requestIdempotencyKey == null
        || requestSnapshot == null
        || warehouseId == null
        || assetId == null
        || predecessorRepairId == null
        || !PREDECESSOR_MODES.contains(predecessorMode)
        || !DRIVER_KINDS.contains(driverKind)
        || taskExternalId == null
        || (taskGuardRequired && (taskExpectedVersion == null || taskExpectedVersion < 0))
        || (!taskGuardRequired && taskExpectedVersion != null)
        || (lease != null && !lease.valid())) {
      throw new IllegalArgumentException("Inventory pre-start replacement intent is incomplete");
    }
    InventoryPublicationPrestartReplacement value =
        new InventoryPublicationPrestartReplacement();
    value.id = id;
    value.requestSha256 = requestSha256;
    value.requestIdempotencyKey = requestIdempotencyKey;
    value.requestSnapshot = requestSnapshot;
    value.warehouseId = warehouseId;
    value.assetId = assetId;
    value.predecessorRepairId = predecessorRepairId;
    value.predecessorMode = predecessorMode;
    value.driverKind = driverKind;
    value.taskExternalId = taskExternalId;
    value.taskExpectedVersion = taskExpectedVersion;
    value.taskGuardRequired = taskGuardRequired;
    if (lease != null) {
      value.leaseId = lease.leaseId();
      value.leaseVersion = lease.leaseVersion();
      value.fencingToken = lease.fencingToken();
      value.leaseOwnerType = lease.ownerType();
      value.leaseOwnerId = lease.ownerId();
    }
    value.phase = "PREPARED";
    return value;
  }

  public void requireSameRequest(String requestSha256) {
    if (!Objects.equals(this.requestSha256, requestSha256)) {
      throw new IllegalArgumentException("STABLE_REPLACEMENT_REQUEST");
    }
  }

  public boolean recordCompensation(
      String driverOutcome,
      String taskOutcome,
      boolean occupancyReassignmentRequired,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) {
    if (!"PREPARED".equals(phase) && !"COMPENSATED".equals(phase)) {
      return false;
    }
    if (!safeDriverOutcome(driverOutcome)
        || (taskGuardRequired && !safeTaskOutcome(taskOutcome))
        || (!taskGuardRequired && taskOutcome != null)
        || (occupancyReassignmentRequired
            && (repairPlaceAllocationId == null
                || repairPlaceAllocationVersion == null
                || repairPlaceAllocationVersion < 0))
        || (!occupancyReassignmentRequired
            && (repairPlaceAllocationId != null || repairPlaceAllocationVersion != null))) {
      throw new IllegalArgumentException("Inventory pre-start compensation truth is invalid");
    }
    if ("COMPENSATED".equals(phase)
        && (!Objects.equals(this.driverOutcome, driverOutcome)
            || !Objects.equals(this.taskOutcome, taskOutcome)
            || this.occupancyReassignmentRequired != occupancyReassignmentRequired
            || !Objects.equals(this.repairPlaceAllocationId, repairPlaceAllocationId)
            || !Objects.equals(this.repairPlaceAllocationVersion, repairPlaceAllocationVersion))) {
      throw new IllegalArgumentException("Inventory pre-start compensation truth changed");
    }
    phase = "COMPENSATED";
    this.driverOutcome = driverOutcome;
    this.taskOutcome = taskOutcome;
    this.occupancyReassignmentRequired = occupancyReassignmentRequired;
    this.repairPlaceAllocationId = repairPlaceAllocationId;
    this.repairPlaceAllocationVersion = repairPlaceAllocationVersion;
    compensatedAt = MaintenanceTime.now();
    return true;
  }

  /**
   * A task registration may win a race immediately after an initially task-less queued repair is
   * claimed. Re-open the remote phase with the newly observed version instead of ever assuming
   * that an unguarded local task can be cancelled.
   */
  public boolean requireTaskGuard(long expectedTaskVersion) {
    if (expectedTaskVersion < 0) {
      throw new IllegalArgumentException("Inventory pre-start task version is invalid");
    }
    if (taskGuardRequired) {
      if (this.taskExpectedVersion != expectedTaskVersion) {
        throw new IllegalArgumentException("Inventory pre-start task version changed");
      }
      return false;
    }
    if (!"PREPARED".equals(phase) && !"COMPENSATED".equals(phase)) {
      throw new IllegalStateException("Inventory pre-start task guard is no longer mutable");
    }
    taskGuardRequired = true;
    taskExpectedVersion = expectedTaskVersion;
    if ("COMPENSATED".equals(phase)) {
      phase = "PREPARED";
      driverOutcome = null;
      taskOutcome = null;
      occupancyReassignmentRequired = false;
      repairPlaceAllocationId = null;
      repairPlaceAllocationVersion = null;
      compensatedAt = null;
    }
    return true;
  }

  /**
   * Records that a remote call may now have produced an external effect. The first attempt can
   * still be atomically aborted only when task-board proves VERSION_CONFLICT with no mutation.
   */
  public boolean beginRemoteAttempt() {
    if (!"PREPARED".equals(phase)) {
      throw new IllegalStateException("Inventory pre-start replacement is not ready for remote compensation");
    }
    remoteAttemptCount = Math.addExact(remoteAttemptCount, 1);
    return remoteAttemptCount == 1;
  }

  public boolean canAbortAfterProvedNoEffect() {
    return "PREPARED".equals(phase) && remoteAttemptCount == 1;
  }

  public boolean attachSuccessor(UUID successorRepairId) {
    if (successorRepairId == null || successorRepairId.equals(predecessorRepairId)) {
      throw new IllegalArgumentException("Inventory pre-start successor identity is invalid");
    }
    if ("SUCCESSOR_CREATED".equals(phase)
        || "LEASE_RELEASED".equals(phase)
        || "APPLIED".equals(phase)) {
      if (!successorRepairId.equals(this.successorRepairId)) {
        throw new IllegalArgumentException("Inventory pre-start successor cannot change");
      }
      return false;
    }
    if (!"COMPENSATED".equals(phase)) {
      throw new IllegalStateException("Inventory pre-start replacement is not compensated");
    }
    this.successorRepairId = successorRepairId;
    phase = "SUCCESSOR_CREATED";
    return true;
  }

  public boolean markLeaseReleased() {
    if ("LEASE_RELEASED".equals(phase) || "APPLIED".equals(phase)) return false;
    if (!"SUCCESSOR_CREATED".equals(phase)) {
      throw new IllegalStateException("Inventory pre-start successor is not ready for lease release");
    }
    phase = "LEASE_RELEASED";
    leaseReleasedAt = MaintenanceTime.now();
    return true;
  }

  public boolean markApplied() {
    if ("APPLIED".equals(phase)) return false;
    if (!"LEASE_RELEASED".equals(phase)) {
      throw new IllegalStateException("Inventory pre-start replacement lease is not released");
    }
    phase = "APPLIED";
    appliedAt = MaintenanceTime.now();
    return true;
  }

  /** Allows a terminal MERGE successor after remote truth proves physical work already began. */
  public void markAppliedAfterStartedTruth(String driverOutcome, String taskOutcome) {
    if (!safeStartedDriverOutcome(driverOutcome)
        && !"STARTED".equals(taskOutcome)) {
      throw new IllegalArgumentException("Inventory pre-start terminal truth is invalid");
    }
    if ("APPLIED".equals(phase)) return;
    if (!"PREPARED".equals(phase)) {
      throw new IllegalStateException("Inventory pre-start replacement already has another outcome");
    }
    this.driverOutcome = driverOutcome;
    this.taskOutcome = taskOutcome;
    phase = "APPLIED";
    appliedAt = MaintenanceTime.now();
  }

  private static boolean safeDriverOutcome(String value) {
    return "ABSENT".equals(value) || "CANCELLED".equals(value) || "COMPLETED".equals(value);
  }

  private static boolean safeStartedDriverOutcome(String value) {
    return "STARTED".equals(value) || "COMPLETED".equals(value);
  }

  private static boolean safeTaskOutcome(String value) {
    return "CANCELLED".equals(value) || "ALREADY_CANCELLED".equals(value);
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = MaintenanceTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = MaintenanceTime.now();
  }

  public InventoryPublicationSourceId getId() { return id; }
  public long getVersion() { return version; }
  public String getRequestSha256() { return requestSha256; }
  public UUID getRequestIdempotencyKey() { return requestIdempotencyKey; }
  public String getRequestSnapshot() { return requestSnapshot; }
  public UUID getWarehouseId() { return warehouseId; }
  public UUID getAssetId() { return assetId; }
  public UUID getPredecessorRepairId() { return predecessorRepairId; }
  public String getPredecessorMode() { return predecessorMode; }
  public String getDriverKind() { return driverKind; }
  public UUID getTaskExternalId() { return taskExternalId; }
  public Long getTaskExpectedVersion() { return taskExpectedVersion; }
  public boolean isTaskGuardRequired() { return taskGuardRequired; }
  public long getRemoteAttemptCount() { return remoteAttemptCount; }
  public UUID getLeaseId() { return leaseId; }
  public Long getLeaseVersion() { return leaseVersion; }
  public Long getFencingToken() { return fencingToken; }
  public String getLeaseOwnerType() { return leaseOwnerType; }
  public UUID getLeaseOwnerId() { return leaseOwnerId; }
  public String getPhase() { return phase; }
  public String getDriverOutcome() { return driverOutcome; }
  public String getTaskOutcome() { return taskOutcome; }
  public boolean isOccupancyReassignmentRequired() { return occupancyReassignmentRequired; }
  public UUID getRepairPlaceAllocationId() { return repairPlaceAllocationId; }
  public Long getRepairPlaceAllocationVersion() { return repairPlaceAllocationVersion; }
  public UUID getSuccessorRepairId() { return successorRepairId; }
  public OffsetDateTime getCompensatedAt() { return compensatedAt; }
  public OffsetDateTime getLeaseReleasedAt() { return leaseReleasedAt; }
  public OffsetDateTime getAppliedAt() { return appliedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  public record LeaseIdentity(
      UUID leaseId, long leaseVersion, long fencingToken, String ownerType, UUID ownerId) {
    private boolean valid() {
      return leaseId != null
          && leaseVersion >= 0
          && fencingToken >= 1
          && ownerType != null
          && !ownerType.isBlank()
          && ownerType.length() <= 64
          && ownerId != null;
    }
  }
}
