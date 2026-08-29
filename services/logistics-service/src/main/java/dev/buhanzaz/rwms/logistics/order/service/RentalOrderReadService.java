package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDesiredEquipmentResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderEquipmentContentResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderHistoryEventResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderMovementCabinResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderMovementResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPermissions;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderRentalItemResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderRentalTermResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderSummaryResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitResponse;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
import dev.buhanzaz.rwms.logistics.order.repository.OrderAuditEventRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * Owns rental-order reads and the single response projection path. Remote reservation snapshots are
 * validated here before they are exposed to a read or reused by an in-transaction command.
 */
@Service
@RequiredArgsConstructor
class RentalOrderReadService {
  private static final Set<String> ORDER_ACTOR_ROLES =
      Set.of(
          "SYSTEM_ADMIN",
          "WMS_ADMIN",
          "WAREHOUSE_MANAGER",
          "RENTAL_MANAGER",
          "CUSTOMER",
          "VIEWER");

  private final RentalOrderRepository orders;
  private final OrderAuditEventRepository auditEvents;
  private final RentalOrderEquipmentRequirementRepository equipmentRequirements;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final LogisticsDocumentRepository logisticsDocuments;
  private final LogisticsDocumentLineRepository logisticsDocumentLines;
  private final OrderAuthorizer access;
  private final RentalOrderResponseMapper mapper;
  private final LogisticsDependencyGateway dependencies;
  private final RentalOrderEditabilityService editability;
  private final RentalOrderInventorySourcePolicy inventorySources;

  OrderPageResponse list(
      OrderActor actor,
      int page,
      int size,
      String search,
      String sort,
      String direction,
      List<RentalOrderStatus> statuses,
      List<ClientType> clientTypes,
      List<UUID> warehouseIds,
      OffsetDateTime createdFrom,
      OffsetDateTime createdTo) {
    return list(
        actor,
        null,
        page,
        size,
        search,
        sort,
        direction,
        statuses,
        clientTypes,
        warehouseIds,
        createdFrom,
        createdTo);
  }

  /** Lists one client's orders while retaining the ordinary per-order visibility predicate. */
  OrderPageResponse listForClient(
      OrderActor actor,
      UUID clientId,
      int page,
      int size,
      String sort,
      String direction,
      List<RentalOrderStatus> statuses,
      List<UUID> warehouseIds,
      OffsetDateTime createdFrom,
      OffsetDateTime createdTo) {
    return list(
        actor,
        clientId,
        page,
        size,
        "",
        sort,
        direction,
        statuses,
        null,
        warehouseIds,
        createdFrom,
        createdTo);
  }

