package dev.buhanzaz.rwms.maintenance.domain;

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
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "maintenance_repair")
public class MaintenanceRepair {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "rental_item_version_snapshot", nullable = false)
  private long rentalItemVersionSnapshot;

  @Column(name = "root_repair_id")
  private UUID rootRepairId;

  @Column(name = "source_repair_id")
  private UUID sourceRepairId;

  @Column(name = "estimate_id")
  private UUID estimateId;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, length = 24)
  private RepairOrigin origin;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private RepairKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "execution_state", nullable = false, length = 24)
  private RepairExecutionState executionState;

  @Enumerated(EnumType.STRING)
  @Column(name = "acceptance_state", nullable = false, length = 24)
  private RepairAcceptanceState acceptanceState;

  @Column(name = "dispatch_date", nullable = false)
  private LocalDate dispatchDate;

  @Column(name = "source_party", length = 512)
  private String sourceParty;

  @Column(name = "rework_reason", length = 2000)
  private String reworkReason;

  @Column(name = "decision_reason", length = 2000)
  private String decisionReason;

  @Column(name = "decision_actor_ref", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String decisionActorRef;

  @Column(name = "decision_recorded_at")
  private OffsetDateTime decisionRecordedAt;

  @Column(name = "actor_ref", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String actorRef;

  @Column(name = "external_task_id", nullable = false)
  private UUID externalTaskId;

  @Column(name = "task_generation_state", nullable = false, length = 32)
  private String taskGenerationState;

  @Column(name = "delivery_state", nullable = false, length = 24)
  private String deliveryState;

  @Column(name = "delivery_attempts", nullable = false)
  private int deliveryAttempts;

  @Column(name = "delivery_updated_at", nullable = false)
  private OffsetDateTime deliveryUpdatedAt;

  @Column(name = "task_board_version")
  private Long taskBoardVersion;

  @Column(name = "lease_id")
  private UUID leaseId;

  @Column(name = "lease_version")
  private Long leaseVersion;

  @Column(name = "fencing_token")
  private Long fencingToken;

  @Column(name = "lease_expires_at")
  private OffsetDateTime leaseExpiresAt;

  @Column(name = "lease_reconciliation_state", nullable = false, length = 32)
  private String leaseReconciliationState;

  @Column(name = "reconciliation_state", nullable = false, length = 32)
  private String reconciliationState;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected MaintenanceRepair() {}

  public static MaintenanceRepair primary(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersionSnapshot,
      UUID estimateId,
      RepairOrigin origin,
      LocalDate dispatchDate,
      String sourceParty,
      String actorRef) {
    return create(
        warehouseId,
        rentalItemId,
        rentalItemVersionSnapshot,
        estimateId,
        origin,
        RepairKind.PRIMARY,
        null,
        null,
        dispatchDate,
        sourceParty,
        null,
        actorRef);
  }

  public static MaintenanceRepair rework(
      MaintenanceRepair source, String reason, String actorRef) {
    if (source.acceptanceState != RepairAcceptanceState.PENDING) {
      throw new IllegalStateException("Only a repair pending acceptance can receive rework");
    }
    UUID root = source.rootRepairId == null ? source.id : source.rootRepairId;
    return create(
        source.warehouseId,
        source.rentalItemId,
        source.rentalItemVersionSnapshot,
        null,
        source.origin,
        RepairKind.REWORK,
        root,
        source.id,
        source.dispatchDate,
        source.sourceParty,
        reason,
        actorRef);
  }

  private static MaintenanceRepair create(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersionSnapshot,
      UUID estimateId,
      RepairOrigin origin,
      RepairKind kind,
      UUID rootRepairId,
      UUID sourceRepairId,
      LocalDate dispatchDate,
      String sourceParty,
      String reworkReason,
      String actorRef) {
    if (warehouseId == null || rentalItemId == null || rentalItemVersionSnapshot < 0
        || origin == null || kind == null || dispatchDate == null) {
      throw new IllegalArgumentException("Repair ownership is required");
    }
    MaintenanceRepair value = new MaintenanceRepair();
    value.warehouseId = warehouseId;
    value.rentalItemId = rentalItemId;
    value.rentalItemVersionSnapshot = rentalItemVersionSnapshot;
    value.estimateId = estimateId;
    value.origin = origin;
    value.kind = kind;
    value.rootRepairId = rootRepairId;
    value.sourceRepairId = sourceRepairId;
    value.executionState = RepairExecutionState.DRAFT;
    value.acceptanceState = RepairAcceptanceState.NOT_READY;
    value.dispatchDate = dispatchDate;
    value.sourceParty = normalize(sourceParty, 512);
    value.reworkReason = normalize(reworkReason, 2000);
    value.actorRef = actorRef == null ? "{}" : actorRef;
    value.externalTaskId = UUID.randomUUID();
    value.taskGenerationState = "PENDING_GENERATION";
    value.deliveryState = "PENDING";
    value.deliveryAttempts = 0;
    value.deliveryUpdatedAt = MaintenanceTime.now();
    value.leaseReconciliationState = "NOT_REQUIRED";
    value.reconciliationState = "NOT_REQUIRED";
    return value;
  }

  public void queue(
      UUID leaseId, long leaseVersion, long fencingToken, OffsetDateTime leaseExpiresAt) {
    if (executionState != RepairExecutionState.DRAFT) {
      throw new IllegalStateException("Only a draft repair can be queued");
    }
    if (leaseId == null || leaseVersion < 0 || fencingToken < 1 || leaseExpiresAt == null) {
      throw new IllegalArgumentException("Active lease snapshot is required");
    }
    executionState = RepairExecutionState.QUEUED;
    this.leaseId = leaseId;
    this.leaseVersion = leaseVersion;
    this.fencingToken = fencingToken;
    this.leaseExpiresAt = leaseExpiresAt;
    leaseReconciliationState = "ACTIVE";
    deliveryState = "RETRY_PENDING";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void confirmRentalItemVersion(long version) {
    if (version < rentalItemVersionSnapshot) {
      throw new IllegalArgumentException("Confirmed rental-item version cannot move backwards");
    }
    rentalItemVersionSnapshot = version;
  }

  public void markTaskGenerated(long taskVersion) {
    if (executionState != RepairExecutionState.QUEUED && executionState != RepairExecutionState.IN_PROGRESS) {
      throw new IllegalStateException("Task generation is not valid for this repair");
    }
    if (taskVersion < 0) throw new IllegalArgumentException("taskVersion is invalid");
    taskGenerationState = "GENERATED";
    deliveryState = "DELIVERED";
    deliveryAttempts = Math.addExact(deliveryAttempts, 1);
    deliveryUpdatedAt = MaintenanceTime.now();
    this.taskBoardVersion = taskVersion;
  }

  public void touchPlan() {
    if (executionState != RepairExecutionState.DRAFT) {
      throw new IllegalStateException("Only a draft repair plan can be changed");
    }
    updatedAt = MaintenanceTime.now();
  }

  public void amendPreStartPlan() {
    requirePreStartAmendment();
    reconciliationState = "RECONCILIATION_REQUIRED";
    if (executionState == RepairExecutionState.QUEUED) {
      deliveryState = "RETRY_PENDING";
      taskGenerationState = "PENDING_GENERATION";
      deliveryUpdatedAt = MaintenanceTime.now();
    }
    updatedAt = MaintenanceTime.now();
  }

  public void begin() {
    if (executionState != RepairExecutionState.QUEUED) {
      throw new IllegalStateException("Only a queued repair can start");
    }
    executionState = RepairExecutionState.IN_PROGRESS;
  }

  public void completeForAcceptance() {
    if (executionState != RepairExecutionState.QUEUED
        && executionState != RepairExecutionState.IN_PROGRESS) {
      throw new IllegalStateException("Repair is not executable");
    }
    executionState = RepairExecutionState.COMPLETED;
    acceptanceState = RepairAcceptanceState.PENDING;
  }

  public void applyStageCompletion(boolean allStagesDone) {
    if (executionState != RepairExecutionState.QUEUED
        && executionState != RepairExecutionState.IN_PROGRESS) {
      throw new IllegalStateException("Repair is not executable");
    }
    if (allStagesDone) {
      executionState = RepairExecutionState.COMPLETED;
      acceptanceState = RepairAcceptanceState.PENDING;
    } else {
      executionState = RepairExecutionState.IN_PROGRESS;
    }
    updatedAt = MaintenanceTime.now();
  }

  public void applyExternalTaskCancellation() {
    if (executionState != RepairExecutionState.QUEUED) {
      throw new IllegalStateException("Only a pre-start queued repair can accept task cancellation");
    }
    executionState = RepairExecutionState.CANCELLED;
    acceptanceState = RepairAcceptanceState.NOT_READY;
    updatedAt = MaintenanceTime.now();
  }

  public void enterRework() {
    if (acceptanceState != RepairAcceptanceState.PENDING) {
      throw new IllegalStateException("Repair is not pending acceptance");
    }
    acceptanceState = RepairAcceptanceState.IN_REWORK;
  }

  public void returnFromRework() {
    if (acceptanceState != RepairAcceptanceState.IN_REWORK) {
      throw new IllegalStateException("Repair is not in rework");
    }
    acceptanceState = RepairAcceptanceState.PENDING;
  }

  public void accept(String reason, String actorRef) {
    if (acceptanceState != RepairAcceptanceState.PENDING) {
      throw new IllegalStateException("Repair is not pending acceptance");
    }
    acceptanceState = RepairAcceptanceState.ACCEPTED;
    recordDecision(reason, actorRef);
  }

  public void writeOff(String reason, String actorRef) {
    if (acceptanceState == RepairAcceptanceState.ACCEPTED
        || acceptanceState == RepairAcceptanceState.WRITTEN_OFF) {
      throw new IllegalStateException("Repair already has a terminal acceptance decision");
    }
    acceptanceState = RepairAcceptanceState.WRITTEN_OFF;
    recordDecision(reason, actorRef);
  }

  public void requirePreStartAmendment() {
    if (executionState != RepairExecutionState.DRAFT && executionState != RepairExecutionState.QUEUED) {
      throw new IllegalStateException("Estimate cannot be amended after repair start");
    }
  }

  public boolean markReconciliationRequired() {
    if ("RECONCILIATION_REQUIRED".equals(reconciliationState)) return false;
    reconciliationState = "RECONCILIATION_REQUIRED";
    deliveryState = "RETRY_PENDING";
    deliveryUpdatedAt = MaintenanceTime.now();
    return true;
  }

  public boolean markReconciliationQuarantined() {
    if ("QUARANTINED".equals(deliveryState)) return false;
    reconciliationState = "RECONCILIATION_REQUIRED";
    deliveryState = "QUARANTINED";
    taskGenerationState = "FAILED";
    deliveryUpdatedAt = MaintenanceTime.now();
    return true;
  }

  public void markReconciled() {
    reconciliationState = "RECONCILED";
    deliveryState = "DELIVERED";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void markLeaseReconciliationRequired() {
    leaseReconciliationState = "RECONCILIATION_REQUIRED";
    reconciliationState = "RECONCILIATION_REQUIRED";
    deliveryState = "RETRY_PENDING";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void renewLease(long leaseVersion, OffsetDateTime expiresAt) {
    if (leaseId == null || leaseVersion < 0 || expiresAt == null) {
      throw new IllegalStateException("An active lease is required for renewal");
    }
    this.leaseVersion = leaseVersion;
    this.leaseExpiresAt = expiresAt;
    leaseReconciliationState = "ACTIVE";
  }

  public void releaseLease() {
    leaseReconciliationState = "RELEASED";
    markReconciled();
  }

  private void recordDecision(String reason, String actorRef) {
    decisionReason = normalize(reason, 2000);
    decisionActorRef = actorRef == null ? "{}" : actorRef;
    decisionRecordedAt = MaintenanceTime.now();
  }

  private static String normalize(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maximum) throw new IllegalArgumentException("Repair text is too long");
    return normalized;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = MaintenanceTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() { updatedAt = MaintenanceTime.now(); }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getWarehouseId() { return warehouseId; }
  public UUID getRentalItemId() { return rentalItemId; }
  public long getRentalItemVersionSnapshot() { return rentalItemVersionSnapshot; }
  public UUID getRootRepairId() { return rootRepairId; }
  public UUID getSourceRepairId() { return sourceRepairId; }
  public UUID getEstimateId() { return estimateId; }
  public RepairOrigin getOrigin() { return origin; }
  public RepairKind getKind() { return kind; }
  public RepairExecutionState getExecutionState() { return executionState; }
  public RepairAcceptanceState getAcceptanceState() { return acceptanceState; }
  public LocalDate getDispatchDate() { return dispatchDate; }
  public String getSourceParty() { return sourceParty; }
  public String getReworkReason() { return reworkReason; }
  public String getDecisionReason() { return decisionReason; }
  public String getDecisionActorRef() { return decisionActorRef; }
  public OffsetDateTime getDecisionRecordedAt() { return decisionRecordedAt; }
  public String getActorRef() { return actorRef; }
  public UUID getExternalTaskId() { return externalTaskId; }
  public String getTaskGenerationState() { return taskGenerationState; }
  public String getDeliveryState() { return deliveryState; }
  public int getDeliveryAttempts() { return deliveryAttempts; }
  public OffsetDateTime getDeliveryUpdatedAt() { return deliveryUpdatedAt; }
  public Long getTaskBoardVersion() { return taskBoardVersion; }
  public UUID getLeaseId() { return leaseId; }
  public Long getLeaseVersion() { return leaseVersion; }
  public Long getFencingToken() { return fencingToken; }
  public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
  public String getLeaseReconciliationState() { return leaseReconciliationState; }
  public String getReconciliationState() { return reconciliationState; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
