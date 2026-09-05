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
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderUnitDesiredEquipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.AdditionalContact;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderQuotedPrice;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.OrderAuditEventRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Operation;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.EquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.EquipmentReservationEvidence;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.EquipmentReservations;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.Intent;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.ReleasedUnits;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.UnitComposition;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.UnitReservationEvidence;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * local preparation/finalization of durable cancel and remove-unit recovery. Remote mutation
 * effects are executed only by the recovery orchestrator outside these local transactions.
 */
@Service
@RequiredArgsConstructor
class RentalOrderReservationService {
  private static final String SAVE_ORDER = "SAVE_ORDER";
  private static final String SET_DESIRED_EQUIPMENT = "SET_DESIRED_EQUIPMENT";
  private static final String APPLY_PRESENTATION_SELECTION = "APPLY_PRESENTATION_SELECTION";

  private final RentalOrderCommandStore store;
  private final OrderAuditEventRepository auditEvents;
  private final RentalOrderEquipmentRequirementRepository equipmentRequirements;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final RentalOrderPaymentReceiptStore paymentReceipts;
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

  /** Reads and validates the owner-authoritative active unit snapshot before intent persistence. */
  List<LogisticsDependencyGateway.OrderUnitReservation> readMutationUnits(
      OrderActor actor, UUID orderId) {
    RentalOrder order = store.requiredOrder(orderId);
    access.requireVisible(actor, order);
    return reads.readUnits(order);
  }

  /** Reads the post-effect owner snapshot outside the local finalization transaction. */
  List<LogisticsDependencyGateway.OrderUnitReservation> readRecoveryUnits(UUID orderId) {
    return reads.readUnits(store.requiredOrder(orderId));
  }

  /**
   * Validates one new command under the locked order row and freezes its exact remote/local intent.
   */
  Intent prepareMutationIntent(
      OrderActor actor,
      Operation operation,
      RentalOrder order,
      UUID targetUnitId,
      long expectedVersion,
      List<LogisticsDependencyGateway.OrderUnitReservation> activeUnits) {
    requireActiveMutationSnapshot(order, activeUnits);
    if (operation == Operation.EXPIRE_UNPAID_ORDER) {
      requireAutomaticPaymentExpiry(actor, order);
      RentalOrderProblems.requireVersion(order, expectedVersion);
    } else if (operation == Operation.CANCEL_ORDER) {
      if ("CUSTOMER".equals(actor.role())) {
        editability.requireEditable(actor, order);
        if (order.getStatus() != RentalOrderStatus.SAVED) {
          throw RentalOrderProblems.conflict(
              "CUSTOMER_BOOKING_NOT_EDITABLE",
              "Бронирование нельзя отменить после начала отгрузки или выполнения работ");
        }
      } else {
        access.requireMutable(actor, order);
        editability.requireNoPendingReplacement(order.getId());
        order.requireDraft();
      }
      RentalOrderProblems.requireVersion(order, expectedVersion);
    } else if (operation == Operation.REMOVE_UNIT) {
      editability.requireEditable(actor, order);
      RentalOrderProblems.requireVersion(order, expectedVersion);
      requiredWarehouse(order);
      LogisticsDependencyGateway.OrderUnitReservation current =
          findCurrentUnit(order.getId(), targetUnitId, activeUnits);
      editability.requireUnitNotAssignedToShipment(order.getId(), targetUnitId);
      if (order.getStatus() == RentalOrderStatus.SAVED
          && current != null
          && activeUnits.size() == 1) {
        throw RentalOrderProblems.conflict(
            "ORDER_UNITS_REQUIRED", "Сохранённое бронирование должно содержать хотя бы одну бытовку");
      }
    } else {
      throw new IllegalArgumentException("Unsupported rental-order mutation");
    }

    List<RentalOrderEquipmentRequirement> existingRequirements =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            order.getId());
    List<LogisticsDependencyGateway.OrderUnitReservation> remainingUnits =
        operation.releasesAllUnits()
            ? List.of()
            : activeUnits.stream()
                .filter(reservation -> !targetUnitId.equals(reservation.unitId()))
                .toList();
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        operation.releasesAllUnits()
            ? List.of()
            : dependencyUnitRequirements(
                remainingUnits, existingRequirements, targetUnitId, Map.of());
    return new Intent(
        activeUnits.stream()
            .map(RentalOrderReservationService::reservationEvidence)
            .sorted(Comparator.comparing(UnitReservationEvidence::unitId))
            .toList(),
        mutationComposition(composition));
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

