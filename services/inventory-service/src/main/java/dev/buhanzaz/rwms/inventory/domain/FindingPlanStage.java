package dev.buhanzaz.rwms.inventory.domain;

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
@Table(name = "finding_plan_stage")
public class FindingPlanStage {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "row_id", nullable = false)
  private UUID id;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "finding_revision", nullable = false)
  private long findingRevision;

  @Column(name = "stage_no", nullable = false)
  private int stageNo;

  @Column(name = "stage_kind", nullable = false, length = 32)
  private String stageKind;

  @Column(name = "catalog_node_id", nullable = false)
  private UUID catalogNodeId;

  @Column(name = "catalog_node_name", nullable = false, length = 255)
  private String catalogNodeName;

  @Column(name = "routing_queue_id", nullable = false)
  private UUID routingQueueId;

  @Column(name = "routing_queue_name", nullable = false, length = 255)
  private String routingQueueName;

  @Column(name = "routing_queue_type", nullable = false, length = 64)
  private String routingQueueType;

  @Column(name = "movement_required", nullable = false)
  private boolean movementRequired;

  @Column(name = "photo_required", nullable = false)
  private boolean photoRequired;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "safe_snapshot", nullable = false, columnDefinition = "jsonb")
  private String safeSnapshot;

  protected FindingPlanStage() {}

  public FindingPlanStage(
      UUID findingId,
      long findingRevision,
      int stageNo,
      String stageKind,
      UUID catalogNodeId,
      String catalogNodeName,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      boolean movementRequired,
      boolean photoRequired,
      String safeSnapshot) {
    if (findingId == null
        || findingRevision < 0
        || stageNo < 0
        || blank(stageKind, 32)
        || catalogNodeId == null
        || blank(catalogNodeName, 255)
        || routingQueueId == null
        || blank(routingQueueName, 255)
        || blank(routingQueueType, 64)
        || safeSnapshot == null) {
      throw new IllegalArgumentException("Finding plan stage is invalid");
    }
    this.findingId = findingId;
    this.findingRevision = findingRevision;
    this.stageNo = stageNo;
    this.stageKind = stageKind.trim();
    this.catalogNodeId = catalogNodeId;
    this.catalogNodeName = catalogNodeName.trim();
    this.routingQueueId = routingQueueId;
    this.routingQueueName = routingQueueName.trim();
    this.routingQueueType = routingQueueType.trim();
    this.movementRequired = movementRequired;
    this.photoRequired = photoRequired;
    this.safeSnapshot = safeSnapshot;
  }

  private static boolean blank(String value, int max) {
    return value == null || value.isBlank() || value.trim().length() > max;
  }

  public UUID getId() { return id; }
  public UUID getFindingId() { return findingId; }
  public long getFindingRevision() { return findingRevision; }
  public int getStageNo() { return stageNo; }
  public String getStageKind() { return stageKind; }
  public UUID getCatalogNodeId() { return catalogNodeId; }
  public String getCatalogNodeName() { return catalogNodeName; }
  public UUID getRoutingQueueId() { return routingQueueId; }
  public String getRoutingQueueName() { return routingQueueName; }
  public String getRoutingQueueType() { return routingQueueType; }
  public boolean isMovementRequired() { return movementRequired; }
  public boolean isPhotoRequired() { return photoRequired; }
  public String getSafeSnapshot() { return safeSnapshot; }
}
