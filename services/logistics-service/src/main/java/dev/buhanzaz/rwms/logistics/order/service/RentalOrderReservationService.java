package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AddOrderUnitRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDesiredEquipmentInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderUnitDesiredEquipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.OrderAuditEventRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
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
  private static final String ADD_UNIT = "ADD_UNIT";
  private static final String REMOVE_UNIT = "REMOVE_UNIT";
  private static final String SET_DESIRED_EQUIPMENT = "SET_DESIRED_EQUIPMENT";

  private final RentalOrderCommandStore store;
  private final OrderAuditEventRepository auditEvents;
  private final RentalOrderEquipmentRequirementRepository equipmentRequirements;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final OrderAuditService audit;
  private final OrderAuthorizer access;
  private final LogisticsDependencyGateway dependencies;
  private final RentalOrderReadService reads;
  private final RentalOrderEditabilityService editability;
  private final RentalSettingsService rentalSettings;

  RentalOrderCommandOutcome addUnit(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      AddOrderUnitRequest request) {
    String checksum =
        OrderCommandChecksum.sha256(
            ADD_UNIT,
            List.of(
                orderId.toString(),
                request.unitId().toString(),
                Long.toString(request.expectedVersion())));
    OrderCommandReceipt replay = store.replay(actor, ADD_UNIT, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }

    RentalOrder order = store.lockedOrder(orderId);
    editability.requireEditable(actor, order);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits = reads.readUnits(order);
    LogisticsDependencyGateway.OrderUnitReservation current =
        findCurrentUnit(orderId, request.unitId(), currentUnits);
    if (current != null) {
      boolean recorded = !ensureUnitAddedEvidence(order, current, actor);
      store.remember(actor, ADD_UNIT, idempotencyKey, checksum, order);
      return new RentalOrderCommandOutcome(reads.detail(order, actor, currentUnits), recorded);
    }
    try {
      LogisticsDependencyGateway.OrderUnitReservation reservation =
          dependencies.reserveOrderUnit(
              idempotencyKey,
              orderId,
              warehouseId,
              request.unitId(),
              order.getClient().getId(),
              order.getClient().getDisplayName(),
              draftReservationExpiresAt(order, actor),
              actor.subjectId(),
              actor.role());
      requireReservation(reservation, orderId, request.unitId(), warehouseId, "ACTIVE");
      if (!actor.subjectId().equals(reservation.addedBySubjectId())
          || !actor.role().equals(reservation.addedByRole())) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      ensureUnitAddedEvidence(order, reservation, actor);
      editability.synchronizeSavedShipmentDraft(order, actor, reads.readUnits(order));
      store.remember(actor, ADD_UNIT, idempotencyKey, checksum, order);
      return new RentalOrderCommandOutcome(
          reads.detail(order, actor, reads.readUnits(order)), false);
    } catch (LogisticsDependencyException exception) {
      String code = exception.dependencyCode();
      if ("UNIT_ALREADY_RESERVED".equals(code)) {
        throw new OrderUnitConflictException(
            orderId,
            request.unitId(),
            code,
            "Бытовка уже занята другим активным заказом");
      }
      OrderProblemException problem = RentalOrderProblems.dependencyProblem(exception);
      if (problem.status() == HttpStatus.CONFLICT) {
        throw new OrderUnitConflictException(
            orderId, request.unitId(), problem.code(), problem.getMessage());
      }
      throw problem;
    }
  }

  RentalOrderCommandOutcome removeUnit(
      OrderActor actor,
      UUID orderId,
      UUID unitId,
      long expectedVersion,
      UUID idempotencyKey) {
    String checksum =
        OrderCommandChecksum.sha256(
            REMOVE_UNIT, List.of(orderId.toString(), unitId.toString(), Long.toString(expectedVersion)));
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
    if (order.getStatus() == RentalOrderStatus.SAVED && current != null && currentUnits.size() == 1) {
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
      if (current == null && !released.replayed()) throw RentalOrderProblems.invalidDependencyResponse();
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
              dependencyRequirements(remainingRequirements));
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
              orderId,
              OrderAuditEventType.RESERVATION_RELEASED,
              released.reservationId());
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
    LogisticsDependencyGateway.OrderUnitReservation unit =
        requireCurrentUnit(orderId, unitId, reads.readUnits(order));
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
              dependencyRequirements(aggregate));
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
    return new RentalOrderCommandOutcome(
        reads.detail(order, actor, reads.readUnits(order)), false);
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
    if (expectedUnitIds.size() != active.size()) throw RentalOrderProblems.invalidDependencyResponse();
    if (releasedUnitIds.size() != released.size()) throw RentalOrderProblems.invalidDependencyResponse();
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
            "Укажите адрес, координаты, контактный телефон и дни приёмки заказа");
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
          "Перед сохранением задайте срок аренды для каждой выбранной бытовки");
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
        requireReservation(synchronizedUnit, order.getId(), current.unitId(), warehouseId, "ACTIVE");
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
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                LinkedHashMap::new));
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
    desired.forEach((equipmentId, quantity) -> aggregate.merge(equipmentId, quantity, Math::addExact));
    return aggregate.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                LinkedHashMap::new));
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
      String nextName = reservation == null ? requirement.getEquipmentName() : reservation.equipmentName();
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
      LogisticsDependencyGateway.OrderEquipmentReservation reservation = reservations.get(entry.getKey());
      if (reservation == null) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      RentalOrderEquipmentRequirement created =
          RentalOrderEquipmentRequirement.create(
              order,
              unitId,
              entry.getKey(),
              reservation.equipmentName(),
              entry.getValue());
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
      UUID orderId,
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      OrderActor actor) {
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
      UUID orderId,
      UUID unitId,
      List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    LogisticsDependencyGateway.OrderUnitReservation current = findCurrentUnit(orderId, unitId, units);
    if (current == null) {
      throw new OrderProblemException(
          HttpStatus.NOT_FOUND, "ORDER_UNIT_NOT_FOUND", "Бытовка не добавлена в этот заказ");
    }
    return current;
  }

  private static LogisticsDependencyGateway.OrderUnitReservation findCurrentUnit(
      UUID orderId,
      UUID unitId,
      List<LogisticsDependencyGateway.OrderUnitReservation> units) {
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

  private static UUID requiredWarehouse(RentalOrder order) {
    if (order.getWarehouseId() == null) {
      throw RentalOrderProblems.conflict(
          "ORDER_WAREHOUSE_REQUIRED", "Сначала выберите склад заказа");
    }
    return order.getWarehouseId();
  }

  private OffsetDateTime draftReservationExpiresAt(RentalOrder order, OrderActor actor) {
    if (order.getStatus() != RentalOrderStatus.DRAFT) {
      return null;
    }
    return OffsetDateTime.now(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS)
        .plusMinutes(rentalSettings.draftReservationHoldMinutes(actor));
  }
}
