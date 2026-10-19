package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseLifecycleOperationsTest {
  private final MaintenanceDependencyGateway dependencies =
      mock(MaintenanceDependencyGateway.class);
  private final WarehouseOperationMarkStore marks = mock(WarehouseOperationMarkStore.class);
  private final WarehouseLifecycleOperations operations =
      new WarehouseLifecycleOperations(dependencies, marks);

  @Test
  void rejectsIncomingWorkWhenCanonicalAdmissionDeniesIt() {
    UUID warehouseId = UUID.randomUUID();
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(
            warehouseId,
            MaintenanceDependencyGateway.WarehouseOperationDirection.INCOMING))
        .thenReturn(new MaintenanceDependencyGateway.WarehouseOperationAdmission(
            warehouseId,
            4,
            MaintenanceDependencyGateway.WarehouseLifecycleState.DRAINING,
            MaintenanceDependencyGateway.WarehouseOperationDirection.INCOMING,
            false));

    assertThatThrownBy(() -> operations.requireIncoming(warehouseId))
        .isInstanceOfSatisfying(
            MaintenanceConflictException.class,
            failure -> assertThat(failure.code()).isEqualTo("WAREHOUSE_OPERATION_NOT_ADMITTED"));
  }

  @Test
  void resolvesCalendarDateFromTimezoneEffectiveAtTheOperationInstant() {
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.parse("2026-09-01T20:30:00Z");
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseTimeZoneAt(warehouseId, at))
        .thenReturn(new MaintenanceDependencyGateway.WarehouseTimeZone(
            warehouseId,
            "Asia/Vladivostok",
            OffsetDateTime.parse("2026-09-01T00:00:00Z")));

    assertThat(operations.localDateAt(warehouseId, at))
        .isEqualTo(LocalDate.of(2026, 9, 2));
  }

  @Test
  void recordsOperationOnlyThroughTheTransactionalOutbox() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.parse("2026-08-05T08:00:00Z");

    operations.recordOperation(warehouseId, operationId, at);

    verify(marks).enqueue(warehouseId, operationId, at);
  }
}
