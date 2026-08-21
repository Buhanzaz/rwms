package dev.buhanzaz.rwms.logistics.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

/**
 * Per-cabin fence that rejects stale or ambiguous inventory sources while allowing a completed
 * inventory to publish a strictly newer immutable final-plan version for the same warehouse and
 * completion instant.
 */
@Entity
@Table(name = "inventory_asset_outcome_watermark")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryAssetOutcomeWatermark {
  @Id
  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "inventory_completed_at", nullable = false)
  private OffsetDateTime inventoryCompletedAt;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static InventoryAssetOutcomeWatermark create(
      UUID assetId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      UUID inventoryId,
      long finalPlanVersion,
      String finalPlanSha256) {
    InventoryAssetOutcomeWatermark value = new InventoryAssetOutcomeWatermark();
    value.assetId = Objects.requireNonNull(assetId, "assetId");
    value.replace(
        warehouseId,
        inventoryCompletedAt,
        inventoryId,
        finalPlanVersion,
        finalPlanSha256);
    return value;
  }

  public boolean sameSource(
      UUID nextWarehouseId,
      OffsetDateTime nextCompletedAt,
      UUID nextInventoryId,
      long nextFinalPlanVersion,
      String nextFinalPlanSha256) {
    return warehouseId.equals(nextWarehouseId)
        && inventoryCompletedAt.equals(nextCompletedAt)
        && inventoryId.equals(nextInventoryId)
        && finalPlanVersion == nextFinalPlanVersion
        && finalPlanSha256.equals(nextFinalPlanSha256);
  }

  /**
   * Returns whether the source is a forward-only correction of this exact completed inventory.
   * Equal and lower plan versions are not corrections, even when their payload hash differs.
   */
  public boolean isStrictlyNewerPlanForSameCompletedInventory(
      UUID nextWarehouseId,
      OffsetDateTime nextCompletedAt,
      UUID nextInventoryId,
      long nextFinalPlanVersion) {
    return warehouseId.equals(nextWarehouseId)
        && inventoryCompletedAt.equals(nextCompletedAt)
        && inventoryId.equals(nextInventoryId)
        && nextFinalPlanVersion > finalPlanVersion;
  }

  /**
   * Advances this fence only after the complete batch passed stale/equal-time correction
   * validation.
   */
  public void replace(
      UUID nextWarehouseId,
      OffsetDateTime nextCompletedAt,
      UUID nextInventoryId,
      long nextFinalPlanVersion,
      String nextFinalPlanSha256) {
    if (nextFinalPlanVersion < 1
        || nextFinalPlanSha256 == null
        || !nextFinalPlanSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory watermark source is invalid");
    }
    warehouseId = Objects.requireNonNull(nextWarehouseId, "warehouseId");
    inventoryCompletedAt = Objects.requireNonNull(nextCompletedAt, "inventoryCompletedAt");
    inventoryId = Objects.requireNonNull(nextInventoryId, "inventoryId");
    finalPlanVersion = nextFinalPlanVersion;
    finalPlanSha256 = nextFinalPlanSha256;
    updatedAt = now();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
