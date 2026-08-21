package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA stage of a repair plan, including its ordered execution state. */
@Entity
@Table(name = "repair_stage")
public class RepairStage {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "row_id", nullable = false)
  private UUID rowId;

  @Column(name = "stage_id", nullable = false)
  private UUID id;

  @Column(name = "repair_id", nullable = false)
  private UUID repairId;

  @Column(name = "stage_no", nullable = false)
  private int stageNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "stage_kind", nullable = false, length = 32)
  private RepairStageKind stageKind;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private RepairStageState state;

  @Column(name = "routing_queue_id", nullable = false)
  private UUID routingQueueId;

  @Column(name = "routing_queue_name", nullable = false, length = 255)
  private String routingQueueName;

  @Column(name = "routing_queue_type", nullable = false, length = 64)
  private String routingQueueType;

  @Column(name = "work_lines", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String workLines;

  @Column(name = "material_lines", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String materialLines;

  @Column(name = "primary_line_id")
  private UUID primaryLineId;

  @Column(name = "group_comment", nullable = false, length = 2000)
  private String groupComment;

  /** Task-board owns this ID; it stays null until registration truth is confirmed. */
  @Column(name = "external_queue_entry_id")
  private UUID externalQueueEntryId;

  @Column(name = "task_board_version")
  private Long taskBoardVersion;

  @Column(name = "task_generation_state", nullable = false, length = 32)
  private String taskGenerationState;

  @Column(name = "delivery_state", nullable = false, length = 24)
  private String deliveryState;

  @Column(name = "delivery_attempts", nullable = false)
  private int deliveryAttempts;

  @Column(name = "delivery_updated_at", nullable = false)
  private OffsetDateTime deliveryUpdatedAt;

  @Column(name = "task_deadline")
  private OffsetDateTime taskDeadline;

  @Column(name = "completed_event_id")
  private UUID completedEventId;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  protected RepairStage() {}

  public RepairStage(
      UUID id,
      UUID repairId,
      int stageNo,
      RepairStageKind stageKind,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      OffsetDateTime taskDeadline) {
    this(
        id,
        repairId,
        stageNo,
        stageKind,
        routingQueueId,
        routingQueueName,
        routingQueueType,
        "[]",
        "[]",
        null,
        "",
        taskDeadline);
  }

  public RepairStage(
      UUID id,
      UUID repairId,
      int stageNo,
      RepairStageKind stageKind,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      String workLines,
      String materialLines,
      UUID primaryLineId,
      String groupComment,
      OffsetDateTime taskDeadline) {
    if (id == null || repairId == null || stageNo < 0 || stageKind != RepairStageKind.REPAIR_WORK || routingQueueId == null
        || routingQueueName == null || routingQueueName.isBlank()
        || routingQueueType == null || routingQueueType.isBlank()
        || workLines == null || materialLines == null || groupComment == null
        || groupComment.length() > 2000) {
      throw new IllegalArgumentException("Repair stage identity is invalid");
    }
    this.id = id;
    this.repairId = repairId;
    this.stageNo = stageNo;
    this.stageKind = stageKind;
    this.state = RepairStageState.PLANNED;
    this.routingQueueId = routingQueueId;
    this.routingQueueName = routingQueueName.trim();
    this.routingQueueType = routingQueueType.trim();
    this.workLines = workLines;
    this.materialLines = materialLines;
    this.primaryLineId = primaryLineId;
    this.groupComment = groupComment;
    this.taskDeadline = MaintenanceTime.postgresPrecision(taskDeadline);
    this.taskGenerationState = "PENDING_GENERATION";
    this.deliveryState = "PENDING";
    this.deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void queued() {
    if (state != RepairStageState.PLANNED) throw new IllegalStateException("Only a planned stage can be queued");
    state = RepairStageState.QUEUED;
    deliveryState = "RETRY_PENDING";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void completeAsExternalCapital() {
    if (state == RepairStageState.DONE && "NOT_REQUIRED".equals(taskGenerationState)) {
      return;
    }
    if (state != RepairStageState.PLANNED
        && state != RepairStageState.QUEUED
        && state != RepairStageState.IN_PROGRESS) {
      throw new IllegalStateException(
          "Only an active repair stage can move to external capital execution");
    }
    state = RepairStageState.DONE;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    deliveryUpdatedAt = MaintenanceTime.now();
    completedAt = MaintenanceTime.now();
  }

  /**
   * Routes an inventory stage to capital repair without fabricating local work completion.
   *
   * <p>Capital execution has no task-board entry, but publishing an inventory result is not proof
   * that the external work finished. The stage therefore remains active and cannot feed repair
   * acceptance.
   */
  public void routeToExternalCapital() {
    if (state == RepairStageState.QUEUED && "NOT_REQUIRED".equals(taskGenerationState)) {
      return;
    }
    if (state != RepairStageState.PLANNED
        && state != RepairStageState.QUEUED
        && state != RepairStageState.IN_PROGRESS
        && !(state == RepairStageState.DONE && "NOT_REQUIRED".equals(taskGenerationState))) {
      throw new IllegalStateException(
          "Only an active repair stage can be routed to external capital execution");
    }
    state = RepairStageState.QUEUED;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    deliveryUpdatedAt = MaintenanceTime.now();
    completedAt = null;
  }

  public void confirmTaskBoardRegistration(
      UUID taskBoardEntryId, long entryVersion) {
    if (taskBoardEntryId == null || entryVersion < 0) {
      throw new IllegalArgumentException("Task-board registration identity is invalid");
    }
    if (externalQueueEntryId != null && !externalQueueEntryId.equals(taskBoardEntryId)) {
      throw new IllegalStateException("Task-board entry mapping cannot be replaced");
    }
    externalQueueEntryId = taskBoardEntryId;
    taskBoardVersion = entryVersion;
    taskGenerationState = "GENERATED";
    deliveryState = "DELIVERED";
    deliveryAttempts = Math.addExact(deliveryAttempts, 1);
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  /**
   * Rebinds this stage to task-board truth after a source-owned pre-start replacement succeeded.
   *
   * <p>A changed task-board response proves that no route entry or assignment started before it
   * replaced the complete route; an exact replay may return that already replaced route later.
   * Only an already confirmed, still queued local stage may therefore exchange its external entry
   * identity. Ordinary registration keeps the stronger immutable-mapping rule.
   */
  public void confirmPreStartTaskBoardReplacement(
      UUID taskBoardEntryId, long entryVersion) {
    if (taskBoardEntryId == null || entryVersion < 0) {
      throw new IllegalArgumentException("Task-board registration identity is invalid");
    }
    if (state != RepairStageState.QUEUED
        || externalQueueEntryId == null
        || taskBoardVersion == null
        || !"GENERATED".equals(taskGenerationState)) {
      throw new IllegalStateException(
          "Only a queued stage with a confirmed mapping can accept a pre-start replacement");
    }
    externalQueueEntryId = taskBoardEntryId;
    taskBoardVersion = entryVersion;
    deliveryState = "DELIVERED";
    deliveryAttempts = Math.addExact(deliveryAttempts, 1);
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void markTaskDeliveryFailed(boolean quarantined) {
    deliveryAttempts = Math.addExact(deliveryAttempts, 1);
    deliveryState = quarantined ? "QUARANTINED" : "RETRY_PENDING";
    taskGenerationState = quarantined ? "FAILED" : "PENDING_GENERATION";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  /** Returns true when this queued stage still mirrors a confirmed task-board entry. */
  public boolean hasConfirmedQueuedTaskMapping() {
    return state == RepairStageState.QUEUED
        && externalQueueEntryId != null
        && taskBoardVersion != null;
  }

  /**
   * Clears only the cancelled task-board mapping while retaining the frozen stage plan and state.
   */
  public void resetCancelledInventoryTaskMapping() {
    if (!hasConfirmedQueuedTaskMapping()) {
      throw new IllegalStateException(
          "Only a queued stage with confirmed task-board mapping can be reset");
    }
    externalQueueEntryId = null;
    taskBoardVersion = null;
    taskGenerationState = "PENDING_GENERATION";
    deliveryState = "RETRY_PENDING";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public void started(long taskBoardVersion) {
    if (state != RepairStageState.QUEUED) throw new IllegalStateException("Only a queued stage can start");
    this.taskBoardVersion = taskBoardVersion;
    state = RepairStageState.IN_PROGRESS;
  }

  public void completed(UUID eventId, long taskBoardVersion, OffsetDateTime occurredAt) {
    if (state != RepairStageState.QUEUED && state != RepairStageState.IN_PROGRESS) {
      throw new IllegalStateException("Stage is not executable");
    }
    if (eventId == null || taskBoardVersion < 0) throw new IllegalArgumentException("Completion fact is invalid");
    state = RepairStageState.DONE;
    this.taskBoardVersion = taskBoardVersion;
    this.completedEventId = eventId;
    this.completedAt = occurredAt == null
        ? MaintenanceTime.now() : MaintenanceTime.postgresPrecision(occurredAt);
  }

  public boolean cancelled(UUID eventId, long taskBoardVersion) {
    if (state == RepairStageState.CANCELLED) return false;
    if (state == RepairStageState.DONE) {
      throw new IllegalStateException("A completed repair stage cannot be cancelled");
    }
    if (eventId == null || taskBoardVersion < 0) {
      throw new IllegalArgumentException("Cancellation fact is invalid");
    }
    state = RepairStageState.CANCELLED;
    this.taskBoardVersion = taskBoardVersion;
    this.completedEventId = eventId;
    return true;
  }

  /** Local cancellation for a never-queued repair superseded before any task-board effect. */
  public void supersedeForInventoryPublication() {
    if (state != RepairStageState.PLANNED || externalQueueEntryId != null) {
      throw new IllegalStateException("Only an unqueued repair stage can be superseded");
    }
    state = RepairStageState.CANCELLED;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  /**
   * Local terminal mirror of task-board's atomic cancel-if-pre-start result. Task-board keeps
   * the external entry as audit truth; maintenance keeps the mapping but makes it ineligible for
   * every local reconciliation worker.
   */
  public void supersedeQueuedForInventoryPublication() {
    if (state != RepairStageState.PLANNED && state != RepairStageState.QUEUED) {
      throw new IllegalStateException("Only a pre-start repair stage can be superseded");
    }
    state = RepairStageState.CANCELLED;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  /** Cancels a queued external-capital stage after remote guards prove work has not started. */
  public void supersedeExternalCapitalForInventoryPublication() {
    if (state != RepairStageState.QUEUED || !"NOT_REQUIRED".equals(taskGenerationState)) {
      throw new IllegalStateException(
          "Only an unaccepted external-capital stage can be superseded");
    }
    state = RepairStageState.CANCELLED;
    deliveryState = "DELIVERED";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  /**
   * Makes unfinished stage work historical after task-board's source-owned authoritative
   * cancellation. A DONE stage remains immutable completed evidence.
   */
  public void supersedeForAuthoritativeInventory() {
    if (state == RepairStageState.DONE || state == RepairStageState.CANCELLED) return;
    state = RepairStageState.CANCELLED;
    taskGenerationState = "NOT_REQUIRED";
    deliveryState = "DELIVERED";
    deliveryUpdatedAt = MaintenanceTime.now();
  }

  public UUID getId() { return id; }
  public UUID getRepairId() { return repairId; }
  public int getStageNo() { return stageNo; }
  public RepairStageKind getStageKind() { return stageKind; }
  public RepairStageState getState() { return state; }
  public UUID getRoutingQueueId() { return routingQueueId; }
  public String getRoutingQueueName() { return routingQueueName; }
  public String getRoutingQueueType() { return routingQueueType; }
  public String getWorkLines() { return workLines; }
  public String getMaterialLines() { return materialLines; }
  public UUID getPrimaryLineId() { return primaryLineId; }
  public String getGroupComment() { return groupComment; }
  public UUID getExternalQueueEntryId() { return externalQueueEntryId; }
  public Long getTaskBoardVersion() { return taskBoardVersion; }
  public String getTaskGenerationState() { return taskGenerationState; }
  public String getDeliveryState() { return deliveryState; }
  public int getDeliveryAttempts() { return deliveryAttempts; }
  public OffsetDateTime getDeliveryUpdatedAt() { return deliveryUpdatedAt; }
  public OffsetDateTime getTaskDeadline() { return taskDeadline; }
  public UUID getCompletedEventId() { return completedEventId; }
  public OffsetDateTime getCompletedAt() { return completedAt; }
}