  private OrderPageResponse list(
      OrderActor actor,
      UUID fixedClientId,
      int page,
      int size,
      String search,
      String sort,
      String direction,
      List<RentalOrderStatus> statuses,
      List<ClientType> clientTypes,
      List<UUID> warehouseIds,
      OffsetDateTime createdFrom,
      OffsetDateTime createdTo) {
    requirePage(page, size);
    if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
      throw new IllegalArgumentException("createdFrom must not be after createdTo");
    }
    Sort pageSort = orderSort(sort, direction);
    String normalizedSearch = normalizeSearch(search);
    Specification<RentalOrder> specification =
        (root, query, builder) -> {
          List<Predicate> predicates = new ArrayList<>();
          Predicate visible = visibility(root, builder, actor);
          predicates.add(visible);
          if (fixedClientId != null) {
            predicates.add(builder.equal(root.get("client").get("id"), fixedClientId));
          }
          if (statuses != null && !statuses.isEmpty()) {
            predicates.add(root.get("status").in(Set.copyOf(statuses)));
          }
          if (clientTypes != null && !clientTypes.isEmpty()) {
            predicates.add(root.join("client").get("clientType").in(Set.copyOf(clientTypes)));
          }
          if (warehouseIds != null && !warehouseIds.isEmpty()) {
            predicates.add(root.get("warehouseId").in(Set.copyOf(warehouseIds)));
          }
          if (createdFrom != null) {
            predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
          }
          if (createdTo != null) {
            predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), createdTo));
          }
          if (!normalizedSearch.isEmpty()) {
            List<Predicate> matching = new ArrayList<>();
            matching.add(
                builder.like(
                    builder.lower(root.get("orderNumber")),
                    "%" + escapeLike(normalizedSearch) + "%",
                    '\\'));
            matching.add(
                builder.like(
                    root.join("client").get("normalizedName"),
                    "%" + escapeLike(normalizedSearch) + "%",
                    '\\'));
            if (actor.canViewOtherManagers()) {
              matching.add(
                  builder.like(
                      builder.lower(root.get("managerDisplayName")),
                      "%" + escapeLike(normalizedSearch) + "%",
                      '\\'));
            }
            predicates.add(builder.or(matching.toArray(Predicate[]::new)));
          }
          return builder.and(predicates.toArray(Predicate[]::new));
        };
    Page<RentalOrder> result = orders.findAll(specification, PageRequest.of(page, size, pageSort));
    List<OrderSummaryResponse> content =
        result.getContent().stream()
            .map(order -> mapper.toSummaryResponse(order, readUnitsForView(actor, order).size()))
            .toList();
    return new OrderPageResponse(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  OrderDetailResponse get(OrderActor actor, UUID orderId) {
    RentalOrder order = order(orderId);
    access.requireVisible(actor, order);
    return detail(order, actor, readUnitsForView(actor, order));
  }

  /**
   * Reads only the client delivery wishes that an order-linked public presentation may expose. The
   * immutable inquiry association supplies the expected client and warehouse so a stale or
   * corrupted presentation cannot read another order's data.
   */
  List<DesiredDeliveryWindowResponse> presentationDesiredDeliveryWindows(
      UUID orderId, UUID expectedClientId, UUID expectedWarehouseId) {
    RentalOrder order = order(orderId);
    if (!order.getClient().getId().equals(expectedClientId)
        || !java.util.Objects.equals(order.getWarehouseId(), expectedWarehouseId)) {
      throw RentalOrderProblems.conflict(
          "PRESENTATION_ORDER_MISMATCH", "Представление больше не соответствует заказу");
    }
    return order.getDesiredDeliveryWindows().stream()
        .map(mapper::toDesiredDeliveryWindowResponse)
        .toList();
  }

  /**
   * Returns furniture physically available for redistribution from active cabins of the linked
   * order after retaining that order's complete desired composition. This amount may augment the
   * shared free pool for a normal add-cabin presentation, but never for a detached or replacement
   * presentation.
   */
  Map<UUID, Long> presentationEquipmentSurplus(
      UUID orderId, UUID expectedClientId, UUID expectedWarehouseId) {
    RentalOrder order = order(orderId);
    if (!order.getClient().getId().equals(expectedClientId)
        || !java.util.Objects.equals(order.getWarehouseId(), expectedWarehouseId)) {
      throw RentalOrderProblems.conflict(
          "PRESENTATION_ORDER_MISMATCH", "Представление больше не соответствует заказу");
    }
    Map<UUID, Long> actual = new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : readUnits(order)) {
      if (reservation.unit().contents() == null) {
        throw RentalOrderProblems.invalidDependencyResponse();
      }
      for (LogisticsDependencyGateway.OrderEquipmentContent content :
          reservation.unit().contents()) {
        if (content == null || content.equipmentId() == null || content.quantity() < 0) {
          throw RentalOrderProblems.invalidDependencyResponse();
        }
        if (content.quantity() > 0) {
          mergeQuantity(actual, content.equipmentId(), content.quantity());
        }
      }
    }
    Map<UUID, Long> desired = new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement requirement :
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            orderId)) {
      if (requirement.getQuantity() > 0) {
        mergeQuantity(desired, requirement.getEquipmentId(), requirement.getQuantity());
      }
    }
    Map<UUID, Long> surplus = new LinkedHashMap<>();
    actual.forEach(
        (equipmentId, quantity) -> {
          long unassigned = quantity - desired.getOrDefault(equipmentId, 0L);
          if (unassigned > 0) surplus.put(equipmentId, unassigned);
        });
    return Map.copyOf(surplus);
  }

  OrderUnitPageResponse availableUnits(
      OrderActor actor, UUID orderId, int page, int size, String search) {
    return availableUnits(actor, orderId, null, page, size, search);
  }

  OrderUnitPageResponse availableUnits(
      OrderActor actor,
      UUID orderId,
      UUID inventorySourceWarehouseId,
      int page,
      int size,
      String search) {
    requirePage(page, size);
    RentalOrder order = order(orderId);
    access.requireVisible(actor, order);
    UUID warehouseId =
        inventorySources.requireReadableSource(actor, order, inventorySourceWarehouseId);
    if (warehouseId == null) {
      throw RentalOrderProblems.conflict(
          "ORDER_WAREHOUSE_REQUIRED", "Сначала выберите склад заказа");
    }
    access.requireWarehouseRead(actor, warehouseId);
    try {
      LogisticsDependencyGateway.OrderUnitCandidatePage result =
          dependencies.readOrderUnitCandidates(
              orderId, warehouseId, page, size, search == null ? "" : search.trim());
      Map<UUID, List<OrderDesiredEquipmentResponse>> desiredByUnit = desiredContentsByUnit(order);
      Map<UUID, OrderRentalTermResponse> rentalTermsByUnit = rentalTermsByUnit(order);
      List<OrderUnitResponse> content =
          result.content().stream()
              .map(
                  candidate -> {
                    if (candidate == null
                        || candidate.unit() == null
                        || !warehouseId.equals(candidate.unit().warehouseId())
                        || (candidate.added() && candidate.reservationId() == null)
                        || (!candidate.added() && candidate.reservationId() != null)) {
                      throw RentalOrderProblems.invalidDependencyResponse();
                    }
                    return new OrderUnitResponse(
                        candidate.reservationId(),
                        candidate.added(),
                        candidate.added() ? "ACTIVE" : null,
                        rentalItem(candidate.unit()),
                        desiredByUnit.getOrDefault(candidate.unit().id(), List.of()),
                        rentalTermsByUnit.get(candidate.unit().id()));
                  })
              .toList();
      return new OrderUnitPageResponse(
          content, result.page(), result.size(), result.totalElements(), result.totalPages());
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
  }

  List<OrderHistoryEventResponse> history(OrderActor actor, UUID orderId) {
    RentalOrder order = order(orderId);
    access.requireVisible(actor, order);
    return auditEvents.findAllByOrderIdOrderByOccurredAtAscIdAsc(orderId).stream()
        .map(mapper::toHistoryResponse)
        .toList();
  }

  OrderDetailResponse visibleDetail(OrderActor actor, RentalOrder order) {
    access.requireVisible(actor, order);
    return detail(order, actor, readUnitsForView(actor, order));
  }

  OrderDetailResponse detail(
      RentalOrder order,
      OrderActor actor,
      List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    OrderSummaryResponse summary = mapper.toSummaryResponse(order, units.size());
    Map<UUID, List<OrderDesiredEquipmentResponse>> desiredByUnit = desiredContentsByUnit(order);
    Map<UUID, OrderRentalTermResponse> rentalTermsByUnit = rentalTermsByUnit(order);
    return new OrderDetailResponse(
        summary.id(),
        summary.version(),
        summary.number(),
        summary.status(),
        summary.client(),
        summary.managerId(),
        summary.managerDisplayName(),
        summary.createdBy(),
        summary.createdByDisplayName(),
        summary.warehouseId(),
        summary.deliveryAddress(),
        summary.latitude(),
        summary.longitude(),
        summary.contactPhone(),
        summary.comment(),
        summary.additionalContacts(),
        summary.desiredDeliveryWindows(),
        summary.unitCount(),
        summary.createdAt(),
        summary.updatedAt(),
        units.stream().map(unit -> unit(unit, desiredByUnit, rentalTermsByUnit)).toList(),
        movements(order.getId()),
        new OrderPermissions(
            editability.canEdit(actor, order),
            editability.canReplaceUnits(actor, order, units),
            access.canExtendRentalTerms(actor, order),
            actor.canViewOtherManagers()));
  }

  private static void mergeQuantity(Map<UUID, Long> target, UUID equipmentId, long quantity) {
    try {
      target.merge(equipmentId, quantity, Math::addExact);
    } catch (ArithmeticException exception) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
  }

  private List<OrderMovementResponse> movements(UUID orderId) {
    List<LogisticsDocument> documents = new ArrayList<>();
    documents.addAll(
        logisticsDocuments.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.SHIPMENT, orderId));
    documents.addAll(
        logisticsDocuments.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.RETURN, orderId));
    documents.sort(
        Comparator.comparing(LogisticsDocument::getCreatedAt)
            .thenComparing(LogisticsDocument::getId));
    return documents.stream().map(this::movement).toList();
  }

  private OrderMovementResponse movement(LogisticsDocument document) {
    List<OrderMovementCabinResponse> cabins =
        logisticsDocumentLines.findAllByDocument_IdOrderByLineNumber(document.getId()).stream()
            .map(RentalOrderReadService::movementCabin)
            .toList();
    return new OrderMovementResponse(
        document.getId(),
        document.getDocumentType().name(),
        document.getState().name(),
        document.getScheduledDate(),
        actualAt(document),
        document.getRentalShipmentId(),
        document.getCreatedAt(),
        document.getUpdatedAt(),
        cabins);
  }

  private static OrderMovementCabinResponse movementCabin(LogisticsDocumentLine line) {
    return new OrderMovementCabinResponse(
        line.getAssetId(), line.getInventorySourceWarehouseId(), line.getState().name());
  }

  /**
   * Returns the document timestamp at which its shipped, accepted, or estimate-requested terminal
   * intake branch completed. This is logistics completion evidence, not a separately captured
   * physical-arrival timestamp.
   */
  private static OffsetDateTime actualAt(LogisticsDocument document) {
    if (document.getDocumentType() == LogisticsDocumentType.SHIPMENT
        && document.getState() == LogisticsDocumentState.SHIPPED) {
      return document.getUpdatedAt();
    }
    if (document.getDocumentType() == LogisticsDocumentType.RETURN
        && (document.getState() == LogisticsDocumentState.ACCEPTED
            || document.getState() == LogisticsDocumentState.ESTIMATE_REQUESTED)) {
      return document.getUpdatedAt();
    }
    return null;
  }

  /**
   * Reads the authoritative remote order-unit snapshot and rejects a malformed or cross-order
   * reservation before callers use it in a local command or response.
   */
  List<LogisticsDependencyGateway.OrderUnitReservation> readUnits(RentalOrder order) {
    return readUnits(order, false);
  }

  /** Reads shipment candidates while preserving their owner-authoritative physical warehouses. */
  List<LogisticsDependencyGateway.OrderUnitReservation> readUnitsForShipment(RentalOrder order) {
    return readUnits(order, true);
  }

  /** Reads independently sourced cabins only when the actor can also see every physical source. */
  private List<LogisticsDependencyGateway.OrderUnitReservation> readUnitsForView(
      OrderActor actor, RentalOrder order) {
    List<LogisticsDependencyGateway.OrderUnitReservation> units = readUnitsForShipment(order);
    if (!actor.globalAdministrator()
        && units.stream()
            .anyMatch(unit -> !actor.readableWarehouses().contains(unit.warehouseId()))) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Insufficient warehouse access");
    }
    return units;
  }

  private List<LogisticsDependencyGateway.OrderUnitReservation> readUnits(
      RentalOrder order, boolean allowIndependentInventorySource) {
    try {
      List<LogisticsDependencyGateway.OrderUnitReservation> result =
          dependencies.readOrderUnits(order.getId());
      if (result == null) throw RentalOrderProblems.invalidDependencyResponse();
      for (LogisticsDependencyGateway.OrderUnitReservation reservation : result) {
        if (reservation == null
            || reservation.reservationId() == null
            || reservation.unitId() == null
            || reservation.addedBySubjectId() == null
            || reservation.addedByRole() == null
            || !hasOrderActorRole(reservation.addedByRole())
            || !order.getId().equals(reservation.orderId())
            || !"ACTIVE".equals(reservation.state())
            || reservation.unit() == null
            || !reservation.unitId().equals(reservation.unit().id())
            || reservation.warehouseId() == null
            || !reservation.warehouseId().equals(reservation.unit().warehouseId())
            || (!allowIndependentInventorySource
                && (order.getWarehouseId() == null
                    || !order.getWarehouseId().equals(reservation.warehouseId())))) {
          throw RentalOrderProblems.invalidDependencyResponse();
        }
      }
      return List.copyOf(result);
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
  }

  private RentalOrder order(UUID orderId) {
    return orders.findWithClientById(orderId).orElseThrow(RentalOrderProblems::notFound);
  }

  static boolean hasOrderActorRole(String role) {
    return ORDER_ACTOR_ROLES.contains(role);
  }

  private OrderUnitResponse unit(
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      Map<UUID, List<OrderDesiredEquipmentResponse>> desiredByUnit,
      Map<UUID, OrderRentalTermResponse> rentalTermsByUnit) {
    return new OrderUnitResponse(
        reservation.reservationId(),
        true,
        reservation.state(),
        rentalItem(reservation.unit()),
        desiredByUnit.getOrDefault(reservation.unitId(), List.of()),
        rentalTermsByUnit.get(reservation.unitId()));
  }

  private Map<UUID, OrderRentalTermResponse> rentalTermsByUnit(RentalOrder order) {
    return rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(order.getId()).stream()
        .collect(
            Collectors.toUnmodifiableMap(
                RentalOrderUnitTerm::getRentalItemId,
                mapper::toRentalTermResponse,
                (left, right) -> left));
  }

  private Map<UUID, List<OrderDesiredEquipmentResponse>> desiredContentsByUnit(RentalOrder order) {
    Map<UUID, List<OrderDesiredEquipmentResponse>> values = new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement requirement :
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            order.getId())) {
      if (requirement.getQuantity() < 1) {
        continue;
      }
      values
          .computeIfAbsent(requirement.getRentalItemId(), ignored -> new ArrayList<>())
          .add(mapper.toDesiredEquipmentResponse(requirement));
    }
    values.replaceAll((ignored, requirements) -> List.copyOf(requirements));
    return Map.copyOf(values);
  }

  private static OrderRentalItemResponse rentalItem(
      LogisticsDependencyGateway.OrderRentalItem unit) {
    if (unit == null || unit.contents() == null || unit.tags() == null) {
      throw RentalOrderProblems.invalidDependencyResponse();
    }
    return new OrderRentalItemResponse(
        unit.id(),
        unit.version(),
        unit.warehouseId(),
        unit.number(),
        unit.status(),
        unit.rentalType(),
        unit.dimensions(),
        unit.finishing(),
        unit.category(),
        unit.characteristics(),
        unit.linoleum(),
        unit.tags(),
        unit.contents().stream()
            .map(
                content ->
                    new OrderEquipmentContentResponse(
                        content.equipmentId(),
                        content.equipmentName(),
                        content.quantity(),
                        content.locationKind()))
            .toList(),
        unit.createdAt(),
        unit.updatedAt());
  }

  private static Predicate visibility(
      jakarta.persistence.criteria.Root<RentalOrder> root,
      jakarta.persistence.criteria.CriteriaBuilder builder,
      OrderActor actor) {
    if (actor.globalAdministrator()) return builder.conjunction();
    Predicate assignedToAccessibleWarehouse =
        actor.readableWarehouses().isEmpty()
            ? builder.disjunction()
            : root.get("warehouseId").in(actor.readableWarehouses());
    Predicate ownUnassigned =
        builder.and(
            builder.equal(root.get("managerId"), actor.subjectId()),
            builder.isNull(root.get("warehouseId")));
    if (actor.localAdministrator()) {
      return builder.or(assignedToAccessibleWarehouse, ownUnassigned);
    }
    return builder.equal(root.get("managerId"), actor.subjectId());
  }

  private static Sort orderSort(String requestedSort, String requestedDirection) {
    String sort = requestedSort == null || requestedSort.isBlank() ? "updatedAt" : requestedSort;
    String property =
        switch (sort) {
          case "number" -> "orderNumber";
          case "client" -> "client.normalizedName";
          case "manager" -> "managerDisplayName";
          case "warehouse" -> "warehouseId";
          case "status", "createdAt", "updatedAt" -> sort;
          default -> throw new IllegalArgumentException("Unsupported order sort field");
        };
    Sort.Direction direction =
        requestedDirection == null || requestedDirection.isBlank()
            ? Sort.Direction.DESC
            : Sort.Direction.fromString(requestedDirection);
    return Sort.by(new Sort.Order(direction, property), new Sort.Order(direction, "id"));
  }

  private static void requirePage(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid order page request");
    }
  }

  private static String normalizeSearch(String search) {
    return search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
