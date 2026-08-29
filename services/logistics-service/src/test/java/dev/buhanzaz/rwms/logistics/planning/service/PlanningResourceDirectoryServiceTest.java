package dev.buhanzaz.rwms.logistics.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseDriverIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseSupportLink;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverAvailabilityKind;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverEmploymentType;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies fail-closed validation of owner-provided planner resources. */
class PlanningResourceDirectoryServiceTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000741");

  @Test
  void exposesOnlyTheValidatedOwnerFields() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    WarehouseIdentity warehouse =
        new WarehouseIdentity(
            WAREHOUSE,
            4,
            true,
            "СПБ",
            "Санкт-Петербург",
            "Кубинская улица, 75",
            "Europe/Moscow");
    UUID worker = UUID.randomUUID();
    when(dependencies.listWarehouseIdentities()).thenReturn(List.of(warehouse));
    when(dependencies.readWarehouseIdentity(WAREHOUSE)).thenReturn(warehouse);
    when(dependencies.listWarehouseDrivers(WAREHOUSE))
        .thenReturn(List.of(staff(worker, " Анна ")));
    PlanningResourceDirectoryService service =
        new PlanningResourceDirectoryService(dependencies);

    assertThat(service.warehouses())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.warehouseId()).isEqualTo(WAREHOUSE);
              assertThat(value.address()).isEqualTo("Кубинская улица, 75");
            });
    assertThat(service.drivers(WAREHOUSE))
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.workerId()).isEqualTo(worker);
              assertThat(value.displayName()).isEqualTo("Анна");
              assertThat(value.employmentType()).isEqualTo(PlanningDriverEmploymentType.STAFF);
              assertThat(value.operationalWarehouseId()).isEqualTo(WAREHOUSE);
            });
  }

  @Test
  void exposesBoundedIncomingContractorAvailabilityForTheRequestedPlanningInstant() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    WarehouseIdentity warehouse =
        new WarehouseIdentity(
            WAREHOUSE, 4, true, "ВН", "Великий Новгород", null, "Europe/Moscow");
    UUID worker = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.parse("2026-09-14T12:00:00Z");
    OffsetDateTime until = at.plusHours(6);
    when(dependencies.readWarehouseIdentity(WAREHOUSE)).thenReturn(warehouse);
    when(dependencies.listWarehouseDrivers(WAREHOUSE, at, true))
        .thenReturn(
            List.of(
                new WarehouseDriverIdentity(
                    worker,
                    " Подрядчик ",
                    "CONTRACTOR",
                    " +79990000000 ",
                    WAREHOUSE,
                    at,
                    until,
                    "INCOMING")));

    PlanningResourceDirectoryService service =
        new PlanningResourceDirectoryService(dependencies);

    assertThat(service.drivers(WAREHOUSE, at, true))
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.employmentType())
                  .isEqualTo(PlanningDriverEmploymentType.CONTRACTOR);
              assertThat(value.availabilityKind())
                  .isEqualTo(PlanningDriverAvailabilityKind.INCOMING);
              assertThat(value.phone()).isEqualTo("+79990000000");
              assertThat(value.availableFrom()).isEqualTo(at);
              assertThat(value.availableUntil()).isEqualTo(until);
            });
  }

  @Test
  void exposesCoordinateReadyDirectedSupportLinksWithoutReinterpretingTheirCalendar() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    UUID supportWarehouseId = UUID.randomUUID();
    UUID linkId = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.parse("2026-09-15T08:30:00Z");
    WarehouseIdentity served =
        new WarehouseIdentity(
            WAREHOUSE,
            5,
            true,
            "Региональный",
            "Город B",
            null,
            new BigDecimal("58.500000"),
            new BigDecimal("31.200000"),
            "Europe/Moscow",
            true);
    WarehouseIdentity support =
        new WarehouseIdentity(
            supportWarehouseId,
            3,
            true,
            "Опорный",
            "Город A",
            null,
            new BigDecimal("59.900000"),
            new BigDecimal("30.300000"),
            "Europe/Moscow",
            false);
    when(dependencies.readWarehouseIdentity(WAREHOUSE)).thenReturn(served);
    when(dependencies.listWarehouseSupportLinks(WAREHOUSE, at))
        .thenReturn(
            List.of(
                new WarehouseSupportLink(
                    linkId,
                    2,
                    support,
                    served,
                    1,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    java.util.Set.of(java.time.DayOfWeek.TUESDAY),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    LocalTime.of(8, 0),
                    LocalTime.of(20, 0))));

    PlanningResourceDirectoryService service =
        new PlanningResourceDirectoryService(dependencies);

    assertThat(service.supportLinks(WAREHOUSE, at))
        .singleElement()
        .satisfies(
            link -> {
              assertThat(link.supportLinkId()).isEqualTo(linkId);
              assertThat(link.supportWarehouse().warehouseId()).isEqualTo(supportWarehouseId);
              assertThat(link.supportWarehouse().routingReady()).isTrue();
              assertThat(link.servedWarehouse().representative()).isTrue();
              assertThat(link.allowDrivers()).isTrue();
            });
  }

  @Test
  void rejectsInactiveWarehouseAndDuplicateDriverIdentity() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    PlanningResourceDirectoryService service =
        new PlanningResourceDirectoryService(dependencies);
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(
            new WarehouseIdentity(
                WAREHOUSE, 4, false, "СПБ", "Санкт-Петербург", null, "Europe/Moscow"));

    assertThatThrownBy(() -> service.drivers(WAREHOUSE))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("invalid active warehouse");

    WarehouseIdentity active =
        new WarehouseIdentity(
            WAREHOUSE, 4, true, "СПБ", "Санкт-Петербург", null, "Europe/Moscow");
    UUID worker = UUID.randomUUID();
    when(dependencies.readWarehouseIdentity(WAREHOUSE)).thenReturn(active);
    when(dependencies.listWarehouseDrivers(WAREHOUSE))
        .thenReturn(
            List.of(
                staff(worker, "Анна"),
                staff(worker, "Анна")));

    assertThatThrownBy(() -> service.drivers(WAREHOUSE))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("invalid warehouse driver directory");
  }

  @Test
  void rejectsAMissingTaskBoardDirectoryInsteadOfFabricatingDrivers() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(
            new WarehouseIdentity(
                WAREHOUSE, 4, true, "СПБ", "Санкт-Петербург", null, "Europe/Moscow"));
    when(dependencies.listWarehouseDrivers(WAREHOUSE)).thenReturn(null);

    PlanningResourceDirectoryService service =
        new PlanningResourceDirectoryService(dependencies);

    assertThatThrownBy(() -> service.drivers(WAREHOUSE))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("no warehouse driver directory");
  }

  private static WarehouseDriverIdentity staff(UUID workerId, String displayName) {
    return new WarehouseDriverIdentity(
        workerId, displayName, "STAFF", null, WAREHOUSE, null, null, "HOME");
  }
}
