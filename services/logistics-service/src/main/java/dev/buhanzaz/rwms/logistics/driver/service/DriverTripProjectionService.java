package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureTaskStatusView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripActualEquipmentResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripCabinResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDesiredEquipmentResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDetailsResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTaskMember;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AdditionalContactResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowResponse;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService;
import java.util.ArrayList;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the structured driver-facing projection of one existing grouped logistics task from its
 * order, immutable members, current asset contents, and existing furniture movement tasks.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DriverTripProjectionService {
  private static final Logger log = LoggerFactory.getLogger(DriverTripProjectionService.class);
  private final DriverLogisticsTaskRepository driverTasks;
  private final LogisticsDocumentRepository documents;
  private final RentalOrderRepository orders;
  private final RentalOrderEquipmentRequirementRepository requirements;
  private final ShipmentFurnitureMovementTaskRepository shipmentFurnitureTasks;
  private final EquipmentMovementTaskRepository equipmentMovementTasks;
  private final LogisticsDependencyGateway dependencies;
  private final ShipmentFurnitureTaskService shipmentFurnitureReadiness;

  /** Returns null for non-grouped work and a complete trip projection for a grouped shipment. */
  public DriverTripDetailsResponse details(UUID driverTaskId) {
    DriverLogisticsTask task = driverTasks.findWithMembersById(driverTaskId).orElseThrow();
    return details(task);
  }

  /**
   * Builds board projections from already loaded tasks and isolates a broken dependency snapshot to
   * its own card. The dedicated detail endpoint remains strict and reports that failure.
   */
  public Map<UUID, DriverTripDetailsResponse> boardDetails(
      Collection<DriverLogisticsTask> loadedTasks) {
    List<DriverLogisticsTask> grouped =
        loadedTasks.stream().filter(DriverLogisticsTask::isGroupedDocument).toList();
    Map<UUID, LogisticsDocument> documentsById =
        documents
            .findAllById(grouped.stream().map(DriverLogisticsTask::getSourceId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(LogisticsDocument::getId, Function.identity()));
    Map<UUID, RentalOrder> ordersById =
        orders
            .findAllWithClientByIdIn(
                documentsById.values().stream()
                    .map(LogisticsDocument::getRentalOrderId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(RentalOrder::getId, Function.identity()));
    Map<UUID, Map<UUID, List<RentalOrderEquipmentRequirement>>> desiredByOrderAndCabin =
        requirements
            .findAllByOrder_IdInOrderByOrder_IdAscRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
                ordersById.keySet())
            .stream()
            .collect(
                Collectors.groupingBy(
                    row -> row.getOrder().getId(),
                    LinkedHashMap::new,
                    Collectors.groupingBy(
                        RentalOrderEquipmentRequirement::getRentalItemId,
                        LinkedHashMap::new,
                        Collectors.toList())));
    Map<UUID, Map<UUID, ShipmentFurnitureMovementTask>> linksByDocumentAndCabin =
        shipmentFurnitureTasks
            .findAllByDocument_IdInOrderByDocument_IdAscUnitNumberAsc(documentsById.keySet())
            .stream()
            .collect(
                Collectors.groupingBy(
                    link -> link.getDocument().getId(),
                    LinkedHashMap::new,
                    Collectors.toMap(
                        ShipmentFurnitureMovementTask::getRentalItemId,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new)));
    Map<UUID, EquipmentMovementTask> movementById =
        equipmentMovementTasks
            .findAllById(
                linksByDocumentAndCabin.values().stream()
                    .flatMap(values -> values.values().stream())
                    .map(ShipmentFurnitureMovementTask::getEquipmentMovementTaskId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(EquipmentMovementTask::getId, Function.identity()));
    Map<UUID, BoardOrderUnits> unitsByOrder = new LinkedHashMap<>();
    for (UUID orderId : ordersById.keySet()) {
      try {
        Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> units =
            dependencies.readOrderUnits(orderId).stream()
                .collect(
                    Collectors.toMap(
                        LogisticsDependencyGateway.OrderUnitReservation::unitId,
                        Function.identity(),
                        (left, right) -> {
                          throw new LogisticsConflictException(
                              "Склад вернул бытовку заказа дважды");
                        },
                        LinkedHashMap::new));
        if (units.values().stream()
            .anyMatch(
                unit ->
                    unit == null
                        || !orderId.equals(unit.orderId())
                        || unit.unit() == null
                        || unit.unit().contents() == null)) {
          throw new LogisticsConflictException("Склад вернул неполный состав заказа");
        }
        unitsByOrder.put(orderId, new BoardOrderUnits(Map.copyOf(units), true));
      } catch (RuntimeException exception) {
        log.warn("Driver board order contents {} are temporarily unavailable", orderId, exception);
        unitsByOrder.put(orderId, new BoardOrderUnits(Map.of(), false));
      }
    }
    Map<UUID, DriverTripDetailsResponse> values = new LinkedHashMap<>();
    for (DriverLogisticsTask task : loadedTasks) {
      try {
        values.put(
            task.getId(),
            boardDetails(
                task,
                documentsById,
                ordersById,
                desiredByOrderAndCabin,
                linksByDocumentAndCabin,
                movementById,
                unitsByOrder));
      } catch (RuntimeException exception) {
        log.warn("Driver trip projection {} is temporarily unavailable", task.getId(), exception);
        values.put(task.getId(), null);
      }
    }
    return java.util.Collections.unmodifiableMap(values);
  }

  private DriverTripDetailsResponse boardDetails(
      DriverLogisticsTask task,
      Map<UUID, LogisticsDocument> documentsById,
      Map<UUID, RentalOrder> ordersById,
      Map<UUID, Map<UUID, List<RentalOrderEquipmentRequirement>>> desiredByOrderAndCabin,
      Map<UUID, Map<UUID, ShipmentFurnitureMovementTask>> linksByDocumentAndCabin,
      Map<UUID, EquipmentMovementTask> movementById,
      Map<UUID, BoardOrderUnits> unitsByOrder) {
    if (!task.isGroupedDocument()) return null;
    LogisticsDocument document = documentsById.get(task.getSourceId());
    if (document == null) {
      throw new LogisticsConflictException("Документ ходки не найден");
    }
    if (document.getRentalOrderId() == null) return null;
    RentalOrder order = ordersById.get(document.getRentalOrderId());
    if (order == null) throw new LogisticsConflictException("Заказ ходки не найден");
    Map<UUID, List<RentalOrderEquipmentRequirement>> desiredByCabin =
        desiredByOrderAndCabin.getOrDefault(order.getId(), Map.of());
    Map<UUID, ShipmentFurnitureMovementTask> links =
        linksByDocumentAndCabin.getOrDefault(document.getId(), Map.of());
    BoardOrderUnits orderUnits =
        unitsByOrder.getOrDefault(order.getId(), new BoardOrderUnits(Map.of(), false));
    List<DriverTripCabinResponse> cabins = new ArrayList<>();
    for (DriverLogisticsTaskMember member : task.getMembers()) {
      UUID cabinId = member.getCabinId();
      List<RentalOrderEquipmentRequirement> desired =
          desiredByCabin.getOrDefault(cabinId, List.of());
      ShipmentFurnitureMovementTask link = links.get(cabinId);
      EquipmentMovementTask movement =
          link == null || link.getEquipmentMovementTaskId() == null
              ? null
              : movementById.get(link.getEquipmentMovementTaskId());
      if (link != null && link.getEquipmentMovementTaskId() != null && movement == null) {
        throw new LogisticsConflictException("Задание на мебель ходки не найдено");
      }
      boolean movementCreated = movement != null;
      boolean movementCompleted =
          movement != null && movement.getState() == EquipmentMovementTaskState.COMPLETED;
      LogisticsDependencyGateway.OrderUnitReservation unit = orderUnits.units().get(cabinId);
      if (orderUnits.available() && (unit == null || unit.unit() == null)) {
        throw new LogisticsConflictException("Состав бытовок ходки не совпадает с заказом");
      }
      List<DriverTripActualEquipmentResponse> actualContents =
          orderUnits.available() ? actualContents(unit) : null;
      Boolean contentReady =
          orderUnits.available()
              ? (!movementCreated || movementCompleted)
                  && exactContents(desired, unit.unit().contents())
              : null;
      cabins.add(
          new DriverTripCabinResponse(
              cabinId,
              member.getUnitNumber(),
              desired.stream()
                  .map(
                      row ->
                          new DriverTripDesiredEquipmentResponse(
                              row.getEquipmentId(), row.getEquipmentName(), row.getQuantity()))
                  .toList(),
              actualContents,
              movementCreated,
              movementCompleted,
              contentReady));
    }
    return response(task, document, order, cabins, provisionalEta(task));
  }

  private DriverTripDetailsResponse details(DriverLogisticsTask task) {
    if (!task.isGroupedDocument()) return null;
    var document =
        documents
            .findById(task.getSourceId())
            .orElseThrow(() -> new LogisticsConflictException("Документ ходки не найден"));
    if (document.getRentalOrderId() == null) return null;
    RentalOrder order =
        orders
            .findWithClientById(document.getRentalOrderId())
            .orElseThrow(() -> new LogisticsConflictException("Заказ ходки не найден"));

    List<RentalOrderEquipmentRequirement> desiredRows =
        requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            order.getId());
    Map<UUID, List<RentalOrderEquipmentRequirement>> desiredByCabin =
        desiredRows.stream()
            .collect(
                Collectors.groupingBy(
                    RentalOrderEquipmentRequirement::getRentalItemId,
                    LinkedHashMap::new,
                    Collectors.toList()));
    Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> units =
        dependencies.readOrderUnits(order.getId()).stream()
            .collect(
                Collectors.toMap(
                    LogisticsDependencyGateway.OrderUnitReservation::unitId,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<UUID, dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask> links =
        shipmentFurnitureTasks.findAllByDocument_IdOrderByUnitNumberAsc(document.getId()).stream()
            .collect(
                Collectors.toMap(
                    dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask
                        ::getRentalItemId,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<UUID, EquipmentMovementTask> movementTasks =
        equipmentMovementTasks
            .findAllById(
                links.values().stream()
                    .map(
                        dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask
                            ::getEquipmentMovementTaskId)
                    .filter(java.util.Objects::nonNull)
                    .toList())
            .stream()
            .collect(Collectors.toMap(EquipmentMovementTask::getId, Function.identity()));
    Map<UUID, ShipmentFurnitureTaskStatusView> readinessByCabin = new LinkedHashMap<>();
    if (document.getDocumentType() == LogisticsDocumentType.SHIPMENT) {
      ShipmentFurnitureReadinessView readiness =
          shipmentFurnitureReadiness.readiness(document.getId());
      readiness.tasks().forEach(status -> readinessByCabin.put(status.rentalItemId(), status));
    }

    List<DriverTripCabinResponse> cabins = new ArrayList<>();
    for (DriverLogisticsTaskMember member : task.getMembers()) {
      UUID cabinId = member.getCabinId();
      LogisticsDependencyGateway.OrderUnitReservation unit = units.get(cabinId);
      if (unit == null || unit.unit() == null) {
        throw new LogisticsConflictException("Состав бытовок ходки не совпадает с заказом");
      }
      List<RentalOrderEquipmentRequirement> desired =
          desiredByCabin.getOrDefault(cabinId, List.of());
      var link = links.get(cabinId);
      EquipmentMovementTask movement =
          link == null || link.getEquipmentMovementTaskId() == null
              ? null
              : movementTasks.get(link.getEquipmentMovementTaskId());
      if (link != null && link.getEquipmentMovementTaskId() != null && movement == null) {
        throw new LogisticsConflictException("Задание на мебель ходки не найдено");
      }
      ShipmentFurnitureTaskStatusView readiness = readinessByCabin.get(cabinId);
      boolean movementCreated =
          readiness == null ? movement != null : readiness.movementTaskCreated();
      boolean movementCompleted =
          readiness == null
              ? movement != null && movement.getState() == EquipmentMovementTaskState.COMPLETED
              : readiness.movementTaskCompleted();
      boolean contentReady =
          readiness == null
              ? (movement == null || movementCompleted)
                  && exactContents(desired, unit.unit().contents())
              : readiness.contentReady();
      cabins.add(
          new DriverTripCabinResponse(
              cabinId,
              member.getUnitNumber(),
              desired.stream()
                  .map(
                      row ->
                          new DriverTripDesiredEquipmentResponse(
                              row.getEquipmentId(), row.getEquipmentName(), row.getQuantity()))
                  .toList(),
              actualContents(unit),
              movementCreated,
              movementCompleted,
              contentReady));
    }

    return response(task, document, order, cabins, provisionalEta(task));
  }

  private static DriverTripDetailsResponse response(
      DriverLogisticsTask task,
      LogisticsDocument document,
      RentalOrder order,
      List<DriverTripCabinResponse> cabins,
      ProvisionalEta preview) {
    List<AdditionalContactResponse> additionalContacts = new ArrayList<>();
    order
        .getClient()
        .getAdditionalContacts()
        .forEach(
            contact ->
                additionalContacts.add(
                    new AdditionalContactResponse(contact.getName(), contact.getPhone())));
    order
        .getAdditionalContacts()
        .forEach(
            contact ->
                additionalContacts.add(
                    new AdditionalContactResponse(contact.getName(), contact.getPhone())));
    String primaryName =
        order.getClient().getContactPerson() == null
            ? order.getClient().getDisplayName()
            : order.getClient().getContactPerson();
    String primaryPhone =
        order.getContactPhone() == null ? order.getClient().getPhone() : order.getContactPhone();
    return new DriverTripDetailsResponse(
        task.getId().toString(),
        task.getTripNumber(),
        task.getKind().name(),
        document.getCustomerDeliveryPurpose(),
        order.getClient().getDisplayName(),
        order.getClient().getClientType(),
        order.getDeliveryAddress(),
        order.getLatitude(),
        order.getLongitude(),
        primaryName,
        primaryPhone,
        List.copyOf(additionalContacts),
        order.getComment(),
        order.getDesiredDeliveryWindows().stream()
            .map(
                window ->
                    new DesiredDeliveryWindowResponse(
                        window.getStartDate(), window.getEndDate()))
            .toList(),
        document.getScheduledDate(),
        preview == null ? null : preview.eta(),
        preview != null,
        preview == null ? null : preview.sourcePlanId(),
        preview == null ? null : preview.sourcePlanVersion(),
        List.copyOf(cabins));
  }

  private ProvisionalEta provisionalEta(DriverLogisticsTask task) {
    if (task.getProvisionalEta() == null
        || task.getDriverAudienceMode()
            != dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode.WAREHOUSE_DRIVERS
        || task.getScheduledDate() == null) {
      return null;
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String timeZone = dependencies.warehouseTimeZoneAt(task.getWarehouseId(), now).timeZone();
    if (!task.getScheduledDate().isAfter(now.toInstant().atZone(ZoneId.of(timeZone)).toLocalDate())) {
      return null;
    }
    return new ProvisionalEta(
        task.getProvisionalEta(),
        task.getProvisionalEtaSourcePlanId(),
        task.getProvisionalEtaSourcePlanVersion());
  }

  /** Versioned approximate preview facts that remain valid only for shared future work. */
  private record ProvisionalEta(OffsetDateTime eta, UUID sourcePlanId, Long sourcePlanVersion) {}

  private static List<DriverTripActualEquipmentResponse> actualContents(
      LogisticsDependencyGateway.OrderUnitReservation unit) {
    return unit.unit().contents().stream()
        .map(
            content ->
                new DriverTripActualEquipmentResponse(
                    content.equipmentId(),
                    content.equipmentName(),
                    content.quantity(),
                    content.locationKind()))
        .toList();
  }

  private static boolean exactContents(
      List<RentalOrderEquipmentRequirement> desired,
      List<LogisticsDependencyGateway.OrderEquipmentContent> actual) {
    Map<UUID, Long> desiredQuantities = new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement row : desired) {
      if (row.getQuantity() > 0) {
        desiredQuantities.merge(row.getEquipmentId(), row.getQuantity(), Math::addExact);
      }
    }
    Map<UUID, Long> actualQuantities = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderEquipmentContent row : actual) {
      if (row.quantity() > 0) {
        actualQuantities.merge(row.equipmentId(), row.quantity(), Math::addExact);
      }
    }
    return desiredQuantities.equals(actualQuantities);
  }

  /** One order-scoped asset read shared by every board card for that order. */
  private record BoardOrderUnits(
      Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> units, boolean available) {}
}
