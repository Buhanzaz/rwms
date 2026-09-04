package dev.buhanzaz.rwms.logistics.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Logistics-owned durable intent for removing one published pre-start customer commitment after a
 * reschedule or authoritative cancellation. Each transition is monotonic: after OWNER_COMMITTED
 * recovery may only finish task-board and local tombstones.
 */
@Entity
@Table(name = "planning_published_reschedule_saga")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanningPublishedRescheduleSaga {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "booking_id")
  private UUID bookingId;

  @Column(name = "customer_subject_id")
  private UUID customerSubjectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation", nullable = false, length = 24)
  private PlanningPublishedRecoveryOperation operation;

  @Column(name = "source_plan_id", nullable = false)
  private UUID sourcePlanId;

  @Column(name = "expected_source_plan_version", nullable = false)
  private long expectedSourcePlanVersion;

  @Column(name = "replacement_plan_version", nullable = false)
  private long replacementPlanVersion;

  @Column(name = "source_plan_warehouse_id", nullable = false)
  private UUID sourcePlanWarehouseId;

  @Column(name = "source_plan_date", nullable = false)
  private LocalDate sourcePlanDate;

  @Column(name = "removed_document_id", nullable = false)
  private UUID removedDocumentId;

  @Column(name = "removed_external_task_id", nullable = false)
  private UUID removedExternalTaskId;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
  private String requestJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private PlanningPublishedRescheduleSagaState state;

  @Column(name = "task_board_hold_id")
  private UUID taskBoardHoldId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "task_board_commit_json", columnDefinition = "jsonb")
  private String taskBoardCommitJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_json", columnDefinition = "jsonb")
  private String responseJson;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "last_error_code", length = 96)
  private String lastErrorCode;

  @Column(name = "last_error_message", length = 1_000)
  private String lastErrorMessage;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates an immutable command intent before the first remote PREPARE attempt. */
  public static PlanningPublishedRescheduleSaga create(
      UUID id,
      UUID orderId,
      UUID bookingId,
      UUID customerSubjectId,
      PlanningPublishedRecoveryOperation operation,
      UUID sourcePlanId,
      long expectedSourcePlanVersion,
      long replacementPlanVersion,
      UUID sourcePlanWarehouseId,
      LocalDate sourcePlanDate,
      UUID removedDocumentId,
      UUID removedExternalTaskId,
      String requestSha256,
      String requestJson,
      OffsetDateTime now) {
    if (expectedSourcePlanVersion < 1 || replacementPlanVersion <= expectedSourcePlanVersion) {
      throw new IllegalArgumentException("Published reschedule plan versions are invalid");
    }
    if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Published reschedule fingerprint is invalid");
    }
    PlanningPublishedRecoveryOperation requiredOperation = Objects.requireNonNull(operation);
    if (requiredOperation == PlanningPublishedRecoveryOperation.RESCHEDULE
        && (bookingId == null || customerSubjectId == null)) {
      throw new IllegalArgumentException("Published reschedule customer context is required");
    }
    PlanningPublishedRescheduleSaga saga = new PlanningPublishedRescheduleSaga();
    saga.id = Objects.requireNonNull(id);
    saga.orderId = Objects.requireNonNull(orderId);
    saga.bookingId = bookingId;
    saga.customerSubjectId = customerSubjectId;
    saga.operation = requiredOperation;
    saga.sourcePlanId = Objects.requireNonNull(sourcePlanId);
    saga.expectedSourcePlanVersion = expectedSourcePlanVersion;
    saga.replacementPlanVersion = replacementPlanVersion;
    saga.sourcePlanWarehouseId = Objects.requireNonNull(sourcePlanWarehouseId);
    saga.sourcePlanDate = Objects.requireNonNull(sourcePlanDate);
    saga.removedDocumentId = Objects.requireNonNull(removedDocumentId);
    saga.removedExternalTaskId = Objects.requireNonNull(removedExternalTaskId);
    saga.requestSha256 = requestSha256;
    saga.requestJson = Objects.requireNonNull(requestJson);
    saga.state = PlanningPublishedRescheduleSagaState.PENDING;
    saga.attemptCount = 0;
    saga.nextAttemptAt = Objects.requireNonNull(now);
    saga.createdAt = now;
    saga.updatedAt = now;
    return saga;
  }

  /** Records task-board's execution hold; exact replay keeps the same checkpoint. */
  public void prepared(UUID holdId, OffsetDateTime now) {
    if (state == PlanningPublishedRescheduleSagaState.PREPARED
        && Objects.equals(taskBoardHoldId, holdId)) return;
    if (state != PlanningPublishedRescheduleSagaState.PENDING) {
      throw new IllegalStateException("Only a pending reschedule can be prepared");
    }
    taskBoardHoldId = Objects.requireNonNull(holdId);
    transition(PlanningPublishedRescheduleSagaState.PREPARED, now);
  }

  /** Checkpoints the irreversible local slot/order/session transaction. */
  public void ownerCommitted(String ownerResponseJson, OffsetDateTime now) {
    String requiredResponse = Objects.requireNonNull(ownerResponseJson, "ownerResponseJson");
    if (state == PlanningPublishedRescheduleSagaState.OWNER_COMMITTED) {
      if (!Objects.equals(responseJson, requiredResponse)) {
        throw new IllegalStateException("Published reschedule owner receipt changed");
      }
      return;
    }
    if (state != PlanningPublishedRescheduleSagaState.PREPARED) {
      throw new IllegalStateException("Published reschedule owner commit is out of order");
    }
    responseJson = requiredResponse;
    transition(PlanningPublishedRescheduleSagaState.OWNER_COMMITTED, now);
  }

  /** Records task-board's authoritative tombstone response for exact local convergence. */
  public void boardCommitted(String responseJson, OffsetDateTime now) {
    if (state == PlanningPublishedRescheduleSagaState.BOARD_COMMITTED) return;
    if (state != PlanningPublishedRescheduleSagaState.OWNER_COMMITTED) {
      throw new IllegalStateException("Published reschedule board commit is out of order");
    }
    taskBoardCommitJson = Objects.requireNonNull(responseJson);
    transition(PlanningPublishedRescheduleSagaState.BOARD_COMMITTED, now);
  }

  /** Completes only after local task/document tombstones have converged with task-board. */
  public void complete(String resultJson, OffsetDateTime now) {
    if (state == PlanningPublishedRescheduleSagaState.COMPLETE) return;
    if (state != PlanningPublishedRescheduleSagaState.BOARD_COMMITTED) {
      throw new IllegalStateException("Published reschedule completion is out of order");
    }
    responseJson = Objects.requireNonNull(resultJson);
    transition(PlanningPublishedRescheduleSagaState.COMPLETE, now);
  }

  /** Requests hold release only while the customer commitment is still unchanged. */
  public void releasePending(String code, String message, OffsetDateTime now) {
    if (state == PlanningPublishedRescheduleSagaState.RELEASE_PENDING) return;
    if (state != PlanningPublishedRescheduleSagaState.PREPARED) {
      throw new IllegalStateException("A committed customer reschedule cannot be released");
    }
    lastErrorCode = bounded(code, 96);
    lastErrorMessage = bounded(message, 1_000);
    transition(PlanningPublishedRescheduleSagaState.RELEASE_PENDING, now);
  }

  /** Marks a proven pre-owner hold release terminal. */
  public void released(OffsetDateTime now) {
    if (state == PlanningPublishedRescheduleSagaState.RELEASED) return;
    if (state != PlanningPublishedRescheduleSagaState.RELEASE_PENDING) {
      throw new IllegalStateException("Published reschedule release is out of order");
    }
    transition(PlanningPublishedRescheduleSagaState.RELEASED, now);
  }

  /** Schedules bounded recovery without changing the monotonic business checkpoint. */
  public void retry(String code, String message, OffsetDateTime retryAt, OffsetDateTime now) {
    if (state == PlanningPublishedRescheduleSagaState.COMPLETE
        || state == PlanningPublishedRescheduleSagaState.RELEASED
        || state == PlanningPublishedRescheduleSagaState.QUARANTINED) return;
    attemptCount = Math.addExact(attemptCount, 1);
    lastErrorCode = bounded(code, 96);
    lastErrorMessage = bounded(message, 1_000);
    nextAttemptAt = Objects.requireNonNull(retryAt);
    updatedAt = Objects.requireNonNull(now);
  }

  /** Stops automatic attempts after the bounded retry budget while preserving all receipts. */
  public void quarantine(String code, String message, OffsetDateTime now) {
    if (state == PlanningPublishedRescheduleSagaState.COMPLETE
        || state == PlanningPublishedRescheduleSagaState.RELEASED) return;
    lastErrorCode = bounded(code, 96);
    lastErrorMessage = bounded(message, 1_000);
    state = PlanningPublishedRescheduleSagaState.QUARANTINED;
    nextAttemptAt = Objects.requireNonNull(now);
    updatedAt = now;
  }

  private void transition(PlanningPublishedRescheduleSagaState next, OffsetDateTime now) {
    state = Objects.requireNonNull(next);
    nextAttemptAt = Objects.requireNonNull(now);
    lastErrorCode = null;
    lastErrorMessage = null;
    updatedAt = now;
  }

  private static String bounded(String value, int max) {
    String normalized = value == null ? "UNKNOWN" : value.trim();
    if (normalized.isEmpty()) normalized = "UNKNOWN";
    return normalized.length() <= max ? normalized : normalized.substring(0, max);
  }
}
