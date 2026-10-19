package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Replay-safe external-effect ledger for one maintenance predecessor superseded by inventory.
 * Stable command identities are derived from this row's source and target identity.
 */
@Entity
@Table(name = "inventory_authoritative_outcome_target")
public class InventoryAuthoritativeOutcomeTarget {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "target_kind", nullable = false, length = 16)
  private String targetKind;

  @Column(name = "target_id", nullable = false)
  private UUID targetId;

  @Column(name = "task_external_id")
  private UUID taskExternalId;

  @Column(name = "task_expected_version")
  private Long taskExpectedVersion;

  @Column(name = "task_attempt_count", nullable = false)
  private long taskAttemptCount;

  @Column(name = "task_outcome", length = 32)
  private String taskOutcome;

  @Column(name = "driver_kind", length = 32)
  private String driverKind;

  @Column(name = "driver_attempt_count", nullable = false)
  private long driverAttemptCount;

  @Column(name = "driver_outcome", length = 32)
  private String driverOutcome;

  @Column(name = "driver_task_id")
  private UUID driverTaskId;

  @Column(name = "repair_place_allocation_id")
  private UUID repairPlaceAllocationId;

  @Column(name = "repair_place_allocation_version")
  private Long repairPlaceAllocationVersion;

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

  @Column(name = "lease_attempt_count", nullable = false)
  private long leaseAttemptCount;

  @Column(name = "lease_released", nullable = false)
  private boolean leaseReleased;

  @Column(name = "local_superseded", nullable = false)
  private boolean localSuperseded;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryAuthoritativeOutcomeTarget() {}

  /** Captures a DRAFT estimate predecessor, which has no external effects. */
  public static InventoryAuthoritativeOutcomeTarget estimate(
      InventoryPublicationSourceId sourceId, UUID estimateId) {
    return create(sourceId, "ESTIMATE", estimateId, null, null, null, null);
  }

  /** Captures all fenced external identities owned by a non-terminal repair predecessor. */
  public static InventoryAuthoritativeOutcomeTarget repair(
      InventoryPublicationSourceId sourceId,
      UUID repairId,
      UUID taskExternalId,
      Long taskExpectedVersion,
      String driverKind,
      LeaseIdentity lease) {
    return create(
        sourceId, "REPAIR", repairId, taskExternalId, taskExpectedVersion, driverKind, lease);
  }

  private static InventoryAuthoritativeOutcomeTarget create(
      InventoryPublicationSourceId sourceId,
      String targetKind,
      UUID targetId,
      UUID taskExternalId,
      Long taskExpectedVersion,
      String driverKind,
      LeaseIdentity lease) {
    boolean taskValid = (taskExternalId == null && taskExpectedVersion == null)
        || (taskExternalId != null && taskExpectedVersion != null && taskExpectedVersion >= 0);
    if (sourceId == null
        || !("ESTIMATE".equals(targetKind) || "REPAIR".equals(targetKind))
        || targetId == null
        || !taskValid
        || (driverKind != null
            && !("DELIVER_TO_REPAIR".equals(driverKind)
                || "CAPITAL_TO_PRODUCTION".equals(driverKind)))
        || (lease != null && !lease.valid())) {
      throw new IllegalArgumentException("Authoritative inventory target is incomplete");
    }
    InventoryAuthoritativeOutcomeTarget value = new InventoryAuthoritativeOutcomeTarget();
    value.inventoryId = sourceId.getInventoryId();
    value.finalPlanVersion = sourceId.getFinalPlanVersion();
    value.findingId = sourceId.getFindingId();
    value.targetKind = targetKind;
    value.targetId = targetId;
    value.taskExternalId = taskExternalId;
    value.taskExpectedVersion = taskExpectedVersion;
    value.driverKind = driverKind;
    if (lease != null) {
      value.leaseId = lease.leaseId();
      value.leaseVersion = lease.leaseVersion();
      value.fencingToken = lease.fencingToken();
      value.leaseOwnerType = lease.ownerType();
      value.leaseOwnerId = lease.ownerId();
    }
    return value;
  }

  /** Records a committed task-board attempt before the callback-capable request. */
  public void beginTaskAttempt() {
    if (taskExternalId == null || taskOutcome != null) return;
    taskAttemptCount = Math.addExact(taskAttemptCount, 1);
  }

  /** Refreshes the optimistic task-board fence from a read-only source-owned task lookup. */
  public void refreshTaskExpectedVersion(long version) {
    if (taskExternalId == null || version < 0 || taskOutcome != null) {
      throw new IllegalArgumentException("Authoritative task version refresh is invalid");
    }
    taskExpectedVersion = version;
  }

  /** Records the terminal task-board state returned by the source-owned cancellation. */
  public void recordTaskOutcome(String outcome, long version) {
    if (taskExternalId == null || outcome == null || outcome.isBlank() || version < 0) {
      throw new IllegalArgumentException("Authoritative task cancellation truth is invalid");
    }
    if (taskOutcome != null && !Objects.equals(taskOutcome, outcome)) {
      throw new IllegalStateException("Authoritative task cancellation truth changed");
    }
    taskOutcome = outcome;
    taskExpectedVersion = version;
  }

  /** Records a committed logistics compensation attempt before the remote request. */
  public void beginDriverAttempt() {
    if (driverKind == null || driverOutcome != null) return;
    driverAttemptCount = Math.addExact(driverAttemptCount, 1);
  }

  /** Stores logistics-owned terminal movement and optional occupied-place truth. */
  public void recordDriverOutcome(
      String outcome,
      UUID driverTaskId,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) {
    if (driverKind == null || outcome == null || outcome.isBlank()) {
      throw new IllegalArgumentException("Authoritative driver compensation truth is invalid");
    }
    if ((repairPlaceAllocationId == null) != (repairPlaceAllocationVersion == null)
        || (repairPlaceAllocationVersion != null && repairPlaceAllocationVersion < 0)) {
      throw new IllegalArgumentException("Authoritative repair-place truth is invalid");
    }
    if (driverOutcome != null
        && (!Objects.equals(driverOutcome, outcome)
            || !Objects.equals(this.driverTaskId, driverTaskId)
            || !Objects.equals(this.repairPlaceAllocationId, repairPlaceAllocationId)
            || !Objects.equals(this.repairPlaceAllocationVersion, repairPlaceAllocationVersion))) {
      throw new IllegalStateException("Authoritative driver compensation truth changed");
    }
    driverOutcome = outcome;
    this.driverTaskId = driverTaskId;
    this.repairPlaceAllocationId = repairPlaceAllocationId;
    this.repairPlaceAllocationVersion = repairPlaceAllocationVersion;
  }

  /** Records a committed asset lease-release attempt before the remote request. */
  public void beginLeaseAttempt() {
    if (leaseId == null || leaseReleased) return;
    leaseAttemptCount = Math.addExact(leaseAttemptCount, 1);
  }

  /** Marks the exact captured asset lease as released. */
  public void markLeaseReleased() {
    if (leaseId != null) leaseReleased = true;
  }

  /** Marks the local aggregate historical only after every required external effect settled. */
  public void markLocalSuperseded() {
    if (!remoteSettled()) {
      throw new IllegalStateException("Authoritative predecessor remote effects are not settled");
    }
    localSuperseded = true;
  }

  /** True only when task, movement and lease work can no longer remain active. */
  public boolean remoteSettled() {
    return (taskExternalId == null || taskOutcome != null)
        && (driverKind == null || driverOutcome != null)
        && (leaseId == null || leaseReleased);
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

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getInventoryId() { return inventoryId; }
  public long getFinalPlanVersion() { return finalPlanVersion; }
  public UUID getFindingId() { return findingId; }
  public String getTargetKind() { return targetKind; }
  public UUID getTargetId() { return targetId; }
  public UUID getTaskExternalId() { return taskExternalId; }
  public Long getTaskExpectedVersion() { return taskExpectedVersion; }
  public long getTaskAttemptCount() { return taskAttemptCount; }
  public String getTaskOutcome() { return taskOutcome; }
  public String getDriverKind() { return driverKind; }
  public long getDriverAttemptCount() { return driverAttemptCount; }
  public String getDriverOutcome() { return driverOutcome; }
  public UUID getDriverTaskId() { return driverTaskId; }
  public UUID getRepairPlaceAllocationId() { return repairPlaceAllocationId; }
  public Long getRepairPlaceAllocationVersion() { return repairPlaceAllocationVersion; }
  public UUID getLeaseId() { return leaseId; }
  public Long getLeaseVersion() { return leaseVersion; }
  public Long getFencingToken() { return fencingToken; }
  public String getLeaseOwnerType() { return leaseOwnerType; }
  public UUID getLeaseOwnerId() { return leaseOwnerId; }
  public long getLeaseAttemptCount() { return leaseAttemptCount; }
  public boolean isLeaseReleased() { return leaseReleased; }
  public boolean isLocalSuperseded() { return localSuperseded; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  /** Captured asset lease ownership used for exact replay-safe release. */
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
