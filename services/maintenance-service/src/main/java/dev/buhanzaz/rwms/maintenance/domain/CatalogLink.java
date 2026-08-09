package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** JPA directed link between two nodes in one maintenance catalog version. */
@Entity
@Table(name = "catalog_link")
public class CatalogLink {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "row_id", nullable = false)
  private UUID rowId;

  @Column(name = "link_id", nullable = false)
  private UUID id;

  @Column(name = "catalog_version_id", nullable = false)
  private UUID catalogVersionId;

  @Column(name = "source_node_id", nullable = false)
  private UUID sourceNodeId;

  @Column(name = "target_node_id", nullable = false)
  private UUID targetNodeId;

  @Column(name = "link_type", nullable = false, length = 32)
  private String linkType;

  @Column(name = "source_anchor", length = 16)
  private String sourceAnchor;

  @Column(name = "target_anchor", length = 16)
  private String targetAnchor;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  protected CatalogLink() {}

  public CatalogLink(
      UUID id,
      UUID catalogVersionId,
      UUID sourceNodeId,
      UUID targetNodeId,
      String linkType,
      String sourceAnchor,
      String targetAnchor,
      int sortOrder) {
    if (id == null || catalogVersionId == null || sourceNodeId == null || targetNodeId == null) {
      throw new IllegalArgumentException("Catalog link references are required");
    }
    if (sourceNodeId.equals(targetNodeId)) throw new IllegalArgumentException("Catalog link cannot self-reference");
    if (sortOrder < 0) throw new IllegalArgumentException("sortOrder is invalid");
    boolean anchorsAbsent = sourceAnchor == null && targetAnchor == null;
    boolean anchorsPresent = isAnchor(sourceAnchor) && isAnchor(targetAnchor);
    if (!anchorsAbsent && !anchorsPresent) {
      throw new IllegalArgumentException("Catalog link anchors must be both absent or both present");
    }
    this.id = id;
    this.catalogVersionId = catalogVersionId;
    this.sourceNodeId = sourceNodeId;
    this.targetNodeId = targetNodeId;
    this.linkType = linkType;
    this.sourceAnchor = sourceAnchor;
    this.targetAnchor = targetAnchor;
    this.sortOrder = sortOrder;
  }

  private static boolean isAnchor(String value) {
    return "TOP".equals(value) || "BOTTOM".equals(value);
  }

  public UUID getId() { return id; }
  public UUID getCatalogVersionId() { return catalogVersionId; }
  public UUID getSourceNodeId() { return sourceNodeId; }
  public UUID getTargetNodeId() { return targetNodeId; }
  public String getLinkType() { return linkType; }
  public String getSourceAnchor() { return sourceAnchor; }
  public String getTargetAnchor() { return targetAnchor; }
  public int getSortOrder() { return sortOrder; }
}
