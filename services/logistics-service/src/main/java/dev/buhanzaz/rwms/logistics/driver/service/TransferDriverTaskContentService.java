package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanSnapshot;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds the exact, sanitized transfer manifest and operation sequence delivered through the
 * existing task-board DriverApp/WorkerApp offline feeds.
 *
 * <p>Cabin and furniture facts are read from their owning services after transfer reservations and
 * preparation have completed. The returned snapshot has no stock side effects and never becomes a
 * second inventory source of truth.
 */
@Service
public class TransferDriverTaskContentService {
  private final LogisticsDependencyGateway dependencies;
  private final DriverTaskSourceMediaService sourceMedia;

  /** Creates the builder over the existing service-to-service dependency boundary. */
  public TransferDriverTaskContentService(
      LogisticsDependencyGateway dependencies, DriverTaskSourceMediaService sourceMedia) {
    this.dependencies = dependencies;
    this.sourceMedia = sourceMedia;
  }

  /** Builds one durable native-task snapshot for a confirmed interwarehouse transfer. */
  public DriverTaskWorkerContent build(
      LogisticsDocument document, TransferPlanSnapshot transfer) {
    requireTransfer(document, transfer);
    String source = warehouseLabel(dependencies.readWarehouseIdentity(document.getWarehouseId()));
    String destination =
        warehouseLabel(
            dependencies.readWarehouseIdentity(document.getDestinationWarehouseId()));

    Set<UUID> equipmentIds = equipmentIds(transfer);
    Map<UUID, String> equipmentNames =
        equipmentNames(
            document.getWarehouseId(), equipmentIds, transfer.totalCabinCount() > 0);
    List<CabinCargo> cabins = cabins(document, transfer, equipmentNames);
    DriverTaskSourceMediaService.Snapshot media =
        sourceMedia.read(
            document.getWarehouseId(),
            cabins.stream().map(CabinCargo::assetId).toList(),
            document.getCreatedAt());
    List<DriverTaskWorkerContent.Work> works =
        works(document.getId(), source, destination, cabins, transfer, equipmentNames, media);
    List<DriverTaskWorkerContent.Material> materials =
        materials(document.getId(), cabins, transfer, equipmentNames);
    List<DriverTaskWorkerContent.Comment> comments = comments(document, transfer);
    String taskText = taskText(source, destination, cabins, transfer, equipmentNames);
    return new DriverTaskWorkerContent(
        taskText, works, materials, comments, media.sourceMedia());
  }

  /**
   * Builds the native route for the original concrete-line transfer flow.
   *
   * <p>Each line is revalidated against its source warehouse and frozen asset version before its
   * exact unit number and READY gallery are persisted in the offline task snapshot.
   */
  public DriverTaskWorkerContent build(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    return build(document, lines, null);
  }

