package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CreateCustomerInquiryRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinEquipmentSelection;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinSelectionResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCartResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerEquipmentAvailability;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerEquipmentSelectionResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerInquiryResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerRentalTermsResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.ReplaceCustomerCabinsRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.ReplaceCustomerEquipmentRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.ReplaceCustomerRentalTermsRequest;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerProfile;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.AvailableCabinResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinFacetsResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSelectionRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSelectionResponse;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSelectionService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-facing rental-cart facade over existing inquiry, asset hold and equipment availability
 * workflows. It adds only customer ownership and optimistic cart fencing.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.NEVER)
public class CustomerRentalService {
  private final CustomerProfileService profiles;
  private final CustomerWarehouseService warehouses;
  private final CustomerRentalSessionStore sessionStore;
  private final CustomerAuthorizer access;
  private final RentalInquiryService inquiries;
  private final RentalInquiryCabinSelectionService selections;
  private final CustomerCabinCatalogService catalog;
  private final CustomerEquipmentCodec equipmentCodec;
  private final CustomerRentalTermCodec rentalTermCodec;
  private final LogisticsDependencyGateway dependencies;

  /** Creates a new inquiry/cart for the current profile and selected active warehouse. */
  public CustomerInquiryResponse createInquiry(
      CustomerIdentity identity, UUID idempotencyKey, CreateCustomerInquiryRequest request) {
    CustomerProfile profile = profiles.required(identity);
    warehouses.required(request.warehouseId());
    OrderActor actor = access.orderActor(identity, request.warehouseId());
    var inquiry =
        inquiries.createCustomer(
            actor, idempotencyKey, profile.getClientId(), request.warehouseId());
    CustomerRentalSession session =
        sessionStore.create(inquiry.id(), identity.subjectId(), request.warehouseId());
    return inquiry(session);
  }

  /** Returns one owned inquiry/cart identity and current optimistic version. */
  public CustomerInquiryResponse inquiry(CustomerIdentity identity, UUID inquiryId) {
    return inquiry(sessionStore.required(identity.subjectId(), inquiryId));
  }

  /** Returns exact asset-owned filters for the inquiry's selected warehouse. */
  public CabinFacetsResponse facets(CustomerIdentity identity, UUID inquiryId) {
    CustomerRentalSession session =
        sessionStore.required(identity.subjectId(), inquiryId);
    return inquiries.facets(access.orderActor(identity, session.getWarehouseId()), inquiryId);
  }

  /** Replaces the authoritative cabin hold set and returns the resulting cart. */
  public CustomerCabinSelectionResponse replaceCabins(
      CustomerIdentity identity,
      UUID inquiryId,
      UUID idempotencyKey,
      ReplaceCustomerCabinsRequest request) {
    String requestHash = selectionHash(request.cabinUnitIds());
    var preparation =
        sessionStore.prepareSelection(
            identity.subjectId(),
            inquiryId,
            request.expectedVersion(),
            idempotencyKey,
            requestHash);
    CustomerRentalSession prepared = preparation.session();
    OrderActor actor = access.orderActor(identity, prepared.getWarehouseId());
    try {
      CabinSelectionResponse held =
          preparation.completedReplay()
              ? selections.get(actor, inquiryId)
              : selections.replace(
                  actor,
                  inquiryId,
                  preparation.commandKey(),
                  new CabinSelectionRequest(
                      prepared.getWarehouseId(), List.copyOf(request.cabinUnitIds())));
      Set<UUID> selectedIds = Set.copyOf(held.rentalItemIds());
      String equipmentJson =
          equipmentCodec.retain(prepared.getEquipmentSelectionJson(), selectedIds);
      String rentalTermsJson = rentalTermCodec.retain(prepared.getRentalTermsJson(), selectedIds);
      CustomerRentalSession completed =
          preparation.completedReplay()
              ? prepared
              : sessionStore.completeSelection(
                  identity.subjectId(),
                  inquiryId,
                  preparation.commandKey(),
                  requestHash,
                  equipmentJson,
                  rentalTermsJson);
      return new CustomerCabinSelectionResponse(
          completed.getVersion(),
          held.expiresAt(),
          catalog.heldCabins(identity, inquiryId, held.items().stream().map(CustomerRentalService::cabin).toList()));
    } catch (RuntimeException exception) {
      if (!preparation.completedReplay()) {
        try {
          sessionStore.failSelection(
              identity.subjectId(),
              inquiryId,
              preparation.commandKey(),
              requestHash);
        } catch (RuntimeException ignored) {
          // The prepared receipt and asset command remain safely retryable with the same key.
        }
      }
      throw exception;
    }
  }

