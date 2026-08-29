package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
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

  /** Creates the builder over the existing service-to-service dependency boundary. */
  public TransferDriverTaskContentService(LogisticsDependencyGateway dependencies) {
    this.dependencies = dependencies;
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
    List<DriverTaskWorkerContent.Work> works =
        works(document.getId(), source, destination, cabins, transfer, equipmentNames);
    List<DriverTaskWorkerContent.Material> materials =
        materials(document.getId(), cabins, transfer, equipmentNames);
    List<DriverTaskWorkerContent.Comment> comments = comments(document, transfer);
    String taskText = taskText(source, destination, cabins, transfer, equipmentNames);
    return new DriverTaskWorkerContent(taskText, works, materials, comments);
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
      Map<UUID, String> equipmentNames) {
    List<DriverTaskWorkerContent.Work> result = new ArrayList<>();
    addWork(result, documentId, "arrive-source", "Прибыть на склад «" + source + "»", null);
    for (CabinCargo cabin : cabins) {
      addWork(
          result,
          documentId,
          "load-cabin:" + cabin.assetId(),
          "Загрузить бытовку №" + cabin.number(),
          cabin.description());
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
          comparison(cabin, equipmentNames));
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
    target.add(
        new DriverTaskWorkerContent.Work(
            stableId(documentId, "work:" + key), name, 1, null, null, comment));
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
}