  /** Builds a concrete-line route and preserves an optional logistics comment. */
  public DriverTaskWorkerContent build(
      LogisticsDocument document, List<LogisticsDocumentLine> lines, String logisticsComment) {
    requireLegacyTransfer(document, lines);
    String source = warehouseLabel(dependencies.readWarehouseIdentity(document.getWarehouseId()));
    String destination =
        warehouseLabel(
            dependencies.readWarehouseIdentity(document.getDestinationWarehouseId()));
    List<LegacyCabinCargo> cabins = new ArrayList<>();
    for (LogisticsDocumentLine line : lines.stream()
        .sorted(Comparator.comparingInt(LogisticsDocumentLine::getLineNumber))
        .toList()) {
      var cabin = dependencies.readRentalItemSnapshot(line.getAssetId());
      if (cabin == null
          || !line.getAssetId().equals(cabin.assetId())
          || line.getAssetVersion() != cabin.version()
          || !line.getInventorySourceWarehouseId().equals(cabin.warehouseId())
          || !document.getWarehouseId().equals(cabin.warehouseId())
          || cabin.number() == null
          || cabin.number().isBlank()) {
        throw new LogisticsConflictException(
            "Конкретная бытовка перемещения изменилась или находится на другом складе");
      }
      cabins.add(new LegacyCabinCargo(cabin.assetId(), cabin.number().trim()));
    }
    DriverTaskSourceMediaService.Snapshot media =
        sourceMedia.read(
            document.getWarehouseId(),
            cabins.stream().map(LegacyCabinCargo::assetId).toList(),
            document.getCreatedAt());
    List<DriverTaskWorkerContent.Work> works = new ArrayList<>();
    addWork(works, document.getId(), "arrive-source", "Прибыть на склад «" + source + "»", null);
    for (LegacyCabinCargo cabin : cabins) {
      addWork(
          works,
          document.getId(),
          "load-cabin:" + cabin.assetId(),
          "Загрузить бытовку №" + cabin.number(),
          "Сверить номер и состояние до погрузки",
          media.mediaIds(cabin.assetId()));
      addWork(
          works,
          document.getId(),
          "verify-cabin:" + cabin.assetId(),
          "Проверить бытовку №" + cabin.number(),
          "Фотографии показывают исходное состояние",
          media.mediaIds(cabin.assetId()));
    }
    addWork(works, document.getId(), "confirm-load", "Подтвердить загрузку всего груза", null);
    addWork(
        works,
        document.getId(),
        "travel",
        "Ехать на склад «" + destination + "»",
        source + " → " + destination);
    for (LegacyCabinCargo cabin : cabins) {
      addWork(
          works,
          document.getId(),
          "unload-cabin:" + cabin.assetId(),
          "Выгрузить бытовку №" + cabin.number(),
          "Подтвердить фактическую выгрузку на складе назначения");
    }
    addWork(
        works,
        document.getId(),
        "confirm-unload",
        "Подтвердить межскладскую выгрузку",
        "Все бытовки должны быть фактически выгружены");
    List<DriverTaskWorkerContent.Material> materials =
        cabins.stream()
            .map(
                cabin ->
                    new DriverTaskWorkerContent.Material(
                        stableId(document.getId(), "material:cabin:" + cabin.assetId()),
                        "Бытовка №" + cabin.number(),
                        1,
                        "шт."))
            .toList();
    String taskText =
        source
            + " → "
            + destination
            + "\nБытовки: "
            + cabins.stream()
                .map(cabin -> "№" + cabin.number())
                .collect(Collectors.joining(", "));
    List<DriverTaskWorkerContent.Comment> comments =
        logisticsComment == null || logisticsComment.isBlank()
            ? List.of()
            : List.of(
                new DriverTaskWorkerContent.Comment(
                    stableId(document.getId(), "comment:logistics"),
                    logisticsComment,
                    "Логист",
                    document.getCreatedAt()));
    return new DriverTaskWorkerContent(
        taskText, List.copyOf(works), materials, comments, media.sourceMedia());
  }

  private List<CabinCargo> cabins(
      LogisticsDocument document,
      TransferPlanSnapshot transfer,
      Map<UUID, String> equipmentNames) {
    List<CabinCargo> result = new ArrayList<>();
    for (TransferPlanSnapshot.CargoGroup group :
        transfer.cabinGroups().stream()
            .sorted(Comparator.comparingInt(TransferPlanSnapshot.CargoGroup::position))
            .toList()) {
      Map<UUID, Long> required = new LinkedHashMap<>();
      for (TransferPlanSnapshot.Furniture item : group.furniturePerCabin()) {
        required.merge(
            item.furnitureCatalogItemId(), item.quantityPerCabin(), Math::addExact);
      }
      for (TransferPlanSnapshot.Allocation allocation : group.allocatedCabins()) {
        var presentation =
            dependencies.readCabinPhotoPresentationSnapshot(allocation.assetId());
        var actual = dependencies.readRentalItemSnapshot(allocation.assetId());
        if (presentation == null
            || actual == null
            || !allocation.assetId().equals(presentation.assetId())
            || !allocation.assetId().equals(actual.assetId())
            || !document.getWarehouseId().equals(presentation.warehouseId())
            || !document.getWarehouseId().equals(actual.warehouseId())
            || presentation.number() == null
            || presentation.number().isBlank()
            || actual.number() == null
            || !presentation.number().trim().equals(actual.number().trim())) {
          throw new LogisticsConflictException(
              "Снимок конкретной бытовки не соответствует складу отправления");
        }
        Map<UUID, Long> factual = new LinkedHashMap<>();
        if (actual.contents() != null) {
          for (LogisticsDependencyGateway.EquipmentContent item : actual.contents()) {
            if (item == null || item.equipmentId() == null || item.quantity() < 0) {
              throw new LogisticsConflictException("Фактическое наполнение бытовки повреждено");
            }
            factual.merge(item.equipmentId(), item.quantity(), Math::addExact);
          }
        }
        factual.keySet().forEach(id -> requireEquipmentName(equipmentNames, id));
        result.add(
            new CabinCargo(
                allocation.assetId(),
                presentation.number().trim(),
                actual.status(),
                cabinDescription(presentation),
                Map.copyOf(required),
                Map.copyOf(factual)));
      }
    }
    if (result.size() != transfer.totalCabinCount()) {
      throw new LogisticsConflictException(
          "Количество конкретных бытовок не совпадает с подтверждённым перемещением");
    }
    return List.copyOf(result);
  }

