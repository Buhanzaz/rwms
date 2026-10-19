package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.TransferLooseFurnitureState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanSnapshot;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanWorkflowState;
import dev.buhanzaz.rwms.logistics.domain.TransferReservationReadiness;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies the exact transfer manifest mapped into the existing WorkerApp content contract. */
class TransferDriverTaskContentServiceTest {
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final TransferDriverTaskContentService service =
      new TransferDriverTaskContentService(
          dependencies, new DriverTaskSourceMediaService(dependencies));

  @Test
  void buildsOrderedCabinFurnitureAndCommentSnapshotWithoutDoubleCountingLooseCargo() {
    UUID source = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID firstCabin = UUID.randomUUID();
    UUID secondCabin = UUID.randomUUID();
    UUID bed = UUID.randomUUID();
    UUID table = UUID.randomUUID();
    UUID bench = UUID.randomUUID();
    UUID firstPhoto = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            source,
            destination,
            LocalDate.of(2026, 9, 14),
            UUID.randomUUID(),
            UUID.randomUUID());
    ReflectionTestUtils.setField(document, "id", documentId);
    ReflectionTestUtils.setField(
        document,
        "createdAt",
        OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC));

    when(dependencies.readWarehouseIdentity(source))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                source, 3, true, "Санкт-Петербург", "", "Europe/Moscow"));
    when(dependencies.readWarehouseIdentity(destination))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                destination, 4, true, "Великий Новгород", "", "Europe/Moscow"));
    when(dependencies.readLogisticsEquipmentAvailability(source))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.EquipmentWarehouseAvailability(
                    bed, "Кровать", true, 30, 8),
                new LogisticsDependencyGateway.EquipmentWarehouseAvailability(
                    table, "Стол", true, 20, 2),
                new LogisticsDependencyGateway.EquipmentWarehouseAvailability(
                    bench, "Лавка", true, 15, 4)));
    when(dependencies.readCabinPhotoPresentationSnapshot(firstCabin))
        .thenReturn(cabin(firstCabin, source, "172"));
    when(dependencies.readCabinPhotoPresentationSnapshot(secondCabin))
        .thenReturn(cabin(secondCabin, source, "311"));
    when(dependencies.readRentalItemSnapshot(firstCabin))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                firstCabin,
                7,
                source,
                "172",
                "FREE",
                List.of(
                    new LogisticsDependencyGateway.EquipmentContent(bed, 3),
                    new LogisticsDependencyGateway.EquipmentContent(table, 1))));
    when(dependencies.readRentalItemSnapshot(secondCabin))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                secondCabin,
                8,
                source,
                "311",
                "FREE",
                List.of(
                    new LogisticsDependencyGateway.EquipmentContent(bed, 4),
                    new LogisticsDependencyGateway.EquipmentContent(table, 1))));
    when(dependencies.readCabinMediaSnapshots(source, List.of(firstCabin, secondCabin)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.CabinMediaSnapshot(
                    firstCabin,
                    1,
                    List.of(
                        new LogisticsDependencyGateway.CabinMediaPhoto(
                            firstPhoto, 3, 0, List.of("thumbnail")))),
                new LogisticsDependencyGateway.CabinMediaSnapshot(secondCabin, 0, List.of())));

    TransferPlanSnapshot snapshot =
        transferSnapshot(firstCabin, secondCabin, bed, table, bench);
    var content = service.build(document, snapshot);

    assertThat(content.taskText())
        .isEqualTo("Санкт-Петербург → Великий Новгород\nБытовки: №172, №311");
    assertThat(content.works())
        .extracting(value -> value.name())
        .containsExactly(
            "Прибыть на склад «Санкт-Петербург»",
            "Загрузить бытовку №172",
            "Загрузить бытовку №311",
            "Загрузить отдельную мебель",
            "Проверить наполнение бытовки №172",
            "Проверить наполнение бытовки №311",
            "Подтвердить загрузку всего груза",
            "Ехать на склад «Великий Новгород»",
            "Выгрузить бытовку №172",
            "Выгрузить бытовку №311",
            "Выгрузить отдельную мебель",
            "Подтвердить межскладскую выгрузку");
    assertThat(
            content.works().stream()
                .filter(value -> value.name().endsWith("№172"))
                .filter(value -> value.name().startsWith("Проверить"))
                .findFirst()
                .orElseThrow()
                .comment())
        .contains("Требуется: Кровать — 4, Стол — 1")
        .contains("Фактически: Кровать — 3, Стол — 1")
        .contains("Добавить: Кровать — 1")
        .contains("Убрать: нет");
    assertThat(content.materials())
        .filteredOn(value -> value.name().equals("Лавка · отдельный груз"))
        .singleElement()
        .extracting(value -> value.quantity())
        .isEqualTo(4.0);
    assertThat(content.materials())
        .filteredOn(value -> value.name().contains("внутри №"))
        .hasSize(4);
    assertThat(content.comments())
        .singleElement()
        .satisfies(value -> assertThat(value.text()).isEqualTo("Проверить крепления перед выездом"));
    assertThat(content.sourceMedia())
        .singleElement()
        .satisfies(
            media -> {
              assertThat(media.mediaId()).isEqualTo(firstPhoto);
              assertThat(media.generation()).isEqualTo(3);
            });
    assertThat(content.works())
        .filteredOn(work -> work.name().equals("Загрузить бытовку №172"))
        .singleElement()
        .satisfies(work -> assertThat(work.sourceMediaIds()).containsExactly(firstPhoto));
    assertThat(content.works())
        .filteredOn(work -> work.name().equals("Загрузить бытовку №311"))
        .singleElement()
        .satisfies(work -> assertThat(work.sourceMediaIds()).isEmpty());
    assertThat(service.build(document, snapshot)).isEqualTo(content);
  }

  private static LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot cabin(
      UUID assetId, UUID warehouseId, String number) {
    return new LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot(
        assetId,
        1,
        warehouseId,
        number,
        "6 × 2,4 м",
        "ЛДСП",
        "BK2",
        List.of("утепление"),
        true);
  }

  private static TransferPlanSnapshot transferSnapshot(
      UUID firstCabin, UUID secondCabin, UUID bed, UUID table, UUID bench) {
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 14, 8, 30, 0, 0, ZoneOffset.ofHours(3));
    return new TransferPlanSnapshot(
        UUID.randomUUID(),
        2,
        TransferPlanState.CONFIRMED,
        TransferReservationReadiness.RESERVED,
        TransferPlanWorkflowState.READY,
        null,
        departure,
        departure.plusHours(4),
        "Проверить крепления перед выездом",
        UUID.randomUUID(),
        UUID.randomUUID(),
        new TransferPlanSnapshot.ResourceIntent(
            null, TransferResourceRepositionMode.NONE, null),
        new TransferPlanSnapshot.ResourceIntent(
            null, TransferResourceRepositionMode.NONE, null),
        null,
        null,
        List.of(
            new TransferPlanSnapshot.CargoGroup(
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(UUID.randomUUID()),
                true,
                2,
                List.of(
                    new TransferPlanSnapshot.Furniture(bed, 4, 8),
                    new TransferPlanSnapshot.Furniture(table, 1, 2)),
                List.of(
                    new TransferPlanSnapshot.Allocation(firstCabin, 7),
                    new TransferPlanSnapshot.Allocation(secondCabin, 8)))),
        List.of(
            new TransferPlanSnapshot.LooseFurniture(
                UUID.randomUUID(),
                1,
                bench,
                4,
                UUID.randomUUID(),
                2L,
                UUID.randomUUID(),
                3L,
                TransferLooseFurnitureState.RESERVED)),
        2,
        3);
  }
}
