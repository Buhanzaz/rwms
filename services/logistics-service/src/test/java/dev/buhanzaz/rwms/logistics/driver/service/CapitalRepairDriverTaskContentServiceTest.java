package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies exact capital-repair cabin identity and gallery in native worker content. */
class CapitalRepairDriverTaskContentServiceTest {
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final CapitalRepairDriverTaskContentService service =
      new CapitalRepairDriverTaskContentService(new DriverTaskSourceMediaService(dependencies));

  @Test
  void buildsExactUnitOperationsAndRetainsReadyGalleryRevision() {
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    OffsetDateTime recordedAt =
        OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 0, ZoneOffset.UTC);
    var repair =
        new LogisticsDependencyGateway.CapitalRepair(
            repairId, cabinId, warehouseId, 2, null, 4);
    var cabin =
        new LogisticsDependencyGateway.RentalItemSnapshot(
            cabinId, 9, warehouseId, "510", "REPAIR", List.of());
    when(dependencies.readCabinMediaSnapshots(warehouseId, List.of(cabinId)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.CabinMediaSnapshot(
                    cabinId,
                    1,
                    List.of(
                        new LogisticsDependencyGateway.CabinMediaPhoto(
                            mediaId, 5, 0, List.of("thumbnail"))))));

    var content = service.build(repair, cabin, recordedAt);

    assertThat(content.taskText()).contains("№510");
    assertThat(content.works())
        .extracting(work -> work.name())
        .containsExactly(
            "Загрузить бытовку №510",
            "Переместить бытовку №510 на производство",
            "Выгрузить бытовку №510");
    assertThat(content.works().getFirst().sourceMediaIds()).containsExactly(mediaId);
    assertThat(content.works().get(1).sourceMediaIds()).isEmpty();
    assertThat(content.works().get(2).sourceMediaIds()).isEmpty();
    assertThat(content.works().stream().flatMap(work -> work.sourceMediaIds().stream()))
        .containsExactly(mediaId);
    assertThat(content.sourceMedia())
        .singleElement()
        .satisfies(
            media -> {
              assertThat(media.mediaId()).isEqualTo(mediaId);
              assertThat(media.generation()).isEqualTo(5);
              assertThat(media.recordedAt()).isEqualTo(recordedAt);
            });
    assertThat(content.materials()).singleElement().satisfies(row -> assertThat(row.name()).contains("№510"));
  }

  @Test
  void missingReadyPhotosKeepsTheCapitalRepairTaskValid() {
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    var repair =
        new LogisticsDependencyGateway.CapitalRepair(
            repairId, cabinId, warehouseId, 2, null, 4);
    var cabin =
        new LogisticsDependencyGateway.RentalItemSnapshot(
            cabinId, 9, warehouseId, "511", "REPAIR", List.of());
    when(dependencies.readCabinMediaSnapshots(warehouseId, List.of(cabinId)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.CabinMediaSnapshot(cabinId, 0, List.of())));

    var content =
        service.build(
            repair,
            cabin,
            OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 0, ZoneOffset.UTC));

    assertThat(content.sourceMedia()).isEmpty();
    assertThat(content.works()).allSatisfy(work -> assertThat(work.sourceMediaIds()).isEmpty());
  }
}
