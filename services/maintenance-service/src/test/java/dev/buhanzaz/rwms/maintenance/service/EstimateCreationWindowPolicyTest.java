package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EstimateCreationWindowPolicyTest {
  private static final UUID WAREHOUSE = UUID.randomUUID();
  private static final UUID RENTAL_ITEM = UUID.randomUUID();
  private static final OffsetDateTime ARRIVED_AT = OffsetDateTime.parse("2026-08-01T20:30:00Z");

  private final EstimateCreationWindowSettingsService settings =
      mock(EstimateCreationWindowSettingsService.class);
  private final WarehouseLifecycleOperations warehouseLifecycle =
      mock(WarehouseLifecycleOperations.class);
  private final MaintenanceDependencyGateway dependencies =
      mock(MaintenanceDependencyGateway.class);
  private final EstimateCreationWindowPolicy policy =
      new EstimateCreationWindowPolicy(settings, warehouseLifecycle, dependencies);

  @Test
  void sevenDayWindowIsInclusiveThroughAugustEighthAndExpiresAugustNinth() {
    when(settings.effectiveDays(WAREHOUSE)).thenReturn(7);
    when(warehouseLifecycle.localDateAt(WAREHOUSE, ARRIVED_AT))
        .thenReturn(LocalDate.of(2026, 8, 1));
    OffsetDateTime augustEighth = OffsetDateTime.parse("2026-08-08T20:59:00Z");
    OffsetDateTime augustNinth = OffsetDateTime.parse("2026-08-09T00:01:00Z");
    when(warehouseLifecycle.localDateAt(WAREHOUSE, augustEighth))
        .thenReturn(LocalDate.of(2026, 8, 8));
    when(warehouseLifecycle.localDateAt(WAREHOUSE, augustNinth))
        .thenReturn(LocalDate.of(2026, 8, 9));

    assertThatCode(() -> policy.requireCreationOpen(WAREHOUSE, ARRIVED_AT, augustEighth))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.requireCreationOpen(WAREHOUSE, ARRIVED_AT, augustNinth))
        .isInstanceOf(MaintenanceValidationException.class)
        .extracting(exception -> ((MaintenanceValidationException) exception).code())
        .isEqualTo("ESTIMATE_CREATION_WINDOW_EXPIRED");
  }

  @Test
  void manualCreationLoadsAndValidatesLogisticsOwnedArrivalIdentity() {
    UUID returnId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    when(dependencies.returnArrival(WAREHOUSE, RENTAL_ITEM))
        .thenReturn(
            new MaintenanceDependencyGateway.ReturnArrival(
                WAREHOUSE, RENTAL_ITEM, returnId, now.minusHours(1)));
    when(settings.effectiveDays(WAREHOUSE)).thenReturn(7);
    when(warehouseLifecycle.localDateAt(
            org.mockito.ArgumentMatchers.eq(WAREHOUSE),
            org.mockito.ArgumentMatchers.any(OffsetDateTime.class)))
        .thenReturn(LocalDate.of(2026, 8, 21));

    assertThatCode(() -> policy.requireManualCreationOpen(WAREHOUSE, RENTAL_ITEM))
        .doesNotThrowAnyException();
    verify(dependencies).returnArrival(WAREHOUSE, RENTAL_ITEM);
  }
}
