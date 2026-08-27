package dev.buhanzaz.rwms.logistics.inquiry.service;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.mapper.RentalInquiryResponseMapper;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderClientService;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns rental inquiry transitions and persists the local state from which presentations are built.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalInquiryService {
  private final RentalInquiryRepository inquiries;
  private final OrderClientService clients;
  private final RentalOrderService rentalOrders;
  private final RentalInquiryResponseMapper mapper;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;
  private final LogisticsTransactionLock transactionLock;

  @Transactional
  public RentalInquiryResponse create(
      OrderActor actor, UUID idempotencyKey, CreateRentalInquiryRequest request) {
    if (actor == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Inquiry actor, request and Idempotency-Key are required");
    }
    if (request.conversationId() != null && !request.conversationId().equals(idempotencyKey)) {
      throw new IllegalArgumentException(
          "Idempotency-Key must equal the assistant conversation ID");
    }
    transactionLock.acquire("rental-inquiry:create:" + actor.subjectId() + ":" + idempotencyKey);
    if (request.conversationId() != null) {
      transactionLock.acquire("rental-inquiry:conversation:" + request.conversationId());
    }
    RentalInquiry existing =
        inquiries
            .findByManagerIdAndCreationIdempotencyKey(actor.subjectId(), idempotencyKey)
            .orElse(null);
    if (existing == null && request.conversationId() != null) {
      existing = inquiries.findByConversationId(request.conversationId()).orElse(null);
    }
    if (existing != null) {
      requireOwner(actor, existing);
      if (!java.util.Objects.equals(existing.getConversationId(), request.conversationId())) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT,
            "INQUIRY_IDEMPOTENCY_CONFLICT",
            "Idempotency-Key уже использован для другого диалога аренды");
      }
      requireSameClient(existing.getClient(), request);
      requireSameOrder(existing, request.rentalOrderId());
      return mapper.toResponse(existing);
    }

    OrderClient client;
    if (request.clientId() != null) {
      client = clients.required(actor, request.clientId());
    } else {
      client = clients.createForOrder(actor, idempotencyKey, request.newClient()).client();
    }
    OrderDetailResponse targetOrder = targetOrder(actor, client, request.rentalOrderId());
    RentalInquiry inquiry =
        inquiries.saveAndFlush(
            RentalInquiry.create(
                request.conversationId(),
                idempotencyKey,
                client,
                actor.subjectId(),
                actor.displayName(),
                actor.role(),
                targetOrder == null ? null : targetOrder.id(),
                targetOrder == null ? null : targetOrder.warehouseId(),
                now()));
    return mapper.toResponse(inquiry);
  }

  /**
   * Creates or replays a customer-owned inquiry and atomically locks it to the selected warehouse.
   * CustomerApp never relies on the manager search flow to establish this ownership fence.
   */
  @Transactional
  public RentalInquiryResponse createCustomer(
      OrderActor actor, UUID idempotencyKey, UUID clientId, UUID warehouseId) {
    if (actor == null
        || !"CUSTOMER".equals(actor.role())
        || idempotencyKey == null
        || clientId == null
        || warehouseId == null) {
      throw new IllegalArgumentException(
          "Customer actor, client, warehouse and Idempotency-Key are required");
    }
    access.requireWarehouseEdit(actor, warehouseId);
    RentalInquiryResponse created =
        create(
            actor,
            idempotencyKey,
            new CreateRentalInquiryRequest(null, clientId, null, null));
    RentalInquiry inquiry = requiredOwned(actor, created.id());
    if (inquiry.getWarehouseId() != null && !warehouseId.equals(inquiry.getWarehouseId())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "INQUIRY_WAREHOUSE_LOCKED",
          "Idempotency-Key уже связан с другим складом");
    }
    inquiry.selectWarehouse(warehouseId, now());
    return mapper.toResponse(inquiries.saveAndFlush(inquiry));
  }

  public RentalInquiryResponse get(OrderActor actor, UUID inquiryId) {
    return mapper.toResponse(requiredOwned(actor, inquiryId));
  }

  /** Lists every manual and assistant inquiry linked to one order after order-level visibility. */
  public List<RentalInquiryResponse> listForOrder(OrderActor actor, UUID rentalOrderId) {
    if (actor == null || rentalOrderId == null) {
      throw new IllegalArgumentException("Order-linked inquiry filter is required");
    }
    rentalOrders.get(actor, rentalOrderId);
    return inquiries.findAllByRentalOrderIdOrderByCreatedAtDescIdDesc(rentalOrderId).stream()
        .map(mapper::toResponse)
        .toList();
  }

  /** Reads asset-owned facets only after the owner check finishes without a local transaction. */
  @Transactional(propagation = Propagation.NEVER)
  public CabinFacetsResponse facets(OrderActor actor, UUID inquiryId) {
    RentalInquiry inquiry = requiredOwned(actor, inquiryId);
    requireActive(inquiry);
    Set<UUID> warehouseIds;
    if (inquiry.getWarehouseId() != null) {
      warehouseIds = Set.of(inquiry.getWarehouseId());
    } else if (actor.globalAdministrator()) {
      try {
        warehouseIds =
            dependencies.listWarehouseIdentities().stream()
                .filter(LogisticsDependencyGateway.WarehouseIdentity::active)
                .map(LogisticsDependencyGateway.WarehouseIdentity::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      } catch (LogisticsDependencyException exception) {
        throw dependencyProblem(exception);
      }
    } else {
      warehouseIds = new LinkedHashSet<>(actor.readableWarehouses());
    }
    List<CabinFacetWarehouse> response = new ArrayList<>();
    for (UUID warehouseId : warehouseIds.stream().sorted().toList()) {
      access.requireWarehouseRead(actor, warehouseId);
      try {
        LogisticsDependencyGateway.WarehouseIdentity warehouse =
            dependencies.readWarehouseIdentity(warehouseId);
        if (!warehouse.active()) continue;
        LogisticsDependencyGateway.CabinFacets facets =
            dependencies.readAvailableCabinFacets(warehouseId, inquiry.getId());
        response.add(
            new CabinFacetWarehouse(
                warehouseId,
                warehouse.name(),
                warehouse.city(),
                facets.cabinTypes(),
                facets.finishes(),
                facets.dimensions(),
                facets.categories(),
                facets.characteristics(),
                facets.typeDimensions().stream()
                    .map(
                        relation ->
                            new CabinTypeDimensionRelation(
                                relation.cabinType(), relation.dimensions()))
                    .toList()));
      } catch (LogisticsDependencyException exception) {
        throw dependencyProblem(exception);
      }
    }
    return new CabinFacetsResponse(List.copyOf(response));
  }

  /** Reads asset-owned availability without holding a logistics database transaction open. */
  @Transactional(propagation = Propagation.NEVER)
  public CabinAvailabilityResponse availability(
      OrderActor actor, UUID inquiryId, CabinAvailabilityRequest request) {
    RentalInquiry inquiry = requiredOwned(actor, inquiryId);
    requireActive(inquiry);
    if (inquiry.getWarehouseId() != null
        && !inquiry.getWarehouseId().equals(request.warehouseId())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "INQUIRY_WAREHOUSE_LOCKED",
          "Для одного диалога можно использовать только один склад");
    }
    access.requireWarehouseRead(actor, request.warehouseId());
    try {
      LogisticsDependencyGateway.CabinAvailability result =
          dependencies.readCabinAvailability(
              request.warehouseId(), uniqueIds(request.rentalItemIds()));
      return new CabinAvailabilityResponse(
          result.warehouseId(),
          result.items().stream()
              .map(
                  value ->
                      new CabinAvailabilityItem(
                          value.rentalItemId(), value.available(), value.reason()))
              .toList());
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  public RentalInquiry requiredOwned(OrderActor actor, UUID inquiryId) {
    RentalInquiry inquiry =
        inquiries.findById(inquiryId).orElseThrow(() -> notFound("Диалог аренды не найден"));
    requireOwner(actor, inquiry);
    return inquiry;
  }

  public static void requireOwner(OrderActor actor, RentalInquiry inquiry) {
    if (!actor.subjectId().equals(inquiry.getManagerId())) {
      throw notFound("Диалог аренды не найден");
    }
  }

  private static void requireActive(RentalInquiry inquiry) {
    if (inquiry.getState() != RentalInquiryState.ACTIVE) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT, "INQUIRY_ARCHIVED", "Диалог уже завершён");
    }
  }

  private static void requireSameClient(OrderClient current, CreateRentalInquiryRequest request) {
    if (request.clientId() != null) {
      if (!current.getId().equals(request.clientId())) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT,
            "CONVERSATION_CLIENT_IMMUTABLE",
            "Клиента существующего диалога нельзя изменить");
      }
      return;
    }
    if (current.getClientType() != request.newClient().clientType()
        || current.getNormalizedPhone() == null
        || !current
            .getNormalizedPhone()
            .equals(OrderClientService.normalizePhone(request.newClient().phone()))) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CONVERSATION_CLIENT_IMMUTABLE",
          "Клиента существующего диалога нельзя изменить");
    }
  }

  private static void requireSameOrder(RentalInquiry inquiry, UUID requestedOrderId) {
    if (!java.util.Objects.equals(inquiry.getRentalOrderId(), requestedOrderId)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CONVERSATION_ORDER_IMMUTABLE",
          "Заказ существующего диалога нельзя изменить");
    }
  }

  private OrderDetailResponse targetOrder(
      OrderActor actor, OrderClient client, UUID rentalOrderId) {
    if (rentalOrderId == null) return null;
    OrderDetailResponse order = rentalOrders.get(actor, rentalOrderId);
    if ((order.status() != RentalOrderStatus.DRAFT && order.status() != RentalOrderStatus.SAVED)
        || !order.client().id().equals(client.getId())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "INQUIRY_ORDER_INVALID",
          "Диалог можно привязать только к действующему заказу этого клиента");
    }
    return order;
  }

  private static List<UUID> uniqueIds(List<UUID> values) {
    if (values == null || values.isEmpty() || values.size() > 100) {
      throw new IllegalArgumentException("rentalItemIds size is invalid");
    }
    LinkedHashSet<UUID> unique = new LinkedHashSet<>(values);
    if (unique.size() != values.size() || unique.contains(null)) {
      throw new IllegalArgumentException("rentalItemIds must be unique");
    }
    return List.copyOf(unique);
  }

  private static OrderProblemException dependencyProblem(LogisticsDependencyException exception) {
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return new OrderProblemException(
          HttpStatus.CONFLICT,
          exception.dependencyCode() == null ? "CABIN_SEARCH_REJECTED" : exception.dependencyCode(),
          "Не удалось выполнить запрос по свободным бытовкам");
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CABIN_SEARCH_UNAVAILABLE",
        "Сервис свободных бытовок временно недоступен");
  }

  private static OrderProblemException notFound(String message) {
    return new OrderProblemException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", message);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