    RentalOrder order = store.lockedOrder(actor, orderId);
    editability.requireEditable(actor, order);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits = reads.readUnits(order);
    LogisticsDependencyGateway.OrderUnitReservation unit =
        requireCurrentUnit(orderId, unitId, currentUnits);
    List<RentalOrderEquipmentRequirement> existing =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId);
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
        dependencyUnitRequirements(currentUnits, existing, unitId, desired);
    List<LogisticsDependencyGateway.OrderEquipmentReservation> reservations;
    try {
      reservations =
          dependencies.replaceOrderEquipmentReservations(
              idempotencyKey,
              orderId,
              warehouseId,
              actor.subjectId(),
              actor.role(),
              composition);
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservationByEquipment =
        requireEquipmentReservations(
            reservations, aggregateCompositionBySource(composition, currentUnits));
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

  /**
   * Applies a confirmed normal presentation inside the order's fenced command boundary. The one to
   * five independently selected days are canonicalized before replay lookup so their client click
   * order cannot create a second command outcome.
   */
  RentalOrderCommandOutcome applyPresentationSelection(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      LogisticsDependencyGateway.ConvertedPresentationHolds conversion,
      Map<UUID, Map<UUID, Long>> selectedRequirements,
      List<DesiredDeliveryWindow> desiredDeliveryWindows,
      Map<UUID, Long> rentalTermsBySelection,
      Map<UUID, RentalOrderQuotedPrice> quotedPrices,
      Long legacyUniformRentalMonths,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      List<AdditionalContact> additionalContacts,
      List<String> legacyDesiredDeliveryTimes) {
    if (selectedRequirements == null
        || selectedRequirements.isEmpty()
        || rentalTermsBySelection == null
        || quotedPrices == null
        || !quotedPrices.keySet().equals(selectedRequirements.keySet())
        || quotedPrices.values().stream().anyMatch(Objects::isNull)
        || !rentalTermsBySelection.keySet().equals(selectedRequirements.keySet())
        || rentalTermsBySelection.values().stream()
            .anyMatch(months -> months == null || months < 1 || months > 120)) {
      throw new IllegalArgumentException("Presentation selection is required");
    }
    List<DesiredDeliveryWindow> normalizedDesiredDeliveryWindows =
        normalizedPresentationDesiredDeliveryWindows(desiredDeliveryWindows);
    List<AdditionalContact> normalizedContacts =
        additionalContacts == null ? List.of() : List.copyOf(additionalContacts);
    List<String> checksumValues =
        presentationChecksumValues(
            orderId,
            normalizedDesiredDeliveryWindows,
            rentalTermsBySelection,
            legacyUniformRentalMonths,
            deliveryAddress,
            latitude,
            longitude,
            normalizedContacts,
            selectedRequirements);
    String checksum = OrderCommandChecksum.sha256(APPLY_PRESENTATION_SELECTION, checksumValues);
    String legacyChecksum =
        legacyPresentationChecksum(
            orderId,
            normalizedDesiredDeliveryWindows,
            legacyUniformRentalMonths,
            legacyDesiredDeliveryTimes,
            selectedRequirements);
    OrderCommandReceipt replay =
        store.replay(actor, APPLY_PRESENTATION_SELECTION, idempotencyKey, checksum, legacyChecksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }

    RentalOrder order = store.lockedOrder(actor, orderId);
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
    if (conversion == null || conversion.equipmentReservations() == null) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservationsByEquipment =
        requireEquipmentReservations(
            conversion.equipmentReservations(),
            aggregateCompositionBySource(composition, currentUnits));
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
      long rentalMonths = rentalTermsBySelection.get(selection.getKey());
      if (existingTerm == null) {
        newRentalTerms.add(
            RentalOrderUnitTerm.create(
                order, selection.getKey(), rentalMonths, quotedPrices.get(selection.getKey())));
      } else if (existingTerm.getRentalMonths() != rentalMonths) {
        throw RentalOrderProblems.conflict(
            "PRESENTATION_RENTAL_TERM_IMMUTABLE",
            "Срок аренды выбранной бытовки уже зафиксирован другим бронированием");
      }
    }
    boolean desiredWindowChanged =
        order.replaceClientDesiredDeliveryWindows(normalizedDesiredDeliveryWindows);
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
          "unitsAndDesiredEquipment,desiredDeliveryWindows,clientDeliveryDetails,rentalTerms");
    }
    editability.synchronizeSavedShipmentDraft(order, actor, currentUnits);
    store.remember(actor, APPLY_PRESENTATION_SELECTION, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(reads.detail(order, actor, currentUnits), false);
  }

  private static List<String> presentationChecksumValues(
      UUID orderId,
      List<DesiredDeliveryWindow> desiredDeliveryWindows,
      Map<UUID, Long> rentalTerms,
      Long legacyUniformRentalMonths,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      List<AdditionalContact> additionalContacts,
      Map<UUID, Map<UUID, Long>> selectedRequirements) {
    List<String> values = new ArrayList<>();
    values.add(orderId.toString());
    desiredDeliveryWindows.forEach(
        window -> {
          values.add(window.getStartDate().toString());
          values.add(window.getEndDate().toString());
        });
    if (legacyUniformRentalMonths != null) {
      values.add(Long.toString(legacyUniformRentalMonths));
    } else {
      values.add("PER_CABIN_RENTAL_TERMS");
      rentalTerms.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              term -> {
                values.add(term.getKey().toString());
                values.add(Long.toString(term.getValue()));
              });
    }
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
      List<DesiredDeliveryWindow> desiredDeliveryWindows,
      Long legacyUniformRentalMonths,
      List<String> legacyDesiredDeliveryTimes,
      Map<UUID, Map<UUID, Long>> selectedRequirements) {
    if (legacyUniformRentalMonths == null
        || desiredDeliveryWindows.size() != 1
        || legacyDesiredDeliveryTimes == null
        || legacyDesiredDeliveryTimes.size() != 2) return null;
    DesiredDeliveryWindow desiredDeliveryWindow = desiredDeliveryWindows.getFirst();
    List<String> values = new ArrayList<>();
    values.add(orderId.toString());
    values.add(desiredDeliveryWindow.getStartDate().toString());
    values.add(desiredDeliveryWindow.getEndDate().toString());
    values.add(legacyDesiredDeliveryTimes.getFirst());
    values.add(legacyDesiredDeliveryTimes.get(1));
    values.add(Long.toString(legacyUniformRentalMonths));
    appendPresentationSelectionChecksumValues(values, selectedRequirements);
    return OrderCommandChecksum.sha256(APPLY_PRESENTATION_SELECTION, values);
  }

  /**
   * Canonicalizes up to five distinct one-day preferences before they affect idempotency or a
   * rental order.
   */
  private static List<DesiredDeliveryWindow> normalizedPresentationDesiredDeliveryWindows(
      List<DesiredDeliveryWindow> values) {
    if (values == null || values.isEmpty() || values.size() > 5) {
      throw new IllegalArgumentException("One to five desired delivery days are required");
    }
    LinkedHashSet<LocalDate> selectedDays = new LinkedHashSet<>();
    for (DesiredDeliveryWindow window : values) {
      if (window == null
          || window.getStartDate() == null
          || window.getEndDate() == null
          || !window.getStartDate().equals(window.getEndDate())
          || !selectedDays.add(window.getStartDate())) {
        throw new IllegalArgumentException("Desired delivery days must be distinct calendar days");
      }
    }
    return values.stream().sorted(Comparator.comparing(DesiredDeliveryWindow::getStartDate)).toList();
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
            pair.replacementReservation().unit().version(),
            link.getReplacementInventorySourceWarehouseId());
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
        previous.put(
            "inventorySourceWarehouseId",
            pair.releasedReservation().warehouseId().toString());
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("rentalItemId", newUnitId.toString());
        replacement.put("unitNumber", pair.replacementReservation().unit().number());
        replacement.put(
            "inventorySourceWarehouseId",
            pair.replacementReservation().warehouseId().toString());
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

  /** Validates and normalizes one replay-safe release-unit or release-all owner receipt. */
  ReleasedUnits validateReleasedUnits(
      Operation operation,
      UUID orderId,
      UUID targetUnitId,
      Intent intent,
      List<LogisticsDependencyGateway.OrderUnitReservation> released) {
    if (released == null || released.stream().anyMatch(Objects::isNull)) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    Map<UUID, UnitReservationEvidence> activeByUnit = evidenceByUnit(intent.activeUnits());
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : released) {
      requireReservationAtOwnSource(reservation, orderId, reservation.unitId(), "RELEASED");
      UnitReservationEvidence expected = activeByUnit.get(reservation.unitId());
      if (expected != null && !expected.warehouseId().equals(reservation.warehouseId())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
    }
    if (operation.releasesAllUnits()) {
      Set<UUID> releasedUnitIds =
          released.stream()
              .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
              .collect(Collectors.toUnmodifiableSet());
      if (releasedUnitIds.size() != released.size()
          || !intent.activeUnits().isEmpty()
              && !activeByUnit.keySet().equals(releasedUnitIds)
          || intent.activeUnits().isEmpty()
              && !released.isEmpty()
              && released.stream().anyMatch(value -> !value.replayed())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      for (LogisticsDependencyGateway.OrderUnitReservation reservation : released) {
        UnitReservationEvidence expected = activeByUnit.get(reservation.unitId());
        if (expected != null
            && (!expected.reservationId().equals(reservation.reservationId())
                || !expected.warehouseId().equals(reservation.warehouseId()))) {
          throw RentalOrderProblems.invalidDependencyResponse();
        }
      }
    } else if (operation == Operation.REMOVE_UNIT) {
      if (released.size() != 1 || !targetUnitId.equals(released.getFirst().unitId())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      UnitReservationEvidence expected = activeByUnit.get(targetUnitId);
      if ((expected == null && !released.getFirst().replayed())
          || (expected != null
              && (!expected.reservationId().equals(released.getFirst().reservationId())
                  || !expected.warehouseId().equals(released.getFirst().warehouseId())))) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
    } else {
      throw new IllegalArgumentException("Unsupported rental-order mutation");
    }
    return new ReleasedUnits(
        released.stream()
            .map(RentalOrderReservationService::reservationEvidence)
            .sorted(Comparator.comparing(UnitReservationEvidence::unitId))
            .toList());
  }

  /** Validates and normalizes the owner receipt for the frozen remaining furniture composition. */
  EquipmentReservations validateEquipmentReservations(
      Intent intent, List<LogisticsDependencyGateway.OrderEquipmentReservation> reservations) {
    Map<SourceEquipmentKey, Long> expected = aggregateCompositionBySource(intent);
    requireEquipmentReservations(reservations, expected);
    Set<UUID> expectedWarehouses =
        expected.keySet().stream()
            .map(SourceEquipmentKey::warehouseId)
            .collect(Collectors.toUnmodifiableSet());
    return new EquipmentReservations(
        reservations.stream()
            .map(
                reservation -> {
                  UUID warehouseId = reservation.warehouseId();
                  if (warehouseId == null && expectedWarehouses.size() == 1) {
                    warehouseId = expectedWarehouses.iterator().next();
                  }
                  return new EquipmentReservationEvidence(
                      warehouseId,
                      reservation.equipmentId(),
                      reservation.equipmentName(),
                      reservation.quantity(),
                      reservation.availableQuantity(),
                      reservation.maximumPerCabin());
                })
            .sorted(
                Comparator.comparing(EquipmentReservationEvidence::warehouseId)
                    .thenComparing(EquipmentReservationEvidence::equipmentId))
            .toList());
  }

  /** Rebuilds only the already frozen asset request body for an idempotent retry. */
  List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> mutationComposition(
      Intent intent) {
    return intent.remainingComposition().stream()
        .map(
            unit ->
                new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                    unit.rentalItemId(),
                    unit.requirements().stream()
                        .map(
                            requirement ->
                                new LogisticsDependencyGateway.OrderEquipmentRequirement(
                                    requirement.equipmentId(), requirement.quantity()))
                        .toList()))
        .toList();
  }

  /**
   * Applies the local mutation only after both stored owner receipts and the current remote unit set
   * agree with the frozen intent. The caller completes the command and idempotency receipt in this
   * same transaction.
   */
  RentalOrder finalizeMutation(
      RentalOrderMutationCommand command,
      Intent intent,
      ReleasedUnits releasedUnits,
      EquipmentReservations equipmentReservations,
      List<LogisticsDependencyGateway.OrderUnitReservation> remainingActiveUnits,
      OffsetDateTime completedAt) {
    RentalOrder order = store.recoveryOrder(command.getOrder().getId());
    RentalOrderProblems.requireVersion(order, command.getExpectedOrderVersion());
    requireRemainingSnapshot(command, order, intent, remainingActiveUnits);
    requireStoredReleaseReceipt(command, intent, releasedUnits);
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservationByEquipment =
        requireStoredEquipmentReceipt(intent, equipmentReservations);
    OrderActor actor = recoveryActor(command, order.getWarehouseId());
    List<RentalOrderEquipmentRequirement> existingRequirements =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            order.getId());

    if (command.getOperation() == Operation.REMOVE_UNIT) {
      editability.requireEditable(actor, order);
      UUID targetUnitId = command.getTargetUnitId();
      UnitReservationEvidence released = releasedUnits.units().getFirst();
      boolean furnitureChanged =
          applyDesiredRequirements(
              order,
              targetUnitId,
              released.unitNumber(),
              existingRequirements,
              Map.of(),
              reservationByEquipment,
              actor);
      rentalTerms.deleteAllByOrder_IdAndRentalItemId(order.getId(), targetUnitId);
      rentalTerms.flush();
      ensureUnitAddedEvidence(order, released, actor);
      boolean recorded =
          hasReservationEvidence(
              order.getId(), OrderAuditEventType.RESERVATION_RELEASED, released.reservationId());
      if (!recorded || furnitureChanged) {
        order.touch();
        store.persist(order);
        if (!recorded) appendUnitReleasedEvidence(order.getId(), released, actor);
        changed(order, actor, furnitureChanged ? "unitsAndDesiredEquipment" : "units");
      }
      editability.synchronizeSavedShipmentDraft(order, actor, remainingActiveUnits);
      return order;
    }

    RentalOrderStatus previousStatus = order.getStatus();
    if (command.getOperation() == Operation.EXPIRE_UNPAID_ORDER) {
      requireAutomaticPaymentExpiry(actor, order);
    } else if ("CUSTOMER".equals(actor.role())) {
      editability.requireEditable(actor, order);
      if (order.getStatus() != RentalOrderStatus.SAVED) {
        throw RentalOrderProblems.conflict(
            "CUSTOMER_BOOKING_NOT_EDITABLE",
            "Бронирование нельзя отменить после начала отгрузки или выполнения работ");
      }
    } else {
      access.requireMutable(actor, order);
      order.requireDraft();
    }
    Map<UUID, String> unitNumbers =
        releasedUnits.units().stream()
            .collect(
                Collectors.toMap(
                    UnitReservationEvidence::unitId,
                    UnitReservationEvidence::unitNumber,
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
    for (UnitReservationEvidence released : releasedUnits.units()) {
      ensureUnitAddedEvidence(order, released, actor);
      if (!hasReservationEvidence(
          order.getId(), OrderAuditEventType.RESERVATION_RELEASED, released.reservationId())) {
        appendUnitReleasedEvidence(order.getId(), released, actor);
      }
    }
    if (command.getOperation() == Operation.EXPIRE_UNPAID_ORDER) {
      order.completePaymentExpiry(completedAt);
    } else if (previousStatus == RentalOrderStatus.SAVED) {
      order.cancelSavedCustomerBooking();
    } else {
      order.cancel();
    }
    store.persist(order);
    audit.append(
        order.getId(),
        OrderAuditEventType.ORDER_CANCELLED,
        actor,
        "ORDER",
        order.getId().toString(),
        Map.of("status", previousStatus.name()),
        command.getOperation() == Operation.EXPIRE_UNPAID_ORDER
            ? Map.of(
                "status",
                RentalOrderStatus.CANCELLED.name(),
                "reason",
                "PAYMENT_RESERVATION_EXPIRED")
            : Map.of("status", RentalOrderStatus.CANCELLED.name()));
    changed(order, actor, "status");
    return order;
  }

  /**
   * Maps the just-flushed recovered aggregate without reloading a stale request-scoped JPA
   * instance.
   */
  OrderDetailResponse recoveryDetail(
      RentalOrderMutationCommand command,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> remainingActiveUnits) {
    return reads.detail(
        order,
        recoveryActor(command, order.getWarehouseId()),
        remainingActiveUnits);
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

    RentalOrder order = store.lockedOrder(actor, orderId);
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
      var receipt = paymentReceipts.capture(order, units);
      order.saveForFulfillment();
      order.startPaymentReservation(receipt.issuedAt());
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

  private static List<UnitComposition> mutationComposition(
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition) {
    return composition.stream()
        .map(
            unit ->
                new UnitComposition(
                    unit.rentalItemId(),
                    unit.requirements().stream()
                        .map(
                            requirement ->
                                new EquipmentRequirement(
                                    requirement.equipmentId(), requirement.quantity()))
                        .sorted(Comparator.comparing(EquipmentRequirement::equipmentId))
                        .toList()))
        .sorted(Comparator.comparing(UnitComposition::rentalItemId))
        .toList();
  }

  private static Map<SourceEquipmentKey, Long> aggregateCompositionBySource(Intent intent) {
    Map<UUID, UUID> sourceByUnit =
        intent.activeUnits().stream()
            .collect(
                Collectors.toMap(
                    UnitReservationEvidence::unitId,
                    UnitReservationEvidence::warehouseId,
                    (left, right) -> {
                      throw new IllegalStateException("Duplicate active order unit reservation");
                    },
                    LinkedHashMap::new));
    Map<SourceEquipmentKey, Long> aggregate = new LinkedHashMap<>();
    for (UnitComposition unit : intent.remainingComposition()) {
      UUID sourceWarehouseId = sourceByUnit.get(unit.rentalItemId());
      if (sourceWarehouseId == null) throw RentalOrderProblems.invalidDependencyResponse();
      for (EquipmentRequirement requirement : unit.requirements()) {
        aggregate.merge(
            new SourceEquipmentKey(sourceWarehouseId, requirement.equipmentId()),
            requirement.quantity(),
            Math::addExact);
      }
    }
    return aggregate;
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

  private static Map<SourceEquipmentKey, Long> aggregateCompositionBySource(
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition,
      List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    Map<UUID, UUID> sourceByUnit =
        units.stream()
            .collect(
                Collectors.toMap(
                    LogisticsDependencyGateway.OrderUnitReservation::unitId,
                    LogisticsDependencyGateway.OrderUnitReservation::warehouseId,
                    (left, right) -> {
                      throw new IllegalStateException("Duplicate active order unit reservation");
                    },
                    LinkedHashMap::new));
    Map<SourceEquipmentKey, Long> aggregate = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderUnitEquipmentRequirements unit : composition) {
      UUID sourceWarehouseId = sourceByUnit.get(unit.rentalItemId());
      if (sourceWarehouseId == null) throw RentalOrderProblems.invalidDependencyResponse();
      for (LogisticsDependencyGateway.OrderEquipmentRequirement requirement : unit.requirements()) {
        aggregate.merge(
            new SourceEquipmentKey(sourceWarehouseId, requirement.equipmentId()),
            requirement.quantity(),
            Math::addExact);
      }
    }
    return aggregate;
  }

  private static Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation>
      requireEquipmentReservations(
          List<LogisticsDependencyGateway.OrderEquipmentReservation> reservations,
          Map<SourceEquipmentKey, Long> expected) {
    if (reservations == null || reservations.size() != expected.size()) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    Set<UUID> expectedWarehouses =
        expected.keySet().stream()
            .map(SourceEquipmentKey::warehouseId)
            .collect(Collectors.toUnmodifiableSet());
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> actual = new LinkedHashMap<>();
    Map<SourceEquipmentKey, Long> actualBySource = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderEquipmentReservation reservation : reservations) {
      UUID warehouseId = reservation == null ? null : reservation.warehouseId();
      if (warehouseId == null && expectedWarehouses.size() == 1) {
        warehouseId = expectedWarehouses.iterator().next();
      }
      if (reservation == null
          || warehouseId == null
          || reservation.equipmentId() == null
          || reservation.equipmentName() == null
          || reservation.equipmentName().isBlank()
          || reservation.quantity() < 1
          || reservation.availableQuantity() < 0
          || reservation.maximumPerCabin() != null && reservation.maximumPerCabin() < 1
          || actualBySource.putIfAbsent(
                  new SourceEquipmentKey(warehouseId, reservation.equipmentId()),
                  reservation.quantity())
              != null
          || !Objects.equals(
              expected.get(new SourceEquipmentKey(warehouseId, reservation.equipmentId())),
              reservation.quantity())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      LogisticsDependencyGateway.OrderEquipmentReservation existing =
          actual.putIfAbsent(reservation.equipmentId(), reservation);
      if (existing != null
          && (!existing.equipmentName().equals(reservation.equipmentName())
              || !Objects.equals(existing.maximumPerCabin(), reservation.maximumPerCabin()))) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
    }
    if (!actualBySource.equals(expected)) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    return actual;
  }

  private void requireRemainingSnapshot(
      RentalOrderMutationCommand command,
      RentalOrder order,
      Intent intent,
      List<LogisticsDependencyGateway.OrderUnitReservation> remainingActiveUnits) {
    requireActiveMutationSnapshot(order, remainingActiveUnits);
    Map<UUID, UnitReservationEvidence> expected =
        intent.activeUnits().stream()
            .filter(
                unit ->
                    !command.getOperation().releasesAllUnits()
                        && !unit.unitId().equals(command.getTargetUnitId()))
            .collect(
                Collectors.toMap(
                    UnitReservationEvidence::unitId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    if (expected.size() != remainingActiveUnits.size()) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    for (LogisticsDependencyGateway.OrderUnitReservation current : remainingActiveUnits) {
      UnitReservationEvidence frozen = expected.get(current.unitId());
      if (frozen == null
          || !frozen.reservationId().equals(current.reservationId())
          || !frozen.warehouseId().equals(current.warehouseId())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
    }
  }

  private static void requireStoredReleaseReceipt(
      RentalOrderMutationCommand command, Intent intent, ReleasedUnits releasedUnits) {
    Map<UUID, UnitReservationEvidence> activeByUnit = evidenceByUnit(intent.activeUnits());
    if (command.getOperation().releasesAllUnits()) {
      Set<UUID> releasedIds =
          releasedUnits.units().stream()
              .map(UnitReservationEvidence::unitId)
              .collect(Collectors.toUnmodifiableSet());
      if (!intent.activeUnits().isEmpty() && !activeByUnit.keySet().equals(releasedIds)
          || intent.activeUnits().isEmpty()
              && !releasedUnits.units().isEmpty()
              && releasedUnits.units().stream().anyMatch(value -> !value.replayed())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      for (UnitReservationEvidence released : releasedUnits.units()) {
        UnitReservationEvidence expected = activeByUnit.get(released.unitId());
        if (expected != null
            && (!expected.reservationId().equals(released.reservationId())
                || !expected.warehouseId().equals(released.warehouseId()))) {
          throw RentalOrderProblems.invalidDependencyResponse();
        }
      }
      return;
    }
    if (releasedUnits.units().size() != 1
        || !command.getTargetUnitId().equals(releasedUnits.units().getFirst().unitId())) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    UnitReservationEvidence expected = activeByUnit.get(command.getTargetUnitId());
    UnitReservationEvidence released = releasedUnits.units().getFirst();
    if ((expected == null && !released.replayed())
        || (expected != null
            && (!expected.reservationId().equals(released.reservationId())
                || !expected.warehouseId().equals(released.warehouseId())))) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
  }

  private static Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation>
      requireStoredEquipmentReceipt(
          Intent intent, EquipmentReservations equipmentReservations) {
    List<LogisticsDependencyGateway.OrderEquipmentReservation> values =
        equipmentReservations.reservations().stream()
            .map(
                reservation ->
                    new LogisticsDependencyGateway.OrderEquipmentReservation(
                        reservation.warehouseId(),
                        reservation.equipmentId(),
                        reservation.equipmentName(),
                        reservation.quantity(),
                        reservation.availableQuantity(),
                        reservation.maximumPerCabin()))
            .toList();
    return requireEquipmentReservations(values, aggregateCompositionBySource(intent));
  }

  private static void requireActiveMutationSnapshot(
      RentalOrder order, List<LogisticsDependencyGateway.OrderUnitReservation> activeUnits) {
    if (activeUnits == null || activeUnits.stream().anyMatch(Objects::isNull)) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    Set<UUID> unitIds = new java.util.HashSet<>();
    Set<UUID> reservationIds = new java.util.HashSet<>();
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : activeUnits) {
      requireReservationAtOwnSource(reservation, order.getId(), reservation.unitId(), "ACTIVE");
      if (!unitIds.add(reservation.unitId()) || !reservationIds.add(reservation.reservationId())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
    }
  }

  private static void requireReservationAtOwnSource(
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      UUID orderId,
      UUID unitId,
      String state) {
    UUID sourceWarehouseId = reservation == null ? null : reservation.warehouseId();
    requireReservation(reservation, orderId, unitId, sourceWarehouseId, state);
  }

  private static UnitReservationEvidence reservationEvidence(
      LogisticsDependencyGateway.OrderUnitReservation reservation) {
    if (reservation == null
        || reservation.unit() == null
        || reservation.unit().number() == null
        || reservation.unit().number().isBlank()) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    return new UnitReservationEvidence(
        reservation.reservationId(),
        reservation.unitId(),
        reservation.warehouseId(),
        reservation.addedBySubjectId(),
        reservation.addedByRole(),
        reservation.unit().number(),
        reservation.replayed());
  }

  private static Map<UUID, UnitReservationEvidence> evidenceByUnit(
      List<UnitReservationEvidence> values) {
    return values.stream()
        .collect(
            Collectors.toMap(
                UnitReservationEvidence::unitId,
                value -> value,
                (left, right) -> {
                  throw new IllegalStateException("Duplicate active order unit reservation");
                },
                LinkedHashMap::new));
  }

  private static OrderActor recoveryActor(
      RentalOrderMutationCommand command, UUID warehouseId) {
    String role = command.getActorRole();
    boolean global = Set.of("SYSTEM_ADMIN", "WMS_ADMIN").contains(role);
    boolean local = "WAREHOUSE_MANAGER".equals(role);
    Set<UUID> warehouses = warehouseId == null ? Set.of() : Set.of(warehouseId);
    return new OrderActor(
        command.getActorSubjectId(),
        role,
        command.getActorSubjectId().toString(),
        warehouses,
        warehouses,
        global,
        local,
        true,
        true);
  }

  private static void requireAutomaticPaymentExpiry(OrderActor actor, RentalOrder order) {
    if (!RentalOrderMutationCommand.AUTOMATIC_RELEASE_ACTOR_ID.equals(actor.subjectId())
        || !"LOGISTICS_SERVICE".equals(actor.role())
        || order.getStatus() != RentalOrderStatus.SAVED
        || order.getPaymentState() != RentalOrderPaymentState.EXPIRING) {
      throw new IllegalStateException("Automatic payment expiry is not owned by this command");
    }
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

  private boolean ensureUnitAddedEvidence(
      RentalOrder order, UnitReservationEvidence reservation, OrderActor actor) {
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

  private void appendUnitAddedEvidence(UUID orderId, UnitReservationEvidence reservation) {
    audit.appendForActor(
        orderId,
        OrderAuditEventType.UNIT_ADDED,
        reservation.addedBySubjectId(),
        reservation.addedByRole(),
        "RENTAL_ITEM",
        reservation.unitId().toString(),
        null,
        Map.of("unitNumber", reservation.unitNumber()));
    audit.appendForActor(
        orderId,
        OrderAuditEventType.RESERVATION_CREATED,
        reservation.addedBySubjectId(),
        reservation.addedByRole(),
        "UNIT_RESERVATION",
        reservation.reservationId().toString(),
        null,
        Map.of("unitNumber", reservation.unitNumber(), "state", "ACTIVE"));
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

  private void appendUnitReleasedEvidence(
      UUID orderId, UnitReservationEvidence reservation, OrderActor actor) {
    audit.append(
        orderId,
        OrderAuditEventType.UNIT_REMOVED,
        actor,
        "RENTAL_ITEM",
        reservation.unitId().toString(),
        Map.of("unitNumber", reservation.unitNumber()),
        null);
    audit.append(
        orderId,
        OrderAuditEventType.RESERVATION_RELEASED,
        actor,
        "UNIT_RESERVATION",
        reservation.reservationId().toString(),
        Map.of("unitNumber", reservation.unitNumber(), "state", "ACTIVE"),
        Map.of("unitNumber", reservation.unitNumber(), "state", "RELEASED"));
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
        receipt.releasedReservation().warehouseId(),
        "RELEASED");
    requireReservation(
        receipt.replacementReservation(),
        order.getId(),
        link.getRentalItemId(),
        link.getReplacementInventorySourceWarehouseId(),
        "ACTIVE");
  }

  private static OrderActor replacementActor(
      ShipmentFurnitureMovementTask link, UUID warehouseId) {
    String role = link.getReplacementActorRole();
    boolean global = Set.of("SYSTEM_ADMIN", "WMS_ADMIN").contains(role);
    boolean local = "WAREHOUSE_MANAGER".equals(role);
    return new OrderActor(
        link.getReplacementActorSubjectId(),
        role,
        link.getReplacementActorSubjectId().toString(),
        replacementWarehouseScopes(warehouseId, link.getReplacementInventorySourceWarehouseId()),
        replacementWarehouseScopes(warehouseId, link.getReplacementInventorySourceWarehouseId()),
        global,
        local,
        true,
        true);
  }

  private void replaceDocumentLine(
      UUID documentId,
      UUID oldUnitId,
      UUID newUnitId,
      long assetVersion,
      UUID inventorySourceWarehouseId) {
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
    line.replaceRentalItem(
        oldUnitId, newUnitId, assetVersion, inventorySourceWarehouseId);
    documentLines.saveAndFlush(line);
  }

  private static Set<UUID> replacementWarehouseScopes(
      UUID serviceWarehouseId, UUID inventorySourceWarehouseId) {
    LinkedHashSet<UUID> scopes = new LinkedHashSet<>();
    scopes.add(serviceWarehouseId);
    scopes.add(inventorySourceWarehouseId);
    return Set.copyOf(scopes);
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

  /** Exact validation key for one source-partitioned asset furniture reservation. */
  private record SourceEquipmentKey(UUID warehouseId, UUID equipmentId) {}
}
