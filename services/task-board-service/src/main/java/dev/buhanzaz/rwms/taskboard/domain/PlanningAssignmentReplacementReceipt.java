package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable replay receipt for one all-or-nothing planner revision replacement. */
@Entity
@Table(name = "planning_assignment_replacement_receipt")
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

  protected PlanningAssignmentReplacementReceipt() {}

  /** Creates the immutable successful owner receipt after every batch mutation has succeeded. */
  public PlanningAssignmentReplacementReceipt(
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
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    this.sourcePlanId = Objects.requireNonNull(sourcePlanId);
    this.expectedSourcePlanVersion = expectedSourcePlanVersion;
    this.replacementPlanVersion = replacementPlanVersion;
    this.sourcePlanWarehouseId = Objects.requireNonNull(sourcePlanWarehouseId);
    this.sourcePlanDate = Objects.requireNonNull(sourcePlanDate);
    this.requestSha256 = requestSha256;
    this.responseJson = Objects.requireNonNull(responseJson);
    this.createdAt = Objects.requireNonNull(createdAt);
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getSourcePlanId() {
    return sourcePlanId;
  }

  public long getExpectedSourcePlanVersion() {
    return expectedSourcePlanVersion;
  }

  public long getReplacementPlanVersion() {
    return replacementPlanVersion;
  }

  public UUID getSourcePlanWarehouseId() {
    return sourcePlanWarehouseId;
  }

  public LocalDate getSourcePlanDate() {
    return sourcePlanDate;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public String getResponseJson() {
    return responseJson;
  }
}
