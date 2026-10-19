package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable entry evidence for one version of a server-owned inventory final plan. */
@Entity
@Table(name = "inventory_final_plan_entry")
@IdClass(InventoryFinalPlanEntry.Key.class)
public class InventoryFinalPlanEntry {
  @Id
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Id
  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Id
  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "finding_revision", nullable = false)
  private long findingRevision;

  @Column(name = "asset_id")
  private UUID assetId;

  @Column(name = "asset_version")
  private Long assetVersion;

  @Column(name = "plan_fingerprint_sha256", length = 64)
  private String planFingerprintSha256;

  @Column(name = "has_work", nullable = false)
  private boolean hasWork;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_kind", length = 16)
  private FinalPlanTargetKind targetKind;

  @Column(name = "plan_order", nullable = false)
  private int order;

  @Column(name = "priority")
  private Integer priority;

  @Column(name = "movement_to_repair", nullable = false)
  private boolean movementToRepair;

  @Column(name = "force_capital_repair", nullable = false)
  private boolean forceCapitalRepair;

  @Column(name = "movement_scheduled_date")
  private LocalDate movementScheduledDate;

  @Column(name = "repair_scheduled_date")
  private LocalDate repairScheduledDate;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "collision_candidates", nullable = false, columnDefinition = "jsonb")
  private String collisionCandidates;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "reconciliation_decision", columnDefinition = "jsonb")
  private String reconciliationDecision;

  @Enumerated(EnumType.STRING)
  @Column(name = "disposition_kind", nullable = false, length = 16)
  private InventoryCabinDispositionKind dispositionKind;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "disposition_details", nullable = false, columnDefinition = "jsonb")
  private String dispositionDetails;

  protected InventoryFinalPlanEntry() {}

  public InventoryFinalPlanEntry(
      UUID inventoryId,
      long finalPlanVersion,
      UUID findingId,
      long findingRevision,
      UUID assetId,
      Long assetVersion,
      String planFingerprintSha256,
      boolean hasWork,
      FinalPlanTargetKind targetKind,
      int order,
      Integer priority,
      boolean movementToRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      String collisionCandidates,
      String reconciliationDecision) {
    this(
        inventoryId,
        finalPlanVersion,
        findingId,
        findingRevision,
        assetId,
        assetVersion,
        planFingerprintSha256,
        hasWork,
        targetKind,
        order,
        priority,
        movementToRepair,
        false,
        movementScheduledDate,
        repairScheduledDate,
        collisionCandidates,
        reconciliationDecision);
  }

  /** Creates immutable final-plan evidence including the frozen capital-repair choice. */
  public InventoryFinalPlanEntry(
      UUID inventoryId,
      long finalPlanVersion,
      UUID findingId,
      long findingRevision,
      UUID assetId,
      Long assetVersion,
      String planFingerprintSha256,
      boolean hasWork,
      FinalPlanTargetKind targetKind,
      int order,
      Integer priority,
      boolean movementToRepair,
      boolean forceCapitalRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      String collisionCandidates,
      String reconciliationDecision) {
    this(
        inventoryId,
        finalPlanVersion,
        findingId,
        findingRevision,
        assetId,
        assetVersion,
        planFingerprintSha256,
        hasWork,
        targetKind,
        order,
        priority,
        movementToRepair,
        forceCapitalRepair,
        movementScheduledDate,
        repairScheduledDate,
        collisionCandidates,
        reconciliationDecision,
        InventoryCabinDispositionKind.LOCAL,
        "{\"formerRental\":null}");
  }

  /** Creates immutable final-plan evidence with its completed cabin disposition decision. */
  public InventoryFinalPlanEntry(
      UUID inventoryId,
      long finalPlanVersion,
      UUID findingId,
      long findingRevision,
      UUID assetId,
      Long assetVersion,
      String planFingerprintSha256,
      boolean hasWork,
      FinalPlanTargetKind targetKind,
      int order,
      Integer priority,
      boolean movementToRepair,
      boolean forceCapitalRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      String collisionCandidates,
      String reconciliationDecision,
      InventoryCabinDispositionKind dispositionKind,
      String dispositionDetails) {
    if (inventoryId == null
        || finalPlanVersion < 1
        || findingId == null
        || findingRevision < 0
        || order < 0
        || collisionCandidates == null
        || !collisionCandidates.startsWith("[")
        || dispositionKind == null
        || dispositionDetails == null
        || !dispositionDetails.trim().startsWith("{")
        || !dispositionDetails.trim().endsWith("}")
        || dispositionDetails.trim().length() > 262_144
        || (dispositionKind != InventoryCabinDispositionKind.LOCAL && hasWork)) {
      throw new IllegalArgumentException("Final-plan entry is incomplete");
    }
    if (!hasWork) {
      if (planFingerprintSha256 != null
          || targetKind != null
          || priority != null
          || movementToRepair
          || forceCapitalRepair
          || movementScheduledDate != null
          || repairScheduledDate != null
          || !"[]".equals(collisionCandidates)
          || reconciliationDecision != null) {
        throw new IllegalArgumentException("No-work final-plan entry has operational data");
      }
    } else if (assetId == null
        || assetVersion == null
        || assetVersion < 0
        || planFingerprintSha256 == null
        || !planFingerprintSha256.matches("^[0-9a-f]{64}$")
        || targetKind == null
        || priority == null
        || priority < 1
        || priority > 5
        || repairScheduledDate == null
        || (movementToRepair != (movementScheduledDate != null))) {
      throw new IllegalArgumentException("Work final-plan entry is invalid");
    }
    this.inventoryId = inventoryId;
    this.finalPlanVersion = finalPlanVersion;
    this.findingId = findingId;
    this.findingRevision = findingRevision;
    this.assetId = assetId;
    this.assetVersion = assetVersion;
    this.planFingerprintSha256 = planFingerprintSha256;
    this.hasWork = hasWork;
    this.targetKind = targetKind;
    this.order = order;
    this.priority = priority;
    this.movementToRepair = movementToRepair;
    this.forceCapitalRepair = forceCapitalRepair;
    this.movementScheduledDate = movementScheduledDate;
    this.repairScheduledDate = repairScheduledDate;
    this.collisionCandidates = collisionCandidates;
    this.reconciliationDecision = reconciliationDecision;
    this.dispositionKind = dispositionKind;
    this.dispositionDetails = dispositionDetails.trim();
  }

  public UUID getInventoryId() { return inventoryId; }
  public long getFinalPlanVersion() { return finalPlanVersion; }
  public UUID getFindingId() { return findingId; }
  public long getFindingRevision() { return findingRevision; }
  public UUID getAssetId() { return assetId; }
  public Long getAssetVersion() { return assetVersion; }
  public String getPlanFingerprintSha256() { return planFingerprintSha256; }
  public boolean isHasWork() { return hasWork; }
  public FinalPlanTargetKind getTargetKind() { return targetKind; }
  public int getOrder() { return order; }
  public Integer getPriority() { return priority; }
  public boolean isMovementToRepair() { return movementToRepair; }
  public boolean isForceCapitalRepair() { return forceCapitalRepair; }
  public LocalDate getMovementScheduledDate() { return movementScheduledDate; }
  public LocalDate getRepairScheduledDate() { return repairScheduledDate; }
  public String getCollisionCandidates() { return collisionCandidates; }
  public String getReconciliationDecision() { return reconciliationDecision; }
  public InventoryCabinDispositionKind getDispositionKind() { return dispositionKind; }
  public String getDispositionDetails() { return dispositionDetails; }

  public static final class Key implements Serializable {
    private UUID inventoryId;
    private long finalPlanVersion;
    private UUID findingId;

    public Key() {}

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Key key)) return false;
      return finalPlanVersion == key.finalPlanVersion
          && Objects.equals(inventoryId, key.inventoryId)
          && Objects.equals(findingId, key.findingId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(inventoryId, finalPlanVersion, findingId);
    }
  }
}
