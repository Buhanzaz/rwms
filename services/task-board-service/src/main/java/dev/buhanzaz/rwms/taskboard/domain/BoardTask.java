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
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Check;

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

  @Column(name = "planned_duration_minutes")
  private Integer plannedDurationMinutes;

  @Column(name = "deadline_at")
  private OffsetDateTime deadlineAt;

  @Column(name = "done_at")
  private OffsetDateTime doneAt;

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

  public OffsetDateTime getDoneAt() {
    return doneAt;
  }

  public void setDoneAt(OffsetDateTime v) {
    doneAt = v;
  }
}
