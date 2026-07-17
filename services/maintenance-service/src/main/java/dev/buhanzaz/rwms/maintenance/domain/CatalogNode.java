package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_node")
public class CatalogNode {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "row_id", nullable = false)
  private UUID rowId;

  @Column(name = "node_id", nullable = false)
  private UUID id;

  @Column(name = "catalog_version_id", nullable = false)
  private UUID catalogVersionId;

  @Column(name = "code", nullable = false, length = 64)
  private String code;

  @Column(name = "node_type", nullable = false, length = 32)
  private String nodeType;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "parent_node_id")
  private UUID parentNodeId;

  @Column(name = "unit", length = 32)
  private String unit;

  @Column(name = "price_minor")
  private Long priceMinor;

  @Column(name = "duration_minutes", nullable = false)
  private Integer durationMinutes;

  @Column(name = "include_in_estimate", nullable = false)
  private boolean includeInEstimate;

  @Column(name = "common_item", nullable = false)
  private boolean commonItem;

  @Column(name = "show_in_main_menu", nullable = false)
  private boolean showInMainMenu;

  @Column(name = "photo_required", nullable = false)
  private boolean photoRequired;

  @Column(name = "routing_queue_id")
  private UUID routingQueueId;

  @Column(name = "routing_queue_code", length = 64)
  private String routingQueueCode;

  @Column(name = "routing_queue_kind", length = 64)
  private String routingQueueKind;

  @Column(name = "opaque_references", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String opaqueReferences;

  @Column(name = "comment", length = 2000)
  private String comment;

  @Column(name = "media_references", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String mediaReferences;

  protected CatalogNode() {}

  public CatalogNode(
      UUID id,
      UUID catalogVersionId,
      String code,
      String nodeType,
      String name,
      boolean active,
      UUID parentNodeId,
      String unit,
      Long priceMinor,
      Integer durationMinutes,
      boolean includeInEstimate,
      boolean commonItem,
      boolean showInMainMenu,
      boolean photoRequired,
      UUID routingQueueId,
      String routingQueueCode,
      String routingQueueKind,
      String opaqueReferences,
      String comment,
      String mediaReferences) {
    this.id = require(id, "id");
    this.catalogVersionId = require(catalogVersionId, "catalogVersionId");
    this.code = text(code, "code", 64);
    this.nodeType = text(nodeType, "nodeType", 32);
    this.name = text(name, "name", 255);
    this.active = active;
    this.parentNodeId = parentNodeId;
    this.unit = optional(unit, 32);
    if (priceMinor != null && priceMinor < 0) throw new IllegalArgumentException("priceMinor is invalid");
    if (durationMinutes != null && durationMinutes < 0) throw new IllegalArgumentException("durationMinutes is invalid");
    this.priceMinor = priceMinor;
    this.durationMinutes = durationMinutes;
    this.includeInEstimate = includeInEstimate;
    this.commonItem = commonItem;
    this.showInMainMenu = showInMainMenu;
    this.photoRequired = photoRequired;
    boolean completeRouting = routingQueueId != null
        && routingQueueCode != null && !routingQueueCode.isBlank()
        && routingQueueKind != null && !routingQueueKind.isBlank();
    boolean emptyRouting = routingQueueId == null
        && (routingQueueCode == null || routingQueueCode.isBlank())
        && (routingQueueKind == null || routingQueueKind.isBlank());
    if (!completeRouting && !emptyRouting) {
      throw new IllegalArgumentException("Routing snapshot must be complete or absent");
    }
    this.routingQueueId = routingQueueId;
    this.routingQueueCode = optional(routingQueueCode, 64);
    this.routingQueueKind = optional(routingQueueKind, 64);
    this.opaqueReferences = opaqueReferences == null ? "[]" : opaqueReferences;
    this.comment = optional(comment, 2000);
    this.mediaReferences = mediaReferences == null ? "[]" : mediaReferences;
  }

  private static <T> T require(T value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    return value;
  }

  private static String text(String value, String field, int maximum) {
    String normalized = optional(value, maximum);
    if (normalized == null) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String optional(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maximum) throw new IllegalArgumentException("Text is too long");
    return normalized;
  }

  public UUID getId() { return id; }
  public UUID getRowId() { return rowId; }
  public UUID getCatalogVersionId() { return catalogVersionId; }
  public String getCode() { return code; }
  public String getNodeType() { return nodeType; }
  public String getName() { return name; }
  public boolean isActive() { return active; }
  public UUID getParentNodeId() { return parentNodeId; }
  public String getUnit() { return unit; }
  public Long getPriceMinor() { return priceMinor; }
  public Integer getDurationMinutes() { return durationMinutes; }
  public boolean isIncludeInEstimate() { return includeInEstimate; }
  public boolean isCommonItem() { return commonItem; }
  public boolean isShowInMainMenu() { return showInMainMenu; }
  public boolean isPhotoRequired() { return photoRequired; }
  public UUID getRoutingQueueId() { return routingQueueId; }
  public String getRoutingQueueCode() { return routingQueueCode; }
  public String getRoutingQueueKind() { return routingQueueKind; }
  public String getOpaqueReferences() { return opaqueReferences; }
  public String getComment() { return comment; }
  public String getMediaReferences() { return mediaReferences; }
}
