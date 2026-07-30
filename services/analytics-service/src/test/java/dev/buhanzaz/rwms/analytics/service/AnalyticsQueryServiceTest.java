package dev.buhanzaz.rwms.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.CoverageStatus;
import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.PeriodType;
import dev.buhanzaz.rwms.analytics.domain.GroupKpiDayEvidence;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent.KpiDayPayload;
import dev.buhanzaz.rwms.analytics.mapper.AnalyticsGroupKpiMapperImpl;
import dev.buhanzaz.rwms.analytics.repository.GroupKpiDayEvidenceRepository;
import dev.buhanzaz.rwms.analytics.security.AnalyticsAuthorizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AnalyticsQueryServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

  @Test
  void aggregatesRawComponentsAcrossDaysBeforeApplyingFormula() {
    UUID warehouseId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    GroupKpiDayEvidence first =
        evidence(warehouseId, groupId, LocalDate.of(2026, 7, 20), 120, 60, 60, 0, 1);
    GroupKpiDayEvidence second =
        evidence(warehouseId, groupId, LocalDate.of(2026, 7, 21), 0, 0, 30, 30, 0);
    GroupKpiDayEvidenceRepository repository = mock(GroupKpiDayEvidenceRepository.class);
    when(repository.findDataAvailableFrom(warehouseId))
        .thenReturn(Optional.of(LocalDate.of(2026, 7, 15)));
    when(repository.findLatestEvidenceDate(warehouseId))
        .thenReturn(Optional.of(LocalDate.of(2026, 7, 30)));
    when(repository.findAllByWarehouseIdAndLocalDateBetweenOrderByWorkerGroupIdAscLocalDateAsc(
            warehouseId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .thenReturn(List.of(first, second));
    AnalyticsAuthorizer authorizer = mock(AnalyticsAuthorizer.class);
    AnalyticsQueryService service =
        new AnalyticsQueryService(
            repository,
            authorizer,
            new AnalyticsGroupKpiMapperImpl(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    Jwt jwt = mock(Jwt.class);

    var response =
        service.get(warehouseId, PeriodType.MONTH, 2026, 7, null, null, jwt);

    verify(authorizer).requireWarehouseView(jwt, warehouseId);
    assertThat(response.status()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(response.coverageStart()).isEqualTo(LocalDate.of(2026, 7, 15));
    assertThat(response.coverageEnd()).isEqualTo(LocalDate.of(2026, 7, 30));
    assertThat(response.groups()).singleElement().satisfies(
        group -> {
          assertThat(group.speed()).isEqualByComparingTo("50.00");
          assertThat(group.utilization()).isEqualByComparingTo("75.00");
          assertThat(group.kpi()).isEqualByComparingTo("37.50");
          assertThat(group.completedBudgetSeconds()).isEqualTo(120);
          assertThat(group.completedTaskCount()).isEqualTo(1);
        });
  }

  @Test
  void marksLatestEvidenceDateProvisionalAndEarlierDateComplete() {
    UUID warehouseId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    GroupKpiDayEvidence current =
        evidence(warehouseId, groupId, LocalDate.of(2026, 7, 30), 60, 30, 20, 10, 1);
    GroupKpiDayEvidenceRepository repository = mock(GroupKpiDayEvidenceRepository.class);
    when(repository.findDataAvailableFrom(warehouseId))
        .thenReturn(Optional.of(LocalDate.of(2026, 7, 1)));
    when(repository.findLatestEvidenceDate(warehouseId))
        .thenReturn(Optional.of(LocalDate.of(2026, 7, 30)));
    when(repository.findAllByWarehouseIdAndLocalDateBetweenOrderByWorkerGroupIdAscLocalDateAsc(
            warehouseId, LocalDate.of(2026, 7, 30), LocalDate.of(2026, 7, 30)))
        .thenReturn(List.of(current));
    when(repository.findAllByWarehouseIdAndLocalDateBetweenOrderByWorkerGroupIdAscLocalDateAsc(
            warehouseId, LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 29)))
        .thenReturn(List.of(
            evidence(warehouseId, groupId, LocalDate.of(2026, 7, 29), 60, 30, 20, 10, 1)));
    AnalyticsQueryService service =
        new AnalyticsQueryService(
            repository,
            mock(AnalyticsAuthorizer.class),
            new AnalyticsGroupKpiMapperImpl(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(service.get(warehouseId, PeriodType.DAY, 2026, 7, 30, null, mock(Jwt.class)).status())
        .isEqualTo(CoverageStatus.PROVISIONAL);
    assertThat(service.get(warehouseId, PeriodType.DAY, 2026, 7, 29, null, mock(Jwt.class)).status())
        .isEqualTo(CoverageStatus.COMPLETE);
  }

  @Test
  void reportsNoDataForPeriodBeforeActivation() {
    UUID warehouseId = UUID.randomUUID();
    GroupKpiDayEvidenceRepository repository = mock(GroupKpiDayEvidenceRepository.class);
    when(repository.findDataAvailableFrom(warehouseId))
        .thenReturn(Optional.of(LocalDate.of(2026, 7, 1)));
    when(repository.findLatestEvidenceDate(warehouseId))
        .thenReturn(Optional.of(LocalDate.of(2026, 7, 30)));
    when(repository.findAllByWarehouseIdAndLocalDateBetweenOrderByWorkerGroupIdAscLocalDateAsc(
            warehouseId, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)))
        .thenReturn(List.of());
    AnalyticsQueryService service =
        new AnalyticsQueryService(
            repository,
            mock(AnalyticsAuthorizer.class),
            new AnalyticsGroupKpiMapperImpl(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    var response =
        service.get(warehouseId, PeriodType.YEAR, 2025, null, null, null, mock(Jwt.class));

    assertThat(response.status()).isEqualTo(CoverageStatus.NO_DATA);
    assertThat(response.coverageStart()).isNull();
    assertThat(response.coverageEnd()).isNull();
    assertThat(response.groups()).isEmpty();
  }

  private static GroupKpiDayEvidence evidence(
      UUID warehouseId,
      UUID groupId,
      LocalDate date,
      long budget,
      long earned,
      long active,
      long idle,
      long tasks) {
    UUID evidenceId = UUID.randomUUID();
    OffsetDateTime asOf = date.atTime(18, 0).atOffset(ZoneOffset.UTC);
    AnalyticsValidatedEvent event =
        new AnalyticsValidatedEvent(
            "rwms.task-board.group-kpi-day.v1",
            0,
            0,
            UUID.randomUUID(),
            evidenceId,
            0,
            asOf,
            asOf,
            UUID.randomUUID(),
            null,
            "a".repeat(64),
            "{}",
            new KpiDayPayload(
                evidenceId,
                warehouseId,
                groupId,
                date,
                LocalDate.of(2026, 7, 15),
                "kpi-v1",
                budget,
                earned,
                active,
                idle,
                tasks,
                null,
                null,
                null,
                null,
                asOf));
    return GroupKpiDayEvidence.initial(event, asOf);
  }
}
