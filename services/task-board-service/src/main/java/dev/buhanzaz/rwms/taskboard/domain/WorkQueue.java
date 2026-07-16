package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.Check;

@Entity
@Table(
    name = "work_queue",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_work_queue_code",
            columnNames = {"warehouse_id", "code"}),
    indexes =
        @Index(name = "idx_work_queue_order", columnList = "warehouse_id,sort_order,name"))
@Check(
    name = "ck_work_queue_holding",
    constraints =
        "queue_type = 'HOLDING' or (holding_period_minutes is null and notification_threshold is null and notify_when_threshold_reached = false)")
public class WorkQueue extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @NotBlank
  @Column(name = "code", nullable = false, length = 64)
  private String code;

  @NotBlank
  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "queue_type", nullable = false, length = 32)
  private QueueType type = QueueType.REPAIR;

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

  @PrePersist
  @PreUpdate
  void normalize() {
    code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    name = name == null ? null : name.trim();
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public void setWarehouseId(UUID v) {
    warehouseId = v;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String v) {
    code = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String v) {
    description = v;
  }

  public QueueType getType() {
    return type;
  }

  public void setType(QueueType v) {
    type = v;
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

  public void touch() {
    revisionMarker = UUID.randomUUID();
  }

  public UUID getRevisionMarker() {
    return revisionMarker;
  }
}
