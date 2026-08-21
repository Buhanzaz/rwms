package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Per-asset pointer that rejects stale or ambiguous completed-inventory outcomes. */
@Entity
@Table(name = "inventory_authoritative_outcome_watermark")
public class InventoryAuthoritativeOutcomeWatermark {
  @Id
  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "inventory_completed_at", nullable = false)
  private OffsetDateTime inventoryCompletedAt;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @Column(name = "finding_revision", nullable = false)
  private long findingRevision;

  @Column(name = "desired_status", nullable = false, length = 32)
  private String desiredStatus;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryAuthoritativeOutcomeWatermark() {}

  /** Creates the first completed-inventory ordering fence for an asset. */
  public static InventoryAuthoritativeOutcomeWatermark create(
      InventoryAuthoritativeOutcome source) {
    InventoryAuthoritativeOutcomeWatermark value = new InventoryAuthoritativeOutcomeWatermark();
    value.replaceWith(source);
    return value;
  }

  /** Returns true when this watermark names the same immutable completed finding. */
  public boolean sameSource(InventoryAuthoritativeOutcome source) {
    return source != null
        && Objects.equals(inventoryId, source.getId().getInventoryId())
        && finalPlanVersion == source.getId().getFinalPlanVersion()
        && Objects.equals(findingId, source.getId().getFindingId())
        && Objects.equals(finalPlanSha256, source.getFinalPlanSha256())
        && findingRevision == source.getFindingRevision()
        && Objects.equals(desiredStatus, source.getDesiredStatus());
  }

  /**
   * Returns true for a strictly newer immutable plan version of this exact completed finding.
   * Content compatibility is checked by the owning application service before replacement.
   */
  public boolean isStrictlyNewerPlanVersionOfSameFinding(
      InventoryAuthoritativeOutcome source) {
    return source != null
        && Objects.equals(warehouseId, source.getWarehouseId())
        && Objects.equals(inventoryCompletedAt, source.getInventoryCompletedAt())
        && Objects.equals(inventoryId, source.getId().getInventoryId())
        && source.getId().getFinalPlanVersion() > finalPlanVersion
        && Objects.equals(findingId, source.getId().getFindingId());
  }

  /** Advances the pointer only after the caller has proved strict completed-time ordering. */
  public void replaceWith(InventoryAuthoritativeOutcome source) {
    if (source == null) {
      throw new IllegalArgumentException("Authoritative inventory watermark source is required");
    }
    assetId = source.getAssetId();
    warehouseId = source.getWarehouseId();
    inventoryCompletedAt = source.getInventoryCompletedAt();
    inventoryId = source.getId().getInventoryId();
    finalPlanVersion = source.getId().getFinalPlanVersion();
    findingId = source.getId().getFindingId();
    finalPlanSha256 = source.getFinalPlanSha256();
    findingRevision = source.getFindingRevision();
    desiredStatus = source.getDesiredStatus();
    requestSha256 = source.getRequestSha256();
  }

  @PrePersist
  @PreUpdate
  void updateTimestamp() {
    updatedAt = MaintenanceTime.now();
  }

  public UUID getAssetId() { return assetId; }
  public long getVersion() { return version; }
  public UUID getWarehouseId() { return warehouseId; }
  public OffsetDateTime getInventoryCompletedAt() { return inventoryCompletedAt; }
  public UUID getInventoryId() { return inventoryId; }
  public long getFinalPlanVersion() { return finalPlanVersion; }
  public UUID getFindingId() { return findingId; }
  public String getFinalPlanSha256() { return finalPlanSha256; }
  public long getFindingRevision() { return findingRevision; }
  public String getDesiredStatus() { return desiredStatus; }
  public String getRequestSha256() { return requestSha256; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
