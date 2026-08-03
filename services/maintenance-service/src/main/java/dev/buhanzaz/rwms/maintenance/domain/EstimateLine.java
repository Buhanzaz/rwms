package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "estimate_line")
public class EstimateLine {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "row_id", nullable = false)
  private UUID rowId;

  @Column(name = "line_id", nullable = false)
  private UUID id;

  @Column(name = "estimate_id", nullable = false)
  private UUID estimateId;

  @Column(name = "estimate_revision", nullable = false)
  private int estimateRevision;

  @Column(name = "line_no", nullable = false)
  private int lineNo;

  @Column(name = "catalog_node_id")
  private UUID catalogNodeId;

  @Column(name = "line_type", nullable = false, length = 24)
  private String lineType;

  @Column(name = "title", nullable = false, length = 1000)
  private String title;

  @Column(name = "unit", length = 32)
  private String unit;

  @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
  private BigDecimal quantity;

  @Column(name = "unit_price_minor", nullable = false)
  private long unitPriceMinor;

  @Column(name = "duration_minutes")
  private Integer durationMinutes;

  @Column(name = "queue_ref", length = 128)
  private String queueRef;

  @Column(name = "catalog_snapshot", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String catalogSnapshot;

  @Column(name = "comment", length = 2000)
  private String comment;

  @Column(name = "media_references", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  // Immutable revision-local snapshot. It deliberately has no shared attachment-row key, so the
  // same logical line ID may be reused by later revisions without overwriting historical media.
  private String mediaReferences;

  protected EstimateLine() {}

  public EstimateLine(
      UUID id,
      UUID estimateId,
      int estimateRevision,
      int lineNo,
      UUID catalogNodeId,
      String lineType,
      String title,
      String unit,
      BigDecimal quantity,
      long unitPriceMinor,
      Integer durationMinutes,
      String queueRef,
      String catalogSnapshot,
      String comment,
      String mediaReferences) {
    if (estimateId == null || estimateRevision < 1 || lineNo < 0) throw new IllegalArgumentException("Estimate line identity is invalid");
    if (quantity == null || quantity.signum() <= 0 || unitPriceMinor < 0) throw new IllegalArgumentException("Estimate line amount is invalid");
    if (durationMinutes != null && durationMinutes < 0) throw new IllegalArgumentException("durationMinutes is invalid");
    if (id == null) throw new IllegalArgumentException("Estimate line ID is required");
    if (!"WORK".equals(lineType) && !"MATERIAL".equals(lineType)) {
      throw new IllegalArgumentException("Estimate line type is invalid");
    }
    String normalizedUnit = optional(unit, 32);
    if (catalogSnapshot == null) {
      if (normalizedUnit == null) {
        throw new IllegalArgumentException("Custom estimate line unit is required");
      }
      if ("WORK".equals(lineType)
          && (durationMinutes == null || durationMinutes < 1 || durationMinutes > 525600)) {
        throw new IllegalArgumentException("Custom estimate work duration is invalid");
      }
      if ("MATERIAL".equals(lineType) && !Integer.valueOf(0).equals(durationMinutes)) {
        throw new IllegalArgumentException("Custom estimate material duration must be zero");
      }
    }
    this.id = id;
    this.estimateId = estimateId;
    this.estimateRevision = estimateRevision;
    this.lineNo = lineNo;
    this.catalogNodeId = catalogNodeId;
    this.lineType = lineType;
    this.title = title.trim();
    this.unit = normalizedUnit;
    this.quantity = quantity.stripTrailingZeros();
    this.unitPriceMinor = unitPriceMinor;
    this.durationMinutes = durationMinutes;
    this.queueRef = queueRef == null || queueRef.isBlank() ? null : queueRef.trim();
    this.catalogSnapshot = catalogSnapshot;
    this.comment = "MATERIAL".equals(lineType) ? null : optional(comment, 2000);
    this.mediaReferences = mediaReferences == null ? "[]" : mediaReferences;
  }

  private static String optional(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maximum) throw new IllegalArgumentException("Estimate line text is too long");
    return normalized;
  }

  public UUID getId() { return id; }
  public UUID getEstimateId() { return estimateId; }
  public int getEstimateRevision() { return estimateRevision; }
  public int getLineNo() { return lineNo; }
  public UUID getCatalogNodeId() { return catalogNodeId; }
  public String getLineType() { return lineType; }
  public String getTitle() { return title; }
  public String getUnit() { return unit; }
  public BigDecimal getQuantity() { return quantity; }
  public long getUnitPriceMinor() { return unitPriceMinor; }
  public Integer getDurationMinutes() { return durationMinutes; }
  public String getQueueRef() { return queueRef; }
  public String getCatalogSnapshot() { return catalogSnapshot; }
  public String getComment() { return "MATERIAL".equals(lineType) ? null : comment; }
  public String getMediaReferences() { return mediaReferences; }
}
