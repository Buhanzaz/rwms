package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable-ended interval that attributes an entry budget and active time to one worker group.
 */
@Entity
@Table(name = "group_kpi_responsibility_segment")
public class GroupKpiResponsibilitySegment extends AbstractVersionedEntity {
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "worker_group_id", nullable = false)
  private UUID workerGroupId;

  @Column(name = "queue_entry_id", nullable = false)
  private UUID queueEntryId;

  @Column(name = "budget_seconds", nullable = false)
  private long budgetSeconds;

  @Column(name = "active_seconds", nullable = false)
  private long activeSeconds;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 32)
  private GroupKpiSegmentOutcome outcome = GroupKpiSegmentOutcome.OPEN;

  protected GroupKpiResponsibilitySegment() {}

  public GroupKpiResponsibilitySegment(
      UUID warehouseId,
      UUID workerGroupId,
      UUID queueEntryId,
      long budgetSeconds,
      OffsetDateTime startedAt) {
    if (budgetSeconds < 0) throw new IllegalArgumentException("Бюджет сегмента не может быть отрицательным");
    this.warehouseId = warehouseId;
    this.workerGroupId = workerGroupId;
    this.queueEntryId = queueEntryId;
    this.budgetSeconds = budgetSeconds;
    this.startedAt = startedAt;
  }

  public void addActiveSeconds(long value) {
    if (value < 0) throw new IllegalArgumentException("Активное время не может быть отрицательным");
    activeSeconds = Math.addExact(activeSeconds, value);
  }

  public void complete(OffsetDateTime at) {
    close(GroupKpiSegmentOutcome.COMPLETED, at);
  }

  /** Closes a partial execution with only the newly completed normative budget. */
  public void completePortion(long completedBudget, OffsetDateTime at) {
    if (completedBudget < 0 || completedBudget > budgetSeconds) {
      throw new IllegalArgumentException("Completed budget exceeds responsibility budget");
    }
    budgetSeconds = completedBudget;
    complete(at);
  }

  public void returnToQueue(OffsetDateTime at) {
    close(GroupKpiSegmentOutcome.RETURNED, at);
  }

  private void close(GroupKpiSegmentOutcome next, OffsetDateTime at) {
    if (outcome != GroupKpiSegmentOutcome.OPEN) {
      throw new IllegalStateException("Сегмент ответственности уже закрыт");
    }
    outcome = next;
    finishedAt = at;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getWorkerGroupId() {
    return workerGroupId;
  }

  public UUID getQueueEntryId() {
    return queueEntryId;
  }

  public long getBudgetSeconds() {
    return budgetSeconds;
  }

  public long getActiveSeconds() {
    return activeSeconds;
  }

  public OffsetDateTime getStartedAt() {
    return startedAt;
  }

  public OffsetDateTime getFinishedAt() {
    return finishedAt;
  }

  public GroupKpiSegmentOutcome getOutcome() {
    return outcome;
  }
}
