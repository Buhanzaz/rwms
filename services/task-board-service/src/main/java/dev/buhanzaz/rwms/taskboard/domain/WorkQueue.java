package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Warehouse-specific physical queue derived from a global definition or logistics driver lane.
 *
 * <p>General queues inherit their initial waiting-task daily-plan count from the global definition,
 * then own the warehouse-local WorkerApp visibility and plan window. Global reconciliation keeps
 * those runtime controls intact while continuing to synchronize shared routing configuration.
 */
@Entity
@Table(
    name = "work_queue",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_work_queue_warehouse_definition",
            columnNames = {"warehouse_id", "definition_id"}),
    indexes =
        @Index(
            name = "idx_work_queue_order",
            columnList = "warehouse_id,sort_order,definition_id,id"))
public class WorkQueue extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "definition_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_work_queue_definition"))
  private QueueDefinition definition;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "hidden", nullable = false)
  private boolean hidden;

  @Column(name = "collapsed", nullable = false)
  private boolean collapsed;

  @Column(name = "holding_period_minutes")
  private Integer holdingPeriodMinutes;

  @Column(name = "notification_threshold")
  private Integer notificationThreshold;

  @Column(name = "notify_when_threshold_reached", nullable = false)
  private boolean notifyWhenThresholdReached;

  @Column(name = "result_photo_min_count", nullable = false)
  private int resultPhotoMinCount = 1;

  @Min(1)
  @Max(50)
  @Column(name = "available_task_limit", nullable = false)
  private int availableTaskLimit = 6;

  @Column(name = "worker_feed_enabled", nullable = false)
  private boolean workerFeedEnabled = true;

  public QueueDefinition getDefinition() {
    return definition;
  }

  public void setDefinition(QueueDefinition value) {
    definition = value;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public void setWarehouseId(UUID v) {
    warehouseId = v;
  }

  public String getName() {
    return definition.getName();
  }

  public String getDescription() {
    return definition.getDescription();
  }

  public QueueType getType() {
    return definition.getType();
  }

  public QueuePurpose getPurpose() {
    return definition.getPurpose();
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int v) {
    sortOrder = v;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean v) {
    active = v;
  }

  public boolean isHidden() {
    return hidden;
  }

  public void setHidden(boolean v) {
    hidden = v;
  }

  public boolean isCollapsed() {
    return collapsed;
  }

  public void setCollapsed(boolean v) {
    collapsed = v;
  }

  public Integer getHoldingPeriodMinutes() {
    return holdingPeriodMinutes;
  }

  public void setHoldingPeriodMinutes(Integer v) {
    holdingPeriodMinutes = v;
  }

  public Integer getNotificationThreshold() {
    return notificationThreshold;
  }

  public void setNotificationThreshold(Integer v) {
    notificationThreshold = v;
  }

  public boolean isNotifyWhenThresholdReached() {
    return notifyWhenThresholdReached;
  }

  public void setNotifyWhenThresholdReached(boolean v) {
    notifyWhenThresholdReached = v;
  }

  public int getResultPhotoMinCount() {
    return resultPhotoMinCount;
  }

  public void setResultPhotoMinCount(int resultPhotoMinCount) {
    this.resultPhotoMinCount = resultPhotoMinCount;
  }

  public int getAvailableTaskLimit() {
    return availableTaskLimit;
  }

  public void setAvailableTaskLimit(int value) {
    if (value < 1 || value > 50) {
      throw new IllegalArgumentException("Размер плана на день должен быть от 1 до 50");
    }
    availableTaskLimit = value;
  }

  public boolean isWorkerFeedEnabled() {
    return workerFeedEnabled;
  }

  public void setWorkerFeedEnabled(boolean value) {
    workerFeedEnabled = value;
  }

  public void touch() {
    revisionMarker = UUID.randomUUID();
  }

  public UUID getRevisionMarker() {
    return revisionMarker;
  }
}
