package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * Durable execution fence for one complete source-plan withdrawal. PREPARED blocks every task in
 * the lineage from being claimed until logistics either commits the agreed reschedule or proves
 * that its owner mutation never happened and releases the hold.
 */
@Entity
@Table(name = "planning_replan_hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanningReplanHold {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

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

  @Column(name = "removed_external_task_id", nullable = false)
  private UUID removedExternalTaskId;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
  private String requestJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private PlanningReplanHoldState state;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "commit_response_json", columnDefinition = "jsonb")
  private String commitResponseJson;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates a hold only after every task, route entry, and shift fence was locked and validated. */
  public static PlanningReplanHold prepare(
      UUID id,
      UUID idempotencyKey,
      UUID sourcePlanId,
      long expectedSourcePlanVersion,
      long replacementPlanVersion,
      UUID sourcePlanWarehouseId,
      LocalDate sourcePlanDate,
      UUID removedExternalTaskId,
      String requestSha256,
      String requestJson,
      OffsetDateTime now) {
    if (expectedSourcePlanVersion < 1 || replacementPlanVersion <= expectedSourcePlanVersion) {
      throw new IllegalArgumentException("Planning hold versions are invalid");
    }
    if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Planning hold fingerprint is invalid");
    }
    PlanningReplanHold hold = new PlanningReplanHold();
    hold.id = Objects.requireNonNull(id);
    hold.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    hold.sourcePlanId = Objects.requireNonNull(sourcePlanId);
    hold.expectedSourcePlanVersion = expectedSourcePlanVersion;
    hold.replacementPlanVersion = replacementPlanVersion;
    hold.sourcePlanWarehouseId = Objects.requireNonNull(sourcePlanWarehouseId);
    hold.sourcePlanDate = Objects.requireNonNull(sourcePlanDate);
    hold.removedExternalTaskId = Objects.requireNonNull(removedExternalTaskId);
    hold.requestSha256 = requestSha256;
    hold.requestJson = Objects.requireNonNull(requestJson);
    hold.state = PlanningReplanHoldState.PREPARED;
    hold.createdAt = Objects.requireNonNull(now);
    hold.updatedAt = now;
    return hold;
  }

  /** Marks the task-board batch complete and stores its exact replay response. */
  public void commit(String responseJson, OffsetDateTime now) {
    if (state == PlanningReplanHoldState.COMMITTED) return;
    if (state != PlanningReplanHoldState.PREPARED) {
      throw new IllegalStateException("Only a prepared planning hold can be committed");
    }
    commitResponseJson = Objects.requireNonNull(responseJson);
    state = PlanningReplanHoldState.COMMITTED;
    updatedAt = Objects.requireNonNull(now);
  }

  /** Releases only a hold whose logistics owner mutation was proven not to have committed. */
  public void release(OffsetDateTime now) {
    if (state == PlanningReplanHoldState.RELEASED) return;
    if (state != PlanningReplanHoldState.PREPARED) {
      throw new IllegalStateException("Committed planning hold cannot be released");
    }
    state = PlanningReplanHoldState.RELEASED;
    updatedAt = Objects.requireNonNull(now);
  }
}
