package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.CABIN_SEARCH;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.ORDER;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Private asset-service client for order units, cabin availability, and presentation holds.
 *
 * <p>Order commands and cabin searches use their own safe rejection classifiers. All other cabin
 * and presentation requests retain the general dependency error contract.
 */
final class LogisticsAssetOrderPresentationDependencyClient {
  private static final String ASSET_CLIENT = "logistics-asset";
  private static final String ASSET_SCOPE = "asset.logistics";

  private final LogisticsOAuthHttpTransport transport;
  private final String assetBase;

  LogisticsAssetOrderPresentationDependencyClient(
      LogisticsOAuthHttpTransport transport, String assetBase) {
    this.transport = transport;
    this.assetBase = assetBase;
  }

  OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search) {
    String uri =
        UriComponentsBuilder.fromUriString(assetBase + "/orders/{orderId}/unit-candidates")
            .queryParam("warehouseId", warehouseId)
            .queryParam("page", page)
            .queryParam("size", size)
            .queryParam("search", search == null ? "" : search)
            .buildAndExpand(orderId)
            .encode()
            .toUriString();
    OrderUnitCandidatePageResponse response =
        transport.get(
            uri,
            OrderUnitCandidatePageResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.content() == null) {
      throw malformed("Asset-service returned an invalid order-unit page");
    }
    return new OrderUnitCandidatePage(
        response.content().stream()
            .map(
                candidate ->
                    new OrderUnitCandidate(
                        candidate.reservationId(),
                        candidate.added(),
                        orderRentalItem(candidate.unit())))
            .toList(),
        response.page(),
        response.size(),
        response.totalElements(),
        response.totalPages());
  }

  List<OrderUnitReservation> readOrderUnits(UUID orderId) {
    return transport
        .getList(
            assetBase + "/orders/" + orderId + "/units",
            new ParameterizedTypeReference<List<OrderUnitReservationResponse>>() {},
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty response",
            ORDER)
        .stream()
        .map(LogisticsAssetOrderPresentationDependencyClient::orderReservation)
        .toList();
  }

  RentalItemReserveSnapshot readRentalItemReserves(UUID rentalItemId, UUID warehouseId) {
    String uri =
        UriComponentsBuilder.fromUriString(assetBase + "/rental-items/{rentalItemId}/reserves")
            .queryParam("warehouseId", warehouseId)
            .buildAndExpand(rentalItemId)
            .encode()
            .toUriString();
    try {
      return transport.get(
          uri,
          RentalItemReserveSnapshot.class,
          ASSET_CLIENT,
          ASSET_SCOPE,
          "Asset-service returned an empty reserve snapshot",
          DEFAULT);
    } catch (LogisticsDependencyException exception) {
      if (exception.getCause() instanceof RestClientResponseException response
          && response.getStatusCode().value() == 404) {
        throw new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            "ASSET_NOT_FOUND",
            "Rental item was not found in warehouse",
            exception);
      }
      throw exception;
    }
  }

  OrderUnitReservation reserveOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole) {
    return orderReservation(
        transport.post(
            assetBase + "/orders/" + orderId + "/units",
            idempotencyKey,
            new ReserveOrderUnitRequest(
                warehouseId,
                unitId,
                clientId,
                tenantSnapshot,
                draftReservationExpiresAt,
                actorSubjectId,
                actorRole),
            OrderUnitReservationResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty response",
            ORDER));
  }

  OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey, UUID orderId, UUID unitId, UUID actorSubjectId, String actorRole) {
    return orderReservation(
        transport.post(
            assetBase + "/orders/" + orderId + "/units/" + unitId + "/release",
            idempotencyKey,
            new OrderActorRequest(actorSubjectId, actorRole),
            OrderUnitReservationResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty response",
            ORDER));
  }

  List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole) {
    return transport
        .postList(
            assetBase + "/orders/" + orderId + "/units/release-all",
            idempotencyKey,
            new OrderActorRequest(actorSubjectId, actorRole),
            new ParameterizedTypeReference<List<OrderUnitReservationResponse>>() {},
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty response",
            ORDER)
        .stream()
        .map(LogisticsAssetOrderPresentationDependencyClient::orderReservation)
        .toList();
  }

  List<EquipmentWarehouseAvailability> readLogisticsEquipmentAvailability(UUID warehouseId) {
    String uri =
        UriComponentsBuilder.fromUriString(assetBase + "/equipment-availability")
            .queryParam("warehouseId", warehouseId)
            .build()
            .encode()
            .toUriString();
    return transport
        .getList(
            uri,
            new ParameterizedTypeReference<List<EquipmentWarehouseResponse>>() {},
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty equipment availability response",
            ORDER)
        .stream()
        .map(LogisticsAssetOrderPresentationDependencyClient::equipmentAvailability)
        .toList();
  }

  List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units) {
    List<OrderEquipmentReservationResponse> response =
        transport.putList(
            assetBase + "/orders/" + orderId + "/equipment-reservations",
            idempotencyKey,
            new ReplaceOrderEquipmentReservationsRequest(
                warehouseId,
                actorSubjectId,
                actorRole,
                units == null ? null : orderUnitEquipmentRequirementRequests(units)),
            new ParameterizedTypeReference<List<OrderEquipmentReservationResponse>>() {},
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty order equipment response",
            ORDER);
    return orderEquipmentReservations(response);
  }

  OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID replacementForRentalItemId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderUnitEquipmentRequirements> units) {
    OrderFurnitureMovementPlanResponse response =
        transport.postWithoutIdempotency(
            assetBase + "/orders/" + orderId + "/equipment-movement-plan",
            new OrderFurnitureMovementPlanRequest(
                warehouseId,
                unitId,
                replacementForRentalItemId,
                orderEquipmentRequirementRequests(unitRequirements),
                orderUnitEquipmentRequirementRequests(units)),
            OrderFurnitureMovementPlanResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty order movement plan",
            ORDER);
    return orderFurnitureMovementPlan(response);
  }

  OrderUnitsReplacementReceipt replaceOrderUnits(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units,
      List<OrderUnitReplacement> replacements) {
    return replaceOrderUnits(
        idempotencyKey,
        orderId,
        warehouseId,
        warehouseId,
        presentationId,
        actorSubjectId,
        actorRole,
        units,
        replacements);
  }

  /** Sends service and physical replacement warehouse identities without conflating them. */
  OrderUnitsReplacementReceipt replaceOrderUnits(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID inventorySourceWarehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units,
      List<OrderUnitReplacement> replacements) {
    OrderUnitsReplacementReceiptResponse response =
        transport.post(
            assetBase + "/orders/" + orderId + "/units/replace",
            idempotencyKey,
            new ReplaceOrderUnitsRequest(
                warehouseId,
                inventorySourceWarehouseId,
                presentationId,
                actorSubjectId,
                actorRole,
                orderUnitEquipmentRequirementRequests(units),
                replacements.stream()
                    .map(LogisticsAssetOrderPresentationDependencyClient::replacementRequest)
                    .toList()),
            OrderUnitsReplacementReceiptResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty order-units replacement receipt",
            ORDER);
    if (response.replacements() == null) {
      throw malformed("Asset-service returned an invalid order-units replacement receipt");
    }
    return new OrderUnitsReplacementReceipt(
        response.replacements().stream()
            .map(LogisticsAssetOrderPresentationDependencyClient::orderUnitReplacementReceipt)
            .toList(),
        response.replayed());
  }

  private static OrderUnitReplacementRequest replacementRequest(OrderUnitReplacement value) {
    if (value == null || value.rentalItemId() == null || value.replacementRentalItemId() == null) {
      throw malformed("Order-unit replacement is invalid");
    }
    OrderUnitReplacementMovement movement = value.movement();
    return new OrderUnitReplacementRequest(
        value.rentalItemId(),
        value.replacementRentalItemId(),
        movement == null
            ? null
            : new OrderUnitReplacementMovementBundleRequest(
                movement.movementId(),
                movement.reservedUntil(),
                movement.lines().stream()
                    .map(
                        line ->
                            new OrderUnitReplacementMovementLineRequest(
                                line.lineId(),
                                line.equipmentId(),
                                line.sourceBalanceId(),
                                line.expectedSourceBalanceVersion(),
                                line.targetRentalItemId(),
                                line.quantity()))
                    .toList()));
  }

  CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId, UUID rentalItemId, List<CabinFurnitureRequirement> requirements) {
    CabinFurnitureMovementPlanResponse response =
        transport.postWithoutIdempotency(
            assetBase + "/rental-items/" + rentalItemId + "/furniture-movement-plan",
            new CabinFurnitureMovementPlanRequest(
                warehouseId, cabinFurnitureRequirementRequests(requirements)),
            CabinFurnitureMovementPlanResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return cabinFurnitureMovementPlan(response);
  }

  CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUriString(assetBase + "/cabin-facets")
            .queryParam("warehouseId", warehouseId);
    if (holdScopeId != null) uri.queryParam("holdScopeId", holdScopeId);
    CabinFacetsResponse response =
        transport.get(
            uri.build().encode().toUriString(),
            CabinFacetsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (!warehouseId.equals(response.warehouseId())
        || response.cabinTypes() == null
        || response.finishes() == null
        || response.dimensions() == null
        || response.categories() == null
        || response.characteristics() == null
        || response.typeDimensions() == null
        || response.typeDimensions().stream()
            .anyMatch(
                relation ->
                    relation == null
                        || relation.cabinType() == null
                        || relation.dimensions() == null)) {
      throw malformed("Asset-service returned invalid cabin facets");
    }
    return new CabinFacets(
        response.warehouseId(),
        List.copyOf(response.cabinTypes()),
        List.copyOf(response.finishes()),
        List.copyOf(response.dimensions()),
        List.copyOf(response.categories()),
        List.copyOf(response.characteristics()),
        response.typeDimensions().stream()
            .map(
                relation ->
                    new CabinTypeDimensionRelation(
                        relation.cabinType(), List.copyOf(relation.dimensions())))
            .toList());
  }

  CabinCatalogPage readCabinCatalog(UUID warehouseId, String query, int page, int size) {
    String uri =
        UriComponentsBuilder.fromUriString(assetBase + "/cabin-catalog")
            .queryParam("warehouseId", warehouseId)
            .queryParam("query", query)
            .queryParam("page", page)
            .queryParam("size", size)
            .build()
            .encode()
            .toUriString();
    CabinCatalogPageResponse response =
        transport.get(
            uri,
            CabinCatalogPageResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.content() == null) {
      throw malformed("Asset-service returned an invalid cabin catalog page");
    }
    return new CabinCatalogPage(
        response.warehouseId(),
        response.content().stream()
            .map(LogisticsAssetOrderPresentationDependencyClient::catalogCabin)
            .toList(),
        response.page(),
        response.size(),
        response.totalElements(),
        response.totalPages());
  }

  /** Reads the asset-owned customer availability projection without acquiring a new hold. */
  CabinCatalogPage readCustomerCabinCatalog(
      UUID warehouseId,
      UUID holdScopeId,
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      int page,
      int size) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUriString(assetBase + "/customer-cabin-catalog")
            .queryParam("warehouseId", warehouseId)
            .queryParam("query", query == null ? "" : query)
            .queryParam("page", page)
            .queryParam("size", size);
    if (holdScopeId != null) uri.queryParam("holdScopeId", holdScopeId);
    if (cabinType != null) uri.queryParam("cabinType", cabinType);
    if (finish != null) uri.queryParam("finish", finish);
    if (dimensions != null) uri.queryParam("dimensions", dimensions);
    if (category != null) uri.queryParam("category", category);
    if (linoleum != null) uri.queryParam("linoleum", linoleum);
    if (characteristics != null) {
      characteristics.forEach(value -> uri.queryParam("characteristics", value));
    }
    CabinCatalogPageResponse response =
        transport.get(
            uri.build().encode().toUriString(),
            CabinCatalogPageResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty customer cabin catalog",
            DEFAULT);
    if (response.content() == null) {
      throw malformed("Asset-service returned an invalid customer cabin catalog page");
    }
    return new CabinCatalogPage(
        response.warehouseId(),
        response.content().stream()
            .map(LogisticsAssetOrderPresentationDependencyClient::catalogCabin)
            .toList(),
        response.page(),
        response.size(),
        response.totalElements(),
        response.totalPages());
  }

  CabinSearchResult searchAvailableCabins(UUID downstreamIdempotencyKey, String exactRequestBody) {
    CabinSearchResponse response =
        transport.postExactJson(
            assetBase + "/cabin-searches",
            downstreamIdempotencyKey,
            exactRequestBody,
            CabinSearchResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            CABIN_SEARCH);
    if (response.warehouseId() == null
        || response.expiresAt() == null
        || response.groups() == null
        || response.groups().stream()
            .anyMatch(group -> group == null || group.group() == null || group.cabins() == null)) {
      throw malformed("Asset-service returned an invalid cabin search result");
    }
    return new CabinSearchResult(
        response.warehouseId(),
        response.expiresAt(),
        response.groups().stream()
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
                        group.cabins().stream()
                            .map(LogisticsAssetOrderPresentationDependencyClient::availableCabin)
                            .toList()))
            .toList());
  }

  List<AvailableCabin> readCabinSnapshots(UUID warehouseId, List<UUID> rentalItemIds) {
    CabinSnapshotsResponse response =
        transport.postWithoutIdempotency(
            assetBase + "/cabin-snapshots",
            new CabinIdsRequest(warehouseId, rentalItemIds),
            CabinSnapshotsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (!warehouseId.equals(response.warehouseId()) || response.items() == null) {
      throw malformed("Asset-service returned invalid cabin snapshots");
    }
    return response.items().stream()
        .map(LogisticsAssetOrderPresentationDependencyClient::availableCabin)
        .toList();
  }

  CabinAvailability readCabinAvailability(UUID warehouseId, List<UUID> rentalItemIds) {
    CabinAvailabilityResponse response =
        transport.postWithoutIdempotency(
            assetBase + "/cabin-availability",
            new CabinIdsRequest(warehouseId, rentalItemIds),
            CabinAvailabilityResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return new CabinAvailability(
        response.warehouseId(),
        response.items().stream()
            .map(
                item ->
                    new CabinAvailabilityItem(item.rentalItemId(), item.available(), item.reason()))
            .toList());
  }

  PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole) {
    return replacePresentationHolds(
        idempotencyKey,
        presentationId,
        warehouseId,
        rentalItemIds,
        expiresAt,
        actorSubjectId,
        actorRole,
        null);
  }

  PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {
    PresentationHoldsResponse response =
        transport.put(
            assetBase + "/presentations/" + presentationId + "/holds",
            idempotencyKey,
            new ReplacePresentationHoldsRequest(
                warehouseId,
                rentalItemIds,
                expiresAt,
                actorSubjectId,
                actorRole,
                sourceHoldScopeId),
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return presentationHolds(response, true);
  }

  PresentationHolds replacePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    PresentationHoldsResponse response =
        transport.putExactJson(
            assetBase + "/presentations/" + presentationId + "/holds",
            idempotencyKey,
            exactRequestBody,
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return presentationHolds(response, true);
  }

  PresentationHolds readPresentationHolds(UUID presentationId) {
    return readPresentationHolds(presentationId, null, null);
  }

  PresentationHolds readPresentationHolds(
      UUID presentationId, UUID actorSubjectId, String actorRole) {
    String query =
        actorSubjectId == null
            ? ""
            : "?actorSubjectId=" + actorSubjectId + "&actorRole=" + actorRole;
    PresentationHoldsResponse response =
        transport.get(
            assetBase + "/presentations/" + presentationId + "/holds" + query,
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return presentationHolds(response, false);
  }

  PresentationHolds releasePresentationHolds(
      UUID idempotencyKey, UUID presentationId, UUID actorSubjectId, String actorRole) {
    PresentationHoldsResponse response =
        transport.post(
            assetBase + "/presentations/" + presentationId + "/holds/release",
            idempotencyKey,
            new OrderActorRequest(actorSubjectId, actorRole),
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return presentationHolds(response, false);
  }

  PresentationHolds releasePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    PresentationHoldsResponse response =
        transport.postExactJson(
            assetBase + "/presentations/" + presentationId + "/holds/release",
            idempotencyKey,
            exactRequestBody,
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return presentationHolds(response, false);
  }

  ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units) {
    ConvertedPresentationHoldsResponse response =
        transport.post(
            assetBase + "/presentations/" + presentationId + "/holds/convert",
            idempotencyKey,
            new ConvertPresentationHoldsRequest(
                orderId,
                warehouseId,
                selectedRentalItemIds,
                clientId,
                tenantSnapshot,
                actorSubjectId,
                actorRole,
                units == null ? null : orderUnitEquipmentRequirementRequests(units)),
            ConvertedPresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return new ConvertedPresentationHolds(
        response.presentationId(),
        response.orderId(),
        response.reservations().stream()
            .map(LogisticsAssetOrderPresentationDependencyClient::orderReservation)
            .toList(),
        List.copyOf(response.releasedRentalItemIds()),
        orderEquipmentReservations(response.equipmentReservations()));
  }

  private static OrderUnitReservation orderReservation(OrderUnitReservationResponse response) {
    if (response == null || response.unit() == null) {
      throw malformed("Asset-service returned an invalid order-unit reservation");
    }
    return new OrderUnitReservation(
        response.reservationId(),
        response.reservationVersion(),
        response.orderId(),
        response.rentalItemId(),
        response.warehouseId(),
        response.state(),
        response.addedBySubjectId(),
        response.addedByRole(),
        response.createdAt(),
        response.releasedAt(),
        response.replayed(),
        orderRentalItem(response.unit()));
  }

  private static OrderRentalItem orderRentalItem(OrderRentalItemResponse response) {
    if (response == null || response.contents() == null || response.tags() == null) {
      throw malformed("Asset-service returned an invalid order rental item");
    }
    return new OrderRentalItem(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.number(),
        response.status(),
        response.rentalType(),
        response.dimensions(),
        response.finishing(),
        response.category(),
        response.characteristics(),
        response.linoleum(),
        List.copyOf(response.tags()),
        response.contents().stream()
            .map(
                content ->
                    new OrderEquipmentContent(
                        content.equipmentId(),
                        content.equipmentName(),
                        content.quantity(),
                        content.locationKind()))
            .toList(),
        response.createdAt(),
        response.updatedAt());
  }

  private static List<OrderEquipmentRequirementRequest> orderEquipmentRequirementRequests(
      List<OrderEquipmentRequirement> requirements) {
    if (requirements == null) {
      throw malformed("Order equipment requirements are required");
    }
    return requirements.stream()
        .map(
            requirement -> {
              if (requirement == null) {
                throw malformed("Order equipment requirement is invalid");
              }
              return new OrderEquipmentRequirementRequest(
                  requirement.equipmentId(), requirement.quantity());
            })
        .toList();
  }

  private static List<OrderUnitEquipmentRequirementsRequest> orderUnitEquipmentRequirementRequests(
      List<OrderUnitEquipmentRequirements> units) {
    if (units == null) {
      throw malformed("Order unit equipment requirements are required");
    }
    return units.stream()
        .map(
            unit -> {
              if (unit == null || unit.rentalItemId() == null) {
                throw malformed("Order unit equipment requirements are invalid");
              }
              return new OrderUnitEquipmentRequirementsRequest(
                  unit.rentalItemId(), orderEquipmentRequirementRequests(unit.requirements()));
            })
        .toList();
  }

  private static EquipmentWarehouseAvailability equipmentAvailability(
      EquipmentWarehouseResponse response) {
    if (response == null
        || response.equipment() == null
        || response.totals() == null
        || response.equipment().id() == null
        || response.equipment().name() == null
        || response.equipment().name().isBlank()
        || response.totals().availableQuantity() < 0
        || response.totals().availableStock() < 0
        || response.totals().balances() == null
        || response.totals().balances().stream()
            .anyMatch(
                balance ->
                    balance == null
                        || balance.id() == null
                        || balance.version() < 0
                        || !response.equipment().id().equals(balance.equipmentId())
                        || balance.warehouseId() == null
                        || balance.locationKind() == null
                        || balance.quantity() < 0
                        || balance.activeHeldQuantity() < 0
                        || balance.availableStock() < 0)
        || response.equipment().maximumPerCabin() != null
            && response.equipment().maximumPerCabin() < 1) {
      throw malformed("Asset-service returned invalid equipment availability");
    }
    return new EquipmentWarehouseAvailability(
        response.equipment().id(),
        response.equipment().name(),
        response.equipment().active(),
        response.totals().availableQuantity(),
        response.equipment().maximumPerCabin(),
        response.totals().availableStock(),
        response.totals().balances().stream()
            .map(
                balance ->
                    new EquipmentBalanceAvailability(
                        balance.id(),
                        balance.version(),
                        balance.equipmentId(),
                        balance.warehouseId(),
                        balance.rentalItemId(),
                        balance.locationKind(),
                        balance.quantity(),
                        balance.activeHeldQuantity(),
                        balance.allocatable(),
                        balance.availableStock()))
            .toList());
  }

  private static List<OrderEquipmentReservation> orderEquipmentReservations(
      List<OrderEquipmentReservationResponse> response) {
    if (response == null) {
      throw malformed("Asset-service returned an empty order equipment reservation list");
    }
    java.util.HashSet<String> ids = new java.util.HashSet<>();
    List<OrderEquipmentReservation> values = new java.util.ArrayList<>(response.size());
    for (OrderEquipmentReservationResponse value : response) {
      if (value == null
          || value.warehouseId() == null
          || value.equipmentId() == null
          || value.equipmentName() == null
          || value.equipmentName().isBlank()
          || value.quantity() < 1
          || value.availableQuantity() < 0
          || !ids.add(value.warehouseId() + ":" + value.equipmentId())) {
        throw malformed("Asset-service returned an invalid order equipment reservation");
      }
      values.add(
          new OrderEquipmentReservation(
              value.warehouseId(),
              value.equipmentId(),
              value.equipmentName(),
              value.quantity(),
              value.availableQuantity(),
              value.maximumPerCabin()));
    }
    return List.copyOf(values);
  }

  private static OrderFurnitureMovementPlan orderFurnitureMovementPlan(
      OrderFurnitureMovementPlanResponse response) {
    if (response == null
        || response.orderId() == null
        || response.rentalItemId() == null
        || response.unitNumber() == null
        || response.lines() == null) {
      throw malformed("Asset-service returned an invalid order furniture movement plan");
    }
    List<OrderFurnitureMovementPlanLine> lines = new java.util.ArrayList<>(response.lines().size());
    for (OrderFurnitureMovementPlanLineResponse line : response.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.equipmentName() == null
          || line.sourceBalanceId() == null
          || line.sourceWarehouseId() == null
          || line.sourceLocationKind() == null
          || line.expectedSourceBalanceVersion() < 0
          || line.targetWarehouseId() == null
          || line.targetLocationKind() == null
          || line.quantity() < 1) {
        throw malformed("Asset-service returned an invalid order furniture movement line");
      }
      lines.add(
          new OrderFurnitureMovementPlanLine(
              line.equipmentId(),
              line.equipmentName(),
              line.sourceBalanceId(),
              line.sourceWarehouseId(),
              line.sourceRentalItemId(),
              line.sourceLocationKind(),
              line.expectedSourceBalanceVersion(),
              line.targetWarehouseId(),
              line.targetRentalItemId(),
              line.targetLocationKind(),
              line.quantity()));
    }
    return new OrderFurnitureMovementPlan(
        response.orderId(), response.rentalItemId(), response.unitNumber(), List.copyOf(lines));
  }

  private static OrderUnitReplacementReceipt orderUnitReplacementReceipt(
      OrderUnitReplacementReceiptResponse response) {
    if (response == null
        || response.releasedReservation() == null
        || response.replacementReservation() == null
        || response.movementReservations() == null) {
      throw malformed("Asset-service returned an invalid order-unit replacement receipt");
    }
    return new OrderUnitReplacementReceipt(
        orderReservation(response.releasedReservation()),
        orderReservation(response.replacementReservation()),
        response.movementReservations().stream()
            .map(EquipmentMovementReservationResponse::decode)
            .toList(),
        response.contentReady());
  }

  private static List<CabinFurnitureRequirementRequest> cabinFurnitureRequirementRequests(
      List<CabinFurnitureRequirement> requirements) {
    if (requirements == null) {
      throw malformed("Cabin furniture requirements are required");
    }
    return requirements.stream()
        .map(
            requirement -> {
              if (requirement == null) {
                throw malformed("Cabin furniture requirement is invalid");
              }
              return new CabinFurnitureRequirementRequest(
                  requirement.equipmentId(), requirement.quantity());
            })
        .toList();
  }

  private static CabinFurnitureMovementPlan cabinFurnitureMovementPlan(
      CabinFurnitureMovementPlanResponse response) {
    if (response == null
        || response.rentalItemId() == null
        || response.unitNumber() == null
        || response.unitNumber().isBlank()
        || response.lines() == null) {
      throw malformed("Asset-service returned an invalid cabin furniture movement plan");
    }
    List<CabinFurnitureMovementPlanLine> lines = new java.util.ArrayList<>(response.lines().size());
    for (CabinFurnitureMovementPlanLineResponse line : response.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.equipmentName() == null
          || line.equipmentName().isBlank()
          || line.sourceBalanceId() == null
          || line.sourceWarehouseId() == null
          || line.sourceLocationKind() == null
          || line.expectedSourceBalanceVersion() < 0
          || line.targetWarehouseId() == null
          || line.targetLocationKind() == null
          || line.quantity() < 1) {
        throw malformed("Asset-service returned an invalid cabin furniture movement line");
      }
      lines.add(
          new CabinFurnitureMovementPlanLine(
              line.equipmentId(),
              line.equipmentName(),
              line.sourceBalanceId(),
              line.sourceWarehouseId(),
              line.sourceRentalItemId(),
              line.sourceLocationKind(),
              line.expectedSourceBalanceVersion(),
              line.targetWarehouseId(),
              line.targetRentalItemId(),
              line.targetLocationKind(),
              line.quantity()));
    }
    return new CabinFurnitureMovementPlan(
        response.rentalItemId(), response.unitNumber(), List.copyOf(lines));
  }

  private static AvailableCabin availableCabin(AvailableCabinResponse response) {
    return cabin(response, true);
  }

  private static AvailableCabin catalogCabin(AvailableCabinResponse response) {
    return cabin(response, false);
  }

  private static AvailableCabin cabin(
      AvailableCabinResponse response, boolean requireAvailableStatus) {
    if (response == null
        || response.id() == null
        || response.version() < 0
        || response.warehouseId() == null
        || response.status() == null
        || response.status().isBlank()
        || (requireAvailableStatus && !isAvailableCabinStatus(response.status()))
        || response.number() == null
        || response.passport() == null
        || response.tags() == null
        || response.tags().stream().anyMatch(java.util.Objects::isNull)
        || response.contents() == null
        || response.updatedAt() == null) {
      throw malformed("Asset-service returned an invalid available cabin");
    }
    return new AvailableCabin(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.status(),
        response.number(),
        response.rentalType(),
        response.dimensions(),
        response.finishing(),
        response.category(),
        response.characteristics(),
        response.linoleum(),
        Collections.unmodifiableMap(new LinkedHashMap<>(response.passport())),
        List.copyOf(response.tags()),
        response.contents().stream()
            .map(
                content ->
                    new OrderEquipmentContent(
                        content.equipmentId(),
                        content.equipmentName(),
                        content.quantity(),
                        content.locationKind()))
            .toList(),
        response.updatedAt());
  }

  private static boolean isAvailableCabinStatus(String status) {
    return "FREE".equals(status);
  }

  private static PresentationHolds presentationHolds(
      PresentationHoldsResponse response, boolean requireCabins) {
    if (response == null
        || response.presentationId() == null
        || response.holds() == null
        || response.holds().stream().anyMatch(java.util.Objects::isNull)
        || (requireCabins
            && (response.cabins() == null
                || response.cabins().stream().anyMatch(java.util.Objects::isNull)))) {
      throw malformed("Asset-service returned invalid presentation holds");
    }
    return new PresentationHolds(
        response.presentationId(),
        response.expiresAt(),
        response.holds().stream()
            .map(
                hold ->
                    new PresentationHold(
                        hold.holdId(),
                        hold.version(),
                        hold.presentationId(),
                        hold.rentalItemId(),
                        hold.warehouseId(),
                        hold.state(),
                        hold.expiresAt(),
                        hold.orderId(),
                        hold.createdAt(),
                        hold.endedAt()))
            .toList(),
        response.cabins() == null
            ? List.of()
            : response.cabins().stream()
                .map(LogisticsAssetOrderPresentationDependencyClient::availableCabin)
                .toList());
  }

  /**
   * Order-unit reservation command carrying the selected cabin, tenant snapshot, draft expiry, and
   * auditable actor identity to asset-service.
   */
  private record ReserveOrderUnitRequest(
      UUID warehouseId,
      UUID rentalItemId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole) {}

  /** Actor identity attached to order-unit release commands for remote authorization and audit. */
  private record OrderActorRequest(UUID actorSubjectId, String actorRole) {}

  /** Requested quantity of one equipment kind in an order or furniture-movement plan. */
  private record OrderEquipmentRequirementRequest(UUID equipmentId, long quantity) {}

  /** Complete desired equipment composition for one order cabin. */
  private record OrderUnitEquipmentRequirementsRequest(
      UUID rentalItemId, List<OrderEquipmentRequirementRequest> requirements) {}

  /**
   * Replacement command declaring the complete desired equipment reservation set for an order;
   * asset-service resolves each active cabin's physical source and partitions the aggregate there.
   */
  private record ReplaceOrderEquipmentReservationsRequest(
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirementsRequest> units) {}

  /** Source-partitioned asset resolution of an equipment requirement and live availability. */
  private record OrderEquipmentReservationResponse(
      UUID warehouseId,
      UUID equipmentId,
      String equipmentName,
      long quantity,
      long availableQuantity,
      Integer maximumPerCabin) {}

  /**
   * Planning request that compares a selected cabin's current equipment with both cabin and
   * order-level requirements.
   */
  private record OrderFurnitureMovementPlanRequest(
      UUID warehouseId,
      UUID rentalItemId,
      UUID replacementForRentalItemId,
      List<OrderEquipmentRequirementRequest> requirements,
      List<OrderUnitEquipmentRequirementsRequest> units) {}

  /** One exact line of an existing furniture movement task supplied to replacement. */
  private record OrderUnitReplacementMovementLineRequest(
      UUID lineId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long expectedSourceBalanceVersion,
      UUID targetRentalItemId,
      long quantity) {}

  /** Existing movement identity and its exact source-fenced lines. */
  private record OrderUnitReplacementMovementBundleRequest(
      UUID movementId,
      OffsetDateTime reservedUntil,
      List<OrderUnitReplacementMovementLineRequest> lines) {}

  /** One ordered replacement pair in the atomic asset batch. */
  private record OrderUnitReplacementRequest(
      UUID rentalItemId,
      UUID replacementRentalItemId,
      OrderUnitReplacementMovementBundleRequest movement) {}

  /** Atomic asset replacement command carrying all pairs and post-swap composition. */
  private record ReplaceOrderUnitsRequest(
      UUID warehouseId,
      UUID inventorySourceWarehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirementsRequest> units,
      List<OrderUnitReplacementRequest> replacements) {}

  /** Atomic order-unit replacement receipt returned by asset-service. */
  private record OrderUnitReplacementReceiptResponse(
      OrderUnitReservationResponse releasedReservation,
      OrderUnitReservationResponse replacementReservation,
      List<EquipmentMovementReservationResponse> movementReservations,
      boolean contentReady) {}

  /** Complete atomic multi-cabin replacement receipt. */
  private record OrderUnitsReplacementReceiptResponse(
      List<OrderUnitReplacementReceiptResponse> replacements, boolean replayed) {}

  /** Minimal equipment catalogue shape needed for live presentation availability. */
  private record EquipmentResponse(UUID id, String name, boolean active, Integer maximumPerCabin) {}

  /** Minimal warehouse totals shape needed for live presentation availability. */
  private record EquipmentTotalsResponse(
      long availableQuantity,
      long availableStock,
      List<EquipmentBalanceResponse> balances) {}

  /** Exact source balance included by the existing asset equipment-availability response. */
  private record EquipmentBalanceResponse(
      UUID id,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      String locationKind,
      long quantity,
      long activeHeldQuantity,
      boolean allocatable,
      long availableStock) {}

  /** Asset-owned warehouse equipment availability row. */
  private record EquipmentWarehouseResponse(
      EquipmentResponse equipment, EquipmentTotalsResponse totals) {}

  /**
   * One remotely planned stock movement, including the optimistic source-balance version and exact
   * source and target locations.
   */
  private record OrderFurnitureMovementPlanLineResponse(
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      long quantity) {}

  /** Asset-service furniture movement plan correlated to the order and selected cabin. */
  private record OrderFurnitureMovementPlanResponse(
      UUID orderId,
      UUID rentalItemId,
      String unitNumber,
      List<OrderFurnitureMovementPlanLineResponse> lines) {}

  /** Equipment quantity requested when planning furniture movements for a cabin alone. */
  private record CabinFurnitureRequirementRequest(UUID equipmentId, long quantity) {}

  /** Warehouse-scoped requirement set submitted for cabin furniture movement planning. */
  private record CabinFurnitureMovementPlanRequest(
      UUID warehouseId, List<CabinFurnitureRequirementRequest> requirements) {}

  /**
   * Cabin planning result line carrying the source fence and precise destination needed for later
   * movement execution.
   */
  private record CabinFurnitureMovementPlanLineResponse(
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      long quantity) {}

  /** Complete asset-service furniture movement plan for one cabin. */
  private record CabinFurnitureMovementPlanResponse(
      UUID rentalItemId, String unitNumber, List<CabinFurnitureMovementPlanLineResponse> lines) {}

  /** Equipment content embedded in an order-facing cabin snapshot. */
  private record OrderEquipmentContentResponse(
      UUID equipmentId, String equipmentName, long quantity, String locationKind) {}

  /**
   * Asset-service cabin snapshot used in order candidates and reservations without importing the
   * asset aggregate into logistics.
   */
  private record OrderRentalItemResponse(
      UUID id,
      long version,
      UUID warehouseId,
      String number,
      String status,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      List<String> tags,
      List<OrderEquipmentContentResponse> contents,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * Authoritative order-unit reservation lifecycle, including optimistic version, release time,
   * replay marker, and the immutable cabin snapshot returned by asset-service.
   */
  private record OrderUnitReservationResponse(
      UUID reservationId,
      long reservationVersion,
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      String state,
      UUID addedBySubjectId,
      String addedByRole,
      OffsetDateTime createdAt,
      OffsetDateTime releasedAt,
      boolean replayed,
      OrderRentalItemResponse unit) {}

  /** Candidate cabin paired with whether the order already holds its reservation. */
  private record OrderUnitCandidateResponse(
      UUID reservationId, boolean added, OrderRentalItemResponse unit) {}

  /** Paginated asset-service candidate result preserving remote paging totals. */
  private record OrderUnitCandidatePageResponse(
      List<OrderUnitCandidateResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  /** Warehouse-scoped facet values available for presentation cabin search. */
  private record CabinFacetsResponse(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories,
      List<String> characteristics,
      List<CabinTypeDimensionRelationResponse> typeDimensions) {}

  /** Exact asset catalog relation between one cabin type and its available dimensions. */
  private record CabinTypeDimensionRelationResponse(String cabinType, List<String> dimensions) {}

  /**
   * Asset-service availability snapshot for a cabin returned to presentation and order search
   * mapping.
   */
  private record AvailableCabinResponse(
      UUID id,
      long version,
      UUID warehouseId,
      String status,
      String number,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<String> tags,
      List<OrderEquipmentContentResponse> contents,
      OffsetDateTime updatedAt) {}

  /** Cabins matched by asset-service to one requested presentation search group. */
  private record CabinSearchGroupResponse(
      CabinSearchGroup group, List<AvailableCabinResponse> cabins) {}

  /** Grouped presentation search result with the authoritative hold expiry. */
  private record CabinSearchResponse(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResponse> groups) {}

  /** Warehouse-scoped batch key used for cabin snapshot and availability reads. */
  private record CabinIdsRequest(UUID warehouseId, List<UUID> rentalItemIds) {}

  /** Batch of authoritative cabin snapshots returned for explicitly requested identifiers. */
  private record CabinSnapshotsResponse(UUID warehouseId, List<AvailableCabinResponse> items) {}

  /** Facts-only paged cabin lookup response returned by asset-service. */
  private record CabinCatalogPageResponse(
      UUID warehouseId,
      List<AvailableCabinResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  /** Per-cabin availability decision and rejection reason returned by asset-service. */
  private record CabinAvailabilityItemResponse(
      UUID rentalItemId, boolean available, String reason) {}

  /** Warehouse-scoped collection of per-cabin availability decisions. */
  private record CabinAvailabilityResponse(
      UUID warehouseId, List<CabinAvailabilityItemResponse> items) {}

  /**
   * Command replacing the complete presentation hold set, including expiry, actor, and optional
   * source search scope used for transfer of temporary holds.
   */
  private record ReplacePresentationHoldsRequest(
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {}

  /**
   * Authoritative presentation hold lifecycle with version, expiry, conversion target, and end
   * timestamps.
   */
  private record PresentationHoldResponse(
      UUID holdId,
      long version,
      UUID presentationId,
      UUID rentalItemId,
      UUID warehouseId,
      String state,
      OffsetDateTime expiresAt,
      UUID orderId,
      OffsetDateTime createdAt,
      OffsetDateTime endedAt) {}

  /** Complete current hold set and expiry for one client presentation. */
  private record PresentationHoldsResponse(
      UUID presentationId,
      OffsetDateTime expiresAt,
      List<PresentationHoldResponse> holds,
      List<AvailableCabinResponse> cabins) {}

  /**
   * Conversion command selecting presentation-held cabins for an order while carrying client,
   * tenant, warehouse, and actor context.
   */
  private record ConvertPresentationHoldsRequest(
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirementsRequest> units) {}

  /**
   * Conversion outcome containing created order reservations and cabins released from the
   * presentation hold set.
   */
  private record ConvertedPresentationHoldsResponse(
      UUID presentationId,
      UUID orderId,
      List<OrderUnitReservationResponse> reservations,
      List<UUID> releasedRentalItemIds,
      List<OrderEquipmentReservationResponse> equipmentReservations) {}
}
