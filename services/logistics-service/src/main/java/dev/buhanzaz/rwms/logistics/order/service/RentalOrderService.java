package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AddOrderUnitRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ExtendOrderRentalTermsRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderHistoryEventResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SelectWarehouseRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderRentalTermsRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SetOrderUnitDesiredEquipmentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.UpdateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns rental-order commands and local versioned state; dependent shipment effects are delegated through logistics workflow boundaries.
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

  public OrderUnitPageResponse availableUnits(
      OrderActor actor, UUID orderId, int page, int size, String search) {
    return reads.availableUnits(actor, orderId, page, size, search);
  }

  public List<OrderHistoryEventResponse> history(OrderActor actor, UUID orderId) {
    return reads.history(actor, orderId);
  }

  @Transactional
  public CreateResult create(
      OrderActor actor, UUID idempotencyKey, CreateOrderRequest request) {
    RentalOrderCommandOutcome outcome = creation.create(actor, idempotencyKey, request);
    return new CreateResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult update(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      UpdateOrderRequest request) {
    RentalOrderCommandOutcome outcome = lifecycle.update(actor, orderId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult selectWarehouse(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      SelectWarehouseRequest request) {
    RentalOrderCommandOutcome outcome =
        lifecycle.selectWarehouse(actor, orderId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult addUnit(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      AddOrderUnitRequest request) {
    RentalOrderCommandOutcome outcome = reservations.addUnit(actor, orderId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult removeUnit(
      OrderActor actor,
      UUID orderId,
      UUID unitId,
      long expectedVersion,
      UUID idempotencyKey) {
    RentalOrderCommandOutcome outcome =
        reservations.removeUnit(actor, orderId, unitId, expectedVersion, idempotencyKey);
    return new MutationResult(outcome.response(), outcome.replayed());
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

  @Transactional
  public MutationResult setRentalTerms(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      SetOrderRentalTermsRequest request) {
    RentalOrderCommandOutcome outcome = terms.setRentalTerms(actor, orderId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult extendRentalTerms(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      ExtendOrderRentalTermsRequest request) {
    RentalOrderCommandOutcome outcome =
        terms.extendRentalTerms(actor, orderId, idempotencyKey, request);
    return new MutationResult(outcome.response(), outcome.replayed());
  }

  @Transactional
  public MutationResult cancel(
      OrderActor actor,
      UUID orderId,
      long expectedVersion,
      UUID idempotencyKey) {
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

  public record CreateResult(OrderDetailResponse response, boolean replayed) {}

  public record MutationResult(OrderDetailResponse response, boolean replayed) {}
}
