package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Permanent audit record binding completed inventory evidence to exactly one maintenance target.
 * The source JSON is intentionally raw, not a reserialized execution DTO.
 */
@Entity
@Table(name = "inventory_publication_source")
public class InventoryPublicationSource {
  @EmbeddedId private InventoryPublicationSourceId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "finding_revision", nullable = false)
  private long findingRevision;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_version_snapshot", nullable = false)
  private long assetVersionSnapshot;

  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @Column(name = "plan_fingerprint_sha256", nullable = false, length = 64)
  private String planFingerprintSha256;

  @Column(name = "snapshot_schema_version", nullable = false)
  private int snapshotSchemaVersion;

  @Column(name = "plan_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String planSnapshot;

  @Column(name = "media_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String mediaSnapshot;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "movement_to_repair", nullable = false)
  private boolean movementToRepair;

  @Column(name = "movement_scheduled_date")
  private LocalDate movementScheduledDate;

  @Column(name = "repair_scheduled_date", nullable = false)
  private LocalDate repairScheduledDate;

  @Column(name = "strategy", nullable = false, length = 16)
  private String strategy;

  @Column(name = "selected_target_kind", length = 16)
  private String selectedTargetKind;

  @Column(name = "selected_target_id")
  private UUID selectedTargetId;

  @Column(name = "superseded_target_kind", length = 16)
  private String supersededTargetKind;

  @Column(name = "superseded_target_id")
  private UUID supersededTargetId;

  /** CREATED, SUCCESSOR or MATCHED. This row remains immutable after publication. */
  @Column(name = "publication_outcome", nullable = false, length = 32)
  private String publicationOutcome;

  /** Present only when a started/queued repair remains the durable predecessor. */
  @Column(name = "predecessor_repair_id")
  private UUID predecessorRepairId;

  /** Immutable per-source retained/removed line explanation. */
  @Column(name = "delta_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String deltaSnapshot;

  @Column(name = "target_kind", length = 16)
  private String targetKind;

  @Column(name = "target_id")
  private UUID targetId;

  @Column(name = "estimate_id")
  private UUID estimateId;

  @Column(name = "repair_id")
  private UUID repairId;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryPublicationSource() {}

  public static InventoryPublicationSource create(
      InventoryPublicationSourceId id,
      UUID warehouseId,
      long findingRevision,
      UUID assetId,
      long assetVersionSnapshot,
      String finalPlanSha256,
      String planFingerprintSha256,
      int snapshotSchemaVersion,
      String planSnapshot,
      String mediaSnapshot,
      int priority,
      boolean movementToRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      String strategy,
      String selectedTargetKind,
      UUID selectedTargetId,
      String supersededTargetKind,
      UUID supersededTargetId,
      String publicationOutcome,
      UUID predecessorRepairId,
      String deltaSnapshot,
      String targetKind,
      UUID targetId,
      UUID estimateId,
      UUID repairId,
      String requestSha256,
      UUID idempotencyKey) {
    if (id == null
        || warehouseId == null
        || findingRevision < 1
        || assetId == null
        || assetVersionSnapshot < 0
        || !sha256(finalPlanSha256)
        || !sha256(planFingerprintSha256)
        || (snapshotSchemaVersion != 1 && snapshotSchemaVersion != 2)
        || planSnapshot == null
        || mediaSnapshot == null
        || priority < 1
        || priority > 5
        || (!movementToRepair && movementScheduledDate != null)
        || repairScheduledDate == null
        || !oneOf(strategy, "CREATE", "REPLACE", "MERGE")
        || !publicationOutcome(publicationOutcome)
        || deltaSnapshot == null
        || !outcomeBinding(
            publicationOutcome,
            predecessorRepairId,
            targetKind,
            targetId,
            estimateId,
            repairId)
        || !selection(
            strategy,
            publicationOutcome,
            selectedTargetKind,
            selectedTargetId,
            supersededTargetKind,
            supersededTargetId)
        || !sha256(requestSha256)
        || idempotencyKey == null) {
      throw new IllegalArgumentException("Inventory publication source is incomplete");
    }
    InventoryPublicationSource value = new InventoryPublicationSource();
    value.id = id;
    value.warehouseId = warehouseId;
    value.findingRevision = findingRevision;
    value.assetId = assetId;
    value.assetVersionSnapshot = assetVersionSnapshot;
    value.finalPlanSha256 = finalPlanSha256;
    value.planFingerprintSha256 = planFingerprintSha256;
    value.snapshotSchemaVersion = snapshotSchemaVersion;
    value.planSnapshot = planSnapshot;
    value.mediaSnapshot = mediaSnapshot;
    value.priority = priority;
    value.movementToRepair = movementToRepair;
    value.movementScheduledDate = movementScheduledDate;
    value.repairScheduledDate = repairScheduledDate;
    value.strategy = strategy;
    value.selectedTargetKind = selectedTargetKind;
    value.selectedTargetId = selectedTargetId;
    value.supersededTargetKind = supersededTargetKind;
    value.supersededTargetId = supersededTargetId;
    value.publicationOutcome = publicationOutcome;
    value.predecessorRepairId = predecessorRepairId;
    value.deltaSnapshot = deltaSnapshot;
    value.targetKind = targetKind;
    value.targetId = targetId;
    value.estimateId = estimateId;
    value.repairId = repairId;
    value.requestSha256 = requestSha256;
    value.idempotencyKey = idempotencyKey;
    value.createdAt = MaintenanceTime.now();
    return value;
  }

  private static boolean selection(
      String strategy,
      String publicationOutcome,
      String selectedKind,
      UUID selectedId,
      String supersededKind,
      UUID supersededId) {
    if ("CREATE".equals(strategy)) {
      return "CREATED".equals(publicationOutcome)
          && selectedKind == null
          && selectedId == null
          && supersededKind == null
          && supersededId == null;
    }
    if (!targetKind(selectedKind) || selectedId == null) return false;
    if ("CREATED".equals(publicationOutcome)) {
      return selectedKind.equals(supersededKind) && selectedId.equals(supersededId);
    }
    return supersededKind == null && supersededId == null;
  }

  private static boolean outcomeBinding(
      String publicationOutcome,
      UUID predecessorRepairId,
      String targetKind,
      UUID targetId,
      UUID estimateId,
      UUID repairId) {
    if ("CREATED".equals(publicationOutcome)) {
      return predecessorRepairId == null && targetBinding(targetKind, targetId, estimateId, repairId);
    }
    if ("SUCCESSOR".equals(publicationOutcome)) {
      return predecessorRepairId != null
          && "REPAIR".equals(targetKind)
          && targetBinding(targetKind, targetId, estimateId, repairId);
    }
    return "MATCHED".equals(publicationOutcome)
        && predecessorRepairId != null
        && targetKind == null
        && targetId == null
        && estimateId == null
        && repairId == null;
  }

  private static boolean targetBinding(
      String targetKind, UUID targetId, UUID estimateId, UUID repairId) {
    return ("ESTIMATE".equals(targetKind)
            && targetId.equals(estimateId)
            && repairId == null)
        || ("REPAIR".equals(targetKind)
            && targetId.equals(repairId)
            && estimateId == null);
  }

  private static boolean targetKind(String value) {
    return oneOf(value, "ESTIMATE", "REPAIR");
  }

  private static boolean publicationOutcome(String value) {
    return oneOf(value, "CREATED", "SUCCESSOR", "MATCHED");
  }

  private static boolean oneOf(String value, String... options) {
    if (value == null) return false;
    for (String option : options) {
      if (option.equals(value)) return true;
    }
    return false;
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public InventoryPublicationSourceId getId() { return id; }
  public long getVersion() { return version; }
  public UUID getWarehouseId() { return warehouseId; }
  public long getFindingRevision() { return findingRevision; }
  public UUID getAssetId() { return assetId; }
  public long getAssetVersionSnapshot() { return assetVersionSnapshot; }
  public String getFinalPlanSha256() { return finalPlanSha256; }
  public String getPlanFingerprintSha256() { return planFingerprintSha256; }
  public int getSnapshotSchemaVersion() { return snapshotSchemaVersion; }
  public String getPlanSnapshot() { return planSnapshot; }
  public String getMediaSnapshot() { return mediaSnapshot; }
  public int getPriority() { return priority; }
  public boolean isMovementToRepair() { return movementToRepair; }
  public LocalDate getMovementScheduledDate() { return movementScheduledDate; }
  public LocalDate getRepairScheduledDate() { return repairScheduledDate; }
  public String getStrategy() { return strategy; }
  public String getSelectedTargetKind() { return selectedTargetKind; }
  public UUID getSelectedTargetId() { return selectedTargetId; }
  public String getSupersededTargetKind() { return supersededTargetKind; }
  public UUID getSupersededTargetId() { return supersededTargetId; }
  public String getPublicationOutcome() { return publicationOutcome; }
  public UUID getPredecessorRepairId() { return predecessorRepairId; }
  public String getDeltaSnapshot() { return deltaSnapshot; }
  public String getTargetKind() { return targetKind; }
  public UUID getTargetId() { return targetId; }
  public UUID getEstimateId() { return estimateId; }
  public UUID getRepairId() { return repairId; }
  public String getRequestSha256() { return requestSha256; }
  public UUID getIdempotencyKey() { return idempotencyKey; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
