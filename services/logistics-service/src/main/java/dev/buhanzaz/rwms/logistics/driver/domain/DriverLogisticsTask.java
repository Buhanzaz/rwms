package dev.buhanzaz.rwms.logistics.driver.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

/**
 * Logistics-owned intent and effect checkpoint for one physical movement.
 *
 * <p>Document tasks may retain several immutable cabin members while task order, worker
 * assignments, and execution status stay authoritative in task-board. A furniture-only transfer
 * deliberately retains no cabin identity and still uses this same task and relay lifecycle.
 */
@Entity
@Table(
    name = "driver_logistics_task",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_driver_logistics_task_external",
          columnNames = "external_task_id"),
      @UniqueConstraint(
          name = "uk_driver_logistics_task_creator_key",
          columnNames = {"created_by_subject_id", "idempotency_key"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriverLogisticsTask {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  /**
   * Primary cabin retained by every cabin movement. It is absent only for a document-owned
   * furniture-only transfer created through {@link #createFurnitureCargoTransfer}.
   */
  @Column(name = "cabin_id")
  private UUID cabinId;

  @Column(name = "repair_id")
  private UUID repairId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32)
  private DriverTaskSourceType sourceType;

  @Column(name = "source_id", nullable = false)
  private UUID sourceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_kind", nullable = false, length = 32)
  private DriverTaskKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "planning_mode", nullable = false, length = 16)
  private DriverTaskPlanningMode planningMode;

  @Column(name = "scheduled_date", nullable = false)
  private LocalDate scheduledDate;

  /**
   * Operator-selected earliest date for a fixed-date task. The mutable scheduled date mirrors the
   * task-board placement and may move later when earlier repair buckets are full.
   */
  @Column(name = "fixed_date_lower_bound")
  private LocalDate fixedDateLowerBound;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "movement_comment", length = 2000)
  private String comment;

  /**
   * Sanitized immutable native-task presentation snapshot. Task-board remains authoritative for
   * execution state, while this JSON makes initial registration and ambiguous retries deterministic.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "worker_content_json", nullable = false, columnDefinition = "jsonb")
  private String workerContentJson;

  /** Immutable client display snapshot retained when a grouped document has one. */
  @Column(name = "client_snapshot", length = 512)
  private String clientSnapshot;

  /** Stable ordinal of this trip inside its source rental order. */
  @Column(name = "trip_number")
  private Integer tripNumber;

  /**
   * Historical physical column retained for old rows; new date-only trips never read or write it.
   */
  @Getter(AccessLevel.NONE)
  @Column(name = "scheduled_time")
  private java.time.LocalTime legacyScheduledTime;

  @Column(name = "unit_number", nullable = false, length = 64)
  private String unitNumber;

  @Column(name = "driver_queue_definition_id", nullable = false)
  private UUID driverQueueDefinitionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "driver_audience_mode", nullable = false, length = 32)
  private DriverTaskAudienceMode driverAudienceMode;

  /** Opaque task-board worker identity, present only for an assigned-driver audience. */
  @Column(name = "planned_driver_worker_id")
  private UUID plannedDriverWorkerId;

  /** Immutable assigned-driver display fallback retained when the worker name later changes. */
  @Column(name = "planned_driver_name_snapshot", length = 512)
  private String plannedDriverNameSnapshot;

  /** Approximate ETA shown only while this task remains an unclaimed shared future preview. */
  @Column(name = "provisional_eta")
  private OffsetDateTime provisionalEta;

  @Column(name = "provisional_eta_source_plan_id")
  private UUID provisionalEtaSourcePlanId;

  @Column(name = "provisional_eta_source_plan_version")
  private Long provisionalEtaSourcePlanVersion;

  /** Stable standalone-plan lineage proving that this shipment may be revision-replaced. */
  @Column(name = "source_plan_id")
  private UUID sourcePlanId;

  @Column(name = "source_plan_version")
  private Long sourcePlanVersion;

  @Column(name = "source_plan_warehouse_id")
  private UUID sourcePlanWarehouseId;

  @Column(name = "source_plan_date")
  private LocalDate sourcePlanDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "planner_membership_state", nullable = false, length = 16)
  private DriverTaskPlannerMembershipState plannerMembershipState =
      DriverTaskPlannerMembershipState.ACTIVE;

  @Column(name = "planner_removed_at")
  private OffsetDateTime plannerRemovedAt;

  @Column(name = "planner_removed_source_plan_version")
  private Long plannerRemovedSourcePlanVersion;

  @Column(name = "external_task_id", nullable = false)
  private UUID externalTaskId;

  @Column(name = "task_board_task_id")
  private UUID taskBoardTaskId;

  @Column(name = "task_board_task_version")
  private Long taskBoardTaskVersion;

  @Column(name = "task_board_entry_id")
  private UUID taskBoardEntryId;

  @Column(name = "task_board_entry_status", length = 24)
  private String taskBoardEntryStatus;

  @Column(name = "task_board_done_at")
  private OffsetDateTime taskBoardDoneAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private DriverTaskState state;

  @Column(name = "repair_place_allocation_id")
  private UUID repairPlaceAllocationId;

  @Column(name = "repair_place_allocation_version")
  private Long repairPlaceAllocationVersion;

  @Column(name = "completion_evidence_id")
  private UUID completionEvidenceId;

  @Column(name = "completion_media_id")
  private UUID completionMediaId;

  @Column(name = "completion_media_generation")
  private Long completionMediaGeneration;

  @Column(name = "completion_entry_id")
  private UUID completionEntryId;

  @Column(name = "cover_applied", nullable = false)
  private boolean coverApplied;

  @Column(name = "repair_place_effect_applied", nullable = false)
  private boolean repairPlaceEffectApplied;

  /**
   * Legacy storage reused only as a durable marker while an already-scheduled repair delivery's
   * reservation release is awaiting confirmation. Its timestamp is never a scheduling deadline;
   * successful release clears it before immediate queue refill.
   */
  @Column(name = "manual_promotion_hold_until")
  private OffsetDateTime manualPromotionHoldUntil;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "next_attempt_at")
  private OffsetDateTime nextAttemptAt;

  @Column(name = "failure_code", length = 96)
  private String failureCode;

  @Column(name = "trip_expiry_requested_at")
  private OffsetDateTime tripExpiryRequestedAt;

  @Column(name = "trip_expiry_cargo_review_required", nullable = false)
  private boolean tripExpiryCargoReviewRequired;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "inventory_cancelled_by")
  private UUID inventoryCancelledBy;

  @Column(name = "inventory_cancelled_at")
  private OffsetDateTime inventoryCancelledAt;

  /**
   * Cabin members are present only for a document-owned cabin trip. Legacy document-line tasks and
   * an explicit furniture-only transfer intentionally retain no member rows.
   */
  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<DriverLogisticsTaskMember> members = new ArrayList<>();

  /**
   * Creates identity-free work when a source has no selected driver: document delivery kinds are
   * unassigned, while warehouse movements remain shared by all qualified drivers.
   */
  public static DriverLogisticsTask create(
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      DriverTaskSourceType sourceType,
      UUID sourceId,
      DriverTaskKind kind,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate,
      int priority,
      String comment,
      String unitNumber,
      UUID driverQueueDefinitionId,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    return create(
        warehouseId,
        cabinId,
        repairId,
        sourceType,
        sourceId,
        kind,
        planningMode,
        scheduledDate,
        priority,
        comment,
        unitNumber,
        driverQueueDefinitionId,
        kind.defaultAudienceMode(),
        null,
        null,
        createdBySubjectId,
        idempotencyKey,
        requestSha256);
  }

  /** Creates one durable driver intent with its planned WorkerApp audience frozen before relay. */
  public static DriverLogisticsTask create(
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      DriverTaskSourceType sourceType,
      UUID sourceId,
      DriverTaskKind kind,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate,
      int priority,
      String comment,
      String unitNumber,
      UUID driverQueueDefinitionId,
      DriverTaskAudienceMode driverAudienceMode,
      UUID plannedDriverWorkerId,
      String plannedDriverNameSnapshot,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    return createInternal(
        warehouseId,
        cabinId,
        repairId,
        sourceType,
        sourceId,
        kind,
        planningMode,
        scheduledDate,
        priority,
        comment,
        unitNumber,
        driverQueueDefinitionId,
        driverAudienceMode,
        plannedDriverWorkerId,
        plannedDriverNameSnapshot,
        createdBySubjectId,
        idempotencyKey,
        requestSha256,
        false);
  }

  /**
   * Creates the only driver-task form that deliberately has no primary cabin and no cabin members:
   * one fixed-date interwarehouse transfer whose physical cargo consists solely of loose furniture.
   * The caller supplies a stable catalog-derived cargo summary; no placeholder cabin identity is
   * invented to pass generic workflow infrastructure.
   */
  public static DriverLogisticsTask createFurnitureCargoTransfer(
      UUID warehouseId,
      UUID documentId,
      LocalDate scheduledDate,
      int technicalPriority,
      String taskText,
      String cargoSummary,
      UUID driverQueueDefinitionId,
      DriverTaskAudienceMode driverAudienceMode,
      UUID plannedDriverWorkerId,
      String plannedDriverNameSnapshot,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    DriverLogisticsTask task =
        createInternal(
            warehouseId,
            null,
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT,
            documentId,
            DriverTaskKind.TRANSFER,
            DriverTaskPlanningMode.FIXED_DATE,
            scheduledDate,
            technicalPriority,
            taskText,
            cargoSummary,
            driverQueueDefinitionId,
            driverAudienceMode,
            plannedDriverWorkerId,
            plannedDriverNameSnapshot,
            createdBySubjectId,
            idempotencyKey,
            requestSha256,
            true);
    task.tripNumber = 1;
    return task;
  }

  /**
   * Initializes the shared persisted workflow while keeping cabinless creation inaccessible to all
   * public generic factories. The boolean is an internal factory fence, not domain state.
   */
  private static DriverLogisticsTask createInternal(
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      DriverTaskSourceType sourceType,
      UUID sourceId,
      DriverTaskKind kind,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate,
      int priority,
      String comment,
      String unitNumber,
      UUID driverQueueDefinitionId,
      DriverTaskAudienceMode driverAudienceMode,
      UUID plannedDriverWorkerId,
      String plannedDriverNameSnapshot,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256,
      boolean allowFurnitureCargoWithoutCabin) {
    boolean furnitureCargoWithoutCabin =
        allowFurnitureCargoWithoutCabin
            && cabinId == null
            && repairId == null
            && sourceType == DriverTaskSourceType.LOGISTICS_DOCUMENT
            && kind == DriverTaskKind.TRANSFER;
    if (warehouseId == null
        || (!furnitureCargoWithoutCabin && cabinId == null)
        || sourceType == null
        || sourceId == null
        || kind == null
        || planningMode == null
        || scheduledDate == null
        || driverQueueDefinitionId == null
        || driverAudienceMode == null
        || createdBySubjectId == null
        || idempotencyKey == null) {
      throw new IllegalArgumentException("Driver task ownership and planning fields are required");
    }
    if (allowFurnitureCargoWithoutCabin && !furnitureCargoWithoutCabin) {
      throw new IllegalArgumentException(
          "Cabinless driver work is allowed only for a document furniture transfer");
    }
    if (priority < 1 || priority > 5) {
      throw new IllegalArgumentException("Driver task priority must be between 1 and 5");
    }
    if ((kind.consumesRepairPlace() || kind.releasesRepairPlace()) && repairId == null) {
      throw new IllegalArgumentException("Repair movement requires repairId");
    }
    if ((sourceType == DriverTaskSourceType.MANUAL) != (kind == DriverTaskKind.GENERAL_MOVEMENT)) {
      throw new IllegalArgumentException("Manual source requires a general movement task");
    }
    if (sourceType == DriverTaskSourceType.LOGISTICS_DOCUMENT
        && kind != DriverTaskKind.SHIPMENT
        && kind != DriverTaskKind.RETURN
        && kind != DriverTaskKind.TRANSFER) {
      throw new IllegalArgumentException("Document group source requires a document movement task");
    }
    DriverLogisticsTask task = new DriverLogisticsTask();
    task.warehouseId = warehouseId;
    task.cabinId = cabinId;
    task.repairId = repairId;
    task.sourceType = sourceType;
    task.sourceId = sourceId;
    task.kind = kind;
    task.planningMode = planningMode;
    task.scheduledDate = scheduledDate;
    task.fixedDateLowerBound =
        planningMode == DriverTaskPlanningMode.FIXED_DATE ? scheduledDate : null;
    task.priority = priority;
    task.comment = optionalComment(comment);
    task.workerContentJson = "{}";
    if (sourceType == DriverTaskSourceType.MANUAL && task.comment == null) {
      throw new IllegalArgumentException("Manual movement comment is required");
    }
    task.unitNumber = requiredText(unitNumber, 64, "unitNumber");
    task.driverQueueDefinitionId = driverQueueDefinitionId;
    task.applyAudience(driverAudienceMode, plannedDriverWorkerId, plannedDriverNameSnapshot);
    task.externalTaskId = UUID.randomUUID();
    task.state = DriverTaskState.REGISTERING;
    task.repairPlaceEffectApplied = !kind.consumesRepairPlace() && !kind.releasesRepairPlace();
    task.createdBySubjectId = createdBySubjectId;
    task.idempotencyKey = idempotencyKey;
    task.requestSha256 = requireHash(requestSha256);
    task.createdAt = now();
    task.updatedAt = task.createdAt;
    task.nextAttemptAt = task.createdAt;
    return task;
  }

  /**
   * Creates the document-owned task that represents every cabin in one newly created shipment. The
   * first member remains in {@code cabinId} for compatibility with existing generic task
   * infrastructure; all task-specific cabin truth lives in {@link #members}.
   */
  public static DriverLogisticsTask createGroupedShipment(
      UUID warehouseId,
      UUID primaryCabinId,
      UUID documentId,
      LocalDate scheduledDate,
      int technicalPriority,
      String taskText,
      String clientSnapshot,
      String unitSummary,
      UUID driverQueueDefinitionId,
      DriverTaskAudienceMode driverAudienceMode,
      UUID plannedDriverWorkerId,
      String plannedDriverNameSnapshot,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    return createGroupedShipment(
        warehouseId,
        primaryCabinId,
        documentId,
        scheduledDate,
        1,
        technicalPriority,
        taskText,
        clientSnapshot,
        unitSummary,
        driverQueueDefinitionId,
        driverAudienceMode,
        plannedDriverWorkerId,
        plannedDriverNameSnapshot,
        createdBySubjectId,
        idempotencyKey,
        requestSha256);
  }

  /** Creates a grouped shipment with its stable order trip number. */
  public static DriverLogisticsTask createGroupedShipment(
      UUID warehouseId,
      UUID primaryCabinId,
      UUID documentId,
      LocalDate scheduledDate,
      int tripNumber,
      int technicalPriority,
      String taskText,
      String clientSnapshot,
      String unitSummary,
      UUID driverQueueDefinitionId,
      DriverTaskAudienceMode driverAudienceMode,
      UUID plannedDriverWorkerId,
      String plannedDriverNameSnapshot,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    return createGroupedDocument(
        warehouseId,
        primaryCabinId,
        documentId,
        DriverTaskKind.SHIPMENT,
        scheduledDate,
        tripNumber,
        technicalPriority,
        taskText,
        clientSnapshot,
        unitSummary,
        driverQueueDefinitionId,
        driverAudienceMode,
        plannedDriverWorkerId,
        plannedDriverNameSnapshot,
        createdBySubjectId,
        idempotencyKey,
        requestSha256);
  }

  /** Creates one grouped document trip with a stable order ordinal. */
  public static DriverLogisticsTask createGroupedDocument(
      UUID warehouseId,
      UUID primaryCabinId,
      UUID documentId,
      DriverTaskKind kind,
      LocalDate scheduledDate,
      int tripNumber,
      int technicalPriority,
      String taskText,
      String clientSnapshot,
      String unitSummary,
      UUID driverQueueDefinitionId,
      DriverTaskAudienceMode driverAudienceMode,
      UUID plannedDriverWorkerId,
      String plannedDriverNameSnapshot,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    if (tripNumber < 1) {
      throw new IllegalArgumentException("tripNumber must be positive");
    }
    if (kind != DriverTaskKind.SHIPMENT
        && kind != DriverTaskKind.RETURN
        && kind != DriverTaskKind.TRANSFER) {
      throw new IllegalArgumentException("Grouped document kind is invalid");
    }
    DriverLogisticsTask task =
        create(
            warehouseId,
            primaryCabinId,
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT,
            documentId,
            kind,
            DriverTaskPlanningMode.FIXED_DATE,
            scheduledDate,
            technicalPriority,
            taskText,
            unitSummary,
            driverQueueDefinitionId,
            driverAudienceMode,
            plannedDriverWorkerId,
            plannedDriverNameSnapshot,
            createdBySubjectId,
            idempotencyKey,
            requestSha256);
    task.clientSnapshot = optionalText(clientSnapshot, 512);
    task.tripNumber = tripNumber;
    return task;
  }

  /** Adds one immutable document-line/cabin snapshot before the grouped task crosses the relay. */
  public void addGroupedDocumentMember(
      UUID documentLineId, UUID memberCabinId, String memberUnitNumber, int position) {
    if (!isGroupedDocument()
        || isFurnitureCargoTransfer()
        || state != DriverTaskState.REGISTERING) {
      throw new IllegalStateException("Document members can be added only to a new grouped task");
    }
    DriverLogisticsTaskMember member =
        DriverLogisticsTaskMember.create(
            this, documentLineId, memberCabinId, memberUnitNumber, position);
    if (members.stream()
        .anyMatch(
            existing ->
                existing.getDocumentLineId().equals(documentLineId)
                    || existing.getCabinId().equals(memberCabinId))) {
      throw new IllegalArgumentException("Grouped document members must be distinct");
    }
    members.add(member);
  }

  /**
   * Replaces one member snapshot only after the caller has proven task-board still reports a
   * waiting, unstarted entry.
   */
  public void replaceGroupedDocumentMember(
      UUID documentLineId,
      UUID expectedOldCabinId,
      UUID replacementCabinId,
      String replacementUnitNumber) {
    if (!isGroupedDocument()
        || (state != DriverTaskState.REGISTERING
            && state != DriverTaskState.SCHEDULED
            && state != DriverTaskState.CURRENT)) {
      throw new IllegalStateException("Only an unstarted grouped trip can replace a cabin");
    }
    DriverLogisticsTaskMember member =
        members.stream()
            .filter(value -> value.getDocumentLineId().equals(documentLineId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Grouped trip member is missing"));
    member.replaceCabin(expectedOldCabinId, replacementCabinId, replacementUnitNumber);
    if (cabinId.equals(expectedOldCabinId)) {
      cabinId = replacementCabinId;
    }
    touch();
  }

  /** Source-compatible grouped-shipment member command. */
  public void addGroupedShipmentMember(
      UUID documentLineId, UUID memberCabinId, String memberUnitNumber, int position) {
    addGroupedDocumentMember(documentLineId, memberCabinId, memberUnitNumber, position);
  }

  /**
   * Returns whether this is one document-owned driver trip. Cabin members are immutable when
   * present; the furniture-only transfer variant deliberately has none.
   */
  public boolean isGroupedDocument() {
    return sourceType == DriverTaskSourceType.LOGISTICS_DOCUMENT
        && (kind == DriverTaskKind.SHIPMENT
            || kind == DriverTaskKind.RETURN
            || kind == DriverTaskKind.TRANSFER);
  }

  /** Returns whether this is the explicitly cabinless, furniture-only transfer task form. */
  public boolean isFurnitureCargoTransfer() {
    return sourceType == DriverTaskSourceType.LOGISTICS_DOCUMENT
        && kind == DriverTaskKind.TRANSFER
        && cabinId == null
        && repairId == null
        && members.isEmpty();
  }

  /** Returns whether this grouped document is specifically a shipment. */
  public boolean isGroupedShipment() {
    return isGroupedDocument() && kind == DriverTaskKind.SHIPMENT;
  }

  /**
   * Records the cover checkpoint for one grouped shipment cabin. The parent cover checkpoint is
   * completed only after every immutable member accepted the same evidence item.
   */
  public void markGroupedShipmentMemberCoverApplied(UUID cabinId, UUID mediaId, UUID entryId) {
    if (!isGroupedDocument()
        || state != DriverTaskState.FINALIZING
        || completionMediaId == null
        || completionEntryId == null
        || !completionMediaId.equals(mediaId)
        || !completionEntryId.equals(entryId)) {
      throw new IllegalStateException("Grouped shipment completion evidence is invalid");
    }
    DriverLogisticsTaskMember member =
        members.stream()
            .filter(value -> value.getCabinId().equals(cabinId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Cabin does not belong to the grouped shipment task"));
    member.markCoverApplied(mediaId, entryId);
    if (members.isEmpty()) {
      throw new IllegalStateException("Grouped shipment task has no cabin members");
    }
    if (members.stream().allMatch(DriverLogisticsTaskMember::isCoverApplied)) {
      markAllGroupedShipmentCoversApplied();
    }
  }

  /** Returns the next cabin whose completion cover effect has not yet been confirmed. */
  public DriverLogisticsTaskMember nextUncoveredGroupedShipmentMember() {
    if (!isGroupedDocument()) {
      throw new IllegalStateException("Only grouped document tasks have member cover checkpoints");
    }
    if (members.isEmpty()) {
      throw new IllegalStateException("Grouped shipment task has no cabin members");
    }
    return members.stream().filter(member -> !member.isCoverApplied()).findFirst().orElse(null);
  }

  /**
   * Replans an unstarted document movement and refreshes its idempotency fingerprint. Task-board
   * performs the authoritative started-state check before this method is used for registered work.
   */
  public void replanBeforeStart(
      LocalDate date,
      DriverTaskAudienceMode audienceMode,
      UUID workerId,
      String workerName,
      String requestHash) {
    if (tripExpiryRequestedAt != null) {
      throw new IllegalStateException("Expiring driver work cannot be replanned");
    }
    if (state != DriverTaskState.REGISTERING
        && state != DriverTaskState.SCHEDULED
        && state != DriverTaskState.CURRENT) {
      throw new IllegalStateException("Only unstarted driver work can be replanned");
    }
    scheduledDate = Objects.requireNonNull(date, "date");
    fixedDateLowerBound = scheduledDate;
    planningMode = DriverTaskPlanningMode.FIXED_DATE;
    clearProvisionalEta();
    applyAudience(audienceMode, workerId, workerName);
    requestSha256 = requireHash(requestHash);
    scheduleImmediately();
  }

  /**
   * Replaces the worker-visible snapshot only while the route is still locally considered
   * unstarted. Registered work must first pass task-board's version-fenced pre-start update.
   */
  public void captureWorkerContent(String canonicalJson) {
    if (state != DriverTaskState.REGISTERING
        && state != DriverTaskState.SCHEDULED
        && state != DriverTaskState.CURRENT) {
      throw new IllegalStateException("Only unstarted driver work can change worker content");
    }
    if (canonicalJson == null || canonicalJson.isBlank()) {
      throw new IllegalArgumentException("Worker content JSON is required");
    }
    workerContentJson = canonicalJson;
    touch();
  }

  /** Identifies unregistered capital work rejected before task-board accepted its snapshot. */
  public boolean canRecoverRejectedCapitalRegistration() {
    return kind == DriverTaskKind.CAPITAL_TO_PRODUCTION
        && state == DriverTaskState.RECONCILIATION_REQUIRED
        && taskBoardTaskId == null
        && "TASK_BOARD_DEPENDENCY_PERMANENT_REJECTION".equals(failureCode);
  }

  /**
   * Retries the same external identity after the workflow repairs proven duplicate photo ownership.
   * Registered tasks and other reconciliation causes cannot reopen through this transition.
   */
  public void recoverRejectedCapitalRegistration(String canonicalJson) {
    if (!canRecoverRejectedCapitalRegistration()) {
      throw new IllegalStateException("Only rejected unregistered capital work can be recovered");
    }
    if (canonicalJson == null || canonicalJson.isBlank()) {
      throw new IllegalArgumentException("Worker content JSON is required");
    }
    workerContentJson = canonicalJson;
    state = DriverTaskState.REGISTERING;
    resumeImmediatelyAfterConfirmation();
  }

  /** Applies the authoritative audience echoed by task-board without changing execution state. */
  public void observeAudience(
      DriverTaskAudienceMode audienceMode, UUID workerId, String workerName) {
    applyAudience(audienceMode, workerId, workerName);
    touch();
  }

  /**
   * Attaches the immutable planner lineage before relay registration. Existing tasks without this
   * proof remain legacy and cannot later enter the atomic replacement flow.
   */
  public void bindPlannerLineage(
      UUID planId, long planVersion, UUID rootWarehouseId, LocalDate planDate) {
    if (sourceType != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || kind != DriverTaskKind.SHIPMENT
        || planVersion < 1
        || !scheduledDate.equals(planDate)) {
      throw new IllegalStateException("Planner lineage requires a dated shipment task");
    }
    UUID requiredPlanId = Objects.requireNonNull(planId, "sourcePlanId");
    UUID requiredWarehouseId = Objects.requireNonNull(rootWarehouseId, "sourcePlanWarehouseId");
    LocalDate requiredDate = Objects.requireNonNull(planDate, "sourcePlanDate");
    if (sourcePlanId != null
        && (!sourcePlanId.equals(requiredPlanId)
            || !Objects.equals(sourcePlanVersion, planVersion)
            || !Objects.equals(sourcePlanWarehouseId, requiredWarehouseId)
            || !Objects.equals(sourcePlanDate, requiredDate))) {
      throw new IllegalStateException("Driver task already belongs to another planner lineage");
    }
    if (sourcePlanId != null) return;
    sourcePlanId = requiredPlanId;
    sourcePlanVersion = planVersion;
    sourcePlanWarehouseId = requiredWarehouseId;
    sourcePlanDate = requiredDate;
    touch();
  }

  /** Applies one pre-start revision and authoritative task-board projection under exact fences. */
  public void applyPlannerReplacement(
      long expectedTaskVersion,
      UUID planId,
      long expectedPlanVersion,
      long replacementPlanVersion,
      DriverTaskAudienceMode audienceMode,
      UUID workerId,
      String workerName,
      OffsetDateTime eta,
      long observedTaskBoardVersion,
      UUID observedEntryId) {
    if (version != expectedTaskVersion
        || state != DriverTaskState.SCHEDULED
        || plannerMembershipState != DriverTaskPlannerMembershipState.ACTIVE
        || sourcePlanId == null
        || !sourcePlanId.equals(planId)
        || sourcePlanVersion == null
        || sourcePlanVersion != expectedPlanVersion
        || replacementPlanVersion <= expectedPlanVersion
        || taskBoardTaskId == null
        || taskBoardEntryId == null
        || !taskBoardEntryId.equals(observedEntryId)
        || taskBoardTaskVersion == null
        || observedTaskBoardVersion < taskBoardTaskVersion
        || !"WAITING".equals(taskBoardEntryStatus)) {
      throw new IllegalStateException("Planner replacement task fence changed");
    }
    clearProvisionalEta();
    applyAudience(audienceMode, workerId, workerName);
    if (eta != null) setProvisionalEta(eta, planId, replacementPlanVersion);
    sourcePlanVersion = replacementPlanVersion;
    taskBoardTaskVersion = observedTaskBoardVersion;
    touch();
  }

  /**
   * Retains the original lineage as a terminal tombstone after the task-board owner removed this
   * pre-start assignment in a strictly newer revision.
   */
  public void removeFromPlannerLineage(
      UUID planId,
      long expectedPlanVersion,
      long replacementPlanVersion,
      OffsetDateTime removedAt) {
    if (state != DriverTaskState.CANCELLED
        || plannerMembershipState != DriverTaskPlannerMembershipState.ACTIVE
        || sourcePlanId == null
        || !sourcePlanId.equals(planId)
        || sourcePlanVersion == null
        || sourcePlanVersion != expectedPlanVersion
        || replacementPlanVersion <= expectedPlanVersion) {
      throw new IllegalStateException("Only a cancelled current planner member can be tombstoned");
    }
    plannerMembershipState = DriverTaskPlannerMembershipState.REMOVED;
    plannerRemovedAt = Objects.requireNonNull(removedAt, "plannerRemovedAt");
    plannerRemovedSourcePlanVersion = replacementPlanVersion;
    clearProvisionalEta();
    touch();
  }

  /** Returns the revision that made this assignment current or removed it from current membership. */
  public Long getPlannerVisibleSourcePlanVersion() {
    return plannerRemovedSourcePlanVersion == null
        ? sourcePlanVersion
        : plannerRemovedSourcePlanVersion;
  }

  /** Stores a versioned approximate ETA for an unclaimed shared future task. */
  public void setProvisionalEta(
      OffsetDateTime eta, UUID sourcePlanId, long sourcePlanVersion) {
    if (driverAudienceMode != DriverTaskAudienceMode.WAREHOUSE_DRIVERS
        || (state != DriverTaskState.REGISTERING && state != DriverTaskState.SCHEDULED)
        || sourcePlanVersion < 1) {
      throw new IllegalStateException("Provisional ETA requires unclaimed shared future work");
    }
    OffsetDateTime normalizedEta =
        Objects.requireNonNull(eta, "provisionalEta").truncatedTo(ChronoUnit.MICROS);
    UUID requiredPlanId = Objects.requireNonNull(sourcePlanId, "sourcePlanId");
    if (Objects.equals(provisionalEtaSourcePlanId, requiredPlanId)
        && provisionalEtaSourcePlanVersion != null
        && sourcePlanVersion < provisionalEtaSourcePlanVersion) {
      throw new IllegalStateException("Provisional ETA plan version moved backwards");
    }
    if (Objects.equals(provisionalEta, normalizedEta)
        && Objects.equals(provisionalEtaSourcePlanId, requiredPlanId)
        && Objects.equals(provisionalEtaSourcePlanVersion, sourcePlanVersion)) {
      return;
    }
    provisionalEta = normalizedEta;
    provisionalEtaSourcePlanId = requiredPlanId;
    provisionalEtaSourcePlanVersion = sourcePlanVersion;
    touch();
  }

  /** Replaces or clears a preview only under the current local aggregate fence. */
  public void replaceProvisionalEta(
      long expectedVersion, OffsetDateTime eta, UUID sourcePlanId, long sourcePlanVersion) {
    if (version != expectedVersion) {
      throw new IllegalStateException("Driver task version changed");
    }
    if (eta == null) {
      clearProvisionalEta();
      touch();
      return;
    }
    setProvisionalEta(eta, sourcePlanId, sourcePlanVersion);
  }

  public boolean matchesRequest(String checksum) {
    return requestSha256.equals(checksum);
  }

  public boolean isDue(OffsetDateTime value) {
    return nextAttemptAt == null || !nextAttemptAt.isAfter(value);
  }

  /**
   * Cancels an intent before it has crossed the logistics/task-board boundary.
   *
   * <p>No row is deleted: the terminal checkpoint prevents the processor from registering the
   * external task on a later retry and preserves the request history for maintenance recovery.
   */
  public void cancelBeforeExternalRegistration() {
    if (state != DriverTaskState.REGISTERING || taskBoardTaskId != null) {
      throw new IllegalStateException(
          "Only an unregistered driver task can be cancelled before registration");
    }
    state = DriverTaskState.CANCELLED;
    clearProvisionalEta();
    manualPromotionHoldUntil = null;
    clearRetryFailure();
    nextAttemptAt = null;
    touch();
  }

  /** A transport date is a deadline, unlike the rolling maintenance queue. */
  public boolean isOverdueTrip(LocalDate warehouseToday) {
    return (kind == DriverTaskKind.SHIPMENT
            || kind == DriverTaskKind.RETURN
            || kind == DriverTaskKind.TRANSFER)
        && scheduledDate != null
        && scheduledDate.isBefore(warehouseToday);
  }

  /** Persists cancellation intent before task-board I/O; no cabin or money transition occurs. */
  public void requestTripExpiry(LocalDate warehouseToday, OffsetDateTime timestamp) {
    if (!isOverdueTrip(warehouseToday)
        || state.isTerminal()
        || state == DriverTaskState.FINALIZING
        || tripExpiryRequestedAt != null) return;
    tripExpiryRequestedAt = Objects.requireNonNull(timestamp);
    tripExpiryCargoReviewRequired = state == DriverTaskState.CURRENT;
    nextAttemptAt = timestamp;
    touch();
  }

  /** Started cargo remains reserved until an explicit return or agreed redirection is recorded. */
  public void observeExpiredTripCargo(String entryStatus) {
    if (tripExpiryRequestedAt == null) throw new IllegalStateException("No trip expiry intent");
    if (!"WAITING".equals(entryStatus) && !tripExpiryCargoReviewRequired) {
      tripExpiryCargoReviewRequired = true;
      touch();
    }
  }

  /** A newer authoritative pre-start date invalidates an expiry based on a stale projection. */
  public void withdrawStaleTripExpiry() {
    if (state == DriverTaskState.CANCELLED || state == DriverTaskState.COMPLETED) return;
    tripExpiryRequestedAt = null;
    tripExpiryCargoReviewRequired = false;
    touch();
  }

  /**
   * Records a task-board-confirmed pre-start cancellation after logistics has released a reserved
   * inbound repair place with its durable compensation key.
   *
   * <p>The caller must never invoke this after an ambiguous or started task-board outcome. A
   * reconciliation checkpoint is accepted solely so a lost remote response can be recovered by a
   * later {@code ALREADY_CANCELLED} guard result.
   */
  public void cancelAfterPreStartCancellation() {
    boolean cancellableState =
        state == DriverTaskState.SCHEDULED
            || state == DriverTaskState.RECONCILIATION_REQUIRED
            || (state == DriverTaskState.CURRENT
                && (sourceType == DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE
                    || sourceType == DriverTaskSourceType.LOGISTICS_DOCUMENT));
    if (!cancellableState || taskBoardTaskId == null) {
      throw new IllegalStateException(
          "Only a registered pre-start driver task can be cancelled after guard confirmation");
    }
    state = DriverTaskState.CANCELLED;
    clearProvisionalEta();
    manualPromotionHoldUntil = null;
    if (kind == DriverTaskKind.DELIVER_TO_REPAIR) {
      repairPlaceAllocationId = null;
      repairPlaceAllocationVersion = null;
    }
    clearRetryFailure();
    nextAttemptAt = null;
    touch();
  }

  /**
   * Records task-board's authoritative broad cancellation for completed inventory. Unlike ordinary
   * maintenance compensation, this transition may follow STARTED work; completed work remains
   * immutable and must never call this method.
   */
  public void cancelForCompletedInventory(UUID inventoryId, Long observedTaskVersion) {
    if (inventoryId == null || state == DriverTaskState.COMPLETED) {
      throw new IllegalStateException("Only unfinished driver work can be inventory-cancelled");
    }
    if (observedTaskVersion != null) {
      if (observedTaskVersion < 0
          || (taskBoardTaskVersion != null && observedTaskVersion < taskBoardTaskVersion)) {
        throw new IllegalArgumentException("Task-board version moved backwards");
      }
      taskBoardTaskVersion = observedTaskVersion;
    }
    inventoryCancelledBy = inventoryId;
    inventoryCancelledAt = now();
    state = DriverTaskState.CANCELLED;
    clearProvisionalEta();
    manualPromotionHoldUntil = null;
    repairPlaceAllocationId = null;
    repairPlaceAllocationVersion = null;
    clearRetryFailure();
    nextAttemptAt = null;
    touch();
  }

  public void registerBoardTask(
      UUID taskId,
      long taskVersion,
      UUID entryId,
      String entryStatus,
      String lane,
      OffsetDateTime doneAt) {
    requireBoardSnapshot(taskId, taskVersion, entryId, entryStatus, lane);
    if (taskBoardTaskId != null && !taskBoardTaskId.equals(taskId)) {
      throw new IllegalStateException("Driver task has a conflicting task-board task");
    }
    taskBoardTaskId = taskId;
    taskBoardTaskVersion = taskVersion;
    taskBoardEntryId = entryId;
    taskBoardEntryStatus = entryStatus;
    taskBoardDoneAt = doneAt;
    observeLaneAndStatus(lane, entryStatus, doneAt);
  }

  public void observeBoardTask(
      UUID taskId,
      long taskVersion,
      UUID entryId,
      String entryStatus,
      LocalDate observedScheduledDate,
      String lane,
      String taskStatus,
      OffsetDateTime doneAt) {
    requireBoardSnapshot(taskId, taskVersion, entryId, entryStatus, lane);
    if (observedScheduledDate == null) {
      throw new IllegalArgumentException("Task-board returned no scheduled date");
    }
    if (taskBoardTaskId == null
        || !taskBoardTaskId.equals(taskId)
        || !taskBoardEntryId.equals(entryId)
        || taskBoardTaskVersion == null
        || taskVersion < taskBoardTaskVersion) {
      throw new IllegalArgumentException("Task-board returned a mismatched driver task");
    }
    taskBoardTaskVersion = taskVersion;
    taskBoardEntryStatus = entryStatus;
    taskBoardDoneAt = doneAt;
    scheduledDate = observedScheduledDate;
    if ("CANCELLED".equals(taskStatus)) {
      state = DriverTaskState.CANCELLED;
      clearProvisionalEta();
      clearRetryFailure();
      if (tripExpiryRequestedAt != null) {
        failureCode =
            tripExpiryCargoReviewRequired ? "TRIP_DAY_EXPIRED_CARGO_REVIEW" : "TRIP_DAY_EXPIRED";
      }
      nextAttemptAt = null;
      touch();
      return;
    }
    if (!"ACTIVE".equals(taskStatus) && !"DONE".equals(taskStatus)) {
      throw new IllegalArgumentException("Unsupported task-board driver task status");
    }
    observeLaneAndStatus(lane, entryStatus, doneAt);
  }

  public void reserveRepairPlace(UUID allocationId, long allocationVersion) {
    if (!kind.consumesRepairPlace() || allocationId == null || allocationVersion < 0) {
      throw new IllegalArgumentException("Invalid repair-place reservation");
    }
    if (repairPlaceAllocationId != null && !repairPlaceAllocationId.equals(allocationId)) {
      throw new IllegalStateException("Driver task has a conflicting repair-place allocation");
    }
    repairPlaceAllocationId = allocationId;
    repairPlaceAllocationVersion = allocationVersion;
    resumeImmediatelyAfterConfirmation();
  }

  public void bindRemovalRepairPlace(UUID allocationId, long allocationVersion) {
    if (!kind.releasesRepairPlace() || allocationId == null || allocationVersion < 0) {
      throw new IllegalArgumentException("Invalid ready repair-place allocation");
    }
    if (repairPlaceAllocationId != null && !repairPlaceAllocationId.equals(allocationId)) {
      throw new IllegalStateException("Driver task has a conflicting repair-place allocation");
    }
    repairPlaceAllocationId = allocationId;
    repairPlaceAllocationVersion = allocationVersion;
    resumeImmediatelyAfterConfirmation();
  }

  public void moveToCurrent(long taskVersion, UUID entryId, String entryStatus) {
    if (state != DriverTaskState.SCHEDULED) {
      throw new IllegalStateException("Only a scheduled driver task can become current");
    }
    if (taskVersion < 0 || entryId == null || entryStatus == null) {
      throw new IllegalArgumentException("Current task-board snapshot is invalid");
    }
    taskBoardTaskVersion = taskVersion;
    taskBoardEntryId = entryId;
    taskBoardEntryStatus = entryStatus;
    state = DriverTaskState.CURRENT;
    clearProvisionalEta();
    manualPromotionHoldUntil = null;
    resumeAfterSeconds(1);
  }

  public boolean hasPendingRepairPlaceRelease() {
    return manualPromotionHoldUntil != null;
  }

  public void markRepairPlaceReleasePending() {
    if (state != DriverTaskState.SCHEDULED
        || !kind.consumesRepairPlace()
        || repairPlaceAllocationId == null
        || repairPlaceAllocationVersion == null) {
      throw new IllegalStateException(
          "Only a scheduled repair delivery can release reserved capacity");
    }
    manualPromotionHoldUntil = now();
    scheduleImmediately();
  }

  public void markFixedDate(LocalDate date) {
    if (state != DriverTaskState.SCHEDULED || date == null) {
      throw new IllegalStateException("Only a scheduled task can receive a fixed date");
    }
    planningMode = DriverTaskPlanningMode.FIXED_DATE;
    scheduledDate = date;
    fixedDateLowerBound = date;
    touch();
  }

  public void releaseRepairPlaceReservation(UUID allocationId, long allocationVersion) {
    if (state != DriverTaskState.SCHEDULED
        || manualPromotionHoldUntil == null
        || !kind.consumesRepairPlace()
        || repairPlaceAllocationId == null
        || !repairPlaceAllocationId.equals(allocationId)
        || allocationVersion < repairPlaceAllocationVersion) {
      throw new IllegalStateException("Pending repair-place release does not match the task");
    }
    repairPlaceAllocationId = null;
    repairPlaceAllocationVersion = null;
    manualPromotionHoldUntil = null;
    resumeImmediatelyAfterConfirmation();
  }

  /**
   * Freezes task-board completion evidence before any service-owned completion effects run. A
   * furniture-only transfer marks its otherwise inapplicable cabin-cover effect complete here,
   * while every cabin task still requires the existing per-cabin cover command.
   */
  public void captureEvidence(UUID evidenceId, UUID mediaId, long mediaGeneration, UUID entryId) {
    if (state != DriverTaskState.FINALIZING
        || evidenceId == null
        || mediaId == null
        || mediaGeneration < 1
        || entryId == null
        || !entryId.equals(taskBoardEntryId)) {
      throw new IllegalArgumentException("Driver completion evidence is invalid");
    }
    if (completionEvidenceId != null
        && (!completionEvidenceId.equals(evidenceId)
            || !completionMediaId.equals(mediaId)
            || completionMediaGeneration != mediaGeneration
            || !completionEntryId.equals(entryId))) {
      throw new IllegalStateException("Driver completion evidence cannot be replaced");
    }
    completionEvidenceId = evidenceId;
    completionMediaId = mediaId;
    completionMediaGeneration = mediaGeneration;
    completionEntryId = entryId;
    if (isFurnitureCargoTransfer()) {
      // Completion evidence belongs to the same task-board workflow, but there is no cabin cover
      // effect to apply. Recording this checkpoint prevents a fabricated asset operation.
      coverApplied = true;
    }
    resumeImmediatelyAfterConfirmation();
  }

  public void markCoverApplied() {
    if (isGroupedDocument()) {
      throw new IllegalStateException(
          "Grouped shipment covers must be confirmed for every cabin member");
    }
    if (state != DriverTaskState.FINALIZING || completionMediaId == null) {
      throw new IllegalStateException("Completion evidence must be captured before setting cover");
    }
    coverApplied = true;
    resumeImmediatelyAfterConfirmation();
  }

  public void markRepairPlaceEffect(UUID allocationId, long allocationVersion) {
    if (state != DriverTaskState.FINALIZING
        || repairPlaceEffectApplied
        || allocationId == null
        || allocationVersion < 0) {
      throw new IllegalStateException("Repair-place completion effect is invalid");
    }
    // The inbound transition response from maintenance is authoritative. It can replace the
    // prior RESERVED checkpoint with a reassigned OCCUPIED allocation, and compensation must
    // later return that exact identity/version for maintenance's next fenced command.
    if (!kind.consumesRepairPlace()
        && repairPlaceAllocationId != null
        && !repairPlaceAllocationId.equals(allocationId)) {
      throw new IllegalStateException("Repair-place allocation changed");
    }
    repairPlaceAllocationId = allocationId;
    repairPlaceAllocationVersion = allocationVersion;
    repairPlaceEffectApplied = true;
    resumeImmediatelyAfterConfirmation();
  }

  /**
   * Completes only after every applicable effect is durable. Cabin trips require their cover
   * checkpoints; a furniture-only transfer has no cabin member and therefore completes from its
   * frozen task-board evidence and the already-satisfied repair-place checkpoint.
   */
  public void complete() {
    if (state != DriverTaskState.FINALIZING
        || !coverApplied
        || !repairPlaceEffectApplied
        || (isGroupedDocument()
            && !isFurnitureCargoTransfer()
            && (members.isEmpty()
                || members.stream().anyMatch(member -> !member.isCoverApplied())))) {
      throw new IllegalStateException("Driver task completion effects are incomplete");
    }
    state = DriverTaskState.COMPLETED;
    completedAt = now();
    clearRetryFailure();
    nextAttemptAt = null;
    touch();
  }

  /**
   * Schedules a retry without letting a long-lived dependency outage overflow the persisted retry
   * counter. The caller owns the bounded delay policy; this aggregate protects the durable fact.
   */
  public void retryAfterSeconds(long seconds, String code, int retryCountCeiling) {
    if (state.isTerminal()) return;
    if (retryCountCeiling < 0) {
      throw new IllegalArgumentException("retryCountCeiling must not be negative");
    }
    int normalizedRetryCount = Math.min(Math.max(retryCount, 0), retryCountCeiling);
    retryCount =
        normalizedRetryCount < retryCountCeiling ? normalizedRetryCount + 1 : retryCountCeiling;
    failureCode = optionalText(code, 96);
    nextAttemptAt = now().plusSeconds(Math.max(1, seconds));
    touch();
  }

  public void requireReconciliation(String code) {
    if (state.isTerminal()) return;
    state = DriverTaskState.RECONCILIATION_REQUIRED;
    failureCode = optionalText(code, 96);
    nextAttemptAt = null;
    touch();
  }

  private void observeLaneAndStatus(String lane, String entryStatus, OffsetDateTime doneAt) {
    if ("DONE".equals(entryStatus)) {
      if (doneAt == null) {
        throw new IllegalArgumentException("Completed driver task has no completion time");
      }
      state = DriverTaskState.FINALIZING;
      clearProvisionalEta();
      resumeImmediatelyAfterConfirmation();
      return;
    }
    if (state == DriverTaskState.FINALIZING || state == DriverTaskState.COMPLETED) {
      return;
    }
    state = "CURRENT".equals(lane) ? DriverTaskState.CURRENT : DriverTaskState.SCHEDULED;
    if (state == DriverTaskState.CURRENT) {
      clearProvisionalEta();
      manualPromotionHoldUntil = null;
    }
    resumeAfterSeconds(1);
  }

  private static void requireBoardSnapshot(
      UUID taskId, long taskVersion, UUID entryId, String entryStatus, String lane) {
    if (taskId == null
        || taskVersion < 0
        || entryId == null
        || entryStatus == null
        || (!"SCHEDULED".equals(lane) && !"CURRENT".equals(lane))) {
      throw new IllegalArgumentException("Task-board driver task snapshot is invalid");
    }
  }

  private void scheduleImmediately() {
    nextAttemptAt = now();
    updatedAt = nextAttemptAt;
  }

  private void resumeImmediatelyAfterConfirmation() {
    clearRetryFailure();
    scheduleImmediately();
  }

  private void resumeAfterSeconds(long seconds) {
    clearRetryFailure();
    scheduleAfterSeconds(seconds);
  }

  private void clearRetryFailure() {
    retryCount = 0;
    failureCode = null;
  }

  private void scheduleAfterSeconds(long seconds) {
    nextAttemptAt = now().plusSeconds(seconds);
    updatedAt = now();
  }

  private void touch() {
    updatedAt = now();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static String requiredText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String optionalText(String value, int maximum) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    return normalized.substring(0, Math.min(normalized.length(), maximum));
  }

  private static String optionalComment(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > 2_000) {
      throw new IllegalArgumentException("comment is invalid");
    }
    return normalized;
  }

  private void markAllGroupedShipmentCoversApplied() {
    if (!isGroupedDocument()
        || members.isEmpty()
        || members.stream().anyMatch(member -> !member.isCoverApplied())) {
      throw new IllegalStateException("Grouped shipment covers are incomplete");
    }
    coverApplied = true;
    resumeImmediatelyAfterConfirmation();
  }

  private void applyAudience(
      DriverTaskAudienceMode audienceMode, UUID workerId, String workerName) {
    DriverTaskAudienceMode requiredMode =
        Objects.requireNonNull(audienceMode, "driverAudienceMode");
    String normalizedName =
        workerName == null ? null : requiredText(workerName, 512, "plannedDriverNameSnapshot");
    if (requiredMode == DriverTaskAudienceMode.UNASSIGNED
        && (workerId != null || normalizedName != null)) {
      throw new IllegalArgumentException("Unassigned driver work cannot retain a worker identity");
    }
    if (requiredMode == DriverTaskAudienceMode.ASSIGNED_DRIVER
        && (workerId == null || normalizedName == null)) {
      throw new IllegalArgumentException("Assigned driver work requires worker identity and name");
    }
    if (requiredMode == DriverTaskAudienceMode.WAREHOUSE_DRIVERS
        && (workerId != null || normalizedName != null)) {
      throw new IllegalArgumentException("Shared driver work cannot retain a worker identity");
    }
    driverAudienceMode = requiredMode;
    plannedDriverWorkerId = workerId;
    plannedDriverNameSnapshot = normalizedName;
    if (requiredMode != DriverTaskAudienceMode.WAREHOUSE_DRIVERS) clearProvisionalEta();
  }

  private void clearProvisionalEta() {
    provisionalEta = null;
    provisionalEtaSourcePlanId = null;
    provisionalEtaSourcePlanVersion = null;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
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
        && id != null
        && Objects.equals(id, ((DriverLogisticsTask) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
