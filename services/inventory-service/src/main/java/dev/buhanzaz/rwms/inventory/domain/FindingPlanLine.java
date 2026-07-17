package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "finding_plan_line")
public class FindingPlanLine {
  @Id @Column(name = "row_id", nullable = false) private UUID id;
  @Column(name = "finding_id", nullable = false) private UUID findingId;
  @Column(name = "finding_revision", nullable = false) private long findingRevision;
  @Column(name = "line_no", nullable = false) private int lineNo;
  @Column(name = "source_kind", nullable = false, length = 16) private String sourceKind;
  @Column(name = "line_type", nullable = false, length = 16) private String lineType;
  @Column(name = "catalog_version_id") private UUID catalogVersionId;
  @Column(name = "catalog_node_id") private UUID catalogNodeId;
  @Column(name = "description", nullable = false, length = 1000) private String description;
  @Column(name = "normalized_description", length = 1000) private String normalizedDescription;
  @Column(name = "unit", nullable = false, length = 32) private String unit;
  @Column(name = "quantity", nullable = false, precision = 20, scale = 6) private BigDecimal quantity;
  @Column(name = "unit_price_minor", nullable = false) private long unitPriceMinor;
  @Column(name = "normative_minutes", nullable = false, precision = 22, scale = 3) private BigDecimal normativeMinutes;

  protected FindingPlanLine() {}

  public FindingPlanLine(UUID findingId, long revision, int lineNo, String sourceKind,
      String lineType, UUID catalogVersionId, UUID catalogNodeId, String description,
      String normalizedDescription, String unit, BigDecimal quantity, long unitPriceMinor,
      BigDecimal normativeMinutes) {
    id = UUID.randomUUID(); this.findingId = findingId; findingRevision = revision;
    this.lineNo = lineNo; this.sourceKind = sourceKind; this.lineType = lineType;
    this.catalogVersionId = catalogVersionId; this.catalogNodeId = catalogNodeId;
    this.description = description; this.normalizedDescription = normalizedDescription;
    this.unit = unit; this.quantity = quantity; this.unitPriceMinor = unitPriceMinor;
    this.normativeMinutes = normativeMinutes;
  }

  public String getSourceKind() { return sourceKind; }
  public String getLineType() { return lineType; }
  public UUID getFindingId() { return findingId; }
  public long getFindingRevision() { return findingRevision; }
  public int getLineNo() { return lineNo; }
  public UUID getCatalogVersionId() { return catalogVersionId; }
  public UUID getCatalogNodeId() { return catalogNodeId; }
  public String getDescription() { return description; }
  public String getNormalizedDescription() { return normalizedDescription; }
  public String getUnit() { return unit; }
  public BigDecimal getQuantity() { return quantity; }
  public long getUnitPriceMinor() { return unitPriceMinor; }
  public BigDecimal getNormativeMinutes() { return normativeMinutes; }
}
