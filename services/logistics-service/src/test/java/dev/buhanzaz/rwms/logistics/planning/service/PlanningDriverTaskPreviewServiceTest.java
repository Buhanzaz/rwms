package dev.buhanzaz.rwms.logistics.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseTimeZone;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningProvisionalEtaUpdateRequest;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the external task/version and future-local-day fences for ETA refreshes. */
class PlanningDriverTaskPreviewServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-01T09:00:00Z");

  @Test
  void refreshesOnlyARealFutureScheduledDayPreview() {
    UUID warehouseId = UUID.randomUUID();
    DriverLogisticsTask task = task(warehouseId, LocalDate.of(2026, 9, 4));
    DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(dependencies.warehouseTimeZoneAt(
            warehouseId, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
        .thenReturn(
            new WarehouseTimeZone(
                warehouseId, "Europe/Moscow", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    PlanningDriverTaskPreviewService service =
        new PlanningDriverTaskPreviewService(tasks, dependencies, Clock.fixed(NOW, ZoneOffset.UTC));
    OffsetDateTime eta = OffsetDateTime.parse("2026-09-04T08:30:00Z");

    var response =
        service.replace(
            task.getExternalTaskId(),
            new PlanningProvisionalEtaUpdateRequest(task.getVersion(), UUID.randomUUID(), 2L, eta));

    assertThat(response.provisionalEta()).isEqualTo(eta);
    assertThat(response.taskVersion()).isEqualTo(task.getVersion());
  }

  @Test
  void rejectsTodayOrAnEtaFromAnotherWarehouseLocalDay() {
    UUID warehouseId = UUID.randomUUID();
    DriverLogisticsTask task = task(warehouseId, LocalDate.of(2026, 9, 1));
    DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(dependencies.warehouseTimeZoneAt(
            warehouseId, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
        .thenReturn(
            new WarehouseTimeZone(
                warehouseId, "Europe/Moscow", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    PlanningDriverTaskPreviewService service =
        new PlanningDriverTaskPreviewService(tasks, dependencies, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                service.replace(
                    task.getExternalTaskId(),
                    new PlanningProvisionalEtaUpdateRequest(
                        task.getVersion(),
                        UUID.randomUUID(),
                        2L,
                        OffsetDateTime.parse("2026-09-01T12:00:00Z"))))
        .isInstanceOf(LogisticsConflictException.class);
  }

  private static DriverLogisticsTask task(UUID warehouseId, LocalDate scheduledDate) {
    return DriverLogisticsTask.create(
        warehouseId,
        UUID.randomUUID(),
        null,
        DriverTaskSourceType.LOGISTICS_DOCUMENT,
        UUID.randomUUID(),
        DriverTaskKind.SHIPMENT,
        DriverTaskPlanningMode.FIXED_DATE,
        scheduledDate,
        3,
        null,
        "БЫТ-102",
        UUID.randomUUID(),
        DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
        null,
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64));
  }
}
