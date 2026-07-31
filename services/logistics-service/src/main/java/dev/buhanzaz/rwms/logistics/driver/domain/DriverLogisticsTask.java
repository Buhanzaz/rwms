package dev.buhanzaz.rwms.logistics.driver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned intent and effect checkpoint for one physical cabin movement.
 *
 * <p>Task order, worker assignments and execution status stay authoritative in task-board.
 */
@Entity
@Table(
    name = "driver_logistics_task",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_driver_logistics_task_external",
          columnNames = "external_task_id"),
      @UniqueConstraint(
          name = "uk_driver_logistics_task_source_kind",
          columnNames = {"source_type", "source_id", "task_kind"}),
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

  @Column(name = "cabin_id", nullable = false)
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

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "unit_number", nullable = false, length = 64)
  private String unitNumber;

  @Column(name = "driver_queue_definition_id", nullable = false)
  private UUID driverQueueDefinitionId;

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

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

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
      String unitNumber,
      UUID driverQueueDefinitionId,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    if (warehouseId == null
        || cabinId == null
        || sourceType == null
        || sourceId == null
        || kind == null
        || planningMode == null
        || scheduledDate == null
        || driverQueueDefinitionId == null
        || createdBySubjectId == null
        || idempotencyKey == null) {
      throw new IllegalArgumentException("Driver task ownership and planning fields are required");
    }
    if (priority < 1 || priority > 5) {
      throw new IllegalArgumentException("Driver task priority must be between 1 and 5");
    }
    if ((kind.consumesRepairPlace() || kind.releasesRepairPlace()) && repairId == null) {
      throw new IllegalArgumentException("Repair movement requires repairId");
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
    task.priority = priority;
    task.unitNumber = requiredText(unitNumber, 64, "unitNumber");
    task.driverQueueDefinitionId = driverQueueDefinitionId;
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

  public boolean matchesRequest(String checksum) {
    return requestSha256.equals(checksum);
  }

  public boolean isDue(OffsetDateTime value) {
    return nextAttemptAt == null || !nextAttemptAt.isAfter(value);
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
    touch();
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
    touch();
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
    retryCount = 0;
    scheduleAfterSeconds(1);
  }

  public void captureEvidence(
      UUID evidenceId, UUID mediaId, long mediaGeneration, UUID entryId) {
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
    touch();
  }

  public void markCoverApplied() {
    if (state != DriverTaskState.FINALIZING || completionMediaId == null) {
      throw new IllegalStateException("Completion evidence must be captured before setting cover");
    }
    coverApplied = true;
    touch();
  }

  public void markRepairPlaceEffect(UUID allocationId, long allocationVersion) {
    if (state != DriverTaskState.FINALIZING
        || repairPlaceEffectApplied
        || allocationId == null
        || allocationVersion < 0) {
      throw new IllegalStateException("Repair-place completion effect is invalid");
    }
    if (repairPlaceAllocationId != null && !repairPlaceAllocationId.equals(allocationId)) {
      throw new IllegalStateException("Repair-place allocation changed");
    }
    repairPlaceAllocationId = allocationId;
    repairPlaceAllocationVersion = allocationVersion;
    repairPlaceEffectApplied = true;
    touch();
  }

  public void complete() {
    if (state != DriverTaskState.FINALIZING || !coverApplied || !repairPlaceEffectApplied) {
      throw new IllegalStateException("Driver task completion effects are incomplete");
    }
    state = DriverTaskState.COMPLETED;
    completedAt = now();
    retryCount = 0;
    nextAttemptAt = null;
    touch();
  }

  public void retryAfterSeconds(long seconds, String code) {
    if (state.isTerminal()) return;
    retryCount = Math.addExact(retryCount, 1);
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

  private void observeLaneAndStatus(
      String lane, String entryStatus, OffsetDateTime doneAt) {
    if ("DONE".equals(entryStatus)) {
      if (doneAt == null) {
        throw new IllegalArgumentException("Completed driver task has no completion time");
      }
      state = DriverTaskState.FINALIZING;
      retryCount = 0;
      scheduleImmediately();
      return;
    }
    if (state == DriverTaskState.FINALIZING || state == DriverTaskState.COMPLETED) {
      return;
    }
    state = "CURRENT".equals(lane) ? DriverTaskState.CURRENT : DriverTaskState.SCHEDULED;
    retryCount = 0;
    scheduleAfterSeconds(1);
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
