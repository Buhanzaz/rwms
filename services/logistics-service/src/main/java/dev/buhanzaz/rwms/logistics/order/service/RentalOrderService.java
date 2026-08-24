package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ExtendOrderRentalTermsRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderHistoryEventResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ReplaceOrderUnitRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderUnitDesiredEquipmentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.UpdateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.domain.AdditionalContact;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns rental-order commands and local versioned state; dependent shipment effects are delegated
 * through logistics workflow boundaries.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalOrderService {
  private final RentalOrderReadService reads;
  private final RentalOrderCreationService creation;
  private final RentalOrderLifecycleService lifecycle;
  private final RentalOrderReservationService reservations;
  private final RentalOrderTermsService terms;
  private final RentalOrderShipmentService shipments;
  private final RentalOrderUnitReplacementService replacements;

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
    return reads.list(
        actor,
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

  public OrderDetailResponse get(OrderActor actor, UUID orderId) {
    return reads.get(actor, orderId);
  }

  /**
   * Returns order-owned advisory delivery windows for the already authorized public presentation
   * association after verifying its client and fixed warehouse.
   */
  public List<DesiredDeliveryWindowResponse> presentationDesiredDeliveryWindows(
      UUID orderId, UUID expectedClientId, UUID expectedWarehouseId) {
    return reads.presentationDesiredDeliveryWindows(orderId, expectedClientId, expectedWarehouseId);
  }

  /**
   * Returns order-owned physical furniture surplus that a normal linked presentation may reuse
   * without counting the order's already desired quantities twice.
   */
  public Map<UUID, Long> presentationEquipmentSurplus(
      UUID orderId, UUID expectedClientId, UUID expectedWarehouseId) {
    return reads.presentationEquipmentSurplus(orderId, expectedClientId, expectedWarehouseId);
  }

  /** Rejects client replacement targets whose specific trip has already started. */
  public void requirePresentationReplacementTargetsPreStart(
      UUID orderId, List<UUID> targetUnitIds) {
    replacements.requirePresentationTargetsPreStart(orderId, targetUnitIds);
  }

  public OrderUnitPageResponse availableUnits(
      OrderActor actor, UUID orderId, int page, int size, String search) {
    return reads.availableUnits(actor, orderId, page, size, search);
  }

  public List<OrderHistoryEventResponse> history(OrderActor actor, UUID orderId) {
    return reads.history(actor, orderId);
  }

  @Transactional
  public CreateResult create(OrderActor actor, UUID idempotencyKey, CreateOrderRequest request) {
    RentalOrderCommandOutcome outcome = creation.create(actor, idempotencyKey, request);
    return new CreateResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult update(
      OrderActor actor, UUID orderId, UUID idempotencyKey, UpdateOrderRequest request) {
    RentalOrderCommandOutcome outcome = lifecycle.update(actor, orderId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  /**
   * Fixes an otherwise unassigned order warehouse only for the existing presentation-booking
   * orchestration. No general public warehouse-selection command is exposed.
   */
  @Transactional
  public MutationResult selectWarehouseForPresentation(
      OrderActor actor, UUID orderId, UUID idempotencyKey, long expectedVersion, UUID warehouseId) {
    RentalOrderCommandOutcome outcome =
        lifecycle.selectWarehouseForPresentation(
            actor, orderId, idempotencyKey, expectedVersion, warehouseId);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult removeUnit(
      OrderActor actor, UUID orderId, UUID unitId, long expectedVersion, UUID idempotencyKey) {
    RentalOrderCommandOutcome outcome =
        reservations.removeUnit(actor, orderId, unitId, expectedVersion, idempotencyKey);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  public MutationResult replaceUnit(
      OrderActor actor,
      UUID orderId,
      UUID oldRentalItemId,
      UUID idempotencyKey,
      ReplaceOrderUnitRequest request) {
    OrderDetailResponse response =
        replacements.replaceDirect(
            actor,
            orderId,
            request.expectedVersion(),
            oldRentalItemId,
            request.replacementRentalItemId(),
            request.reason(),
            idempotencyKey);
    return new MutationResult(response, false);
  }

  @Transactional
  public MutationResult setDesiredEquipment(
      OrderActor actor,
      UUID orderId,
      UUID unitId,
      UUID idempotencyKey,
      SetOrderUnitDesiredEquipmentRequest request) {
    RentalOrderCommandOutcome outcome =
        reservations.setDesiredEquipment(actor, orderId, unitId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  /**
   * Applies one confirmed normal presentation's per-cabin furniture selection, independently
   * selected client receiving days, delivery facts and initial duration to its existing target
   * order using the same editability and order-wide asset reservation boundary as manual editing.
   */
  @Transactional
  public MutationResult applyPresentationSelection(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.ConvertedPresentationHolds
          conversion,
      Map<UUID, Map<UUID, Long>> selectedRequirements,
      List<DesiredDeliveryWindow> desiredDeliveryWindows,
      long rentalMonths,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      List<AdditionalContact> additionalContacts,
      List<String> legacyDesiredDeliveryTimes) {
    RentalOrderCommandOutcome outcome =
        reservations.applyPresentationSelection(
            actor,
            orderId,
            idempotencyKey,
            conversion,
            selectedRequirements,
            desiredDeliveryWindows,
            rentalMonths,
            deliveryAddress,
            latitude,
            longitude,
            additionalContacts,
            legacyDesiredDeliveryTimes);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  /** Returns the authoritative all-unit furniture composition for atomic hold conversion. */
  @Transactional(readOnly = true)
  public List<
          dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway
              .OrderUnitEquipmentRequirements>
      presentationComposition(
          OrderActor actor, UUID orderId, Map<UUID, Map<UUID, Long>> selectedRequirements) {
    return reservations.presentationComposition(actor, orderId, selectedRequirements);
  }

  @Transactional
  public MutationResult extendRentalTerms(
      OrderActor actor, UUID orderId, UUID idempotencyKey, ExtendOrderRentalTermsRequest request) {
    RentalOrderCommandOutcome outcome =
        terms.extendRentalTerms(actor, orderId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult cancel(
      OrderActor actor, UUID orderId, long expectedVersion, UUID idempotencyKey) {
    RentalOrderCommandOutcome outcome =
        reservations.cancel(actor, orderId, expectedVersion, idempotencyKey);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult save(
      OrderActor actor,
      UUID orderId,
      long expectedVersion,
      UUID idempotencyKey,
      UUID correlationId) {
    RentalOrderCommandOutcome outcome =
        reservations.save(actor, orderId, expectedVersion, idempotencyKey, correlationId);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public LogisticsDocumentService.CreateResult createRentalShipment(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateOrderRentalShipmentRequest request) {
    return shipments.createRentalShipment(actor, orderId, idempotencyKey, correlationId, request);
  }

  public UUID rentalShipmentAdmissionWarehouse(
      OrderActor actor, UUID orderId, LocalDate scheduledDate) {
    return shipments.rentalShipmentAdmissionWarehouse(actor, orderId, scheduledDate);
  }

  /** Returns the exact prior shipment result without re-running mutable planning checks. */
  public LogisticsDocumentService.CreateResult replayRentalShipment(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      CreateOrderRentalShipmentRequest request) {
    return shipments.replayRentalShipment(actor, orderId, idempotencyKey, request);
  }

  @Transactional
  public LogisticsDocumentService.CreateResult createRentalShipment(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateOrderRentalShipmentRequest request,
      AdmissionTicket admission) {
    return shipments.createRentalShipment(
        actor, orderId, idempotencyKey, correlationId, request, admission);
  }

  /** Rental-order create result with stable idempotency replay truth. */
  public record CreateResult(OrderDetailResponse response, boolean replayed) {}

  /** Rental-order mutation result with stable command replay truth. */
  public record MutationResult(OrderDetailResponse response, boolean replayed) {}
}
