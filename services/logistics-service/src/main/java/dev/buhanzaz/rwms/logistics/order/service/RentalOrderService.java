package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AddOrderUnitRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDesiredEquipmentInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDesiredEquipmentResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderEquipmentContentResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderHistoryEventResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPermissions;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderRentalItemResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderSummaryResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SelectWarehouseRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderUnitDesiredEquipmentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.UpdateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
import dev.buhanzaz.rwms.logistics.order.repository.OrderAuditEventRepository;
import dev.buhanzaz.rwms.logistics.order.repository.OrderCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalOrderService {
  private static final Set<String> ORDER_ACTOR_ROLES =
      Set.of(
          "SYSTEM_ADMIN",
          "WMS_ADMIN",
          "WAREHOUSE_MANAGER",
          "RENTAL_MANAGER",
          "VIEWER");
  private static final String CREATE_ORDER = "CREATE_ORDER";
  private static final String UPDATE_ORDER = "UPDATE_ORDER";
  private static final String SELECT_WAREHOUSE = "SELECT_WAREHOUSE";
  private static final String SAVE_ORDER = "SAVE_ORDER";
  private static final String CANCEL_ORDER = "CANCEL_ORDER";
  private static final String ADD_UNIT = "ADD_UNIT";
  private static final String REMOVE_UNIT = "REMOVE_UNIT";
  private static final String SET_DESIRED_EQUIPMENT = "SET_DESIRED_EQUIPMENT";

  private final RentalOrderRepository orders;
  private final OrderCommandReceiptRepository receipts;
  private final OrderAuditEventRepository auditEvents;
  private final RentalOrderEquipmentRequirementRepository equipmentRequirements;
  private final OrderClientService clientService;
  private final OrderAuditService audit;
  private final OrderAuthorizer access;
  private final RentalOrderResponseMapper mapper;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsDocumentService documents;

  public OrderPageResponse list(
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
          if (statuses != null && !statuses.isEmpty()) {
            predicates.add(root.get("status").in(Set.copyOf(statuses)));
          }
          if (clientTypes != null && !clientTypes.isEmpty()) {
            predicates.add(
                root.join("client").get("clientType").in(Set.copyOf(clientTypes)));
          }
          if (warehouseIds != null && !warehouseIds.isEmpty()) {
            predicates.add(root.get("warehouseId").in(Set.copyOf(warehouseIds)));
          }
          if (createdFrom != null) {
            predicates.add(
                builder.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
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
    Page<RentalOrder> result =
        orders.findAll(specification, PageRequest.of(page, size, pageSort));
    List<OrderSummaryResponse> content =
        result.getContent().stream()
            .map(order -> mapper.toSummaryResponse(order, readUnits(order).size()))
            .toList();
    return new OrderPageResponse(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  public OrderDetailResponse get(OrderActor actor, UUID orderId) {
    RentalOrder order = order(orderId);
    access.requireVisible(actor, order);
    return detail(order, actor, readUnits(order));
  }

  public OrderUnitPageResponse availableUnits(
      OrderActor actor, UUID orderId, int page, int size, String search) {
    requirePage(page, size);
    RentalOrder order = order(orderId);
    access.requireVisible(actor, order);
    UUID warehouseId = order.getWarehouseId();
    if (warehouseId == null) {
      throw conflict("ORDER_WAREHOUSE_REQUIRED", "Сначала выберите склад заказа");
    }
    access.requireWarehouseRead(actor, warehouseId);
    try {
      LogisticsDependencyGateway.OrderUnitCandidatePage result =
          dependencies.readOrderUnitCandidates(
              orderId, warehouseId, page, size, search == null ? "" : search.trim());
      Map<UUID, List<OrderDesiredEquipmentResponse>> desiredByUnit =
          desiredContentsByUnit(order);
      List<OrderUnitResponse> content =
          result.content().stream()
              .map(
                  candidate -> {
                    if (candidate == null
                        || candidate.unit() == null
                        || !warehouseId.equals(candidate.unit().warehouseId())
                        || (candidate.added() && candidate.reservationId() == null)
                        || (!candidate.added() && candidate.reservationId() != null)) {
                      throw invalidDependencyResponse();
                    }
                    return new OrderUnitResponse(
                        candidate.reservationId(),
                        candidate.added(),
                        rentalItem(candidate.unit()),
                        desiredByUnit.getOrDefault(candidate.unit().id(), List.of()));
                  })
              .toList();
      return new OrderUnitPageResponse(
          content,
          result.page(),
          result.size(),
          result.totalElements(),
          result.totalPages());
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  public List<OrderHistoryEventResponse> history(OrderActor actor, UUID orderId) {
    RentalOrder order = order(orderId);
    access.requireVisible(actor, order);
    return auditEvents.findAllByOrderIdOrderByOccurredAtAscIdAsc(orderId).stream()
        .map(mapper::toHistoryResponse)
        .toList();
  }

  @Transactional
  public CreateResult create(
      OrderActor actor, UUID idempotencyKey, CreateOrderRequest request) {
    if (actor == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Order actor, request and Idempotency-Key are required");
    }
    UUID managerId = actor.subjectId();
    String checksum = creationChecksum(request);
    UUID scopedKey = OrderCommandChecksum.scopedKey(idempotencyKey, CREATE_ORDER);
    orders.acquireTransactionLock(
        "rental-order:idempotency:" + actor.subjectId() + ":" + scopedKey);
    RentalOrder replay =
        orders
            .findByCreatedBySubjectIdAndCreationIdempotencyKey(actor.subjectId(), scopedKey)
            .orElse(null);
    if (replay != null) {
      if (!replay.matchesCreationRequest(checksum)) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key уже использован для другой команды");
      }
      access.requireVisible(actor, replay);
      return new CreateResult(detail(replay, actor, readUnits(replay)), true);
    }

    OrderClientService.CreatedClient createdClient = null;
    OrderClient client;
    if (request.clientId() != null) {
      client = clientService.required(request.clientId());
    } else {
      createdClient = clientService.createForOrder(actor, idempotencyKey, request.newClient());
      client = createdClient.client();
    }
    String managerDisplayName =
        managerId.equals(actor.subjectId()) ? actor.displayName() : managerId.toString();
    String number = "ORD-%06d".formatted(orders.nextOrderNumber());
    RentalOrder order =
        orders.saveAndFlush(
            RentalOrder.create(
                number,
                client,
                managerId,
                managerDisplayName,
                actor.subjectId(),
                actor.displayName(),
                actor.role(),
                scopedKey,
                checksum));
    if (createdClient != null && !createdClient.replayed()) {
      audit.append(
          order.getId(),
          OrderAuditEventType.CLIENT_CREATED,
          actor,
          "CLIENT",
          client.getId().toString(),
          null,
          Map.of(
              "clientType", client.getClientType().name(),
              "displayName", client.getDisplayName()));
    }
    audit.append(
        order.getId(),
        OrderAuditEventType.CLIENT_SELECTED,
        actor,
        "CLIENT",
        client.getId().toString(),
        null,
        Map.of("displayName", client.getDisplayName()));
    audit.append(
        order.getId(),
        OrderAuditEventType.ORDER_CREATED,
        actor,
        "ORDER",
        order.getId().toString(),
        null,
        Map.of(
            "number", number,
            "status", order.getStatus().name()));
    return new CreateResult(detail(order, actor, List.of()), false);
  }

  @Transactional
  public MutationResult update(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      UpdateOrderRequest request) {
    String checksum =
        OrderCommandChecksum.sha256(
            UPDATE_ORDER,
            List.of(
                orderId.toString(),
                request.clientId().toString(),
                Long.toString(request.expectedVersion())));
    OrderCommandReceipt replay = replay(actor, UPDATE_ORDER, idempotencyKey, checksum);
    if (replay != null) {
      RentalOrder replayedOrder = replay.getOrder();
      access.requireVisible(actor, replayedOrder);
      return new MutationResult(
          detail(replayedOrder, actor, readUnits(replayedOrder)), true);
    }

    RentalOrder order = lockedOrder(orderId);
    access.requireMutable(actor, order);
    requireVersion(order, request.expectedVersion());
    order.requireDraft();
    OrderClient nextClient = clientService.required(request.clientId());
    OrderClient previousClient = order.getClient();
    if (!order.changeClient(nextClient)) {
      remember(actor, UPDATE_ORDER, idempotencyKey, checksum, order);
      return new MutationResult(detail(order, actor, readUnits(order)), false);
    }

    orders.saveAndFlush(order);
    audit.append(
        orderId,
        OrderAuditEventType.CLIENT_SELECTED,
        actor,
        "CLIENT",
        nextClient.getId().toString(),
        Map.of("displayName", previousClient.getDisplayName()),
        Map.of("displayName", nextClient.getDisplayName()));
    changed(order, actor, "clientId");
    remember(actor, UPDATE_ORDER, idempotencyKey, checksum, order);
    return new MutationResult(detail(order, actor, readUnits(order)), false);
  }

  @Transactional
  public MutationResult selectWarehouse(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      SelectWarehouseRequest request) {
    String checksum =
        OrderCommandChecksum.sha256(
            SELECT_WAREHOUSE,
            List.of(
                orderId.toString(),
                request.warehouseId().toString(),
                Long.toString(request.expectedVersion())));
    OrderCommandReceipt replay =
        replay(actor, SELECT_WAREHOUSE, idempotencyKey, checksum);
    if (replay != null) {
      RentalOrder replayedOrder = replay.getOrder();
      access.requireVisible(actor, replayedOrder);
      return new MutationResult(
          detail(replayedOrder, actor, readUnits(replayedOrder)), true);
    }
    RentalOrder order = lockedOrder(orderId);
    access.requireMutable(actor, order);
    requireVersion(order, request.expectedVersion());
    order.requireDraft();
    access.requireWarehouseEdit(actor, request.warehouseId());
    if (Objects.equals(order.getWarehouseId(), request.warehouseId())) {
      remember(actor, SELECT_WAREHOUSE, idempotencyKey, checksum, order);
      return new MutationResult(detail(order, actor, readUnits(order)), false);
    }
    List<LogisticsDependencyGateway.OrderUnitReservation> units = readUnits(order);
    if (!units.isEmpty()) {
      throw conflict(
          "ORDER_WAREHOUSE_LOCKED",
          "Склад нельзя изменить после добавления первой бытовки");
    }
    LogisticsDependencyGateway.WarehouseIdentity warehouse;
    try {
      warehouse = dependencies.readWarehouseIdentity(request.warehouseId());
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
    if (!request.warehouseId().equals(warehouse.id()) || !warehouse.active()) {
      throw conflict("WAREHOUSE_UNAVAILABLE", "Выбранный склад недоступен");
    }
    UUID previous = order.getWarehouseId();
    order.selectWarehouse(request.warehouseId());
    orders.saveAndFlush(order);
    audit.append(
        orderId,
        OrderAuditEventType.WAREHOUSE_SELECTED,
        actor,
        "WAREHOUSE",
        request.warehouseId().toString(),
        previous == null ? null : Map.of("warehouseId", previous.toString()),
        Map.of("warehouseId", request.warehouseId().toString()));
    changed(order, actor, "warehouseId");
    remember(actor, SELECT_WAREHOUSE, idempotencyKey, checksum, order);
    return new MutationResult(detail(order, actor, units), false);
  }

  @Transactional
  public MutationResult addUnit(
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
    OrderCommandReceipt replay = replay(actor, ADD_UNIT, idempotencyKey, checksum);
    if (replay != null) {
      RentalOrder replayedOrder = replay.getOrder();
      access.requireVisible(actor, replayedOrder);
      return new MutationResult(
          detail(replayedOrder, actor, readUnits(replayedOrder)), true);
    }

    RentalOrder order = lockedOrder(orderId);
    access.requireMutable(actor, order);
    requireVersion(order, request.expectedVersion());
    order.requireDraft();
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits =
        readUnits(order);
    LogisticsDependencyGateway.OrderUnitReservation current =
        findCurrentUnit(orderId, request.unitId(), currentUnits);
    if (current != null) {
      boolean recorded = !ensureUnitAddedEvidence(order, current, actor);
      remember(actor, ADD_UNIT, idempotencyKey, checksum, order);
      return new MutationResult(detail(order, actor, currentUnits), recorded);
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
              actor.subjectId(),
              actor.role());
      requireReservation(reservation, orderId, request.unitId(), warehouseId, "ACTIVE");
      if (!actor.subjectId().equals(reservation.addedBySubjectId())
          || !actor.role().equals(reservation.addedByRole())) {
        throw invalidDependencyResponse();
      }
      ensureUnitAddedEvidence(order, reservation, actor);
      remember(actor, ADD_UNIT, idempotencyKey, checksum, order);
      return new MutationResult(detail(order, actor, readUnits(order)), false);
    } catch (LogisticsDependencyException exception) {
      String code = exception.dependencyCode();
      if ("UNIT_ALREADY_RESERVED".equals(code)) {
        throw new OrderUnitConflictException(
            orderId,
            request.unitId(),
            code,
            "Бытовка уже занята другим активным заказом");
      }
      OrderProblemException problem = dependencyProblem(exception);
      if (problem.status() == HttpStatus.CONFLICT) {
        throw new OrderUnitConflictException(
            orderId, request.unitId(), problem.code(), problem.getMessage());
      }
      throw problem;
    }
  }

  @Transactional
  public MutationResult removeUnit(
      OrderActor actor,
      UUID orderId,
      UUID unitId,
      long expectedVersion,
      UUID idempotencyKey) {
    String checksum =
        OrderCommandChecksum.sha256(
            REMOVE_UNIT,
            List.of(orderId.toString(), unitId.toString(), Long.toString(expectedVersion)));
    OrderCommandReceipt replay = replay(actor, REMOVE_UNIT, idempotencyKey, checksum);
    if (replay != null) {
      RentalOrder replayedOrder = replay.getOrder();
      access.requireVisible(actor, replayedOrder);
      return new MutationResult(
          detail(replayedOrder, actor, readUnits(replayedOrder)), true);
    }
    RentalOrder order = lockedOrder(orderId);
    access.requireMutable(actor, order);
    requireVersion(order, expectedVersion);
    order.requireDraft();
    UUID warehouseId = requiredWarehouse(order);
    LogisticsDependencyGateway.OrderUnitReservation current =
        findCurrentUnit(orderId, unitId, readUnits(order));
    List<RentalOrderEquipmentRequirement> existingRequirements =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentCodeAsc(orderId);
    Map<UUID, Long> remainingRequirements =
        aggregateRequirements(existingRequirements, unitId, Map.of());
    try {
      LogisticsDependencyGateway.OrderUnitReservation released =
          dependencies.releaseOrderUnit(
              idempotencyKey, orderId, unitId, actor.subjectId(), actor.role());
      requireReservation(released, orderId, unitId, warehouseId, "RELEASED");
      if (current == null && !released.replayed()) throw invalidDependencyResponse();
      if (current != null
          && !current.reservationId().equals(released.reservationId())) {
        throw invalidDependencyResponse();
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
      ensureUnitAddedEvidence(order, released, actor);
      boolean recorded =
          hasReservationEvidence(
              orderId,
              OrderAuditEventType.RESERVATION_RELEASED,
              released.reservationId());
      if (!recorded || furnitureChanged) {
        order.touch();
        orders.saveAndFlush(order);
        if (!recorded) {
          appendUnitReleasedEvidence(orderId, released, actor);
        }
        changed(order, actor, furnitureChanged ? "unitsAndDesiredEquipment" : "units");
      }
      remember(actor, REMOVE_UNIT, idempotencyKey, checksum, order);
      return new MutationResult(detail(order, actor, readUnits(order)), recorded);
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  @Transactional
  public MutationResult setDesiredEquipment(
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
    OrderCommandReceipt replay = replay(actor, SET_DESIRED_EQUIPMENT, idempotencyKey, checksum);
    if (replay != null) {
      RentalOrder replayedOrder = replay.getOrder();
      access.requireVisible(actor, replayedOrder);
      return new MutationResult(detail(replayedOrder, actor, readUnits(replayedOrder)), true);
    }

    RentalOrder order = lockedOrder(orderId);
    access.requireMutable(actor, order);
    requireVersion(order, request.expectedVersion());
    order.requireDraft();
    UUID warehouseId = requiredWarehouse(order);
    LogisticsDependencyGateway.OrderUnitReservation unit =
        requireCurrentUnit(orderId, unitId, readUnits(order));
    List<RentalOrderEquipmentRequirement> existing =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentCodeAsc(orderId);
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
      throw dependencyProblem(exception);
    }
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> reservationByEquipment =
        requireEquipmentReservations(reservations, aggregate);
    boolean changed =
        applyDesiredRequirements(
            order, unitId, unit.unit().number(), existing, desired, reservationByEquipment, actor);
    if (changed) {
      order.touch();
      orders.saveAndFlush(order);
      changed(order, actor, "desiredEquipment");
    }
    remember(actor, SET_DESIRED_EQUIPMENT, idempotencyKey, checksum, order);
    return new MutationResult(detail(order, actor, readUnits(order)), false);
  }

  @Transactional
  public MutationResult cancel(
      OrderActor actor,
      UUID orderId,
      long expectedVersion,
      UUID idempotencyKey) {
    String checksum =
        OrderCommandChecksum.sha256(
            CANCEL_ORDER, List.of(orderId.toString(), Long.toString(expectedVersion)));
    OrderCommandReceipt replay = replay(actor, CANCEL_ORDER, idempotencyKey, checksum);
    if (replay != null) {
      RentalOrder replayedOrder = replay.getOrder();
      access.requireVisible(actor, replayedOrder);
      return new MutationResult(
          detail(replayedOrder, actor, readUnits(replayedOrder)), true);
    }
    RentalOrder order = lockedOrder(orderId);
    access.requireMutable(actor, order);
    requireVersion(order, expectedVersion);
    order.requireDraft();
    List<LogisticsDependencyGateway.OrderUnitReservation> active = readUnits(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> released;
    try {
      released =
          dependencies.releaseAllOrderUnits(
              idempotencyKey, orderId, actor.subjectId(), actor.role());
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
    if (released == null) throw invalidDependencyResponse();
    for (LogisticsDependencyGateway.OrderUnitReservation reservation : released) {
      if (reservation == null || reservation.unitId() == null) {
        throw invalidDependencyResponse();
      }
      requireReservation(
          reservation,
          orderId,
          reservation.unitId(),
          order.getWarehouseId(),
          "RELEASED");
    }
    Set<UUID> expectedUnitIds =
        active.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(Collectors.toUnmodifiableSet());
    Set<UUID> releasedUnitIds =
        released.stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(Collectors.toUnmodifiableSet());
    if (expectedUnitIds.size() != active.size()) throw invalidDependencyResponse();
    if (releasedUnitIds.size() != released.size()) throw invalidDependencyResponse();
    if (!active.isEmpty() && !expectedUnitIds.equals(releasedUnitIds)) {
      throw invalidDependencyResponse();
    }
    if (active.isEmpty()
        && !released.isEmpty()
        && released.stream().anyMatch(value -> !value.replayed())) {
      throw invalidDependencyResponse();
    }
    List<RentalOrderEquipmentRequirement> existingRequirements =
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentCodeAsc(orderId);
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
        throw dependencyProblem(exception);
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
      if (current != null
          && !current.reservationId().equals(reservation.reservationId())) {
        throw invalidDependencyResponse();
      }
      ensureUnitAddedEvidence(order, reservation, actor);
      if (!hasReservationEvidence(
          orderId,
          OrderAuditEventType.RESERVATION_RELEASED,
          reservation.reservationId())) {
        appendUnitReleasedEvidence(orderId, reservation, actor);
      }
    }
    order.cancel();
    orders.saveAndFlush(order);
    audit.append(
        orderId,
        OrderAuditEventType.ORDER_CANCELLED,
        actor,
        "ORDER",
        orderId.toString(),
        Map.of("status", RentalOrderStatus.DRAFT.name()),
        Map.of("status", RentalOrderStatus.CANCELLED.name()));
    changed(order, actor, "status");
    remember(actor, CANCEL_ORDER, idempotencyKey, checksum, order);
    return new MutationResult(detail(order, actor, List.of()), false);
  }

  @Transactional
  public MutationResult save(
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
    OrderCommandReceipt replay = replay(actor, SAVE_ORDER, idempotencyKey, checksum);
    if (replay != null) {
      RentalOrder replayedOrder = replay.getOrder();
      access.requireVisible(actor, replayedOrder);
      return new MutationResult(
          detail(replayedOrder, actor, readUnits(replayedOrder)), true);
    }

    RentalOrder order = lockedOrder(orderId);
    access.requireVisible(actor, order);
    boolean firstSave = order.getStatus() == RentalOrderStatus.DRAFT;
    if (firstSave) {
      access.requireMutable(actor, order);
    } else if (order.getStatus() == RentalOrderStatus.SAVED) {
      access.requireWarehouseEdit(actor, requiredWarehouse(order));
    } else {
      throw conflict("ORDER_NOT_EDITABLE", "Заказ больше нельзя сохранять");
    }
    requireVersion(order, expectedVersion);
    List<LogisticsDependencyGateway.OrderUnitReservation> units = readUnits(order);
    if (units.isEmpty()) {
      throw conflict("ORDER_UNITS_REQUIRED", "Добавьте в заказ хотя бы одну бытовку");
    }
    units = synchronizeOrderUnits(order, actor, units);
    if (firstSave) {
      order.saveForFulfillment();
      orders.saveAndFlush(order);
    }
    documents.createRentalOrderShipmentDraft(
        actor.subjectId(), correlationId, order, units);
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
    remember(actor, SAVE_ORDER, idempotencyKey, checksum, order);
    return new MutationResult(detail(order, actor, units), false);
  }

  private List<LogisticsDependencyGateway.OrderUnitReservation> synchronizeOrderUnits(
      RentalOrder order,
      OrderActor actor,
      List<LogisticsDependencyGateway.OrderUnitReservation> currentUnits) {
    UUID warehouseId = requiredWarehouse(order);
    List<LogisticsDependencyGateway.OrderUnitReservation> synchronizedUnits =
        new ArrayList<>(currentUnits.size());
    for (LogisticsDependencyGateway.OrderUnitReservation current : currentUnits) {
      UUID synchronizationKey = UUID.nameUUIDFromBytes(
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
                actor.subjectId(),
                actor.role());
        requireReservation(
            synchronizedUnit,
            order.getId(),
            current.unitId(),
            warehouseId,
            "ACTIVE");
        synchronizedUnits.add(synchronizedUnit);
      } catch (LogisticsDependencyException exception) {
        throw dependencyProblem(exception);
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
      if (!replacedUnitId.equals(requirement.getRentalItemId())
          && requirement.getQuantity() > 0) {
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
      throw invalidDependencyResponse();
    }
    Map<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> actual =
        new LinkedHashMap<>();
    for (LogisticsDependencyGateway.OrderEquipmentReservation reservation : reservations) {
      if (reservation == null
          || reservation.equipmentId() == null
          || reservation.equipmentCode() == null
          || reservation.equipmentCode().isBlank()
          || reservation.equipmentName() == null
          || reservation.equipmentName().isBlank()
          || reservation.quantity() < 1
          || reservation.availableQuantity() < 0
          || actual.putIfAbsent(reservation.equipmentId(), reservation) != null
          || !Objects.equals(expected.get(reservation.equipmentId()), reservation.quantity())) {
        throw invalidDependencyResponse();
      }
    }
    if (!actual.keySet().equals(expected.keySet())) {
      throw invalidDependencyResponse();
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
      String nextCode = reservation == null ? requirement.getEquipmentCode() : reservation.equipmentCode();
      String nextName = reservation == null ? requirement.getEquipmentName() : reservation.equipmentName();
      if (requirement.change(nextCode, nextName, nextQuantity)) {
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
        throw invalidDependencyResponse();
      }
      RentalOrderEquipmentRequirement created =
          RentalOrderEquipmentRequirement.create(
              order,
              unitId,
              entry.getKey(),
              reservation.equipmentCode(),
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

  private RentalOrder order(UUID orderId) {
    return orders
        .findWithClientById(orderId)
        .orElseThrow(
            () ->
                new OrderProblemException(
                    HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Заказ не найден"));
  }

  private RentalOrder lockedOrder(UUID orderId) {
    return orders
        .findForUpdate(orderId)
        .orElseThrow(
            () ->
                new OrderProblemException(
                    HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Заказ не найден"));
  }

  private OrderCommandReceipt replay(
      OrderActor actor, String operation, UUID key, String checksum) {
    if (actor == null || key == null) {
      throw new IllegalArgumentException("Order actor and Idempotency-Key are required");
    }
    receipts.acquireTransactionLock(
        "rental-order:command:" + actor.subjectId() + ":" + operation + ":" + key);
    OrderCommandReceipt receipt =
        receipts
            .findByActorSubjectIdAndOperationNameAndIdempotencyKey(
                actor.subjectId(), operation, key)
            .orElse(null);
    if (receipt != null && !receipt.matches(checksum)) {
      throw conflict(
          "IDEMPOTENCY_KEY_REUSED",
          "Idempotency-Key уже использован для другой команды");
    }
    return receipt;
  }

  private void remember(
      OrderActor actor,
      String operation,
      UUID key,
      String checksum,
      RentalOrder order) {
    receipts.save(
        OrderCommandReceipt.complete(
            order, actor.subjectId(), operation, key, checksum));
  }

  private boolean hasReservationEvidence(
      UUID orderId, OrderAuditEventType eventType, UUID reservationId) {
    return auditEvents.existsByOrderIdAndEventTypeAndSubjectTypeAndSubjectId(
        orderId,
        eventType,
        "UNIT_RESERVATION",
        reservationId.toString());
  }

  private boolean ensureUnitAddedEvidence(
      RentalOrder order,
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      OrderActor actor) {
    if (hasReservationEvidence(
        order.getId(),
        OrderAuditEventType.RESERVATION_CREATED,
        reservation.reservationId())) {
      return false;
    }
    order.touch();
    orders.saveAndFlush(order);
    appendUnitAddedEvidence(order.getId(), reservation);
    changed(order, actor, "units");
    return true;
  }

  private void appendUnitAddedEvidence(
      UUID orderId,
      LogisticsDependencyGateway.OrderUnitReservation reservation) {
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

  private OrderDetailResponse detail(
      RentalOrder order,
      OrderActor actor,
      List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    OrderSummaryResponse summary = mapper.toSummaryResponse(order, units.size());
    Map<UUID, List<OrderDesiredEquipmentResponse>> desiredByUnit =
        desiredContentsByUnit(order);
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
        summary.unitCount(),
        summary.createdAt(),
        summary.updatedAt(),
        units.stream().map(unit -> unit(unit, desiredByUnit)).toList(),
        new OrderPermissions(
            access.canEdit(actor, order), actor.canViewOtherManagers()));
  }

  private OrderUnitResponse unit(
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      Map<UUID, List<OrderDesiredEquipmentResponse>> desiredByUnit) {
    return new OrderUnitResponse(
        reservation.reservationId(),
        true,
        rentalItem(reservation.unit()),
        desiredByUnit.getOrDefault(reservation.unitId(), List.of()));
  }

  private Map<UUID, List<OrderDesiredEquipmentResponse>> desiredContentsByUnit(
      RentalOrder order) {
    Map<UUID, List<OrderDesiredEquipmentResponse>> values = new LinkedHashMap<>();
    for (RentalOrderEquipmentRequirement requirement :
        equipmentRequirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentCodeAsc(order.getId())) {
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

  private OrderRentalItemResponse rentalItem(
      LogisticsDependencyGateway.OrderRentalItem unit) {
    if (unit == null || unit.contents() == null || unit.tags() == null) {
      throw invalidDependencyResponse();
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
                        content.equipmentCode(),
                        content.equipmentName(),
                        content.quantity(),
                        content.locationKind()))
            .toList(),
        unit.createdAt(),
        unit.updatedAt());
  }

  private List<LogisticsDependencyGateway.OrderUnitReservation> readUnits(
      RentalOrder order) {
    try {
      List<LogisticsDependencyGateway.OrderUnitReservation> result =
          dependencies.readOrderUnits(order.getId());
      if (result == null) throw invalidDependencyResponse();
      for (LogisticsDependencyGateway.OrderUnitReservation reservation : result) {
        if (reservation == null
            || reservation.reservationId() == null
            || reservation.unitId() == null
            || reservation.addedBySubjectId() == null
            || reservation.addedByRole() == null
            || !ORDER_ACTOR_ROLES.contains(reservation.addedByRole())
            || !order.getId().equals(reservation.orderId())
            || !"ACTIVE".equals(reservation.state())
            || reservation.unit() == null
            || !reservation.unitId().equals(reservation.unit().id())
            || order.getWarehouseId() == null
            || !order.getWarehouseId().equals(reservation.warehouseId())
            || !order.getWarehouseId().equals(reservation.unit().warehouseId())) {
          throw invalidDependencyResponse();
        }
      }
      return List.copyOf(result);
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  private static LogisticsDependencyGateway.OrderUnitReservation requireCurrentUnit(
      UUID orderId,
      UUID unitId,
      List<LogisticsDependencyGateway.OrderUnitReservation> units) {
    LogisticsDependencyGateway.OrderUnitReservation current =
        findCurrentUnit(orderId, unitId, units);
    if (current == null) {
      throw new OrderProblemException(
          HttpStatus.NOT_FOUND,
          "ORDER_UNIT_NOT_FOUND",
          "Бытовка не добавлена в этот заказ");
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
        || !ORDER_ACTOR_ROLES.contains(reservation.addedByRole())
        || warehouseId == null
        || !orderId.equals(reservation.orderId())
        || !unitId.equals(reservation.unitId())
        || !warehouseId.equals(reservation.warehouseId())
        || !state.equals(reservation.state())
        || reservation.unit() == null
        || !unitId.equals(reservation.unit().id())
        || !warehouseId.equals(reservation.unit().warehouseId())) {
      throw invalidDependencyResponse();
    }
  }

  private static UUID requiredWarehouse(RentalOrder order) {
    if (order.getWarehouseId() == null) {
      throw conflict("ORDER_WAREHOUSE_REQUIRED", "Сначала выберите склад заказа");
    }
    return order.getWarehouseId();
  }

  private static void requireVersion(RentalOrder order, long expectedVersion) {
    if (expectedVersion < 0 || order.getVersion() != expectedVersion) {
      throw conflict(
          "ORDER_VERSION_CONFLICT",
          "Заказ был изменён параллельно; обновите данные и повторите действие");
    }
  }

  private static String creationChecksum(CreateOrderRequest request) {
    List<String> values = new ArrayList<>();
    if (request.clientId() != null) {
      values.add("EXISTING");
      values.add(request.clientId().toString());
    } else {
      values.add("NEW");
      values.add(request.newClient().clientType().name());
      values.add(OrderClientService.normalizeName(request.newClient().displayName()));
    }
    return OrderCommandChecksum.sha256(CREATE_ORDER, values);
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
    return Sort.by(
        new Sort.Order(direction, property),
        new Sort.Order(direction, "id"));
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

  private static OrderProblemException dependencyProblem(
      LogisticsDependencyException exception) {
    String code = exception.dependencyCode();
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return switch (code == null ? "" : code) {
        case "UNIT_WAREHOUSE_MISMATCH" ->
            conflict(code, "Бытовка находится на другом складе");
        case "UNIT_NOT_AVAILABLE" ->
            conflict(code, "Бытовка больше не доступна для заказа");
        case "UNIT_NOT_EDITABLE" ->
            conflict(code, "Наполнение этой бытовки нельзя изменить в заказе");
        case "EQUIPMENT_QUANTITY_CONFLICT" ->
            conflict(code, "Количество оборудования изменилось параллельно");
        case "INSUFFICIENT_STOCK" ->
            conflict(code, "Недостаточный остаток оборудования на складе");
        case "ASSET_NOT_FOUND" ->
            new OrderProblemException(
                HttpStatus.NOT_FOUND, "ORDER_UNIT_NOT_FOUND", "Бытовка не найдена");
        default ->
            new OrderProblemException(
                HttpStatus.CONFLICT,
                "ORDER_ASSET_COMMAND_REJECTED",
                "Складской сервис отклонил операцию заказа");
      };
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "ORDER_ASSET_SERVICE_UNAVAILABLE",
        "Складской сервис временно недоступен");
  }

  private static OrderProblemException invalidDependencyResponse() {
    return new OrderProblemException(
        HttpStatus.BAD_GATEWAY,
        "ORDER_ASSET_RESPONSE_INVALID",
        "Складской сервис вернул некорректный ответ");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  public record CreateResult(OrderDetailResponse response, boolean replayed) {}

  public record MutationResult(OrderDetailResponse response, boolean replayed) {}

}
