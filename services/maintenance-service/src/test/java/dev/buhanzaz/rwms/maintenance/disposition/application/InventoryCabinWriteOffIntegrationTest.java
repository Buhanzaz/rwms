package dev.buhanzaz.rwms.maintenance.disposition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryCabinWriteOffRequest;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies inventory-owned cabin shortages enter the existing approval-based write-off saga. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.maintenance.property-disposition.initial-delay=1h",
      "rwms.maintenance.property-disposition.delay=1h",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryCabinWriteOffIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired private PropertyDispositionApplicationService dispositions;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private MaintenanceDependencyGateway dependencies;

  @BeforeEach
  void clearDecisions() {
    jdbc.execute(
        "truncate table property_disposition_processing_attempt, "
            + "property_disposition_processing_claim, "
            + "property_disposition_contents_snapshot_line, "
            + "property_disposition_decision cascade");
  }

  @Test
  void missingCabinCreatesOneReplaySafePendingWriteOffWithAllContentsDisposed() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tableId = UUID.randomUUID();
    UUID chairId = UUID.randomUUID();
    var request =
        new CreateInventoryCabinWriteOffRequest(
            inventoryId,
            findingId,
            warehouseId,
            cabinId,
            17L,
            "Бытовка не найдена после оформления всех отгрузок",
            null);
    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN, cabinId, warehouseId))
        .thenReturn(
            new MaintenanceDependencyGateway.PropertyAssetSnapshot(
                MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
                cabinId,
                "БТ-117",
                warehouseId,
                17,
                "RENTED",
                null,
                null,
                List.of(
                    new MaintenanceDependencyGateway.PropertyAssetContentSnapshot(
                        tableId, "Стол", "шт.", 2, 31),
                    new MaintenanceDependencyGateway.PropertyAssetContentSnapshot(
                        chairId, "Стул", "шт.", 4, 29)),
                false,
                false,
                false,
                true));

    var first = dispositions.createInventoryCabinWriteOff(UUID.randomUUID(), request);

    assertThat(first.replayed()).isFalse();
    assertThat(first.response().assetKind()).isEqualTo(PropertyDispositionAssetKind.CABIN);
    assertThat(first.response().assetId()).isEqualTo(cabinId);
    assertThat(first.response().disposition()).isEqualTo(PropertyDispositionKind.WRITE_OFF);
    assertThat(first.response().source()).isEqualTo(PropertyDispositionSource.INVENTORY);
    assertThat(first.response().state()).isEqualTo(PropertyDispositionState.PENDING_APPROVAL);
    assertThat(first.response().inventorySessionId()).isEqualTo(inventoryId);
    assertThat(first.response().findingId()).isEqualTo(findingId);
    assertThat(first.response().contentsPlan().mode())
        .isEqualTo(PropertyDispositionContentsMode.DISPOSE_WITH_CABIN);
    assertThat(first.response().contentsPlan().lines())
        .hasSize(2)
        .allSatisfy(
            line -> {
              assertThat(line.moveToStockQuantity()).isZero();
              assertThat(line.disposeQuantity()).isEqualTo(line.currentQuantity());
            });

    clearInvocations(dependencies);
    var replay = dispositions.createInventoryCabinWriteOff(UUID.randomUUID(), request);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(first.response());
    verifyNoInteractions(dependencies);
    assertThatThrownBy(
            () ->
                dispositions.createInventoryCabinWriteOff(
                    UUID.randomUUID(),
                    new CreateInventoryCabinWriteOffRequest(
                        inventoryId,
                        findingId,
                        warehouseId,
                        cabinId,
                        17L,
                        "Другая причина",
                        null)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("different property disposition");
    verifyNoInteractions(dependencies);
  }
}
