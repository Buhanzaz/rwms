package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Permanent inventory source identity and immutable maintenance-issued plan evidence. */
@Entity
@Table(name = "inventory_repair_source")
public class InventoryRepairSource {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "row_id", nullable = false)
  private UUID rowId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "source_revision", nullable = false)
  private long sourceRevision;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "catalog_version_id", nullable = false)
  private UUID catalogVersionId;

  @Column(name = "plan_request_sha256", nullable = false, length = 64)
  private String planRequestSha256;

  @Column(name = "plan_fingerprint", nullable = false, length = 64)
  private String planFingerprint;

  @Column(name = "plan_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String planSnapshot;

  @Column(name = "media_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String mediaSnapshot;

  @Column(name = "source_fingerprint", length = 64)
  private String sourceFingerprint;

  @Column(name = "rental_item_id")
  private UUID rentalItemId;

  @Column(name = "rental_item_version_snapshot")
  private Long rentalItemVersionSnapshot;

  @Column(name = "repair_id")
  private UUID repairId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "repair_bound_at")
  private OffsetDateTime repairBoundAt;

  protected InventoryRepairSource() {}

  public static InventoryRepairSource freeze(
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      UUID warehouseId,
      UUID catalogVersionId,
      String planRequestSha256,
      String planFingerprint,
      String planSnapshot,
      String mediaSnapshot) {
    if (inventoryId == null || findingId == null || sourceRevision < 1 || warehouseId == null
        || catalogVersionId == null || !sha256(planRequestSha256) || !sha256(planFingerprint)
        || planSnapshot == null || mediaSnapshot == null) {
      throw new IllegalArgumentException("Inventory repair source freeze is incomplete");
    }
    InventoryRepairSource value = new InventoryRepairSource();
    value.inventoryId = inventoryId;
    value.findingId = findingId;
    value.sourceRevision = sourceRevision;
    value.warehouseId = warehouseId;
    value.catalogVersionId = catalogVersionId;
    value.planRequestSha256 = planRequestSha256;
    value.planFingerprint = planFingerprint;
    value.planSnapshot = planSnapshot;
    value.mediaSnapshot = mediaSnapshot;
    return value;
  }

  public void bindRepair(
      UUID repairId,
      UUID rentalItemId,
      long rentalItemVersionSnapshot,
      String sourceFingerprint) {
    if (this.repairId != null) {
      throw new IllegalStateException("Inventory repair source is already bound");
    }
    if (repairId == null || rentalItemId == null || rentalItemVersionSnapshot < 0
        || !sha256(sourceFingerprint)) {
      throw new IllegalArgumentException("Inventory repair binding is incomplete");
    }
    this.repairId = repairId;
    this.rentalItemId = rentalItemId;
    this.rentalItemVersionSnapshot = rentalItemVersionSnapshot;
    this.sourceFingerprint = sourceFingerprint;
    this.repairBoundAt = MaintenanceTime.now();
  }

  @PrePersist
  void beforeInsert() {
    createdAt = MaintenanceTime.now();
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public UUID getRowId() { return rowId; }
  public long getVersion() { return version; }
  public UUID getInventoryId() { return inventoryId; }
  public UUID getFindingId() { return findingId; }
  public long getSourceRevision() { return sourceRevision; }
  public UUID getWarehouseId() { return warehouseId; }
  public UUID getCatalogVersionId() { return catalogVersionId; }
  public String getPlanRequestSha256() { return planRequestSha256; }
  public String getPlanFingerprint() { return planFingerprint; }
  public String getPlanSnapshot() { return planSnapshot; }
  public String getMediaSnapshot() { return mediaSnapshot; }
  public String getSourceFingerprint() { return sourceFingerprint; }
  public UUID getRentalItemId() { return rentalItemId; }
  public Long getRentalItemVersionSnapshot() { return rentalItemVersionSnapshot; }
  public UUID getRepairId() { return repairId; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getRepairBoundAt() { return repairBoundAt; }
}
