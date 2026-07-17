package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

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

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  protected CatalogLink() {}

  public CatalogLink(
      UUID id,
      UUID catalogVersionId,
      UUID sourceNodeId,
      UUID targetNodeId,
      String linkType,
      int sortOrder) {
    if (id == null || catalogVersionId == null || sourceNodeId == null || targetNodeId == null) {
      throw new IllegalArgumentException("Catalog link references are required");
    }
    if (sourceNodeId.equals(targetNodeId)) throw new IllegalArgumentException("Catalog link cannot self-reference");
    if (sortOrder < 0) throw new IllegalArgumentException("sortOrder is invalid");
    this.id = id;
    this.catalogVersionId = catalogVersionId;
    this.sourceNodeId = sourceNodeId;
    this.targetNodeId = targetNodeId;
    this.linkType = linkType;
    this.sortOrder = sortOrder;
  }

  public UUID getId() { return id; }
  public UUID getCatalogVersionId() { return catalogVersionId; }
  public UUID getSourceNodeId() { return sourceNodeId; }
  public UUID getTargetNodeId() { return targetNodeId; }
  public String getLinkType() { return linkType; }
  public int getSortOrder() { return sortOrder; }
}
