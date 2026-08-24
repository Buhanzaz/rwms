package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns the global queue identity and policy mirrored by warehouse-specific work queues.
 *
 * <p>The policy includes the initial WorkerApp daily-plan count copied when a warehouse projection
 * is created. Each physical queue then owns its warehouse-local publication switch and plan, so
 * later global reconciliation does not overwrite the manager's operational choice.
 */
@Entity
@Table(
    name = "queue_definition",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_queue_definition_identity",
            columnNames = {"normalized_name", "queue_type"}))
public class QueueDefinition extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @NotBlank
  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @NotBlank
  @Column(name = "normalized_name", nullable = false, length = 128)
  private String normalizedName;

  @Column(name = "description", length = 1000)
  private String description;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "queue_type", nullable = false, length = 32)
  private QueueType type = QueueType.REPAIR;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "queue_purpose", nullable = false, length = 32)
  private QueuePurpose purpose = QueuePurpose.GENERAL;

  /**
   * System-wide presentation order for GENERAL task-board queues.  Warehouse
   * work_queue rows mirror this value but never own it.
   */
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

  @PrePersist
  @PreUpdate
  void normalize() {
    name = name == null ? null : name.trim().replaceAll("\\s+", " ");
    normalizedName = normalizeName(name);
  }

  public static String normalizeName(String value) {
    return value == null
        ? null
        : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    name = value;
    normalizedName = normalizeName(value);
  }

  public String getNormalizedName() {
    return normalizedName;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String value) {
    description = value == null ? null : value.trim();
  }

  public QueueType getType() {
    return type;
  }

  public void setType(QueueType value) {
    type = value;
  }

  public QueuePurpose getPurpose() {
    return purpose;
  }

  public void setPurpose(QueuePurpose value) {
    purpose = value;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int value) {
    sortOrder = value;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean value) {
    active = value;
  }

  public boolean isHidden() {
    return hidden;
  }

  public void setHidden(boolean value) {
    hidden = value;
  }

  public boolean isCollapsed() {
    return collapsed;
  }

  public void setCollapsed(boolean value) {
    collapsed = value;
  }

  public Integer getHoldingPeriodMinutes() {
    return holdingPeriodMinutes;
  }

  public void setHoldingPeriodMinutes(Integer value) {
    holdingPeriodMinutes = value;
  }

  public Integer getNotificationThreshold() {
    return notificationThreshold;
  }

  public void setNotificationThreshold(Integer value) {
    notificationThreshold = value;
  }

  public boolean isNotifyWhenThresholdReached() {
    return notifyWhenThresholdReached;
  }

  public void setNotifyWhenThresholdReached(boolean value) {
    notifyWhenThresholdReached = value;
  }

  public int getResultPhotoMinCount() {
    return resultPhotoMinCount;
  }

  public void setResultPhotoMinCount(int value) {
    resultPhotoMinCount = value;
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

  public UUID getRevisionMarker() {
    return revisionMarker;
  }

  public void touch() {
    revisionMarker = UUID.randomUUID();
  }
}
