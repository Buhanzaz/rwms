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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

  @Enumerated(EnumType.STRING)
  @Column(name = "furniture_accounting_mode", nullable = false, length = 32)
  private FurnitureAccountingMode furnitureAccountingMode;

  @Column(name = "dispatch_date", nullable = false)
  private LocalDate dispatchDate;

  @Column(name = "priority", nullable = false)
  private int priority = 3;

  @Column(name = "source_party", length = 512)
  private String sourceParty;

  @Column(name = "cover_media_id")
  private UUID coverMediaId;

  @Column(name = "movement_to_repair", nullable = false)
  private boolean movementToRepair;

  @Enumerated(EnumType.STRING)
  @Column(name = "logistics_planning_mode", length = 16)
  private RepairLogisticsPlanningMode logisticsPlanningMode;

  @Column(name = "logistics_scheduled_date")
  private LocalDate logisticsScheduledDate;

  @Column(name = "transfer_state", nullable = false, length = 24)
  private String transferState = "NONE";

  @Column(name = "transfer_document_id")
  private UUID transferDocumentId;

  @Column(name = "transfer_line_id")
  private UUID transferLineId;

  @Column(name = "transfer_target_warehouse_id")
  private UUID transferTargetWarehouseId;

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

  @Enumerated(EnumType.STRING)
  @Column(name = "reclassification_state", nullable = false, length = 24)
  private RepairReclassificationState reclassificationState;

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
    value.furnitureAccountingMode = FurnitureAccountingMode.TRACKED_CABIN_CONTENTS;
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
    value.reclassificationState = RepairReclassificationState.STABLE;
    value.transferState = "NONE";
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

  /**
   * Records the explicit legacy-cabin confirmation made while completing an estimate.
   *
   * <p>No inventory balance can be inferred in this mode; selected furniture is instead proposed
   * as a separately approved loss after repair completion.
   */
  public void useUnaccountedFurnitureAccounting() {
    if (origin != RepairOrigin.ESTIMATE
        || kind != RepairKind.PRIMARY
        || executionState != RepairExecutionState.DRAFT
        || acceptanceState != RepairAcceptanceState.NOT_READY) {
      throw new IllegalStateException(
          "Unaccounted furniture accounting can be selected only for a new estimate repair");
    }
    furnitureAccountingMode = FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS;
  }

  public void queueUnderExistingRepair() {
    if (executionState != RepairExecutionState.DRAFT) {
      throw new IllegalStateException("Only a draft repair can be queued");
    }
    if (leaseId != null
        || leaseVersion != null
        || fencingToken != null
        || leaseExpiresAt != null
        || !"NOT_REQUIRED".equals(leaseReconciliationState)) {
      throw new IllegalStateException(
          "A repair queued under an existing repair cannot own an operation lease");
    }
    executionState = RepairExecutionState.QUEUED;
    deliveryState = "RETRY_PENDING";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void queueExternalCapital(
      UUID leaseId, long leaseVersion, long fencingToken, OffsetDateTime leaseExpiresAt) {
    queue(leaseId, leaseVersion, fencingToken, leaseExpiresAt);
    completeAsExternalCapital();
  }

  public void queueExternalCapitalUnderExistingRepair() {
    queueUnderExistingRepair();
    completeAsExternalCapital();
  }

  public void completeAsExternalCapital() {
    if (executionState != RepairExecutionState.QUEUED
        && executionState != RepairExecutionState.IN_PROGRESS) {
      throw new IllegalStateException(
          "Only an active repair can move to external capital execution");
    }
    executionState = RepairExecutionState.COMPLETED;
    acceptanceState = RepairAcceptanceState.PENDING;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    reconciliationState = "RECONCILED";
    reclassificationState = RepairReclassificationState.EXTERNAL_CAPITAL;
    deliveryUpdatedAt = MaintenanceTime.now();
    taskBoardVersion = null;
  }

  public void markReclassifyingCapital() {
    if (executionState != RepairExecutionState.QUEUED
        && executionState != RepairExecutionState.IN_PROGRESS) {
      throw new IllegalStateException(
          "Only active ordinary repair work can be reclassified");
    }
    reclassificationState = RepairReclassificationState.RECLASSIFYING_CAPITAL;
    reconciliationState = "RECONCILIATION_REQUIRED";
    updatedAt = MaintenanceTime.now();
  }

  public boolean stabilizeOrdinaryClassification() {
    if (executionState != RepairExecutionState.QUEUED
        && executionState != RepairExecutionState.IN_PROGRESS) {
      throw new IllegalStateException(
          "Only active repair work can confirm an ordinary classification");
    }
    if (reclassificationState == RepairReclassificationState.STABLE) {
      return false;
    }
    reclassificationState = RepairReclassificationState.STABLE;
    updatedAt = MaintenanceTime.now();
    return true;
  }

  public boolean isQueuedWithoutOperationLease() {
    return kind == RepairKind.PRIMARY
        && executionState != RepairExecutionState.DRAFT
        && leaseId == null
        && leaseVersion == null
        && fencingToken == null
        && leaseExpiresAt == null
        && "NOT_REQUIRED".equals(leaseReconciliationState);
  }

  public void selectPriority(int priority) {
    if (executionState != RepairExecutionState.DRAFT) {
      throw new IllegalStateException("Priority can only be selected before queueing");
    }
    if (priority < 1 || priority > 5) {
      throw new IllegalArgumentException("Repair priority must be between 1 and 5");
    }
    this.priority = priority;
  }

  /**
   * Stores the canonical selection for the full repair movement cycle: delivery into the repair
   * area and automatic removal after repair completion. Planning belongs only to the inbound leg.
   */
  public boolean selectMovementToRepair(
      boolean movementToRepair,
      RepairLogisticsPlanningMode mode,
      LocalDate scheduledDate) {
    if (executionState != RepairExecutionState.DRAFT) {
      throw new IllegalStateException(
          "Inbound movement can only be selected before repair queueing");
    }
    if (!movementToRepair && (mode != null || scheduledDate != null)) {
      throw new IllegalArgumentException(
          "Inbound logistics planning must be absent when movement to repair is disabled");
    }
    if (movementToRepair
        && (mode == null
            || (mode == RepairLogisticsPlanningMode.AUTO
                && scheduledDate != null)
            || (mode == RepairLogisticsPlanningMode.FIXED_DATE
                && scheduledDate == null))) {
      throw new IllegalArgumentException(
          "Inbound logistics planning mode and date are inconsistent");
    }
    boolean changed =
        this.movementToRepair != movementToRepair
            || logisticsPlanningMode != mode
            || !java.util.Objects.equals(
                logisticsScheduledDate, scheduledDate);
    this.movementToRepair = movementToRepair;
    logisticsPlanningMode = mode;
    logisticsScheduledDate = scheduledDate;
    if (changed) {
      updatedAt = MaintenanceTime.now();
    }
    return changed;
  }

  public boolean prepareWarehouseTransfer(
      UUID transferDocumentId, UUID transferLineId, UUID targetWarehouseId) {
    if (transferDocumentId == null || transferLineId == null || targetWarehouseId == null) {
      throw new IllegalArgumentException("Transfer coordinates are required");
    }
    if (warehouseId.equals(targetWarehouseId)) {
      throw new IllegalArgumentException("Transfer target warehouse must be different");
    }
    if ("DEPARTURE_PREPARED".equals(transferState)) {
      if (!transferDocumentId.equals(this.transferDocumentId)
          || !transferLineId.equals(this.transferLineId)
          || !targetWarehouseId.equals(this.transferTargetWarehouseId)) {
        throw new IllegalStateException(
            "Repair is already prepared for another warehouse transfer");
      }
      return false;
    }
    if (!"NONE".equals(transferState)) {
      throw new IllegalStateException("Repair cannot be transferred in its current state");
    }
    transferState = "DEPARTURE_PREPARED";
    this.transferDocumentId = transferDocumentId;
    this.transferLineId = transferLineId;
    transferTargetWarehouseId = targetWarehouseId;
    updatedAt = MaintenanceTime.now();
    return true;
  }

  public void completeWarehouseTransfer(
      UUID transferDocumentId,
      UUID transferLineId,
      UUID targetWarehouseId,
      int priority) {
    if (!"DEPARTURE_PREPARED".equals(transferState)
        || !transferDocumentId.equals(this.transferDocumentId)
        || !transferLineId.equals(this.transferLineId)
        || !targetWarehouseId.equals(transferTargetWarehouseId)) {
      throw new IllegalStateException(
          "Repair was not prepared for this warehouse transfer");
    }
    if (priority < 1 || priority > 5) {
      throw new IllegalArgumentException("Repair priority must be between 1 and 5");
    }
    warehouseId = targetWarehouseId;
    this.priority = priority;
    transferState = "NONE";
    this.transferDocumentId = null;
    this.transferLineId = null;
    transferTargetWarehouseId = null;
    updatedAt = MaintenanceTime.now();
  }

  public void markTaskRelocated(long taskVersion) {
    if (taskVersion < 0) {
      throw new IllegalArgumentException("Task-board version is invalid");
    }
    taskBoardVersion = taskVersion;
    updatedAt = MaintenanceTime.now();
  }

  public void adoptLeaseAfterTransfer(
      UUID leaseId,
      long leaseVersion,
      long fencingToken,
      OffsetDateTime leaseExpiresAt) {
    if (leaseId == null
        || leaseVersion < 0
        || fencingToken < 1
        || leaseExpiresAt == null) {
      throw new IllegalArgumentException("Arrival lease snapshot is invalid");
    }
    this.leaseId = leaseId;
    this.leaseVersion = leaseVersion;
    this.fencingToken = fencingToken;
    this.leaseExpiresAt = leaseExpiresAt;
    leaseReconciliationState = "ACTIVE";
    reconciliationState = "RECONCILED";
    updatedAt = MaintenanceTime.now();
  }

  public void replaceCoverMediaId(UUID coverMediaId) {
    if (executionState != RepairExecutionState.DRAFT
        && executionState != RepairExecutionState.QUEUED) {
      throw new IllegalStateException("Cover photo can only be changed before repair work starts");
    }
    this.coverMediaId = coverMediaId;
    updatedAt = MaintenanceTime.now();
  }

  /**
   * Applies a newer task-board schedule fact to the repair that registered that external task.
   * Source and rework links deliberately do not share this schedule: each repair owns its own
   * task-board task.
   */
  public boolean synchronizeTaskBoardSchedule(LocalDate scheduledDate, long taskBoardVersion) {
    if (scheduledDate == null || taskBoardVersion < 0) {
      throw new IllegalArgumentException("Task-board schedule fact is invalid");
    }
    if (executionState != RepairExecutionState.QUEUED) {
      return false;
    }
    if (this.taskBoardVersion != null && taskBoardVersion <= this.taskBoardVersion) {
      return false;
    }
    dispatchDate = scheduledDate;
    this.taskBoardVersion = taskBoardVersion;
    updatedAt = MaintenanceTime.now();
    return true;
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

  public void markTaskDeliveryFailed(boolean quarantined) {
    deliveryAttempts = Math.addExact(deliveryAttempts, 1);
    reconciliationState = "RECONCILIATION_REQUIRED";
    deliveryState = quarantined ? "QUARANTINED" : "RETRY_PENDING";
    taskGenerationState = quarantined ? "FAILED" : "PENDING_GENERATION";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  /**
   * Restores only an ordinary queued inbound delivery after its exact durable logistics intent
   * has been reviewed and resumed. This intentionally does not share the draft-only movement
   * selector: recovery must not broaden ordinary plan editing after queueing.
   */
  public void retryQuarantinedInboundDelivery(
      RepairLogisticsPlanningMode mode, LocalDate scheduledDate) {
    if (kind != RepairKind.PRIMARY
        || executionState != RepairExecutionState.QUEUED
        || acceptanceState != RepairAcceptanceState.NOT_READY
        || reclassificationState != RepairReclassificationState.STABLE) {
      throw new IllegalStateException(
          "Only an ordinary queued repair can retry quarantined inbound delivery");
    }
    if (!movementToRepair) {
      throw new IllegalStateException("Repair does not require inbound movement to repair");
    }
    if (!"QUARANTINED".equals(deliveryState)
        || !"RECONCILIATION_REQUIRED".equals(reconciliationState)
        || !"FAILED".equals(taskGenerationState)) {
      throw new IllegalStateException("Inbound delivery is not quarantined for recovery");
    }
    if (mode == null
        || (mode == RepairLogisticsPlanningMode.AUTO && scheduledDate != null)
        || (mode == RepairLogisticsPlanningMode.FIXED_DATE && scheduledDate == null)) {
      throw new IllegalArgumentException("Inbound logistics planning mode and date are inconsistent");
    }
    logisticsPlanningMode = mode;
    logisticsScheduledDate = scheduledDate;
    reconciliationState = "RECONCILIATION_REQUIRED";
    deliveryState = "RETRY_PENDING";
    taskGenerationState = "PENDING_GENERATION";
    deliveryUpdatedAt = MaintenanceTime.now();
    updatedAt = deliveryUpdatedAt;
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

  /** Cancels a never-queued inventory repair before it owns any external effect. */
  public void supersedeForInventoryPublication() {
    if (executionState != RepairExecutionState.DRAFT) {
      throw new IllegalStateException("Only an unstarted draft repair can be superseded");
    }
    executionState = RepairExecutionState.CANCELLED;
    acceptanceState = RepairAcceptanceState.NOT_READY;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    reconciliationState = "RECONCILED";
    deliveryUpdatedAt = MaintenanceTime.now();
    updatedAt = deliveryUpdatedAt;
  }

  /**
   * Cancels an ordinary queued inventory repair only after the durable coordinator has proved
   * both its driver movement and its task-board route were cancelled while pre-start.
   */
  public void supersedeQueuedForInventoryPublication() {
    if (executionState != RepairExecutionState.QUEUED
        || acceptanceState != RepairAcceptanceState.NOT_READY
        || reclassificationState != RepairReclassificationState.STABLE) {
      throw new IllegalStateException(
          "Only an ordinary queued repair can be superseded after pre-start compensation");
    }
    executionState = RepairExecutionState.CANCELLED;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    reconciliationState = "RECONCILED";
    deliveryUpdatedAt = MaintenanceTime.now();
    updatedAt = deliveryUpdatedAt;
  }

  /**
   * Cancels an external-capital handoff only after logistics has atomically cancelled its pending
   * capital movement. A completed/started capital movement is intentionally not eligible here.
   */
  public void supersedeExternalCapitalForInventoryPublication() {
    if (executionState != RepairExecutionState.COMPLETED
        || acceptanceState != RepairAcceptanceState.PENDING
        || reclassificationState != RepairReclassificationState.EXTERNAL_CAPITAL) {
      throw new IllegalStateException(
          "Only a pending external-capital handoff can be superseded after compensation");
    }
    executionState = RepairExecutionState.CANCELLED;
    acceptanceState = RepairAcceptanceState.NOT_READY;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    reconciliationState = "RECONCILED";
    deliveryUpdatedAt = MaintenanceTime.now();
    updatedAt = deliveryUpdatedAt;
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
      throw new IllegalStateException("Repair plan cannot be amended after repair start");
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

  /**
   * Adopts a newly acquired fence after the previous operation lease expired.
   *
   * <p>This is intentionally distinct from renewal: a replacement lease has a new identity and
   * fencing token. It may only replace a complete local lease snapshot; callers must validate the
   * remote owner and rental-item identity before invoking it.
   */
  public void replaceExpiredLease(
      UUID leaseId, long leaseVersion, long fencingToken, OffsetDateTime expiresAt) {
    if (this.leaseId == null
        || this.leaseVersion == null
        || this.fencingToken == null
        || this.leaseExpiresAt == null) {
      throw new IllegalStateException("An existing lease snapshot is required for replacement");
    }
    if (leaseId == null || leaseVersion < 0 || fencingToken < 1 || expiresAt == null) {
      throw new IllegalArgumentException("Replacement lease snapshot is invalid");
    }
    this.leaseId = leaseId;
    this.leaseVersion = leaseVersion;
    this.fencingToken = fencingToken;
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
  public FurnitureAccountingMode getFurnitureAccountingMode() { return furnitureAccountingMode; }
  public LocalDate getDispatchDate() { return dispatchDate; }
  public int getPriority() { return priority; }
  public String getSourceParty() { return sourceParty; }
  public UUID getCoverMediaId() { return coverMediaId; }
  public boolean isMovementToRepair() { return movementToRepair; }
  public RepairLogisticsPlanningMode getLogisticsPlanningMode() {
    return logisticsPlanningMode;
  }
  public LocalDate getLogisticsScheduledDate() {
    return logisticsScheduledDate;
  }
  public String getTransferState() { return transferState; }
  public UUID getTransferDocumentId() { return transferDocumentId; }
  public UUID getTransferLineId() { return transferLineId; }
  public UUID getTransferTargetWarehouseId() { return transferTargetWarehouseId; }
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
  public RepairReclassificationState getReclassificationState() { return reclassificationState; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
