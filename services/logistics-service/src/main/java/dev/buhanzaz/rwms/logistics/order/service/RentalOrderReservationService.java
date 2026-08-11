package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDesiredEquipmentInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderUnitDesiredEquipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.AdditionalContact;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.OrderAuditEventRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns rental-item and desired-equipment reservation commands, their audit evidence, and the
 * save/cancel reconciliation that uses the same asset boundary. It retains remote effects in the
 * original caller-owned transaction and does not introduce a retry policy of its own.
 */
@Service
@RequiredArgsConstructor
class RentalOrderReservationService {
  private static final String SAVE_ORDER = "SAVE_ORDER";
  private static final String CANCEL_ORDER = "CANCEL_ORDER";
  private static final String REMOVE_UNIT = "REMOVE_UNIT";
  private static final String SET_DESIRED_EQUIPMENT = "SET_DESIRED_EQUIPMENT";
  private static final String APPLY_PRESENTATION_SELECTION = "APPLY_PRESENTATION_SELECTION";

  private final RentalOrderCommandStore store;
  private final OrderAuditEventRepository auditEvents;
  private final RentalOrderEquipmentRequirementRepository equipmentRequirements;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final OrderAuditService audit;
  private final OrderAuthorizer access;
  private final LogisticsDependencyGateway dependencies;
  private final RentalOrderReadService reads;
  private final RentalOrderEditabilityService editability;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository documentLines;
  private final DriverLogisticsTaskRepository driverTasks;
  private final ShipmentFurnitureMovementTaskRepository furnitureTaskLinks;
  private final EquipmentMovementTaskService movementTasks;
  private final DocumentDriverTaskPlanner driverTaskPlanner;

