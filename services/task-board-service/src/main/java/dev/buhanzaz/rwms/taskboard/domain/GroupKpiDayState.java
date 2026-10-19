package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mutable accumulator for one worker group's KPI evidence on one warehouse-local date.
 *
 * <p>Published daily facts are derived from this task-board-owned state; analytics consumes those
 * facts as a read projection and does not recalculate task execution.
 */
@Entity
@Table(
    name = "group_kpi_day_state",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_group_kpi_day",
            columnNames = {"warehouse_id", "worker_group_id", "local_date"}))
public class GroupKpiDayState extends AbstractVersionedEntity {
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "worker_group_id", nullable = false)
  private UUID workerGroupId;

  @Column(name = "local_date", nullable = false)
  private LocalDate localDate;

  @Column(name = "data_available_from", nullable = false)
  private LocalDate dataAvailableFrom;

  @Column(name = "formula_version", nullable = false, length = 32)
  private String formulaVersion = "kpi-v1";

  @Column(name = "completed_budget_seconds", nullable = false)
  private long completedBudgetSeconds;

  @Column(name = "earned_remaining_seconds", nullable = false)
  private long earnedRemainingSeconds;

  @Column(name = "active_seconds", nullable = false)
  private long activeSeconds;

  @Column(name = "penalized_idle_seconds", nullable = false)
  private long penalizedIdleSeconds;

  @Column(name = "completed_task_count", nullable = false)
  private long completedTaskCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "open_state", length = 32)
  private GroupKpiOpenState openState;

  @Column(name = "open_state_started_at")
  private OffsetDateTime openStateStartedAt;

  @Column(name = "penalty_starts_at")
  private OffsetDateTime penaltyStartsAt;

  @Column(name = "next_transition_at")
  private OffsetDateTime nextTransitionAt;

  @Column(name = "evidence_as_of", nullable = false)
  private OffsetDateTime asOf;

  protected GroupKpiDayState() {}

  public static GroupKpiDayState create(
      UUID evidenceId,
      UUID warehouseId,
      UUID workerGroupId,
      LocalDate localDate,
      LocalDate dataAvailableFrom,
      OffsetDateTime asOf) {
    var value = new GroupKpiDayState();
    value.assignReviewedId(evidenceId);
    value.warehouseId = warehouseId;
    value.workerGroupId = workerGroupId;
    value.localDate = localDate;
    value.dataAvailableFrom = dataAvailableFrom;
    value.asOf = asOf;
    return value;
  }

  public void addActiveSeconds(long value) {
    activeSeconds = Math.addExact(activeSeconds, nonNegative(value));
  }

  public void subtractActiveSeconds(long value) {
    value = nonNegative(value);
    if (value > activeSeconds) {
      throw new IllegalStateException("Нельзя исключить больше рабочего времени, чем учтено");
    }
    activeSeconds -= value;
  }

  public void addPenalizedIdleSeconds(long value) {
    penalizedIdleSeconds = Math.addExact(penalizedIdleSeconds, nonNegative(value));
  }

  public void addCompletedSegment(long budgetSeconds, long activeSegmentSeconds) {
    addCompletedSegment(budgetSeconds, activeSegmentSeconds, true);
  }

  /** Partial work contributes time evidence without claiming that the task is finished. */
  public void addCompletedSegment(long budgetSeconds, long activeSegmentSeconds, boolean taskCompleted) {
    budgetSeconds = positive(budgetSeconds);
    activeSegmentSeconds = nonNegative(activeSegmentSeconds);
    completedBudgetSeconds = Math.addExact(completedBudgetSeconds, budgetSeconds);
    earnedRemainingSeconds =
        Math.addExact(
            earnedRemainingSeconds, Math.max(Math.subtractExact(budgetSeconds, activeSegmentSeconds), 0));
    if (taskCompleted) completedTaskCount = Math.addExact(completedTaskCount, 1);
  }

  public void transition(
      GroupKpiOpenState state,
      OffsetDateTime stateStartedAt,
      OffsetDateTime penaltyStartsAt,
      OffsetDateTime nextTransitionAt,
      OffsetDateTime asOf) {
    openState = state;
    openStateStartedAt = state == null ? null : stateStartedAt;
    this.penaltyStartsAt = state == null ? null : penaltyStartsAt;
    this.nextTransitionAt = nextTransitionAt;
    this.asOf = asOf;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getWorkerGroupId() {
    return workerGroupId;
  }

  public LocalDate getLocalDate() {
    return localDate;
  }

  public LocalDate getDataAvailableFrom() {
    return dataAvailableFrom;
  }

  public String getFormulaVersion() {
    return formulaVersion;
  }

  public long getCompletedBudgetSeconds() {
    return completedBudgetSeconds;
  }

  public long getEarnedRemainingSeconds() {
    return earnedRemainingSeconds;
  }

  public long getActiveSeconds() {
    return activeSeconds;
  }

  public long getPenalizedIdleSeconds() {
    return penalizedIdleSeconds;
  }

  public long getCompletedTaskCount() {
    return completedTaskCount;
  }

  public GroupKpiOpenState getOpenState() {
    return openState;
  }

  public OffsetDateTime getOpenStateStartedAt() {
    return openStateStartedAt;
  }

  public OffsetDateTime getPenaltyStartsAt() {
    return penaltyStartsAt;
  }

  public OffsetDateTime getNextTransitionAt() {
    return nextTransitionAt;
  }

  public OffsetDateTime getAsOf() {
    return asOf;
  }

  private static long nonNegative(long value) {
    if (value < 0) throw new IllegalArgumentException("Время KPI не может быть отрицательным");
    return value;
  }

  private static long positive(long value) {
    if (value <= 0) throw new IllegalArgumentException("Бюджет KPI должен быть положительным");
    return value;
  }
}
