package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Worker result evidence projected from task-board facts for acceptance and rework. */
@Entity
@Table(name = "repair_task_evidence")
public class RepairTaskEvidence {
  @Id
  @Column(name = "evidence_id", nullable = false)
  private UUID evidenceId;

  @Column(name = "aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "repair_id", nullable = false)
  private UUID repairId;

  @Column(name = "repair_stage_id", nullable = false)
  private UUID repairStageId;

  @Column(name = "entry_id", nullable = false)
  private UUID entryId;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "route_index", nullable = false)
  private int routeIndex;

  @Column(name = "worker_id", nullable = false)
  private UUID workerId;

  @Column(name = "worker_group_id")
  private UUID workerGroupId;

  @Column(name = "media_id", nullable = false)
  private UUID mediaId;

  @Column(name = "media_generation", nullable = false)
  private long mediaGeneration;

  @Column(name = "captured_at", nullable = false)
  private OffsetDateTime capturedAt;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "evidence_state", nullable = false, length = 24)
  private String evidenceState;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RepairTaskEvidence() {}

  public static RepairTaskEvidence create(
      UUID evidenceId,
      long aggregateVersion,
      UUID repairId,
      UUID repairStageId,
      UUID entryId,
      UUID taskId,
      int routeIndex,
      UUID workerId,
      UUID workerGroupId,
      UUID mediaId,
      long mediaGeneration,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      String evidenceState) {
    RepairTaskEvidence value = new RepairTaskEvidence();
    value.evidenceId = evidenceId;
    value.repairId = repairId;
    value.repairStageId = repairStageId;
    value.entryId = entryId;
    value.taskId = taskId;
    value.routeIndex = routeIndex;
    value.workerId = workerId;
    value.workerGroupId = workerGroupId;
    value.apply(
        aggregateVersion,
        mediaId,
        mediaGeneration,
        capturedAt,
        recordedAt,
        evidenceState);
    return value;
  }

  public void apply(
      long aggregateVersion,
      UUID mediaId,
      long mediaGeneration,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      String evidenceState) {
    if (aggregateVersion < 0
        || mediaId == null
        || mediaGeneration < 1
        || capturedAt == null
        || recordedAt == null
        || (!"READY".equals(evidenceState) && !"REVIEW_REQUIRED".equals(evidenceState))) {
      throw new IllegalArgumentException("Repair task evidence is invalid");
    }
    if (updatedAt != null && aggregateVersion <= this.aggregateVersion) return;
    this.aggregateVersion = aggregateVersion;
    this.mediaId = mediaId;
    this.mediaGeneration = mediaGeneration;
    this.capturedAt = MaintenanceTime.postgresPrecision(capturedAt);
    this.recordedAt = MaintenanceTime.postgresPrecision(recordedAt);
    this.evidenceState = evidenceState;
    this.updatedAt = MaintenanceTime.now();
  }

  public void requireIdentity(
      UUID repairId,
      UUID repairStageId,
      UUID entryId,
      UUID taskId,
      int routeIndex,
      UUID workerId,
      UUID workerGroupId) {
    if (!this.repairId.equals(repairId)
        || !this.repairStageId.equals(repairStageId)
        || !this.entryId.equals(entryId)
        || !this.taskId.equals(taskId)
        || this.routeIndex != routeIndex
        || !this.workerId.equals(workerId)
        || !java.util.Objects.equals(this.workerGroupId, workerGroupId)) {
      throw new IllegalStateException("Task evidence identity cannot be replaced");
    }
  }

  public UUID getEvidenceId() { return evidenceId; }
  public long getAggregateVersion() { return aggregateVersion; }
  public UUID getRepairId() { return repairId; }
  public UUID getRepairStageId() { return repairStageId; }
  public UUID getEntryId() { return entryId; }
  public UUID getTaskId() { return taskId; }
  public int getRouteIndex() { return routeIndex; }
  public UUID getWorkerId() { return workerId; }
  public UUID getWorkerGroupId() { return workerGroupId; }
  public UUID getMediaId() { return mediaId; }
  public long getMediaGeneration() { return mediaGeneration; }
  public OffsetDateTime getCapturedAt() { return capturedAt; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
  public String getEvidenceState() { return evidenceState; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