  RentalOrderCommandOutcome removeUnit(
      OrderActor actor, UUID orderId, UUID unitId, long expectedVersion, UUID idempotencyKey) {
    String checksum =
        OrderCommandChecksum.sha256(
            REMOVE_UNIT,
            List.of(orderId.toString(), unitId.toString(), Long.toString(expectedVersion)));
    OrderCommandReceipt replay = store.replay(actor, REMOVE_UNIT, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }
    RentalOrder order = store.lockedOrder(orderId);
    editability.requireEditable(actor, order);
    RentalOrderProblems.requireVersion(order, expectedVersion);
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits = reads.readUnits(order);
    LogisticsDependencyGateway.OrderUnitReservation current =
        findCurrentUnit(orderId, unitId, currentUnits);
    editability.requireUnitNotAssignedToShipment(orderId, unitId);
    if (order.getStatus() == RentalOrderStatus.SAVED
        && current != null
        && currentUnits.size() == 1) {
      throw RentalOrderProblems.conflict(
          "ORDER_UNITS_REQUIRED", "Сохранённое бронирование должно содержать хотя бы одну бытовку");
    }
    List<RentalOrderEquipmentRequirement> existingRequirements =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    Map<UUID, Long> remainingRequirements =
        aggregateRequirements(existingRequirements, unitId, Map.of());
    try {
      LogisticsDependencyGateway.OrderUnitReservation released =
          dependencies.releaseOrderUnit(
              idempotencyKey, orderId, unitId, actor.subjectId(), actor.role());
      requireReservation(released, orderId, unitId, warehouseId, "RELEASED");
      if (current == null && !released.replayed())
        throw RentalOrderProblems.invalidDependencyResponse();
      if (current != null && !current.reservationId().equals(released.reservationId())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      List<LogisticsDependencyGateway.OrderEquipmentReservation> furnitureReservations =
          dependencies.replaceOrderEquipmentReservations(
              idempotencyKey,
              orderId,
              warehouseId,
              actor.subjectId(),
              actor.role(),
              dependencyUnitRequirements(
                  currentUnits.stream()
                      .filter(reservation -> !unitId.equals(reservation.unitId()))
                      .toList(),
                  existingRequirements,
                  unitId,
                  Map.of()));
      Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservationByEquipment =
          requireEquipmentReservations(furnitureReservations, remainingRequirements);
      boolean furnitureChanged =
          applyDesiredRequirements(
              order,
              unitId,
              released.unit().number(),
              existingRequirements,
              Map.of(),
              reservationByEquipment,
              actor);
      rentalTerms.deleteAllByOrder_IdAndRentalItemId(orderId, unitId);
      rentalTerms.flush();
      ensureUnitAddedEvidence(order, released, actor);
      boolean recorded =
          hasReservationEvidence(
              orderId, OrderAuditEventType.RESERVATION_RELEASED, released.reservationId());
      if (!recorded || furnitureChanged) {
        order.touch();
        store.persist(order);
        if (!recorded) {
          appendUnitReleasedEvidence(orderId, released, actor);
        }
        changed(order, actor, furnitureChanged ? "unitsAndDesiredEquipment" : "units");
      }
      editability.synchronizeSavedShipmentDraft(order, actor, reads.readUnits(order));
      store.remember(actor, REMOVE_UNIT, idempotencyKey, checksum, order);
      return new RentalOrderCommandOutcome(
          reads.detail(order, actor, reads.readUnits(order)), recorded);
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
  }

  RentalOrderCommandOutcome setDesiredEquipment(
      OrderActor actor,
      UUID orderId,
      UUID unitId,
      UUID idempotencyKey,
      SetOrderUnitDesiredEquipmentRequest request) {
    Map<UUID, Long> desired = desiredRequirements(request.requirements());
    List<String> checksumValues = new ArrayList<>();
    checksumValues.add(orderId.toString());
    checksumValues.add(unitId.toString());
    checksumValues.add(Long.toString(request.expectedVersion()));
    desired.forEach(
        (equipmentId, quantity) -> {
          checksumValues.add(equipmentId.toString());
          checksumValues.add(Long.toString(quantity));
        });
    String checksum = OrderCommandChecksum.sha256(SET_DESIRED_EQUIPMENT, checksumValues);
    OrderCommandReceipt replay =
        store.replay(actor, SET_DESIRED_EQUIPMENT, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }

    RentalOrder order = store.lockedOrder(orderId);
    editability.requireEditable(actor, order);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits = reads.readUnits(order);
    LogisticsDependencyGateway.OrderUnitReservation unit =
        requireCurrentUnit(orderId, unitId, currentUnits);
    List<RentalOrderEquipmentRequirement> existing =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    Map<UUID, Long> aggregate = aggregateRequirements(existing, unitId, desired);
    List<LogisticsDependencyGateway.OrderEquipmentReservation> reservations;
    try {
      reservations =
          dependencies.replaceOrderEquipmentReservations(
              idempotencyKey,
              orderId,
              warehouseId,
              actor.subjectId(),
              actor.role(),
              dependencyUnitRequirements(currentUnits, existing, unitId, desired));
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservationByEquipment =
        requireEquipmentReservations(reservations, aggregate);
    boolean changed =
        applyDesiredRequirements(
            order, unitId, unit.unit().number(), existing, desired, reservationByEquipment, actor);
    if (changed) {
      order.touch();
      store.persist(order);
      changed(order, actor, "desiredEquipment");
    }
    store.remember(actor, SET_DESIRED_EQUIPMENT, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(reads.detail(order, actor, reads.readUnits(order)), false);
  }

  RentalOrderCommandOutcome applyPresentationSelection(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      LogisticsDependencyGateway.ConvertedPresentationHolds conversion,
      Map<UUID, Map<UUID, Long>> selectedRequirements,
      DesiredDeliveryWindow desiredDeliveryWindow,
      long rentalMonths,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      List<AdditionalContact> additionalContacts,
      List<String> legacyDesiredDeliveryTimes) {
    if (selectedRequirements == null
        || selectedRequirements.isEmpty()
        || desiredDeliveryWindow == null
        || rentalMonths < 1) {
      throw new IllegalArgumentException("Presentation selection is required");
    }
    List<AdditionalContact> normalizedContacts =
        additionalContacts == null ? List.of() : List.copyOf(additionalContacts);
    List<String> checksumValues =
        presentationChecksumValues(
            orderId,
            desiredDeliveryWindow,
            rentalMonths,
            deliveryAddress,
            latitude,
            longitude,
            normalizedContacts,
            selectedRequirements);
    String checksum = OrderCommandChecksum.sha256(APPLY_PRESENTATION_SELECTION, checksumValues);
    String legacyChecksum =
        legacyPresentationChecksum(
            orderId,
            desiredDeliveryWindow,
            rentalMonths,
            legacyDesiredDeliveryTimes,
            selectedRequirements);
    OrderCommandReceipt replay =
        store.replay(actor, APPLY_PRESENTATION_SELECTION, idempotencyKey, checksum, legacyChecksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }

    RentalOrder order = store.lockedOrder(orderId);
    editability.requireEditable(actor, order);
    requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits = reads.readUnits(order);
    Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> unitsById =
        currentUnits.stream()
            .collect(
                Collectors.toMap(
                    LogisticsDependencyGateway.OrderUnitReservation::unitId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    if (!unitsById.keySet().containsAll(selectedRequirements.keySet())) {
      throw RentalOrderProblems.conflict(
          "PRESENTATION_CONVERSION_INVALID", "Не все выбранные бытовки добавлены в заказ");
    }
    List<RentalOrderEquipmentRequirement> existing =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        dependencyUnitRequirements(currentUnits, existing, selectedRequirements);
    Map<UUID, Long> aggregate = aggregateComposition(composition);
    if (conversion == null || conversion.equipmentReservations() == null) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservationsByEquipment =
        requireEquipmentReservations(conversion.equipmentReservations(), aggregate);
    Map<UUID, RentalOrderUnitTerm> rentalTermsByUnit =
        rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(orderId).stream()
            .collect(
                Collectors.toMap(
                    RentalOrderUnitTerm::getRentalItemId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    List<RentalOrderUnitTerm> newRentalTerms = new ArrayList<>();
    boolean changed = false;
    for (Map.Entry<UUID, Map<UUID, Long>> selection : selectedRequirements.entrySet()) {
      LogisticsDependencyGateway.OrderUnitReservation unit = unitsById.get(selection.getKey());
      changed |= ensureUnitAddedEvidence(order, unit, actor);
      changed |=
          applyDesiredRequirements(
              order,
              selection.getKey(),
              unit.unit().number(),
              existing,
              selection.getValue(),
              reservationsByEquipment,
              actor);
      RentalOrderUnitTerm existingTerm = rentalTermsByUnit.get(selection.getKey());
      if (existingTerm == null) {
        newRentalTerms.add(RentalOrderUnitTerm.create(order, selection.getKey(), rentalMonths));
      } else if (existingTerm.getRentalMonths() != rentalMonths) {
        throw RentalOrderProblems.conflict(
            "PRESENTATION_RENTAL_TERM_IMMUTABLE",
            "Срок аренды выбранной бытовки уже зафиксирован другим бронированием");
      }
    }
    boolean desiredWindowChanged = order.replaceClientDesiredDeliveryWindow(desiredDeliveryWindow);
    boolean deliveryDetailsChanged =
        deliveryAddress == null
            ? false
            : order.replaceClientDeliveryDetails(
                deliveryAddress, latitude, longitude, normalizedContacts);
    if (!newRentalTerms.isEmpty()) {
      rentalTerms.saveAllAndFlush(newRentalTerms);
    }
    if (changed || desiredWindowChanged || deliveryDetailsChanged || !newRentalTerms.isEmpty()) {
      if (!desiredWindowChanged && !deliveryDetailsChanged) {
        order.touch();
      }
      store.persist(order);
      changed(
          order,
          actor,
          "unitsAndDesiredEquipment,desiredDeliveryWindow,clientDeliveryDetails,rentalTerms");
    }
    editability.synchronizeSavedShipmentDraft(order, actor, currentUnits);
    store.remember(actor, APPLY_PRESENTATION_SELECTION, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(reads.detail(order, actor, currentUnits), false);
  }

  private static List<String> presentationChecksumValues(
      UUID orderId,
      DesiredDeliveryWindow desiredDeliveryWindow,
      long rentalMonths,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      List<AdditionalContact> additionalContacts,
      Map<UUID, Map<UUID, Long>> selectedRequirements) {
    List<String> values = new ArrayList<>();
    values.add(orderId.toString());
    values.add(desiredDeliveryWindow.getStartDate().toString());
    values.add(desiredDeliveryWindow.getEndDate().toString());
    values.add(Long.toString(rentalMonths));
    values.add(deliveryAddress == null ? "" : deliveryAddress);
    values.add(decimal(latitude));
    values.add(decimal(longitude));
    additionalContacts.forEach(
        contact -> {
          values.add(contact.getName());
          values.add(contact.getPhone());
        });
    appendPresentationSelectionChecksumValues(values, selectedRequirements);
    return values;
  }

  /**
   * Reconstructs only the V48 checksum shape when a durable booking still contains its retired
   * client time fields. New date-only receipts never produce this compatible checksum.
   */
  private static String legacyPresentationChecksum(
      UUID orderId,
      DesiredDeliveryWindow desiredDeliveryWindow,
      long rentalMonths,
      List<String> legacyDesiredDeliveryTimes,
      Map<UUID, Map<UUID, Long>> selectedRequirements) {
    if (legacyDesiredDeliveryTimes == null || legacyDesiredDeliveryTimes.size() != 2) return null;
    List<String> values = new ArrayList<>();
    values.add(orderId.toString());
    values.add(desiredDeliveryWindow.getStartDate().toString());
    values.add(desiredDeliveryWindow.getEndDate().toString());
    values.add(legacyDesiredDeliveryTimes.getFirst());
    values.add(legacyDesiredDeliveryTimes.get(1));
    values.add(Long.toString(rentalMonths));
    appendPresentationSelectionChecksumValues(values, selectedRequirements);
    return OrderCommandChecksum.sha256(APPLY_PRESENTATION_SELECTION, values);
  }

  private static void appendPresentationSelectionChecksumValues(
      List<String> values, Map<UUID, Map<UUID, Long>> selectedRequirements) {
    selectedRequirements.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            unit -> {
              values.add(unit.getKey().toString());
              unit.getValue().entrySet().stream()
                  .sorted(Map.Entry.comparingByKey())
                  .forEach(
                      equipment -> {
                        values.add(equipment.getKey().toString());
                        values.add(Long.toString(equipment.getValue()));
                      });
            });
  }

  private static String decimal(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  /**
   * Builds the authoritative post-conversion furniture composition while selected cabins are still
   * held by the presentation. The result is replay-stable after asset conversion because selected
   * requirements override any local state for the same cabin.
   */
  @Transactional(readOnly = true)
  List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> presentationComposition(
      OrderActor actor, UUID orderId, Map<UUID, Map<UUID, Long>> selectedRequirements) {
    if (selectedRequirements == null || selectedRequirements.isEmpty()) {
      throw new IllegalArgumentException("Presentation selection is required");
    }
    RentalOrder order = store.requiredOrder(orderId);
    editability.requireEditable(actor, order);
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits = reads.readUnits(order);
    if (currentUnits.stream().anyMatch(unit -> !warehouseId.equals(unit.warehouseId()))) {
      throw RentalOrderProblems.conflict(
          "ORDER_WAREHOUSE_LOCKED", "В заказ нельзя добавить бытовки с другого склада");
    }
    List<RentalOrderEquipmentRequirement> existing =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    Map<UUID, Map<UUID, Long>> requirementsByUnit = new LinkedHashMap<>();
    currentUnits.forEach(unit -> requirementsByUnit.put(unit.unitId(), new LinkedHashMap<>()));
    selectedRequirements
        .keySet()
        .forEach(unitId -> requirementsByUnit.putIfAbsent(unitId, new LinkedHashMap<>()));
    for (RentalOrderEquipmentRequirement requirement : existing) {
      if (requirement.getQuantity() > 0
          && !selectedRequirements.containsKey(requirement.getRentalItemId())) {
        Map<UUID, Long> unit = requirementsByUnit.get(requirement.getRentalItemId());
        if (unit != null) {
          unit.put(requirement.getEquipmentId(), requirement.getQuantity());
        }
      }
    }
    selectedRequirements.forEach(
        (unitId, desired) -> requirementsByUnit.get(unitId).putAll(desired));
    return requirementsByUnit.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                    entry.getKey(), dependencyRequirements(entry.getValue())))
        .toList();
  }

  /** Builds the authoritative post-swap per-cabin furniture composition for asset replay. */
  @Transactional(readOnly = true)
  List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> replacementComposition(
      UUID orderId, UUID oldRentalItemId, UUID replacementRentalItemId) {
    return replacementComposition(orderId, Map.of(oldRentalItemId, replacementRentalItemId));
  }

  /** Builds one authoritative post-swap composition for every pair in an atomic batch. */
  @Transactional(readOnly = true)
  List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> replacementComposition(
      UUID orderId, Map<UUID, UUID> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      throw new IllegalArgumentException("Replacement mapping is required");
    }
    RentalOrder order = store.requiredOrder(orderId);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits = reads.readUnits(order);
    Set<UUID> currentIds =
        currentUnits.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(Collectors.toUnmodifiableSet());
    Set<UUID> newIds = Set.copyOf(replacements.values());
    if (newIds.size() != replacements.size()
        || !java.util.Collections.disjoint(replacements.keySet(), newIds)
        || !currentIds.containsAll(replacements.keySet())
        || currentIds.stream().anyMatch(newIds::contains)) {
      throw RentalOrderProblems.conflict(
          "REPLACEMENT_COMPOSITION_INVALID", "Состав бытовок заказа изменился во время замены");
    }
    List<RentalOrderEquipmentRequirement> existing =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    Map<UUID, List<LogisticsDependencyGateway.OrderEquipmentRequirement>> byUnit =
        new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement requirement : existing) {
      if (requirement.getQuantity() < 1) continue;
      UUID target =
          replacements.getOrDefault(requirement.getRentalItemId(), requirement.getRentalItemId());
      byUnit
          .computeIfAbsent(target, ignored -> new ArrayList<>())
          .add(
              new LogisticsDependencyGateway.OrderEquipmentRequirement(
                  requirement.getEquipmentId(), requirement.getQuantity()));
    }
    return currentUnits.stream()
        .map(unit -> replacements.getOrDefault(unit.unitId(), unit.unitId()))
        .distinct()
        .sorted()
        .map(
            unitId ->
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                    unitId,
                    byUnit.getOrDefault(unitId, List.of()).stream()
                        .sorted(
                            java.util.Comparator.comparing(
                                LogisticsDependencyGateway.OrderEquipmentRequirement::equipmentId))
                        .toList()))
        .toList();
  }

  /**
   * Commits a complete replayable asset replacement batch into the existing order, document lines,
   * grouped trip members, audit stream, and movement links in one local transaction.
   */
  @Transactional
  RentalOrderCommandOutcome finalizeReplacements(
      UUID orderId,
      UUID batchIdempotencyKey,
      LogisticsDependencyGateway.OrderUnitsReplacementReceipt receipt) {
    RentalOrder order = store.lockedOrder(orderId);
    List<ShipmentFurnitureMovementTask> links =
        furnitureTaskLinks.findReplacementBatchForUpdate(orderId, batchIdempotencyKey);
    if (links.isEmpty()) {
      throw RentalOrderProblems.conflict("REPLACEMENT_NOT_FOUND", "Замена бытовки не найдена");
    }
    OrderActor actor = replacementActor(links.getFirst(), order.getWarehouseId());
    if (links.stream().allMatch(link -> link.getReplacementCompletedAt() != null)) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, order), true);
    }
    if (links.stream().anyMatch(link -> !link.isReplacementPending())
        || receipt == null
        || receipt.replacements() == null
        || receipt.replacements().size() != links.size()) {
      throw RentalOrderProblems.conflict("REPLACEMENT_REJECTED", "Замена бытовки была отклонена");
    }

    for (int index = 0; index < links.size(); index++) {
      ShipmentFurnitureMovementTask link = links.get(index);
      LogisticsDependencyGateway.OrderUnitReplacementReceipt pair =
          receipt.replacements().get(index);
      if (!orderId.equals(link.getOrder().getId())
          || !batchIdempotencyKey.equals(link.getReplacementBatchIdempotencyKey())
          || link.getReplacementPairIndex() != index) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      requireReplacementReceipt(link, order, pair);
      if (link.getEquipmentMovementTaskId() == null) {
        if (!pair.movementReservations().isEmpty()) {
          throw RentalOrderProblems.invalidDependencyResponse();
        }
      } else {
        if (pair.contentReady()) {
          throw RentalOrderProblems.invalidDependencyResponse();
        }
        movementTasks.validateReplacementReservations(
            link.getEquipmentMovementTaskId(), pair.movementReservations());
      }
    }

    List<RentalOrderEquipmentRequirement> allRequirements =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    List<RentalOrderUnitTerm> terms = rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(orderId);
    Map<UUID, UUID> mappings =
        links.stream()
            .collect(
                Collectors.toMap(
                    ShipmentFurnitureMovementTask::getOldRentalItemId,
                    ShipmentFurnitureMovementTask::getRentalItemId,
                    (left, right) -> left,
                    LinkedHashMap::new));
    allRequirements.forEach(
        requirement -> {
          UUID replacement = mappings.get(requirement.getRentalItemId());
          if (replacement != null) requirement.transferToRentalItem(replacement);
        });
    terms.forEach(
        term -> {
          UUID replacement = mappings.get(term.getRentalItemId());
          if (replacement != null) term.transferToRentalItem(replacement);
        });
    equipmentRequirements.saveAllAndFlush(allRequirements);
    rentalTerms.saveAllAndFlush(terms);

    boolean audited = false;
    Set<UUID> affectedDocumentIds = new java.util.LinkedHashSet<>();
    for (int index = 0; index < links.size(); index++) {
      ShipmentFurnitureMovementTask link = links.get(index);
      LogisticsDependencyGateway.OrderUnitReplacementReceipt pair =
          receipt.replacements().get(index);
      UUID oldUnitId = link.getOldRentalItemId();
      UUID newUnitId = link.getRentalItemId();
      if (link.getDocument() != null) {
        replaceDocumentLine(
            link.getDocument().getId(),
            oldUnitId,
            newUnitId,
            pair.replacementReservation().unit().version());
        affectedDocumentIds.add(link.getDocument().getId());
        ShipmentFurnitureMovementTask oldLink =
            furnitureTaskLinks
                .findByDocument_IdAndRentalItemId(link.getDocument().getId(), oldUnitId)
                .orElse(null);
        if (oldLink != null && !oldLink.getId().equals(link.getId()) && !oldLink.isReplacement()) {
          furnitureTaskLinks.delete(oldLink);
        }
      }
      if (!auditEvents.existsByOrderIdAndEventTypeAndSubjectTypeAndSubjectId(
          orderId,
          OrderAuditEventType.UNIT_REPLACED,
          "UNIT_REPLACEMENT",
          link.getId().toString())) {
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("rentalItemId", oldUnitId.toString());
        previous.put("unitNumber", pair.releasedReservation().unit().number());
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("rentalItemId", newUnitId.toString());
        replacement.put("unitNumber", pair.replacementReservation().unit().number());
        if (link.getReplacementReason() != null) {
          replacement.put("reason", link.getReplacementReason());
        }
        audit.append(
            orderId,
            OrderAuditEventType.UNIT_REPLACED,
            actor,
            "UNIT_REPLACEMENT",
            link.getId().toString(),
            Map.copyOf(previous),
            Map.copyOf(replacement));
        audited = true;
      }
      link.completeReplacement(
          link.getEquipmentMovementTaskId() == null
              ? null
              : pair.releasedReservation().reservationId(),
          now());
      if (link.getEquipmentMovementTaskId() != null) {
        movementTasks.resumeReplacementReservationReplay(link.getEquipmentMovementTaskId());
      }
    }
    for (UUID documentId : affectedDocumentIds) {
      LogisticsDocument document =
          documents
              .findForUpdate(documentId)
              .orElseThrow(
                  () ->
                      RentalOrderProblems.conflict(
                          "REPLACEMENT_DOCUMENT_NOT_FOUND", "Ходка замены не найдена"));
      driverTaskPlanner.plan(
          document, documentLines.findAllByDocument_IdOrderByLineNumber(documentId));
    }
    if (audited) {
      order.touch();
      store.persist(order);
      changed(order, actor, "unitsAndDesiredEquipment");
    }
    furnitureTaskLinks.saveAllAndFlush(links);
    for (ShipmentFurnitureMovementTask link : links) {
      store.remember(
          actor,
          "REPLACE_UNIT",
          link.getReplacementIdempotencyKey(),
          link.getReplacementRequestSha256(),
          order);
    }
    return new RentalOrderCommandOutcome(
        reads.detail(order, actor, reads.readUnits(order)), receipt.replayed());
  }

  RentalOrderCommandOutcome cancel(
      OrderActor actor, UUID orderId, long expectedVersion, UUID idempotencyKey) {
    String checksum =
        OrderCommandChecksum.sha256(
            CANCEL_ORDER, List.of(orderId.toString(), Long.toString(expectedVersion)));
    OrderCommandReceipt replay = store.replay(actor, CANCEL_ORDER, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }
    RentalOrder order = store.lockedOrder(orderId);
    access.requireMutable(actor, order);
    RentalOrderProblems.requireVersion(order, expectedVersion);
    order.requireDraft();
    List<LogisticsDependencyGateway.OrderUnitReservation> active = reads.readUnits(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> released;
    try {
      released =
          dependencies.releaseAllOrderUnits(
              idempotencyKey, orderId, actor.subjectId(), actor.role());
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
    if (released == null) throw RentalOrderProblems.invalidDependencyResponse();
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : released) {
      if (reservation == null || reservation.unitId() == null) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      requireReservation(
          reservation, orderId, reservation.unitId(), order.getWarehouseId(), "RELEASED");
    }
    Set<UUID> expectedUnitIds =
        active.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(Collectors.toUnmodifiableSet());
    Set<UUID> releasedUnitIds =
        released.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(Collectors.toUnmodifiableSet());
    if (expectedUnitIds.size() != active.size())
      throw RentalOrderProblems.invalidDependencyResponse();
    if (releasedUnitIds.size() != released.size())
      throw RentalOrderProblems.invalidDependencyResponse();
    if (!active.isEmpty() && !expectedUnitIds.equals(releasedUnitIds)) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    if (active.isEmpty()
        && !released.isEmpty()
        && released.stream().anyMatch(value -> !value.replayed())) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    List<RentalOrderEquipmentRequirement> existingRequirements =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    if (order.getWarehouseId() != null) {
      List<LogisticsDependencyGateway.OrderEquipmentReservation> furnitureReservations;
      try {
        furnitureReservations =
            dependencies.replaceOrderEquipmentReservations(
                idempotencyKey,
                orderId,
                order.getWarehouseId(),
                actor.subjectId(),
                actor.role(),
                List.of());
      } catch (LogisticsDependencyException exception) {
        throw RentalOrderProblems.dependencyProblem(exception);
      }
      requireEquipmentReservations(furnitureReservations, Map.of());
    }
    Map<UUID, String> unitNumbers =
        released.stream()
            .collect(
                Collectors.toMap(
                    LogisticsDependencyGateway.OrderUnitReservation::unitId,
                    reservation -> reservation.unit().number(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    for (UUID unitId :
        existingRequirements.stream()
            .map(RentalOrderEquipmentRequirement::getRentalItemId)
            .distinct()
            .toList()) {
      applyDesiredRequirements(
          order,
          unitId,
          unitNumbers.getOrDefault(unitId, "Бытовка"),
          existingRequirements,
          Map.of(),
          Map.of(),
          actor);
    }
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : released) {
      LogisticsDependencyGateway.OrderUnitReservation current =
          findCurrentUnit(orderId, reservation.unitId(), active);
      if (current != null && !current.reservationId().equals(reservation.reservationId())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      ensureUnitAddedEvidence(order, reservation, actor);
      if (!hasReservationEvidence(
          orderId, OrderAuditEventType.RESERVATION_RELEASED, reservation.reservationId())) {
        appendUnitReleasedEvidence(orderId, reservation, actor);
      }
    }
    order.cancel();
    store.persist(order);
    audit.append(
        orderId,
        OrderAuditEventType.ORDER_CANCELLED,
        actor,
        "ORDER",
        orderId.toString(),
        Map.of("status", RentalOrderStatus.DRAFT.name()),
        Map.of("status", RentalOrderStatus.CANCELLED.name()));
    changed(order, actor, "status");
    store.remember(actor, CANCEL_ORDER, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(reads.detail(order, actor, List.of()), false);
  }

  RentalOrderCommandOutcome save(
      OrderActor actor,
      UUID orderId,
      long expectedVersion,
      UUID idempotencyKey,
      UUID correlationId) {
    if (correlationId == null) {
      throw new IllegalArgumentException("Order correlation identifier is required");
    }
    String checksum =
        OrderCommandChecksum.sha256(
            SAVE_ORDER, List.of(orderId.toString(), Long.toString(expectedVersion)));
    OrderCommandReceipt replay = store.replay(actor, SAVE_ORDER, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }

    RentalOrder order = store.lockedOrder(orderId);
    access.requireVisible(actor, order);
    editability.requireNoPendingReplacement(orderId);
    boolean firstSave = order.getStatus() == RentalOrderStatus.DRAFT;
    if (firstSave) {
      access.requireMutable(actor, order);
    } else if (order.getStatus() == RentalOrderStatus.SAVED) {
      editability.requireEditable(actor, order);
    } else {
      throw RentalOrderProblems.conflict("ORDER_NOT_EDITABLE", "Заказ больше нельзя сохранять");
    }
    RentalOrderProblems.requireVersion(order, expectedVersion);
    if (firstSave) {
      try {
        order.requireFulfillmentDetails();
      } catch (IllegalStateException exception) {
        throw RentalOrderProblems.conflict(
            "ORDER_DELIVERY_DETAILS_REQUIRED",
            "Укажите адрес и контактный телефон заказа");
      }
    }
    List<LogisticsDependencyGateway.OrderUnitReservation> units = reads.readUnits(order);
    if (units.isEmpty()) {
      throw RentalOrderProblems.conflict(
          "ORDER_UNITS_REQUIRED", "Добавьте в заказ хотя бы одну бытовку");
    }
    units = synchronizeOrderUnits(order, actor, units);
    requireCompleteRentalTerms(order, units);
    if (firstSave) {
      order.saveForFulfillment();
      store.persist(order);
    }
    if (firstSave) {
      audit.append(
          orderId,
          OrderAuditEventType.ORDER_SAVED,
          actor,
          "ORDER",
          orderId.toString(),
          Map.of("status", RentalOrderStatus.DRAFT.name()),
          Map.of("status", RentalOrderStatus.SAVED.name()));
      changed(order, actor, "status");
    }
    store.remember(actor, SAVE_ORDER, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(reads.detail(order, actor, units), false);
  }

  private void requireCompleteRentalTerms(
      RentalOrder order, List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    Set<UUID> unitIds =
        units.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(Collectors.toUnmodifiableSet());
    Set<UUID> configured =
        rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(order.getId()).stream()
            .map(RentalOrderUnitTerm::getRentalItemId)
            .collect(Collectors.toUnmodifiableSet());
    if (!unitIds.equals(configured)) {
      throw RentalOrderProblems.conflict(
          "ORDER_RENTAL_TERMS_REQUIRED",
          "Перед сохранением подтвердите выбранный клиентом срок аренды для каждой бытовки");
    }
  }

  private List<LogisticsDependencyGateway.OrderUnitReservation> synchronizeOrderUnits(
      RentalOrder order,
      OrderActor actor,
      List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits) {
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> synchronizedUnits =
        new ArrayList<>(currentUnits.size());
    for (LogisticsDependencyGateway.OrderUnitReservation current : currentUnits) {
      UUID synchronizationKey =
          UUID.nameUUIDFromBytes(
              ("rental-order-reservation-v2:" + order.getId() + ":" + current.unitId())
                  .getBytes(StandardCharsets.UTF_8));
      try {
        LogisticsDependencyGateway.OrderUnitReservation synchronizedUnit =
            dependencies.reserveOrderUnit(
                synchronizationKey,
                order.getId(),
                warehouseId,
                current.unitId(),
                order.getClient().getId(),
                order.getClient().getDisplayName(),
                null,
                actor.subjectId(),
                actor.role());
        requireReservation(
            synchronizedUnit, order.getId(), current.unitId(), warehouseId, "ACTIVE");
        synchronizedUnits.add(synchronizedUnit);
      } catch (LogisticsDependencyException exception) {
        throw RentalOrderProblems.dependencyProblem(exception);
      }
    }
    return List.copyOf(synchronizedUnits);
  }

  private static Map<UUID, Long> desiredRequirements(
      List<OrderDesiredEquipmentInput> requirements) {
    if (requirements == null) {
      throw new IllegalArgumentException("Equipment requirements are required");
    }
    Map<UUID, Long> values = new LinkedHashMap<>();
    for (OrderDesiredEquipmentInput requirement : requirements) {
      if (requirement == null
          || requirement.equipmentId() == null
          || requirement.quantity() == null
          || requirement.quantity() < 1
          || values.putIfAbsent(requirement.equipmentId(), requirement.quantity()) != null) {
        throw new IllegalArgumentException("Equipment requirements are invalid");
      }
    }
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
  }

  private static Map<UUID, Long> aggregateRequirements(
      List<RentalOrderEquipmentRequirement> existing,
      UUID replacedUnitId,
      Map<UUID, Long> desired) {
    Map<UUID, Long> aggregate = new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement requirement : existing) {
      if (!replacedUnitId.equals(requirement.getRentalItemId()) && requirement.getQuantity() > 0) {
        aggregate.merge(requirement.getEquipmentId(), requirement.getQuantity(), Math::addExact);
      }
    }
    desired.forEach(
        (equipmentId, quantity) -> aggregate.merge(equipmentId, quantity, Math::addExact));
    return aggregate.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
  }

  private static List<LogisticsDependencyGateway.OrderEquipmentRequirement> dependencyRequirements(
      Map<UUID, Long> requirements) {
    return requirements.entrySet().stream()
        .map(
            entry ->
                new LogisticsDependencyGateway.OrderEquipmentRequirement(
                    entry.getKey(), entry.getValue()))
        .toList();
  }

  private static List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements>
      dependencyUnitRequirements(
          List<LogisticsDependencyGateway.OrderUnitReservation> units,
          List<RentalOrderEquipmentRequirement> existing,
          UUID replacedUnitId,
          Map<UUID, Long> desired) {
    Map<UUID, Map<UUID, Long>> requirementsByUnit = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderUnitReservation unit : units) {
      requirementsByUnit.put(unit.unitId(), new LinkedHashMap<>());
    }
    for (RentalOrderEquipmentRequirement requirement : existing) {
      if (requirement.getQuantity() > 0 && !replacedUnitId.equals(requirement.getRentalItemId())) {
        Map<UUID, Long> unitRequirements = requirementsByUnit.get(requirement.getRentalItemId());
        if (unitRequirements != null) {
          unitRequirements.put(requirement.getEquipmentId(), requirement.getQuantity());
        }
      }
    }
    Map<UUID, Long> replacement = requirementsByUnit.get(replacedUnitId);
    if (replacement != null) {
      replacement.putAll(desired);
    }
    return requirementsByUnit.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                    entry.getKey(), dependencyRequirements(entry.getValue())))
        .toList();
  }

  private static List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements>
      dependencyUnitRequirements(
          List<LogisticsDependencyGateway.OrderUnitReservation> units,
          List<RentalOrderEquipmentRequirement> existing,
          Map<UUID, Map<UUID, Long>> replacements) {
    Map<UUID, Map<UUID, Long>> requirementsByUnit = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderUnitReservation unit : units) {
      requirementsByUnit.put(unit.unitId(), new LinkedHashMap<>());
    }
    for (RentalOrderEquipmentRequirement requirement : existing) {
      if (requirement.getQuantity() > 0
          && !replacements.containsKey(requirement.getRentalItemId())) {
        Map<UUID, Long> unitRequirements = requirementsByUnit.get(requirement.getRentalItemId());
        if (unitRequirements != null) {
          unitRequirements.put(requirement.getEquipmentId(), requirement.getQuantity());
        }
      }
    }
    replacements.forEach(
        (unitId, desired) -> {
          Map<UUID, Long> target = requirementsByUnit.get(unitId);
          if (target != null) target.putAll(desired);
        });
    return requirementsByUnit.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                    entry.getKey(), dependencyRequirements(entry.getValue())))
        .toList();
  }