  private static List<DriverTaskWorkerContent.Work> works(
      UUID documentId,
      String source,
      String destination,
      List<CabinCargo> cabins,
      TransferPlanSnapshot transfer,
      Map<UUID, String> equipmentNames,
      DriverTaskSourceMediaService.Snapshot media) {
    List<DriverTaskWorkerContent.Work> result = new ArrayList<>();
    addWork(result, documentId, "arrive-source", "Прибыть на склад «" + source + "»", null);
    for (CabinCargo cabin : cabins) {
      addWork(
          result,
          documentId,
          "load-cabin:" + cabin.assetId(),
          "Загрузить бытовку №" + cabin.number(),
          cabin.description(),
          media.mediaIds(cabin.assetId()));
    }
    if (!transfer.looseFurniture().isEmpty()) {
      addWork(
          result,
          documentId,
          "load-loose-furniture",
          "Загрузить отдельную мебель",
          looseFurnitureSummary(transfer, equipmentNames));
    }
    for (CabinCargo cabin : cabins) {
      addWork(
          result,
          documentId,
          "verify-cabin:" + cabin.assetId(),
          "Проверить наполнение бытовки №" + cabin.number(),
          comparison(cabin, equipmentNames),
          media.mediaIds(cabin.assetId()));
    }
    addWork(result, documentId, "confirm-load", "Подтвердить загрузку всего груза", null);
    addWork(
        result,
        documentId,
        "travel",
        "Ехать на склад «" + destination + "»",
        source + " → " + destination);
    for (CabinCargo cabin : cabins) {
      addWork(
          result,
          documentId,
          "unload-cabin:" + cabin.assetId(),
          "Выгрузить бытовку №" + cabin.number(),
          cabin.description());
    }
    if (!transfer.looseFurniture().isEmpty()) {
      addWork(
          result,
          documentId,
          "unload-loose-furniture",
          "Выгрузить отдельную мебель",
          looseFurnitureSummary(transfer, equipmentNames));
    }
    addWork(
        result,
        documentId,
        "confirm-unload",
        "Подтвердить межскладскую выгрузку",
        "Груз должен быть фактически выгружен на складе «" + destination + "»");
    if (result.size() > 100) {
      throw new LogisticsConflictException(
          "Последовательность перемещения превышает лимит водительского задания");
    }
    return List.copyOf(result);
  }

  private static void addWork(
      List<DriverTaskWorkerContent.Work> target,
      UUID documentId,
      String key,
      String name,
      String comment) {
    addWork(target, documentId, key, name, comment, List.of());
  }

  private static void addWork(
      List<DriverTaskWorkerContent.Work> target,
      UUID documentId,
      String key,
      String name,
      String comment,
      List<UUID> sourceMediaIds) {
    target.add(
        new DriverTaskWorkerContent.Work(
            stableId(documentId, "work:" + key),
            name,
            1,
            null,
            null,
            comment,
            sourceMediaIds));
  }

