package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Current head of the append-only final-plan versions for an inventory session. */
@Entity
@Table(name = "inventory_final_plan")
public class InventoryFinalPlan {
  @Id
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Version
  @Column(name = "row_revision", nullable = false)
  private long rowRevision;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private FinalPlanState state;

  @Column(name = "basis_session_revision", nullable = false)
  private long basisSessionRevision;

  @Column(name = "planning_settings_revision", nullable = false)
  private long planningSettingsRevision;

  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "movement_schedule_mode", nullable = false, length = 16)
  private FinalPlanScheduleMode movementScheduleMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "repair_schedule_mode", nullable = false, length = 16)
  private FinalPlanScheduleMode repairScheduleMode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryFinalPlan() {}

  public static InventoryFinalPlan create(
      UUID inventoryId,
      long basisSessionRevision,
      long planningSettingsRevision,
      String finalPlanSha256,
      FinalPlanScheduleMode movementScheduleMode,
      FinalPlanScheduleMode repairScheduleMode) {
    InventoryFinalPlan value = new InventoryFinalPlan();
    value.inventoryId = require(inventoryId, "Inventory");
    value.finalPlanVersion = 1;
    value.replace(
        basisSessionRevision,
        planningSettingsRevision,
        finalPlanSha256,
        movementScheduleMode,
        repairScheduleMode);
    return value;
  }

  public void nextVersion(
      long nextBasisSessionRevision,
      long nextPlanningSettingsRevision,
      String nextFinalPlanSha256,
      FinalPlanScheduleMode nextMovementScheduleMode,
      FinalPlanScheduleMode nextRepairScheduleMode) {
    finalPlanVersion = Math.addExact(finalPlanVersion, 1);
    replace(
        nextBasisSessionRevision,
        nextPlanningSettingsRevision,
        nextFinalPlanSha256,
        nextMovementScheduleMode,
        nextRepairScheduleMode);
  }

  public void markStale() {
    if (state == FinalPlanState.DRAFT) state = FinalPlanState.STALE;
  }

  public void complete() {
    if (state != FinalPlanState.DRAFT) {
      throw new IllegalStateException("Only a current final plan may complete an inventory");
    }
    state = FinalPlanState.COMPLETED;
  }

  private void replace(
      long nextBasisSessionRevision,
      long nextPlanningSettingsRevision,
      String nextFinalPlanSha256,
      FinalPlanScheduleMode nextMovementScheduleMode,
      FinalPlanScheduleMode nextRepairScheduleMode) {
    if (nextBasisSessionRevision < 0 || nextPlanningSettingsRevision < 0) {
      throw new IllegalArgumentException("Final-plan revisions cannot be negative");
    }
    if (nextFinalPlanSha256 == null || !nextFinalPlanSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Final-plan SHA-256 is required");
    }
    basisSessionRevision = nextBasisSessionRevision;
    planningSettingsRevision = nextPlanningSettingsRevision;
    finalPlanSha256 = nextFinalPlanSha256;
    movementScheduleMode = require(nextMovementScheduleMode, "Movement schedule mode");
    repairScheduleMode = require(nextRepairScheduleMode, "Repair schedule mode");
    state = FinalPlanState.DRAFT;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = current;
    updatedAt = current;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public long getFinalPlanVersion() {
    return finalPlanVersion;
  }

  public FinalPlanState getState() {
    return state;
  }

  public long getBasisSessionRevision() {
    return basisSessionRevision;
  }

  public long getPlanningSettingsRevision() {
    return planningSettingsRevision;
  }

  public String getFinalPlanSha256() {
    return finalPlanSha256;
  }

  public FinalPlanScheduleMode getMovementScheduleMode() {
    return movementScheduleMode;
  }

  public FinalPlanScheduleMode getRepairScheduleMode() {
    return repairScheduleMode;
  }

  private static <T> T require(T value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    return value;
  }
}
