package dev.buhanzaz.rwms.analytics.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AnalyticsApiModels {
  private AnalyticsApiModels() {}

  public enum PeriodType {
    DAY,
    MONTH,
    QUARTER,
    YEAR
  }

  public enum CoverageStatus {
    PROVISIONAL,
    PARTIAL,
    COMPLETE,
    NO_DATA
  }

  public record GroupKpiResponse(
      UUID warehouseId,
      PeriodType periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate coverageStart,
      LocalDate coverageEnd,
      CoverageStatus status,
      LocalDate dataAvailableFrom,
      String formulaVersion,
      OffsetDateTime asOf,
      List<GroupKpi> groups) {
    public GroupKpiResponse {
      groups = List.copyOf(groups);
    }
  }

  public record GroupKpi(
      UUID workerGroupId,
      BigDecimal kpi,
      BigDecimal speed,
      BigDecimal utilization,
      long completedTaskCount,
      long completedBudgetSeconds,
      long activeSeconds,
      long penalizedIdleSeconds) {}
}
