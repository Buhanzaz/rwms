package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "finding_plan_stage")
public class FindingPlanStage {
  @Id @Column(name = "row_id", nullable = false) private UUID id;
  @Column(name = "finding_id", nullable = false) private UUID findingId;
  @Column(name = "finding_revision", nullable = false) private long findingRevision;
  @Column(name = "stage_no", nullable = false) private int stageNo;
  @Column(name = "stage_kind", nullable = false, length = 32) private String stageKind;
  @Column(name = "routing_queue_id", nullable = false) private UUID routingQueueId;
  @Column(name = "routing_queue_code", nullable = false, length = 64) private String routingQueueCode;
  @Column(name = "routing_queue_kind", nullable = false, length = 64) private String routingQueueKind;
  @Column(name = "movement_required", nullable = false) private boolean movementRequired;
  @Column(name = "photo_required", nullable = false) private boolean photoRequired;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "safe_snapshot", nullable = false, columnDefinition = "jsonb")
  private String safeSnapshot;

  protected FindingPlanStage() {}

  public FindingPlanStage(UUID findingId, long revision, int stageNo, String stageKind,
      UUID queueId, String queueCode, String queueKind, boolean movementRequired,
      boolean photoRequired, String snapshot) {
    id = UUID.randomUUID(); this.findingId = findingId; findingRevision = revision;
    this.stageNo = stageNo; this.stageKind = stageKind; routingQueueId = queueId;
    routingQueueCode = queueCode; routingQueueKind = queueKind;
    this.movementRequired = movementRequired; this.photoRequired = photoRequired;
    safeSnapshot = snapshot;
  }

  public UUID getFindingId() { return findingId; }
  public long getFindingRevision() { return findingRevision; }
  public int getStageNo() { return stageNo; }
  public String getStageKind() { return stageKind; }
  public UUID getRoutingQueueId() { return routingQueueId; }
  public String getRoutingQueueCode() { return routingQueueCode; }
  public String getRoutingQueueKind() { return routingQueueKind; }
  public boolean isMovementRequired() { return movementRequired; }
  public boolean isPhotoRequired() { return photoRequired; }
}
