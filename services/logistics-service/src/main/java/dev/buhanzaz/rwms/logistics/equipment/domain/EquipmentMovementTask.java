package dev.buhanzaz.rwms.logistics.equipment.domain;

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
 * A single physical furniture movement. Source balances are reserved before a
 * board task is shown to a worker; asset balances change only after that task
 * has been completed.
 */
@Entity
@Table(
    name = "equipment_movement_task",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_equipment_movement_task_creator_key",
          columnNames = {"created_by_subject_id", "idempotency_key"}),
      @UniqueConstraint(
          name = "uk_equipment_movement_task_cancellation_key",
          columnNames = {"cancellation_requested_by_subject_id", "cancellation_idempotency_key"}),
      @UniqueConstraint(name = "uk_equipment_movement_task_external", columnNames = "external_task_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentMovementTask {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "external_task_id", nullable = false)
  private UUID externalTaskId;

  @Column(name = "task_board_task_id")
  private UUID taskBoardTaskId;

  @Column(name = "task_board_task_version")
  private Long taskBoardTaskVersion;

  @Column(name = "task_board_done_at")
  private OffsetDateTime taskBoardDoneAt;

  @Column(name = "task_board_cancelled", nullable = false)
  private boolean taskBoardCancelled;

  @Column(name = "unit_number", length = 64)
  private String unitNumber;

  @Column(name = "planned_duration_minutes", nullable = false)
  private Integer plannedDurationMinutes;

  @Column(name = "deadline_at", nullable = false)
  private OffsetDateTime deadlineAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private EquipmentMovementTaskState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "terminal_state", length = 32)
  private EquipmentMovementTaskState terminalState;

  @Column(name = "failure_code", length = 96)
  private String failureCode;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "cancellation_requested_by_subject_id")
  private UUID cancellationRequestedBySubjectId;

  @Column(name = "cancellation_idempotency_key")
  private UUID cancellationIdempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "cancellation_request_sha256", length = 64)
  private String cancellationRequestSha256;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "next_attempt_at")
  private OffsetDateTime nextAttemptAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static EquipmentMovementTask create(
      UUID warehouseId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    if (warehouseId == null
        || deadlineAt == null
        || createdBySubjectId == null
        || idempotencyKey == null) {
      throw new IllegalArgumentException("Equipment movement task ownership and deadline are required");
    }
    if (plannedDurationMinutes == null || plannedDurationMinutes < 1) {
      throw new IllegalArgumentException("plannedDurationMinutes is invalid");
    }
    EquipmentMovementTask task = new EquipmentMovementTask();
    task.warehouseId = warehouseId;
    task.externalTaskId = UUID.randomUUID();
    task.unitNumber = optionalText(unitNumber, 64, "unitNumber");
    task.plannedDurationMinutes = plannedDurationMinutes;
    task.deadlineAt = deadlineAt;
    task.state = EquipmentMovementTaskState.RESERVING;
    task.createdBySubjectId = createdBySubjectId;
    task.idempotencyKey = idempotencyKey;
    task.requestSha256 = requireHash(requestSha256);
    task.retryCount = 0;
    task.createdAt = now();
    task.updatedAt = task.createdAt;
    task.nextAttemptAt = task.createdAt;
    return task;
  }

  public boolean matchesRequest(String checksum) {
    return requestSha256.equals(checksum);
  }

  public boolean matchesCancellationRequest(
      UUID actorSubjectId, UUID nextCancellationIdempotencyKey, String checksum) {
    return cancellationRequestedBySubjectId != null
        && cancellationRequestedBySubjectId.equals(actorSubjectId)
        && cancellationIdempotencyKey != null
        && cancellationIdempotencyKey.equals(nextCancellationIdempotencyKey)
        && Objects.equals(cancellationRequestSha256, checksum);
  }

  public boolean isDue(OffsetDateTime at) {
    return nextAttemptAt == null || !nextAttemptAt.isAfter(at);
  }

  public boolean isExpired(OffsetDateTime at) {
    return !deadlineAt.isAfter(at);
  }

  public void reservationsComplete() {
    requireState(EquipmentMovementTaskState.RESERVING);
    state = EquipmentMovementTaskState.REGISTERING_TASK;
    scheduleImmediately();
  }

  public void registerBoardTask(UUID taskId, long taskVersion, String taskStatus, OffsetDateTime doneAt) {
    if (taskId == null || taskVersion < 0 || !"ACTIVE".equals(taskStatus)) {
      throw new IllegalArgumentException("Task-board registration result is invalid");
    }
    if (state != EquipmentMovementTaskState.REGISTERING_TASK
        && state != EquipmentMovementTaskState.AWAITING_WORKER) {
      throw new IllegalStateException("Movement task cannot register a board task in its current state");
    }
    if (taskBoardTaskId != null && !taskBoardTaskId.equals(taskId)) {
      throw new IllegalStateException("Movement task has a conflicting board task");
    }
    taskBoardTaskId = taskId;
    taskBoardTaskVersion = taskVersion;
    taskBoardDoneAt = doneAt;
    taskBoardCancelled = false;
    state = EquipmentMovementTaskState.AWAITING_WORKER;
    retryCount = 0;
    scheduleImmediately();
  }

  public void observeBoardTask(long taskVersion, String taskStatus, OffsetDateTime doneAt) {
    if (taskBoardTaskVersion == null || taskVersion < taskBoardTaskVersion || taskStatus == null) {
      throw new IllegalArgumentException("Task-board status result is invalid");
    }
    if (state != EquipmentMovementTaskState.AWAITING_WORKER) return;
    taskBoardTaskVersion = taskVersion;
    taskBoardDoneAt = doneAt;
    if ("DONE".equals(taskStatus)) {
      if (doneAt == null || !doneAt.isBefore(deadlineAt)) {
        throw new IllegalArgumentException("Completed board task is outside the movement deadline");
      }
      state = EquipmentMovementTaskState.EXECUTING;
      retryCount = 0;
      scheduleImmediately();
      return;
    }
    if ("CANCELLED".equals(taskStatus)) {
      taskBoardCancelled = true;
      beginCancellation(EquipmentMovementTaskState.CANCELLED, "TASK_BOARD_CANCELLED");
      return;
    }
    if (!"ACTIVE".equals(taskStatus)) {
      throw new IllegalArgumentException("Task-board movement task has an unsupported status");
    }
    scheduleAfterSeconds(2);
  }

  public void beginCancellation(EquipmentMovementTaskState targetState, String code) {
    if (targetState != EquipmentMovementTaskState.CANCELLED
        && targetState != EquipmentMovementTaskState.EXPIRED
        && targetState != EquipmentMovementTaskState.CONFLICT) {
      throw new IllegalArgumentException("Movement cancellation terminal state is invalid");
    }
    if (state.isTerminal()) return;
    if (state == EquipmentMovementTaskState.EXECUTING) {
      throw new IllegalStateException("An executing movement cannot be cancelled");
    }
    terminalState = targetState;
    failureCode = optionalText(code, 96, "failureCode");
    state = EquipmentMovementTaskState.CANCELLING;
    scheduleImmediately();
  }

  public void requestCancellation(
      UUID actorSubjectId,
      UUID nextCancellationIdempotencyKey,
      String checksum,
      EquipmentMovementTaskState targetState,
      String code) {
    if (actorSubjectId == null || nextCancellationIdempotencyKey == null) {
      throw new IllegalArgumentException("Movement cancellation actor and Idempotency-Key are required");
    }
    if (cancellationIdempotencyKey != null) {
      if (!nextCancellationIdempotencyKey.equals(cancellationIdempotencyKey)
          || !actorSubjectId.equals(cancellationRequestedBySubjectId)
          || !Objects.equals(checksum, cancellationRequestSha256)) {
        throw new IllegalStateException("Equipment movement task cancellation has already been requested");
      }
      return;
    }
    cancellationRequestedBySubjectId = actorSubjectId;
    cancellationIdempotencyKey = nextCancellationIdempotencyKey;
    cancellationRequestSha256 = requireHash(checksum);
    beginCancellation(targetState, code);
  }

  public void boardTaskCancelled(long taskVersion) {
    if (taskBoardTaskVersion == null || taskVersion < taskBoardTaskVersion) {
      throw new IllegalArgumentException("Task-board cancellation version is invalid");
    }
    if (state != EquipmentMovementTaskState.CANCELLING) return;
    taskBoardTaskVersion = taskVersion;
    taskBoardCancelled = true;
    touch();
  }

  public void complete() {
    requireState(EquipmentMovementTaskState.EXECUTING);
    state = EquipmentMovementTaskState.COMPLETED;
    retryCount = 0;
    nextAttemptAt = null;
    touch();
  }

  /** A known rejected atomic execute can still release its untouched reservations. */
  public void failExecution(String code) {
    requireState(EquipmentMovementTaskState.EXECUTING);
    terminalState = EquipmentMovementTaskState.CONFLICT;
    failureCode = optionalText(code, 96, "failureCode");
    state = EquipmentMovementTaskState.CANCELLING;
    scheduleImmediately();
  }

  public void finishCancellation() {
    requireState(EquipmentMovementTaskState.CANCELLING);
    state = Objects.requireNonNullElse(terminalState, EquipmentMovementTaskState.CANCELLED);
    retryCount = 0;
    nextAttemptAt = null;
    touch();
  }

  public void retryAfterSeconds(long seconds) {
    if (state.isTerminal()) return;
    retryCount = Math.addExact(retryCount, 1);
    OffsetDateTime candidate = now().plusSeconds(seconds);
    nextAttemptAt = candidate;
    touch();
  }

  public void requireReconciliation(String code) {
    if (state.isTerminal()) return;
    state = EquipmentMovementTaskState.RECONCILIATION_REQUIRED;
    failureCode = optionalText(code, 96, "failureCode");
    nextAttemptAt = null;
    touch();
  }

  public void scheduleImmediately() {
    nextAttemptAt = now();
    touch();
  }

  private void scheduleAfterSeconds(long seconds) {
    nextAttemptAt = now().plusSeconds(seconds);
    touch();
  }

  private void requireState(EquipmentMovementTaskState expected) {
    if (state != expected) {
      throw new IllegalStateException("Equipment movement task lifecycle transition is not allowed");
    }
  }

  private void touch() {
    OffsetDateTime candidate = now();
    updatedAt = candidate.isAfter(updatedAt) ? candidate : updatedAt.plusNanos(1_000);
  }

  private static String optionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximum) throw new IllegalArgumentException(field + " is invalid");
    return normalized;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
    return thisClass == otherClass && id != null && Objects.equals(id, ((EquipmentMovementTask) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
