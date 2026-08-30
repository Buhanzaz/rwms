package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Builds exact native DriverApp/WorkerApp content for an external capital-repair movement. */
@Service
public class CapitalRepairDriverTaskContentService {
  private final DriverTaskSourceMediaService sourceMedia;

  /** Creates the builder over the shared validated cabin-gallery boundary. */
  public CapitalRepairDriverTaskContentService(DriverTaskSourceMediaService sourceMedia) {
    this.sourceMedia = sourceMedia;
  }

  /**
   * Builds load, move and unload operations for the exact capital-repair cabin.
   * Missing photos remain a valid empty gallery while the unit number is always retained.
   */
  public DriverTaskWorkerContent build(
      LogisticsDependencyGateway.CapitalRepair repair,
      LogisticsDependencyGateway.RentalItemSnapshot cabin,
      OffsetDateTime recordedAt) {
    if (repair == null
        || cabin == null
        || recordedAt == null
        || repair.repairId() == null
        || repair.rentalItemId() == null
        || repair.warehouseId() == null
        || !repair.rentalItemId().equals(cabin.assetId())
        || !repair.warehouseId().equals(cabin.warehouseId())
        || cabin.number() == null
        || cabin.number().isBlank()) {
      throw new LogisticsConflictException("Капитальный ремонт не соответствует бытовке");
    }
    String number = cabin.number().trim();
    DriverTaskSourceMediaService.Snapshot media =
        sourceMedia.read(cabin.warehouseId(), List.of(cabin.assetId()), recordedAt);
    List<UUID> photoIds = media.mediaIds(cabin.assetId());
    List<DriverTaskWorkerContent.Work> works = new ArrayList<>();
    works.add(
        work(
            repair.repairId(),
            "load",
            "Загрузить бытовку №" + number,
            "Сверить номер и исходное состояние",
            photoIds));
    works.add(
        work(
            repair.repairId(),
            "move",
            "Переместить бытовку №" + number + " на производство",
            "Капитальный ремонт",
            photoIds));
    works.add(
        work(
            repair.repairId(),
            "unload",
            "Выгрузить бытовку №" + number,
            "Подтвердить фактическую выгрузку",
            List.of()));
    DriverTaskWorkerContent.Material material =
        new DriverTaskWorkerContent.Material(
            stableId(repair.repairId(), "material"), "Бытовка №" + number, 1, "шт.");
    return new DriverTaskWorkerContent(
        "Капитальный ремонт · бытовка №" + number,
        List.copyOf(works),
        List.of(material),
        List.of(),
        media.sourceMedia());
  }

  private static DriverTaskWorkerContent.Work work(
      UUID repairId, String key, String name, String comment, List<UUID> sourceMediaIds) {
    return new DriverTaskWorkerContent.Work(
        stableId(repairId, "work:" + key),
        name,
        1,
        "шт.",
        null,
        comment,
        sourceMediaIds);
  }

  private static UUID stableId(UUID repairId, String key) {
    return UUID.nameUUIDFromBytes(
        ("capital-repair-driver:" + repairId + ":" + key)
            .getBytes(StandardCharsets.UTF_8));
  }
}
