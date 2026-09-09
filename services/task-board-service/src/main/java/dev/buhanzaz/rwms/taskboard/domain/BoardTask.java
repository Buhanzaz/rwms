package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Check;

/**
 * Task-board-owned operational task aggregate.
 *
 * <p>It retains the stable source identity, scheduling lane, priority, terminal state, and route
 * entries while the source domain continues to own the business reason for the work.
 */
@Entity
@Table(
    name = "board_task",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_board_task_external", columnNames = "external_task_id"),
    indexes =
        @Index(
            name = "idx_board_task_warehouse_status",
            columnList = "warehouse_id,status"))
@Check(
    name = "ck_board_task_request_fingerprint",
    constraints = "request_fingerprint is null or request_fingerprint ~ '^[0-9a-f]{64}$'")
@Check(name = "ck_board_task_priority", constraints = "priority between 1 and 5")
@Check(
    name = "ck_board_task_driver_audience",
    constraints =
        "(driver_audience_mode is null and planned_driver_worker_id is null and "
            + "planned_driver_name_snapshot is null) or "
            + "(driver_audience_mode = 'UNASSIGNED' and planned_driver_worker_id is null and "
            + "planned_driver_name_snapshot is null) or "
            + "(driver_audience_mode = 'ASSIGNED_DRIVER' and planned_driver_worker_id is not null "
            + "and planned_driver_name_snapshot is not null) or "
            + "(driver_audience_mode = 'WAREHOUSE_DRIVERS' and planned_driver_worker_id is null "
            + "and planned_driver_name_snapshot is null)")
public class BoardTask extends AbstractVersionedEntity {
  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "external_task_id")
  private UUID externalTaskId;

  @Column(name = "request_fingerprint", length = 64)
  private String requestFingerprint;

  @NotBlank
  @Column(name = "title", nullable = false, length = 256)
  private String title;

  @Column(name = "unit_number", length = 64)
  private String unitNumber;

  @Column(name = "description", length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private TaskStatus status = TaskStatus.ACTIVE;

  @Column(name = "suspended", nullable = false)
  private boolean suspended;

  public boolean isSuspended() {
    return suspended;
  }

  /** Disables unfinished execution without changing the source-owned task lifecycle. */
  public void suspend() {
    if (status != TaskStatus.ACTIVE) throw new IllegalStateException("Task is not active");
    suspended = true;
  }

  /** Restores availability; assignment history is never reactivated implicitly. */
  public void restore() {
    if (status != TaskStatus.ACTIVE) throw new IllegalStateException("Task is not active");
    suspended = false;
  }

  @Column(name = "planned_duration_minutes")
  private Integer plannedDurationMinutes;

  @Column(name = "deadline_at")
  private OffsetDateTime deadlineAt;

  @NotNull
  @Column(name = "scheduled_date", nullable = false)
  private LocalDate scheduledDate;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "task_lane", nullable = false, length = 16)
  private TaskLane lane = TaskLane.SCHEDULED;

  @Column(name = "priority", nullable = false)
  private int priority = 3;

  @Column(name = "pinned", nullable = false)
  private boolean pinned;

  @Column(name = "completion_deadline_enforced", nullable = false)
  private boolean completionDeadlineEnforced;

  @Column(name = "done_at")
  private OffsetDateTime doneAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "driver_audience_mode", length = 32)
  private DriverTaskAudienceMode driverAudienceMode;

  @Column(name = "planned_driver_worker_id")
  private UUID plannedDriverWorkerId;

  @Column(name = "planned_driver_name_snapshot", length = 512)
  private String plannedDriverNameSnapshot;

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public void setWarehouseId(UUID v) {
    warehouseId = v;
  }

  public UUID getExternalTaskId() {
    return externalTaskId;
  }

  public void setExternalTaskId(UUID v) {
    externalTaskId = v;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public void setRequestFingerprint(String requestFingerprint) {
    this.requestFingerprint = requestFingerprint;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String v) {
    title = v;
  }

  public String getUnitNumber() {
    return unitNumber;
  }

  public void setUnitNumber(String v) {
    unitNumber = v;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String v) {
    description = v;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public void setStatus(TaskStatus v) {
    status = v;
  }

  public Integer getPlannedDurationMinutes() {
    return plannedDurationMinutes;
  }

  public void setPlannedDurationMinutes(Integer v) {
    plannedDurationMinutes = v;
  }

  public OffsetDateTime getDeadlineAt() {
    return deadlineAt;
  }

  public void setDeadlineAt(OffsetDateTime v) {
    deadlineAt = v;
  }

  public LocalDate getScheduledDate() {
    return scheduledDate;
  }

  public void setScheduledDate(LocalDate v) {
    if (v == null) throw new IllegalArgumentException("Scheduled date is required");
    scheduledDate = v;
  }

  public TaskLane getLane() {
    return lane;
  }

  public void setLane(TaskLane value) {
    if (value == null) throw new IllegalArgumentException("Task lane is required");
    lane = value;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int v) {
    if (v < 1 || v > 5) {
      throw new IllegalArgumentException("Task priority must be between 1 and 5");
    }
    priority = v;
  }

  public boolean isPinned() {
    return pinned;
  }

  public void setPinned(boolean v) {
    pinned = v;
  }

  public boolean isCompletionDeadlineEnforced() {
    return completionDeadlineEnforced;
  }

  public void setCompletionDeadlineEnforced(boolean v) {
    completionDeadlineEnforced = v;
  }

  public OffsetDateTime getDoneAt() {
    return doneAt;
  }

  public void setDoneAt(OffsetDateTime v) {
    doneAt = v;
  }

  public DriverTaskAudienceMode getDriverAudienceMode() {
    return driverAudienceMode;
  }

  public void setDriverAudienceMode(DriverTaskAudienceMode value) {
    driverAudienceMode = value;
  }

  public UUID getPlannedDriverWorkerId() {
    return plannedDriverWorkerId;
  }

  public void setPlannedDriverWorkerId(UUID value) {
    plannedDriverWorkerId = value;
  }

  public String getPlannedDriverNameSnapshot() {
    return plannedDriverNameSnapshot;
  }

  public void setPlannedDriverNameSnapshot(String value) {
    plannedDriverNameSnapshot = value;
  }
}
