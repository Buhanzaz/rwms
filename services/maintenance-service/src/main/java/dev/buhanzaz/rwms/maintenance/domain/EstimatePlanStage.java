package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "estimate_plan_stage")
public class EstimatePlanStage {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "row_id", nullable = false)
  private UUID rowId;

  @Column(name = "stage_id", nullable = false)
  private UUID id;

  @Column(name = "estimate_id", nullable = false)
  private UUID estimateId;

  @Column(name = "estimate_revision", nullable = false)
  private int estimateRevision;

  @Column(name = "stage_no", nullable = false)
  private int stageNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "stage_kind", nullable = false, length = 32)
  private RepairStageKind stageKind;

  @Column(name = "routing_queue_id", nullable = false)
  private UUID routingQueueId;

  @Column(name = "routing_queue_name", nullable = false, length = 255)
  private String routingQueueName;

  @Column(name = "routing_queue_type", nullable = false, length = 64)
  private String routingQueueType;

  @Column(name = "included_line_ids", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String includedLineIds;

  @Column(name = "primary_line_id")
  private UUID primaryLineId;

  @Column(name = "group_comment", nullable = false, length = 2000)
  private String groupComment;

  @Column(name = "task_deadline")
  private OffsetDateTime taskDeadline;

  protected EstimatePlanStage() {}

  public EstimatePlanStage(
      UUID id,
      UUID estimateId,
      int estimateRevision,
      int stageNo,
      RepairStageKind stageKind,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      OffsetDateTime taskDeadline) {
    this(
        id,
        estimateId,
        estimateRevision,
        stageNo,
        stageKind,
        routingQueueId,
        routingQueueName,
        routingQueueType,
        "[]",
        null,
        "",
        taskDeadline);
  }

  public EstimatePlanStage(
      UUID id,
      UUID estimateId,
      int estimateRevision,
      int stageNo,
      RepairStageKind stageKind,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      String includedLineIds,
      UUID primaryLineId,
      String groupComment,
      OffsetDateTime taskDeadline) {
    if (id == null || estimateId == null || estimateRevision < 1 || stageNo < 0 || stageKind == null
        || routingQueueId == null || routingQueueName == null || routingQueueName.isBlank()
        || routingQueueType == null || routingQueueType.isBlank()
        || includedLineIds == null || groupComment == null || groupComment.length() > 2000) {
      throw new IllegalArgumentException("Estimate plan stage identity is invalid");
    }
    this.id = id;
    this.estimateId = estimateId;
    this.estimateRevision = estimateRevision;
    this.stageNo = stageNo;
    this.stageKind = stageKind;
    this.routingQueueId = routingQueueId;
    this.routingQueueName = routingQueueName.trim();
    this.routingQueueType = routingQueueType.trim();
    this.includedLineIds = includedLineIds;
    this.primaryLineId = primaryLineId;
    this.groupComment = groupComment;
    this.taskDeadline = MaintenanceTime.postgresPrecision(taskDeadline);
  }

  public UUID getId() { return id; }
  public UUID getEstimateId() { return estimateId; }
  public int getEstimateRevision() { return estimateRevision; }
  public int getStageNo() { return stageNo; }
  public RepairStageKind getStageKind() { return stageKind; }
  public UUID getRoutingQueueId() { return routingQueueId; }
  public String getRoutingQueueName() { return routingQueueName; }
  public String getRoutingQueueType() { return routingQueueType; }
  public String getIncludedLineIds() { return includedLineIds; }
  public UUID getPrimaryLineId() { return primaryLineId; }
  public String getGroupComment() { return groupComment; }
  public OffsetDateTime getTaskDeadline() { return taskDeadline; }
}
