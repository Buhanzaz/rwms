package dev.buhanzaz.rwms.taskboard.kpi;

/** Raw duration and task-count evidence consumed by the versioned KPI formula. */
public record KpiRawComponents(
    long completedBudgetSeconds,
    long earnedRemainingSeconds,
    long activeSeconds,
    long penalizedIdleSeconds,
    long completedTaskCount) {

  public KpiRawComponents {
    if (completedBudgetSeconds < 0
        || earnedRemainingSeconds < 0
        || activeSeconds < 0
        || penalizedIdleSeconds < 0
        || completedTaskCount < 0) {
      throw new IllegalArgumentException("KPI counters must not be negative");
    }
    if (earnedRemainingSeconds > completedBudgetSeconds) {
      throw new IllegalArgumentException(
          "KPI earned remaining seconds must not exceed completed budget");
    }
    if (completedBudgetSeconds == 0 && completedTaskCount > 0) {
      throw new IllegalArgumentException("Completed tasks require a positive completed budget");
    }
  }

  public KpiRawComponents plus(KpiRawComponents other) {
    return new KpiRawComponents(
        Math.addExact(completedBudgetSeconds, other.completedBudgetSeconds),
        Math.addExact(earnedRemainingSeconds, other.earnedRemainingSeconds),
        Math.addExact(activeSeconds, other.activeSeconds),
        Math.addExact(penalizedIdleSeconds, other.penalizedIdleSeconds),
        Math.addExact(completedTaskCount, other.completedTaskCount));
  }
}
