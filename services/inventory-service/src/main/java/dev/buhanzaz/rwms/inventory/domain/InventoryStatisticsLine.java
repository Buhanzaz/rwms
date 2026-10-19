package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA entity that persists inventory statistics line in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_statistics_line")
public class InventoryStatisticsLine {
  @Id @Column(name = "row_id", nullable = false) private UUID id;
  @Column(name = "inventory_id", nullable = false) private UUID inventoryId;
  @Column(name = "aggregation_kind", nullable = false, length = 16) private String aggregationKind;
  @Column(name = "catalog_version_id") private UUID catalogVersionId;
  @Column(name = "catalog_node_id") private UUID catalogNodeId;
  @Column(name = "normalized_description", length = 1000) private String normalizedDescription;
  @Column(name = "line_type", nullable = false, length = 16) private String lineType;
  @Column(name = "unit", nullable = false, length = 32) private String unit;
  @Column(name = "unit_price_minor", nullable = false) private long unitPriceMinor;
  @Column(name = "quantity", nullable = false, precision = 20, scale = 6) private BigDecimal quantity;
  @Column(name = "row_total_minor", nullable = false) private long rowTotalMinor;

  protected InventoryStatisticsLine() {}
  public InventoryStatisticsLine(UUID inventoryId, String kind, UUID catalogVersionId,
      UUID catalogNodeId, String normalized, String type, String unit, long unitPrice,
      BigDecimal quantity, long rowTotal) {
    id = UUID.randomUUID(); this.inventoryId = inventoryId; aggregationKind = kind;
    this.catalogVersionId = catalogVersionId; this.catalogNodeId = catalogNodeId;
    normalizedDescription = normalized; lineType = type; this.unit = unit;
    unitPriceMinor = unitPrice; this.quantity = quantity; rowTotalMinor = rowTotal;
  }
  public String getAggregationKind() { return aggregationKind; } public UUID getCatalogVersionId() { return catalogVersionId; }
  public UUID getCatalogNodeId() { return catalogNodeId; } public String getNormalizedDescription() { return normalizedDescription; }
  public String getLineType() { return lineType; } public String getUnit() { return unit; }
  public long getUnitPriceMinor() { return unitPriceMinor; } public BigDecimal getQuantity() { return quantity; }
  public long getRowTotalMinor() { return rowTotalMinor; }
}
