package dev.buhanzaz.rwms.logistics.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Immutable logistics-side replay receipt for one converged remote and local plan replacement. */
@Entity
@Table(name = "planning_assignment_replacement_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanningAssignmentReplacementReceipt {
  @Id
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

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_json", nullable = false, columnDefinition = "jsonb")
  private String responseJson;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** Creates the receipt only after the task-board response has converged locally. */
  public static PlanningAssignmentReplacementReceipt create(
      UUID idempotencyKey,
      UUID sourcePlanId,
      long expectedSourcePlanVersion,
      long replacementPlanVersion,
      UUID sourcePlanWarehouseId,
      LocalDate sourcePlanDate,
      String requestSha256,
      String responseJson,
      OffsetDateTime createdAt) {
    if (expectedSourcePlanVersion < 1 || replacementPlanVersion <= expectedSourcePlanVersion) {
      throw new IllegalArgumentException("Planner replacement versions are invalid");
    }
    if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Planner replacement fingerprint is invalid");
    }
    PlanningAssignmentReplacementReceipt receipt = new PlanningAssignmentReplacementReceipt();
    receipt.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    receipt.sourcePlanId = Objects.requireNonNull(sourcePlanId);
    receipt.expectedSourcePlanVersion = expectedSourcePlanVersion;
    receipt.replacementPlanVersion = replacementPlanVersion;
    receipt.sourcePlanWarehouseId = Objects.requireNonNull(sourcePlanWarehouseId);
    receipt.sourcePlanDate = Objects.requireNonNull(sourcePlanDate);
    receipt.requestSha256 = requestSha256;
    receipt.responseJson = Objects.requireNonNull(responseJson);
    receipt.createdAt = Objects.requireNonNull(createdAt);
    return receipt;
  }
}