  private static List<DriverTaskWorkerContent.Material> materials(
      UUID documentId,
      List<CabinCargo> cabins,
      TransferPlanSnapshot transfer,
      Map<UUID, String> equipmentNames) {
    List<DriverTaskWorkerContent.Material> result = new ArrayList<>();
    for (CabinCargo cabin : cabins) {
      result.add(
          new DriverTaskWorkerContent.Material(
              stableId(documentId, "material:cabin:" + cabin.assetId()),
              "Бытовка №" + cabin.number() + " · " + cabin.description(),
              1,
              "шт."));
      for (Map.Entry<UUID, Long> item : cabin.factualFurniture().entrySet()) {
        if (item.getValue() == 0) continue;
        result.add(
            new DriverTaskWorkerContent.Material(
                stableId(
                    documentId,
                    "material:cabin:" + cabin.assetId() + ":" + item.getKey()),
                equipmentNames.get(item.getKey()) + " · внутри №" + cabin.number(),
                item.getValue(),
                "шт."));
      }
    }
    for (TransferPlanSnapshot.LooseFurniture item : transfer.looseFurniture()) {
      result.add(
          new DriverTaskWorkerContent.Material(
              stableId(documentId, "material:loose:" + item.lineId()),
              equipmentNames.get(item.furnitureCatalogItemId()) + " · отдельный груз",
              item.quantity(),
              "шт."));
    }
    if (result.size() > 100) {
      throw new LogisticsConflictException(
          "Состав перемещения превышает лимит водительского задания");
    }
    return List.copyOf(result);
  }

  private static List<DriverTaskWorkerContent.Comment> comments(
      LogisticsDocument document, TransferPlanSnapshot transfer) {
    String text = transfer.logisticsComment();
    return text == null || text.isBlank()
        ? List.of()
        : List.of(
            new DriverTaskWorkerContent.Comment(
                stableId(document.getId(), "comment:logistics"),
                text,
                "Логист",
                document.getCreatedAt()));
  }

  private static String taskText(
      String source,
      String destination,
      List<CabinCargo> cabins,
      TransferPlanSnapshot transfer,
      Map<UUID, String> equipmentNames) {
    String cargo =
        cabins.isEmpty()
            ? "Межскладской груз: " + looseFurnitureSummary(transfer, equipmentNames)
            : "Бытовки: "
                + cabins.stream()
                    .map(cabin -> "№" + cabin.number())
                    .collect(Collectors.joining(", "));
    return source + " → " + destination + "\n" + cargo;
  }

  private static String comparison(CabinCargo cabin, Map<UUID, String> equipmentNames) {
    Map<UUID, Long> add = new LinkedHashMap<>();
    Map<UUID, Long> remove = new LinkedHashMap<>();
    Set<UUID> ids = new LinkedHashSet<>(cabin.requiredFurniture().keySet());
    ids.addAll(cabin.factualFurniture().keySet());
    for (UUID id : ids) {
      long required = cabin.requiredFurniture().getOrDefault(id, 0L);
      long factual = cabin.factualFurniture().getOrDefault(id, 0L);
      if (required > factual) add.put(id, required - factual);
      if (factual > required) remove.put(id, factual - required);
    }
    return "Статус: %s. Требуется: %s. Фактически: %s. Добавить: %s. Убрать: %s."
        .formatted(
            cabin.status() == null ? "не указан" : cabin.status(),
            furnitureSummary(cabin.requiredFurniture(), equipmentNames),
            furnitureSummary(cabin.factualFurniture(), equipmentNames),
            furnitureSummary(add, equipmentNames),
            furnitureSummary(remove, equipmentNames));
  }

  private static String looseFurnitureSummary(
      TransferPlanSnapshot transfer, Map<UUID, String> equipmentNames) {
    Map<UUID, Long> totals = new LinkedHashMap<>();
    transfer.looseFurniture().stream()
        .sorted(Comparator.comparingInt(TransferPlanSnapshot.LooseFurniture::position))
        .forEach(
            item ->
                totals.merge(
                    item.furnitureCatalogItemId(), item.quantity(), Math::addExact));
    return furnitureSummary(totals, equipmentNames);
  }

  private static String furnitureSummary(
      Map<UUID, Long> quantities, Map<UUID, String> equipmentNames) {
    String value =
        quantities.entrySet().stream()
            .filter(item -> item.getValue() > 0)
            .sorted(Comparator.comparing(item -> equipmentNames.get(item.getKey())))
            .map(item -> equipmentNames.get(item.getKey()) + " — " + item.getValue())
            .collect(Collectors.joining(", "));
    return value.isEmpty() ? "нет" : value;
  }