  /** Returns the current cabin selection without renewing its holds. */
  public CustomerCabinSelectionResponse cabinSelection(
      CustomerIdentity identity, UUID inquiryId) {
    CustomerRentalSession session =
        sessionStore.required(identity.subjectId(), inquiryId);
    CabinSelectionResponse held =
        selections.get(access.orderActor(identity, session.getWarehouseId()), inquiryId);
    return new CustomerCabinSelectionResponse(
        session.getVersion(),
        held.expiresAt(),
        catalog.heldCabins(identity, inquiryId, held.items().stream().map(CustomerRentalService::cabin).toList()));
  }

  /** Lists only active furniture positions with positive warehouse availability. */
  public List<CustomerEquipmentAvailability> equipment(
      CustomerIdentity identity, UUID inquiryId) {
    CustomerRentalSession session =
        sessionStore.required(identity.subjectId(), inquiryId);
    try {
      return dependencies.readLogisticsEquipmentAvailability(session.getWarehouseId()).stream()
          .filter(LogisticsDependencyGateway.EquipmentWarehouseAvailability::active)
          .filter(item -> item.availableQuantity() > 0)
          .map(
              item ->
                  new CustomerEquipmentAvailability(
                      item.equipmentId(),
                      item.equipmentName(),
                      null,
                      item.availableQuantity(),
                      item.maximumPerCabin()))
          .toList();
    } catch (LogisticsDependencyException exception) {
      throw new OrderProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CUSTOMER_EQUIPMENT_UNAVAILABLE",
          "Остатки мебели временно недоступны");
    }
  }

  /** Replaces the complete furniture intent after checking live stock and per-cabin limits. */
  public CustomerEquipmentSelectionResponse replaceEquipment(
      CustomerIdentity identity,
      UUID inquiryId,
      ReplaceCustomerEquipmentRequest request) {
    CustomerRentalSession current =
        sessionStore.required(identity.subjectId(), inquiryId);
    CabinSelectionResponse held =
        selections.get(access.orderActor(identity, current.getWarehouseId()), inquiryId);
    Set<UUID> selectedCabins = Set.copyOf(held.rentalItemIds());
    validateEquipmentAvailability(equipment(identity, inquiryId), request.selections());
    String normalized = equipmentCodec.encode(request.selections(), selectedCabins);
    CustomerRentalSession updated =
        sessionStore.replaceEquipment(
            identity.subjectId(),
            inquiryId,
            request.expectedVersion(),
            normalized);
    return new CustomerEquipmentSelectionResponse(
        updated.getVersion(), equipmentCodec.decode(updated.getEquipmentSelectionJson()));
  }

  /** Replaces the complete per-cabin rental durations for the current held cabin set. */
  public CustomerRentalTermsResponse replaceRentalTerms(
      CustomerIdentity identity,
      UUID inquiryId,
      ReplaceCustomerRentalTermsRequest request) {
    CustomerRentalSession current =
        sessionStore.required(identity.subjectId(), inquiryId);
    CabinSelectionResponse held =
        selections.get(access.orderActor(identity, current.getWarehouseId()), inquiryId);
    Set<UUID> selectedCabins = Set.copyOf(held.rentalItemIds());
    String normalized = rentalTermCodec.encode(request.terms(), selectedCabins);
    CustomerRentalSession updated =
        sessionStore.replaceRentalTerms(
            identity.subjectId(),
            inquiryId,
            request.expectedVersion(),
            normalized);
    return new CustomerRentalTermsResponse(
        updated.getVersion(), rentalTermCodec.decode(updated.getRentalTermsJson()));
  }

  /** Returns the complete customer cart from authoritative cabin holds plus local furniture intent. */
  public CustomerCartResponse cart(CustomerIdentity identity, UUID inquiryId) {
    CustomerRentalSession session =
        sessionStore.required(identity.subjectId(), inquiryId);
    CabinSelectionResponse held =
        selections.get(access.orderActor(identity, session.getWarehouseId()), inquiryId);
    return new CustomerCartResponse(
        inquiryId,
        session.getWarehouseId(),
        session.getVersion(),
        session.getState().name(),
        catalog.heldCabins(identity, inquiryId, held.items().stream().map(CustomerRentalService::cabin).toList()),
        equipmentCodec.decode(session.getEquipmentSelectionJson()),
        rentalTermCodec.decode(session.getRentalTermsJson()),
        session.getDeliverySlotId());
  }

  /** Returns current authoritative cabin IDs for slot capacity and checkout orchestration. */
  public List<UUID> selectedCabinIds(CustomerIdentity identity, UUID inquiryId) {
    CustomerRentalSession session =
        sessionStore.required(identity.subjectId(), inquiryId);
    return selections
        .get(access.orderActor(identity, session.getWarehouseId()), inquiryId)
        .rentalItemIds();
  }

  /** Returns the current cart entity for slot and checkout services. */
  public CustomerRentalSession requiredSession(CustomerIdentity identity, UUID inquiryId) {
    return sessionStore.required(identity.subjectId(), inquiryId);
  }

  private static CustomerInquiryResponse inquiry(CustomerRentalSession session) {
    return new CustomerInquiryResponse(
        session.getInquiryId(),
        session.getWarehouseId(),
        session.getVersion(),
        session.getState().name());
  }

  private static LogisticsDependencyGateway.AvailableCabin cabin(AvailableCabinResponse source) {
    return new LogisticsDependencyGateway.AvailableCabin(
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
        source.contents().stream()
            .map(
                item ->
                    new LogisticsDependencyGateway.OrderEquipmentContent(
                        item.equipmentId(),
                        item.equipmentName(),
                        item.quantity(),
                        item.locationKind()))
            .toList(),
        source.updatedAt());
  }

  private static void validateEquipmentAvailability(
      List<CustomerEquipmentAvailability> available,
      List<CustomerCabinEquipmentSelection> requested) {
    Map<UUID, CustomerEquipmentAvailability> byId =
        available.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CustomerEquipmentAvailability::inventoryItemId, item -> item));
    Map<UUID, Long> totals = new LinkedHashMap<>();
    for (CustomerCabinEquipmentSelection selection : requested) {
      CustomerEquipmentAvailability item = byId.get(selection.inventoryItemId());
      if (item == null) {
        throw conflict("CUSTOMER_EQUIPMENT_NOT_AVAILABLE", "Позиция мебели закончилась на складе");
      }
      if (item.maximumPerCabin() != null && selection.quantity() > item.maximumPerCabin()) {
        throw conflict(
            "CUSTOMER_EQUIPMENT_CABIN_LIMIT",
            "Количество мебели превышает лимит для одной бытовки");
      }
      totals.merge(selection.inventoryItemId(), selection.quantity(), Math::addExact);
    }
    totals.forEach(
        (id, quantity) -> {
          if (quantity > byId.get(id).availableQuantity()) {
            throw conflict(
                "CUSTOMER_EQUIPMENT_NOT_AVAILABLE",
                "Выбранного количества мебели нет на складе");
          }
        });
  }

  private static String selectionHash(List<UUID> ids) {
    String value =
        ids.stream()
            .sorted()
            .map(UUID::toString)
            .collect(java.util.stream.Collectors.joining("\n"));
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }
}
