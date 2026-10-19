package dev.buhanzaz.rwms.analytics.api;

import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.PeriodType;
import dev.buhanzaz.rwms.analytics.service.AnalyticsQueryService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only HTTP adapter for warehouse group KPI queries; all authorization and formula semantics remain in application services. */
@RestController
public class AnalyticsController {
  private final AnalyticsQueryService queries;

  public AnalyticsController(AnalyticsQueryService queries) {
    this.queries = queries;
  }

  @GetMapping("/api/v1/warehouses/{warehouseId}/group-kpi")
  public AnalyticsApiModels.GroupKpiResponse getGroupKpi(
      @PathVariable UUID warehouseId,
      @RequestParam PeriodType periodType,
      @RequestParam Integer year,
      @RequestParam(required = false) Integer month,
      @RequestParam(required = false) Integer day,
      @RequestParam(required = false) Integer quarter,
      @AuthenticationPrincipal Jwt jwt) {
    return queries.get(warehouseId, periodType, year, month, day, quarter, jwt);
  }
}