  private Map<UUID, String> equipmentNames(
      UUID warehouseId, Set<UUID> requiredIds, boolean cabinCargoPresent) {
    if (requiredIds.isEmpty() && !cabinCargoPresent) return Map.of();
    List<LogisticsDependencyGateway.EquipmentWarehouseAvailability> values =
        dependencies.readLogisticsEquipmentAvailability(warehouseId);
    if (values == null) {
      throw new LogisticsConflictException("Каталог мебели склада недоступен");
    }
    Map<UUID, String> names = new LinkedHashMap<>();
    for (var value : values) {
      if (value == null
          || value.equipmentId() == null
          || value.equipmentName() == null
          || value.equipmentName().isBlank()) {
        throw new LogisticsConflictException("Каталог мебели склада содержит некорректную запись");
      }
      names.putIfAbsent(value.equipmentId(), value.equipmentName().trim());
    }
    requiredIds.forEach(id -> requireEquipmentName(names, id));
    return Map.copyOf(names);
  }

  private static Set<UUID> equipmentIds(TransferPlanSnapshot transfer) {
    Set<UUID> result = new LinkedHashSet<>();
    transfer.cabinGroups().forEach(
        group ->
            group.furniturePerCabin().forEach(
                item -> result.add(item.furnitureCatalogItemId())));
    transfer.looseFurniture().forEach(item -> result.add(item.furnitureCatalogItemId()));
    return result;
  }

  private static String requireEquipmentName(Map<UUID, String> names, UUID id) {
    String name = names.get(id);
    if (name == null || name.isBlank()) {
      throw new LogisticsConflictException("Позиция мебели отсутствует в текущем каталоге");
    }
    return name;
  }

  private static String cabinDescription(
      LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot cabin) {
    List<String> values = new ArrayList<>();
    addText(values, cabin.category());
    addText(values, cabin.dimensions());
    addText(values, cabin.finishing());
    if (cabin.characteristics() != null) cabin.characteristics().forEach(value -> addText(values, value));
    if (Boolean.TRUE.equals(cabin.linoleum())
        && values.stream().noneMatch(value -> value.equalsIgnoreCase("линолеум"))) {
      values.add("линолеум");
    }
    return values.isEmpty() ? "характеристики не указаны" : String.join(", ", values);
  }

  private static void addText(List<String> values, String value) {
    if (value != null && !value.isBlank()) values.add(value.trim());
  }

  private static String warehouseLabel(LogisticsDependencyGateway.WarehouseIdentity warehouse) {
    if (warehouse == null || warehouse.id() == null || !warehouse.active()) {
      throw new LogisticsConflictException("Склад перемещения недоступен");
    }
    if (warehouse.name() != null && !warehouse.name().isBlank()) return warehouse.name().trim();
    if (warehouse.city() != null && !warehouse.city().isBlank()) return warehouse.city().trim();
    throw new LogisticsConflictException("У склада перемещения не задано название");
  }

  private static void requireTransfer(
      LogisticsDocument document, TransferPlanSnapshot transfer) {
    if (document == null
        || document.getId() == null
        || document.getDocumentType() != LogisticsDocumentType.TRANSFER
        || document.getWarehouseId() == null
        || document.getDestinationWarehouseId() == null
        || document.getCreatedAt() == null
        || transfer == null
        || transfer.planId() == null
        || transfer.cabinGroups() == null
        || transfer.looseFurniture() == null) {
      throw new IllegalArgumentException("Confirmed transfer snapshot is required");
    }
  }

  private static void requireLegacyTransfer(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    if (document == null
        || document.getId() == null
        || document.getDocumentType() != LogisticsDocumentType.TRANSFER
        || document.getWarehouseId() == null
        || document.getDestinationWarehouseId() == null
        || document.getCreatedAt() == null
        || lines == null
        || lines.isEmpty()
        || lines.size() > 100
        || lines.stream()
            .anyMatch(
                line ->
                    line == null
                        || line.getId() == null
                        || line.getAssetId() == null
                        || line.getDocument() == null
                        || !document.getId().equals(line.getDocument().getId()))) {
      throw new IllegalArgumentException("Persisted concrete transfer lines are required");
    }
  }

  private static UUID stableId(UUID documentId, String key) {
    return UUID.nameUUIDFromBytes(
        ("driver-transfer:" + documentId + ":" + key).getBytes(StandardCharsets.UTF_8));
  }

  /** Exact immutable display and composition snapshot of one allocated physical cabin. */
  private record CabinCargo(
      UUID assetId,
      String number,
      String status,
      String description,
      Map<UUID, Long> requiredFurniture,
      Map<UUID, Long> factualFurniture) {}

  /** Exact physical cabin identity used by the original concrete-line transfer route. */
  private record LegacyCabinCargo(UUID assetId, String number) {}
}
