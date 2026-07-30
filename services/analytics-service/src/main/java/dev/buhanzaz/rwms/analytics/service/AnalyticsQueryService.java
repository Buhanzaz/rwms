package dev.buhanzaz.rwms.analytics.service;

import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels;
import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.CoverageStatus;
import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.PeriodType;
import dev.buhanzaz.rwms.analytics.domain.GroupKpiDayEvidence;
import dev.buhanzaz.rwms.analytics.mapper.AnalyticsGroupKpiMapper;
import dev.buhanzaz.rwms.analytics.repository.GroupKpiDayEvidenceRepository;
import dev.buhanzaz.rwms.analytics.security.AnalyticsAuthorizer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsQueryService {
  public static final String FORMULA_VERSION = "kpi-v1";

  private final GroupKpiDayEvidenceRepository evidence;
  private final AnalyticsAuthorizer authorizer;
  private final AnalyticsGroupKpiMapper mapper;
  private final Clock clock;

  public AnalyticsQueryService(
      GroupKpiDayEvidenceRepository evidence,
      AnalyticsAuthorizer authorizer,
      AnalyticsGroupKpiMapper mapper,
      Clock clock) {
    this.evidence = evidence;
    this.authorizer = authorizer;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AnalyticsApiModels.GroupKpiResponse get(
      UUID warehouseId,
      PeriodType periodType,
      Integer year,
      Integer month,
      Integer day,
      Integer quarter,
      Jwt jwt) {
    authorizer.requireWarehouseView(jwt, warehouseId);
    KpiPeriod period = KpiPeriod.resolve(periodType, year, month, day, quarter);
    OffsetDateTime queryAsOf = OffsetDateTime.now(clock);
    LocalDate dataAvailableFrom = evidence.findDataAvailableFrom(warehouseId).orElse(null);
    LocalDate latestEvidenceDate = evidence.findLatestEvidenceDate(warehouseId).orElse(null);
    List<GroupKpiDayEvidence> rows =
        evidence.findAllByWarehouseIdAndLocalDateBetweenOrderByWorkerGroupIdAscLocalDateAsc(
            warehouseId, period.start(), period.end());

    if (dataAvailableFrom == null || period.end().isBefore(dataAvailableFrom) || rows.isEmpty()) {
      return new AnalyticsApiModels.GroupKpiResponse(
          warehouseId,
          period.type(),
          period.start(),
          period.end(),
          null,
          null,
          CoverageStatus.NO_DATA,
          dataAvailableFrom,
          FORMULA_VERSION,
          queryAsOf,
          List.of());
    }

    Map<UUID, MutableTotals> totals = new LinkedHashMap<>();
    OffsetDateTime responseAsOf = null;
    for (GroupKpiDayEvidence row : rows) {
      if (!FORMULA_VERSION.equals(row.getFormulaVersion())) {
        throw new IllegalStateException("ANALYTICS_FORMULA_VERSION_MIXED");
      }
      MutableTotals total =
          totals.computeIfAbsent(row.getWorkerGroupId(), ignored -> new MutableTotals());
      OffsetDateTime rowQueryAsOf =
          row.getLocalDate().equals(latestEvidenceDate) ? queryAsOf : row.getAsOf();
      total.completedBudgetSeconds =
          Math.addExact(total.completedBudgetSeconds, row.getCompletedBudgetSeconds());
      total.earnedRemainingSeconds =
          Math.addExact(total.earnedRemainingSeconds, row.getEarnedRemainingSeconds());
      total.activeSeconds =
          Math.addExact(total.activeSeconds, row.activeSecondsAt(rowQueryAsOf));
      total.penalizedIdleSeconds =
          Math.addExact(
              total.penalizedIdleSeconds, row.penalizedIdleSecondsAt(rowQueryAsOf));
      total.completedTaskCount =
          Math.addExact(total.completedTaskCount, row.getCompletedTaskCount());
      OffsetDateTime rowAsOf = row.projectedAsOf(rowQueryAsOf);
      if (responseAsOf == null || rowAsOf.isAfter(responseAsOf)) responseAsOf = rowAsOf;
    }

    List<GroupResult> groupResults = new ArrayList<>();
    totals.forEach(
        (groupId, total) -> {
          KpiFormula.Result calculated =
              KpiFormula.calculate(
                  total.completedBudgetSeconds,
                  total.earnedRemainingSeconds,
                  total.activeSeconds,
                  total.penalizedIdleSeconds);
          groupResults.add(
              new GroupResult(
                  groupId,
                  KpiFormula.round(calculated.kpi()),
                  KpiFormula.round(calculated.speed()),
                  KpiFormula.round(calculated.utilization()),
                  total.completedTaskCount,
                  total.completedBudgetSeconds,
                  total.activeSeconds,
                  total.penalizedIdleSeconds));
        });
    groupResults.sort(Comparator.comparing(result -> result.workerGroupId().toString()));

    LocalDate coverageStart =
        period.start().isAfter(dataAvailableFrom) ? period.start() : dataAvailableFrom;
    LocalDate coverageEnd =
        latestEvidenceDate == null || period.end().isBefore(latestEvidenceDate)
            ? period.end()
            : latestEvidenceDate;
    CoverageStatus status =
        period.start().isBefore(dataAvailableFrom)
            ? CoverageStatus.PARTIAL
            : contains(period, latestEvidenceDate)
                ? CoverageStatus.PROVISIONAL
                : CoverageStatus.COMPLETE;
    return new AnalyticsApiModels.GroupKpiResponse(
        warehouseId,
        period.type(),
        period.start(),
        period.end(),
        coverageStart,
        coverageEnd,
        status,
        dataAvailableFrom,
        FORMULA_VERSION,
        responseAsOf == null ? queryAsOf : responseAsOf,
        groupResults.stream().map(mapper::toApi).toList());
  }

  private static boolean contains(KpiPeriod period, LocalDate date) {
    return date != null && !date.isBefore(period.start()) && !date.isAfter(period.end());
  }

  public record GroupResult(
      UUID workerGroupId,
      BigDecimal kpi,
      BigDecimal speed,
      BigDecimal utilization,
      long completedTaskCount,
      long completedBudgetSeconds,
      long activeSeconds,
      long penalizedIdleSeconds) {}

  private static final class MutableTotals {
    private long completedTaskCount;
    private long completedBudgetSeconds;
    private long earnedRemainingSeconds;
    private long activeSeconds;
    private long penalizedIdleSeconds;
  }
}
