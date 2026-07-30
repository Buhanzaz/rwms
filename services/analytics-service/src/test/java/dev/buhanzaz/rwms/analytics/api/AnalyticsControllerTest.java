package dev.buhanzaz.rwms.analytics.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.CoverageStatus;
import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.PeriodType;
import dev.buhanzaz.rwms.analytics.service.AnalyticsQueryService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalyticsControllerTest {
  @Test
  void delegatesQuarterFilterAndSerializesCoverage() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    AnalyticsQueryService service = mock(AnalyticsQueryService.class);
    when(service.get(
            eq(warehouseId),
            eq(PeriodType.QUARTER),
            eq(2026),
            eq(null),
            eq(null),
            eq(3),
            nullable(Jwt.class)))
        .thenReturn(
            new AnalyticsApiModels.GroupKpiResponse(
                warehouseId,
                PeriodType.QUARTER,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 30),
                CoverageStatus.PARTIAL,
                LocalDate.of(2026, 7, 15),
                "kpi-v1",
                OffsetDateTime.parse("2026-07-30T12:00:00Z"),
                List.of()));
    Jwt jwt =
        new Jwt(
            "token",
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("sub", "test"));

    var response =
        new AnalyticsController(service)
            .getGroupKpi(warehouseId, PeriodType.QUARTER, 2026, null, null, 3, jwt);

    assertThat(response.status()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(response.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(response.groups()).isEmpty();

    verify(service)
        .get(
            eq(warehouseId),
            eq(PeriodType.QUARTER),
            eq(2026),
            eq(null),
            eq(null),
            eq(3),
            nullable(Jwt.class));
  }

  @Test
  void invalidPeriodTypeReturnsProblemDetails() throws Exception {
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new AnalyticsController(mock(AnalyticsQueryService.class)))
            .setControllerAdvice(new AnalyticsProblemHandler())
            .build();

    mvc.perform(
            get("/api/v1/warehouses/{warehouseId}/group-kpi", UUID.randomUUID())
                .queryParam("periodType", "WEEK")
                .queryParam("year", "2026"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ANALYTICS_INVALID_PERIOD"));
  }
}