  private static Map<UUID, Long> aggregateComposition(
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition) {
    Map<UUID, Long> aggregate = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderUnitEquipmentRequirements unit : composition) {
      for (LogisticsDependencyGateway.OrderEquipmentRequirement requirement : unit.requirements()) {
        aggregate.merge(requirement.equipmentId(), requirement.quantity(), Math::addExact);
      }
    }
    return aggregate;
  }

  private static Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation>
      requireEquipmentReservations(
          List<LogisticsDependencyGateway.OrderEquipmentReservation> reservations,
          Map<UUID, Long> expected) {
    if (reservations == null || reservations.size() != expected.size()) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> actual = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderEquipmentReservation reservation : reservations) {
      if (reservation == null
          || reservation.equipmentId() == null
          || reservation.equipmentName() == null
          || reservation.equipmentName().isBlank()
          || reservation.quantity() < 1
          || reservation.availableQuantity() < 0
          || reservation.maximumPerCabin() != null && reservation.maximumPerCabin() < 1
          || actual.putIfAbsent(reservation.equipmentId(), reservation) != null
          || !Objects.equals(expected.get(reservation.equipmentId()), reservation.quantity())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
    }
    if (!actual.keySet().equals(expected.keySet())) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    return actual;
  }

  private boolean applyDesiredRequirements(
      RentalOrder order,
      UUID unitId,
      String unitNumber,
      List<RentalOrderEquipmentRequirement> existing,
      Map<UUID, Long> desired,
      Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservations,
      OrderActor actor) {
    Map<UUID, RentalOrderEquipmentRequirement> current = new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement requirement : existing) {
      if (unitId.equals(requirement.getRentalItemId())
          && current.putIfAbsent(requirement.getEquipmentId(), requirement) != null) {
        throw new IllegalStateException("Duplicate order equipment requirement");
      }
    }
    List<RentalOrderEquipmentRequirement> changedRequirements = new ArrayList<>();
    for (RentalOrderEquipmentRequirement requirement : current.values()) {
      long previousQuantity = requirement.getQuantity();
      long nextQuantity = desired.getOrDefault(requirement.getEquipmentId(), 0L);
      LogisticsDependencyGateway.OrderEquipmentReservation reservation =
          reservations.get(requirement.getEquipmentId());
      String nextName =
          reservation == null ? requirement.getEquipmentName() : reservation.equipmentName();
      if (requirement.change(nextName, nextQuantity)) {
        changedRequirements.add(requirement);
        appendDesiredEquipmentEvidence(
            order.getId(),
            actor,
            unitNumber,
            nextName,
            requirement.getEquipmentId(),
            previousQuantity,
            nextQuantity);
      }
    }
    for (Map.Entry<UUID, Long> entry : desired.entrySet()) {
      if (current.containsKey(entry.getKey())) {
        continue;
      }
      LogisticsDependencyGateway.OrderEquipmentReservation reservation =
          reservations.get(entry.getKey());
      if (reservation == null) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      RentalOrderEquipmentRequirement created =
          RentalOrderEquipmentRequirement.create(
              order, unitId, entry.getKey(), reservation.equipmentName(), entry.getValue());
      changedRequirements.add(created);
      appendDesiredEquipmentEvidence(
          order.getId(),
          actor,
          unitNumber,
          reservation.equipmentName(),
          entry.getKey(),
          0,
          entry.getValue());
    }
    if (!changedRequirements.isEmpty()) {
      equipmentRequirements.saveAllAndFlush(changedRequirements);
      return true;
    }
    return false;
  }

  private void appendDesiredEquipmentEvidence(
      UUID orderId,
      OrderActor actor,
      String unitNumber,
      String equipmentName,
      UUID equipmentId,
      long previousQuantity,
      long nextQuantity) {
    OrderAuditEventType eventType =
        previousQuantity == 0
            ? OrderAuditEventType.EQUIPMENT_ADDED
            : nextQuantity > previousQuantity
                ? OrderAuditEventType.EQUIPMENT_INCREASED
                : OrderAuditEventType.EQUIPMENT_DECREASED;
    audit.append(
        orderId,
        eventType,
        actor,
        "EQUIPMENT",
        equipmentId.toString(),
        Map.of(
            "unitNumber", unitNumber,
            "equipmentName", equipmentName,
            "quantity", previousQuantity),
        Map.of(
            "unitNumber", unitNumber,
            "equipmentName", equipmentName,
            "quantity", nextQuantity));
  }

  private boolean hasReservationEvidence(
      UUID orderId, OrderAuditEventType eventType, UUID reservationId) {
    return auditEvents.existsByOrderIdAndEventTypeAndSubjectTypeAndSubjectId(
        orderId, eventType, "UNIT_RESERVATION", reservationId.toString());
  }

  private boolean ensureUnitAddedEvidence(
      RentalOrder order,
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      OrderActor actor) {
    if (hasReservationEvidence(
        order.getId(), OrderAuditEventType.RESERVATION_CREATED, reservation.reservationId())) {
      return false;
    }
    order.touch();
    store.persist(order);
    appendUnitAddedEvidence(order.getId(), reservation);
    changed(order, actor, "units");
    return true;
  }

  private void appendUnitAddedEvidence(
      UUID orderId, LogisticsDependencyGateway.OrderUnitReservation reservation) {
    audit.appendForActor(
        orderId,
        OrderAuditEventType.UNIT_ADDED,
        reservation.addedBySubjectId(),
        reservation.addedByRole(),
        "RENTAL_ITEM",
        reservation.unitId().toString(),
        null,
        Map.of("unitNumber", reservation.unit().number()));
    audit.appendForActor(
        orderId,
        OrderAuditEventType.RESERVATION_CREATED,
        reservation.addedBySubjectId(),
        reservation.addedByRole(),
        "UNIT_RESERVATION",
        reservation.reservationId().toString(),
        null,
        Map.of("unitNumber", reservation.unit().number(), "state", "ACTIVE"));
  }

  private void appendUnitReleasedEvidence(
      UUID orderId, LogisticsDependencyGateway.OrderUnitReservation reservation, OrderActor actor) {
    audit.append(
        orderId,
        OrderAuditEventType.UNIT_REMOVED,
        actor,
        "RENTAL_ITEM",
        reservation.unitId().toString(),
        Map.of("unitNumber", reservation.unit().number()),
        null);
    audit.append(
        orderId,
        OrderAuditEventType.RESERVATION_RELEASED,
        actor,
        "UNIT_RESERVATION",
        reservation.reservationId().toString(),
        Map.of("unitNumber", reservation.unit().number(), "state", "ACTIVE"),
        Map.of("unitNumber", reservation.unit().number(), "state", "RELEASED"));
  }

  private void changed(RentalOrder order, OrderActor actor, String field) {
    audit.append(
        order.getId(),
        OrderAuditEventType.ORDER_CHANGED,
        actor,
        "ORDER",
        order.getId().toString(),
        null,
        Map.of("changedField", field, "version", order.getVersion()));
  }

  private static LogisticsDependencyGateway.OrderUnitReservation requireCurrentUnit(
      UUID orderId, UUID unitId, List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    LogisticsDependencyGateway.OrderUnitReservation current =
        findCurrentUnit(orderId, unitId, units);
    if (current == null) {
      throw new OrderProblemException(
          HttpStatus.NOT_FOUND, "ORDER_UNIT_NOT_FOUND", "Бытовка не добавлена в этот заказ");
    }
    return current;
  }

  private static LogisticsDependencyGateway.OrderUnitReservation findCurrentUnit(
      UUID orderId, UUID unitId, List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    return units.stream()
        .filter(value -> orderId.equals(value.orderId()) && unitId.equals(value.unitId()))
        .findFirst()
        .orElse(null);
  }

  private static void requireReservation(
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      UUID orderId,
      UUID unitId,
      UUID warehouseId,
      String state) {
    if (reservation == null
        || reservation.reservationId() == null
        || reservation.addedBySubjectId() == null
        || reservation.addedByRole() == null
        || !RentalOrderReadService.hasOrderActorRole(reservation.addedByRole())
        || warehouseId == null
        || !orderId.equals(reservation.orderId())
        || !unitId.equals(reservation.unitId())
        || !warehouseId.equals(reservation.warehouseId())
        || !state.equals(reservation.state())
        || reservation.unit() == null
        || !unitId.equals(reservation.unit().id())
        || !warehouseId.equals(reservation.unit().warehouseId())) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
  }

  private static void requireReplacementReceipt(
      ShipmentFurnitureMovementTask link,
      RentalOrder order,
      LogisticsDependencyGateway.OrderUnitReplacementReceipt receipt) {
    if (receipt == null
        || receipt.releasedReservation() == null
        || receipt.replacementReservation() == null
        || receipt.movementReservations() == null) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    requireReservation(
        receipt.releasedReservation(),
        order.getId(),
        link.getOldRentalItemId(),
        order.getWarehouseId(),
        "RELEASED");
    requireReservation(
        receipt.replacementReservation(),
        order.getId(),
        link.getRentalItemId(),
        order.getWarehouseId(),
        "ACTIVE");
  }

  private static OrderActor replacementActor(ShipmentFurnitureMovementTask link, UUID warehouseId) {
    String role = link.getReplacementActorRole();
    boolean global = Set.of("SYSTEM_ADMIN", "WMS_ADMIN").contains(role);
    boolean local = "WAREHOUSE_MANAGER".equals(role);
    return new OrderActor(
        link.getReplacementActorSubjectId(),
        role,
        link.getReplacementActorSubjectId().toString(),
        Set.of(warehouseId),
        Set.of(warehouseId),
        global,
        local,
        true,
        true);
  }

  private void replaceDocumentLine(
      UUID documentId, UUID oldUnitId, UUID newUnitId, long assetVersion) {
    LogisticsDocument document =
        documents
            .findForUpdate(documentId)
            .orElseThrow(
                () ->
                    RentalOrderProblems.conflict(
                        "REPLACEMENT_DOCUMENT_NOT_FOUND", "Отгрузка замены не найдена"));
    if (document.getState() == LogisticsDocumentState.SHIPPED
        || document.getState() == LogisticsDocumentState.DEPARTING
        || document.getState() == LogisticsDocumentState.IN_TRANSIT
        || document.getState() == LogisticsDocumentState.COMPLETED) {
      throw RentalOrderProblems.conflict(
          "REPLACEMENT_SHIPMENT_STARTED", "Начатую ходку нельзя перевести на другую бытовку");
    }
    List<LogisticsDocumentLine> lines =
        documentLines.findAllByDocument_IdOrderByLineNumber(documentId);
    LogisticsDocumentLine line =
        lines.stream()
            .filter(value -> oldUnitId.equals(value.getAssetId()))
            .findFirst()
            .orElseThrow(
                () ->
                    RentalOrderProblems.conflict(
                        "REPLACEMENT_DOCUMENT_LINE_NOT_FOUND",
                        "Заменяемая бытовка отсутствует в ходке"));
    if (driverTasks
        .findActiveForUpdateBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, documentId, DriverTaskKind.SHIPMENT)
        .isPresent()) {
      throw RentalOrderProblems.conflict(
          "REPLACEMENT_TRIP_FENCE_LOST", "Ходка должна быть остановлена перед заменой бытовки");
    }
    line.replaceRentalItem(oldUnitId, newUnitId, assetVersion);
    documentLines.saveAndFlush(line);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static UUID requiredWarehouse(RentalOrder order) {
    if (order.getWarehouseId() == null) {
      throw RentalOrderProblems.conflict(
          "ORDER_WAREHOUSE_REQUIRED", "Сначала выберите склад заказа");
    }
    return order.getWarehouseId();
  }
}
