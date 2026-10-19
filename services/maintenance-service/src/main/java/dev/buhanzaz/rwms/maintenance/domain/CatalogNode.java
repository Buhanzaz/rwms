package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** JPA node in a maintenance catalog version; catalog commands own its position and references. */
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

  @Column(name = "node_type", nullable = false, length = 32)
  private String nodeType;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "parent_node_id")
  private UUID parentNodeId;

  @Column(name = "furniture_category", nullable = false)
  private boolean furnitureCategory;

  @Column(name = "furniture_equipment_id")
  private UUID furnitureEquipmentId;

  @Column(name = "furniture_equipment_name", length = 255)
  private String furnitureEquipmentName;

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

  @Column(name = "canvas_x")
  private Integer canvasX;

  @Column(name = "canvas_y")
  private Integer canvasY;

  @Column(name = "routing_queue_id")
  private UUID routingQueueId;

  @Column(name = "routing_queue_name", length = 255)
  private String routingQueueName;

  @Column(name = "routing_queue_type", length = 64)
  private String routingQueueType;

  @Column(name = "comment", length = 2000)
  private String comment;

  @Column(name = "display_color", length = 7)
  private String displayColor;

  @Column(name = "forces_capital_repair", nullable = false)
  private boolean forcesCapitalRepair;

  @Column(name = "characteristic_id")
  private UUID characteristicId;

  @Column(name = "characteristic_name", length = 255)
  private String characteristicName;

  protected CatalogNode() {}

  public CatalogNode(
      UUID id,
      UUID catalogVersionId,
      String nodeType,
      String name,
      boolean active,
      UUID parentNodeId,
      boolean furnitureCategory,
      UUID furnitureEquipmentId,
      String furnitureEquipmentName,
      String unit,
      Long priceMinor,
      Integer durationMinutes,
      boolean includeInEstimate,
      boolean commonItem,
      boolean showInMainMenu,
      Integer canvasX,
      Integer canvasY,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      String comment,
      String displayColor) {
    this(
        id,
        catalogVersionId,
        nodeType,
        name,
        active,
        parentNodeId,
        furnitureCategory,
        furnitureEquipmentId,
        furnitureEquipmentName,
        unit,
        priceMinor,
        durationMinutes,
        includeInEstimate,
        commonItem,
        showInMainMenu,
        canvasX,
        canvasY,
        routingQueueId,
        routingQueueName,
        routingQueueType,
        comment,
        displayColor,
        false,
        null,
        null);
  }

  public CatalogNode(
      UUID id,
      UUID catalogVersionId,
      String nodeType,
      String name,
      boolean active,
      UUID parentNodeId,
      boolean furnitureCategory,
      UUID furnitureEquipmentId,
      String furnitureEquipmentName,
      String unit,
      Long priceMinor,
      Integer durationMinutes,
      boolean includeInEstimate,
      boolean commonItem,
      boolean showInMainMenu,
      Integer canvasX,
      Integer canvasY,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      String comment,
      String displayColor,
      boolean forcesCapitalRepair,
      UUID characteristicId,
      String characteristicName) {
    this.id = require(id, "id");
    this.catalogVersionId = require(catalogVersionId, "catalogVersionId");
    this.nodeType = text(nodeType, "nodeType", 32);
    this.name = text(name, "name", 255);
    this.active = active;
    this.parentNodeId = parentNodeId;
    if (furnitureCategory && !"CATEGORY".equals(nodeType)) {
      throw new IllegalArgumentException("Only a category can mark a furniture tree");
    }
    boolean completeFurnitureEquipment = furnitureEquipmentId != null
        && furnitureEquipmentName != null && !furnitureEquipmentName.isBlank();
    boolean emptyFurnitureEquipment = furnitureEquipmentId == null
        && (furnitureEquipmentName == null || furnitureEquipmentName.isBlank());
    if ((!completeFurnitureEquipment && !emptyFurnitureEquipment)
        || (completeFurnitureEquipment && !"MATERIAL".equals(nodeType))) {
      throw new IllegalArgumentException(
          "Furniture equipment snapshot must be complete, absent and material-only");
    }
    this.furnitureCategory = furnitureCategory;
    this.furnitureEquipmentId = furnitureEquipmentId;
    this.furnitureEquipmentName = optional(furnitureEquipmentName, 255);
    this.unit = optional(unit, 32);
    if (priceMinor != null && priceMinor < 0) throw new IllegalArgumentException("priceMinor is invalid");
    if (durationMinutes != null && durationMinutes < 0) {
      throw new IllegalArgumentException("durationMinutes is invalid");
    }
    if ("WORK".equals(nodeType) && (durationMinutes == null || durationMinutes < 1)) {
      throw new IllegalArgumentException("durationMinutes must be positive for WORK");
    }
    this.priceMinor = priceMinor;
    this.durationMinutes = durationMinutes;
    this.includeInEstimate = includeInEstimate;
    this.commonItem = commonItem;
    this.showInMainMenu = showInMainMenu;
    this.canvasX = canvasX;
    this.canvasY = canvasY;
    boolean completeRouting = routingQueueId != null
        && routingQueueName != null && !routingQueueName.isBlank()
        && routingQueueType != null && !routingQueueType.isBlank();
    boolean emptyRouting = routingQueueId == null
        && (routingQueueName == null || routingQueueName.isBlank())
        && (routingQueueType == null || routingQueueType.isBlank());
    if (!completeRouting && !emptyRouting) {
      throw new IllegalArgumentException("Routing snapshot must be complete or absent");
    }
    this.routingQueueId = routingQueueId;
    this.routingQueueName = optional(routingQueueName, 255);
    this.routingQueueType = optional(routingQueueType, 64);
    this.comment = "MATERIAL".equals(this.nodeType) ? null : optional(comment, 2000);
    this.displayColor = color(displayColor);
    if (forcesCapitalRepair && !"WORK".equals(nodeType)) {
      throw new IllegalArgumentException("Only a WORK can force capital repair");
    }
    boolean completeCharacteristic =
        characteristicId != null
            && characteristicName != null
            && !characteristicName.isBlank();
    boolean emptyCharacteristic =
        characteristicId == null
            && (characteristicName == null || characteristicName.isBlank());
    if ((!completeCharacteristic && !emptyCharacteristic)
        || (completeCharacteristic && !"MATERIAL".equals(nodeType))) {
      throw new IllegalArgumentException(
          "Cabin characteristic snapshot must be complete, absent and material-only");
    }
    this.forcesCapitalRepair = forcesCapitalRepair;
    this.characteristicId = characteristicId;
    this.characteristicName = optional(characteristicName, 255);
  }

  public CatalogNode(
      UUID id,
      UUID catalogVersionId,
      String nodeType,
      String name,
      boolean active,
      UUID parentNodeId,
      boolean furnitureCategory,
      UUID furnitureEquipmentId,
      String furnitureEquipmentName,
      String unit,
      Long priceMinor,
      Integer durationMinutes,
      boolean includeInEstimate,
      boolean commonItem,
      boolean showInMainMenu,
      Integer canvasX,
      Integer canvasY,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      String comment) {
    this(
        id,
        catalogVersionId,
        nodeType,
        name,
        active,
        parentNodeId,
        furnitureCategory,
        furnitureEquipmentId,
        furnitureEquipmentName,
        unit,
        priceMinor,
        durationMinutes,
        includeInEstimate,
        commonItem,
        showInMainMenu,
        canvasX,
        canvasY,
        routingQueueId,
        routingQueueName,
        routingQueueType,
        comment,
        null);
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

  private static String color(String value) {
    String normalized = optional(value, 7);
    if (normalized == null) return null;
    if (!normalized.matches("^#[0-9A-Fa-f]{6}$")) {
      throw new IllegalArgumentException("displayColor is invalid");
    }
    return normalized.toUpperCase(java.util.Locale.ROOT);
  }

  public UUID getId() { return id; }
  public UUID getRowId() { return rowId; }
  public UUID getCatalogVersionId() { return catalogVersionId; }
  public String getNodeType() { return nodeType; }
  public String getName() { return name; }
  public boolean isActive() { return active; }
  public UUID getParentNodeId() { return parentNodeId; }
  public boolean isFurnitureCategory() { return furnitureCategory; }
  public UUID getFurnitureEquipmentId() { return furnitureEquipmentId; }
  public String getFurnitureEquipmentName() { return furnitureEquipmentName; }
  public String getUnit() { return unit; }
  public Long getPriceMinor() { return priceMinor; }
  public Integer getDurationMinutes() { return durationMinutes; }
  public boolean isIncludeInEstimate() { return includeInEstimate; }
  public boolean isCommonItem() { return commonItem; }
  public boolean isShowInMainMenu() { return showInMainMenu; }
  public Integer getCanvasX() { return canvasX; }
  public Integer getCanvasY() { return canvasY; }
  public UUID getRoutingQueueId() { return routingQueueId; }
  public String getRoutingQueueName() { return routingQueueName; }
  public String getRoutingQueueType() { return routingQueueType; }
  public String getComment() { return "MATERIAL".equals(nodeType) ? null : comment; }
  public String getDisplayColor() { return displayColor; }
  public boolean isForcesCapitalRepair() { return forcesCapitalRepair; }
  public UUID getCharacteristicId() { return characteristicId; }
  public String getCharacteristicName() { return characteristicName; }
}
