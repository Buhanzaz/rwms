package dev.buhanzaz.rwms.logistics.inquiry.service;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.mapper.RentalInquiryResponseMapper;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderClientService;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
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
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalInquiryService {
  private final RentalInquiryRepository inquiries;
  private final OrderClientService clients;
  private final RentalInquiryResponseMapper mapper;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;
  private final RentalSettingsService settings;

  @Transactional
  public RentalInquiryResponse create(
      OrderActor actor, UUID idempotencyKey, CreateRentalInquiryRequest request) {
    if (actor == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Inquiry actor, request and Idempotency-Key are required");
    }
    if (!request.conversationId().equals(idempotencyKey)) {
      throw new IllegalArgumentException(
          "Idempotency-Key must equal the assistant conversation ID");
    }
    inquiries.acquireTransactionLock("rental-inquiry:conversation:" + request.conversationId());
    RentalInquiry existing =
        inquiries.findByConversationId(request.conversationId()).orElse(null);
    if (existing != null) {
      requireOwner(actor, existing);
      requireSameClient(existing.getClient(), request);
      return mapper.toResponse(existing);
    }

    OrderClient client;
    if (request.clientId() != null) {
      client = clients.required(request.clientId());
    } else {
      client = clients.createForOrder(actor, idempotencyKey, request.newClient()).client();
    }
    RentalInquiry inquiry =
        inquiries.saveAndFlush(
            RentalInquiry.create(
                request.conversationId(),
                client,
                actor.subjectId(),
                actor.displayName(),
                actor.role(),
                now()));
    return mapper.toResponse(inquiry);
  }

  public RentalInquiryResponse get(OrderActor actor, UUID inquiryId) {
    return mapper.toResponse(requiredOwned(actor, inquiryId));
  }

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
                .collect(
                    java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
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
                facets.categories()));
      } catch (LogisticsDependencyException exception) {
        throw dependencyProblem(exception);
      }
    }
    return new CabinFacetsResponse(List.copyOf(response));
  }

  @Transactional
  public CabinSearchResponse search(
      OrderActor actor, UUID inquiryId, CabinSearchRequest request) {
    RentalInquiry inquiry =
        inquiries
            .findForUpdate(inquiryId)
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    requireOwner(actor, inquiry);
    requireActive(inquiry);
    access.requireWarehouseRead(actor, request.warehouseId());
    try {
      LogisticsDependencyGateway.WarehouseIdentity warehouse =
          dependencies.readWarehouseIdentity(request.warehouseId());
      if (!warehouse.active()) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT, "WAREHOUSE_UNAVAILABLE", "Склад недоступен");
      }
      inquiry.selectWarehouse(request.warehouseId(), now());
      inquiries.saveAndFlush(inquiry);
      OffsetDateTime expiresAt =
          now().plusMinutes(settings.chatSelectionHoldMinutes(actor));
      LogisticsDependencyGateway.CabinSearchResult result =
          dependencies.searchAvailableCabins(
              request.warehouseId(),
              inquiry.getId(),
              expiresAt,
              actor.subjectId(),
              actor.role(),
              request.groups().stream()
                  .map(
                      group ->
                          new LogisticsDependencyGateway.CabinSearchGroup(
                              group.cabinType(),
                              group.finish(),
                              group.dimensions(),
                              group.category(),
                              group.characteristics(),
                              group.linoleum(),
                              group.quantity()))
                  .toList());
      return new CabinSearchResponse(
          result.warehouseId(),
          result.expiresAt(),
          result.groups().stream()
              .map(
                  group ->
                      new CabinSearchGroupResult(
                          new CabinSearchGroup(
                              group.group().cabinType(),
                              group.group().finish(),
                              group.group().dimensions(),
                              group.group().category(),
                              group.group().characteristics(),
                              group.group().linoleum(),
                              group.group().quantity()),
                          group.cabins().stream().map(RentalInquiryService::cabin).toList()))
              .toList());
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    } catch (IllegalStateException exception) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "INQUIRY_WAREHOUSE_LOCKED",
          "Для одного диалога можно использовать только один склад");
    }
  }

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

  private static void requireSameClient(
      OrderClient current, CreateRentalInquiryRequest request) {
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

  private static AvailableCabinResponse cabin(
      LogisticsDependencyGateway.AvailableCabin source) {
    return new AvailableCabinResponse(
        source.id(),
        source.version(),
        source.warehouseId(),
        source.status(),
        source.number(),
        source.rentalType(),
        source.dimensions(),
        source.finishing(),
        source.category(),
        source.characteristics(),
        source.linoleum(),
        source.passport(),
        source.tags(),
        source.updatedAt());
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

  private static OrderProblemException dependencyProblem(
      LogisticsDependencyException exception) {
    if (exception.kind()
        == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return new OrderProblemException(
          HttpStatus.CONFLICT,
          exception.dependencyCode() == null
              ? "CABIN_SEARCH_REJECTED"
              : exception.dependencyCode(),
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
