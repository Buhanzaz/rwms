package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.AcknowledgeCustomerBookingChangeAlertRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeAlertResponse;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeAlertAcknowledgement;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeCharge;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.mapper.CustomerBookingChangeResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingChangeAlertAcknowledgementRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingChangeChargeRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingMutationRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completed changes fan out by current warehouse READ permission, with independent read receipts.
 */
@Service
@RequiredArgsConstructor
public class CustomerBookingChangeAlertService {
  private final CustomerBookingMutationRepository mutations;
  private final CustomerDeliverySlotRepository slots;
  private final CustomerBookingChangeChargeRepository charges;
  private final CustomerBookingChangeAlertAcknowledgementRepository acknowledgements;
  private final RentalOrderRepository orders;
  private final CustomerBookingChangeResponseMapper mapper;
  private final OrderAuthorizer access;
  private final LogisticsTransactionLock locks;
  private final Clock clock;

  /**
   * At most fifty unread changes; all dependent facts are fetched in bounded batches, not per row.
   */
  @Transactional(readOnly = true)
  public List<CustomerBookingChangeAlertResponse> list(OrderActor actor) {
    requireManager(actor);
    if (actor.readableWarehouses().isEmpty()) return List.of();
    var changes =
        mutations.findUnreadChanges(
            actor.subjectId(),
            actor.readableWarehouses(),
            CustomerBookingMutationState.COMPLETED,
            PageRequest.of(0, 50));
    if (changes.isEmpty()) return List.of();
    var slotIds = new HashSet<UUID>();
    changes.forEach(
        change -> {
          slotIds.add(change.getOldSlotId());
          if (change.getNewSlotId() != null) slotIds.add(change.getNewSlotId());
        });
    var slotMap =
        slots.findAllById(slotIds).stream()
            .collect(Collectors.toMap(CustomerDeliverySlot::getId, Function.identity()));
    var chargeMap =
        charges
            .findAllByMutationIdIn(changes.stream().map(change -> change.getId()).toList())
            .stream()
            .collect(
                Collectors.toMap(CustomerBookingChangeCharge::getMutationId, Function.identity()));
    var orderMap =
        orders
            .findAllById(changes.stream().map(change -> change.getOrderId()).distinct().toList())
            .stream()
            .collect(Collectors.toMap(RentalOrder::getId, Function.identity()));
    return changes.stream()
        .map(
            change -> {
              var original = slotMap.get(change.getOldSlotId());
              if (original == null)
                throw new IllegalStateException("Completed change has no original slot");
              var replacement = slotMap.get(change.getNewSlotId());
              if (change.getNewSlotId() != null && replacement == null) {
                throw new IllegalStateException("Completed reschedule has no replacement slot");
              }
              var charge = chargeMap.get(change.getId());
              var order = orderMap.get(change.getOrderId());
              Long fee =
                  charge == null
                      ? null
                      : charge.getSettlement() == CustomerChangeSettlement.WAIVED
                          ? Long.valueOf(0)
                          : charge.getAmountRubles();
              return mapper.toAlert(
                  change,
                  original,
                  replacement,
                  fee,
                  charge == null ? null : charge.getSettlement(),
                  order != null && access.isVisible(actor, order));
            })
        .toList();
  }

  /** An acknowledgement is UI state for this manager, not a booking/order command. */
  @Transactional
  public void acknowledge(
      OrderActor actor,
      UUID mutationId,
      UUID key,
      AcknowledgeCustomerBookingChangeAlertRequest request) {
    requireManager(actor);
    locks.acquire("customer-change-alert:" + actor.subjectId());
    var change =
        mutations.findById(mutationId).orElseThrow(CustomerBookingChangeChargeStore::notFound);
    var original =
        slots
            .findById(change.getOldSlotId())
            .orElseThrow(CustomerBookingChangeChargeStore::notFound);
    if (!actor.readableWarehouses().contains(original.getWarehouseId())
        || change.getState() != CustomerBookingMutationState.COMPLETED)
      throw CustomerBookingChangeChargeStore.notFound();
    var replay = acknowledgements.findByManagerIdAndIdempotencyKey(actor.subjectId(), key);
    if (replay.isPresent()) {
      if (!replay.get().getMutationId().equals(mutationId)
          || replay.get().getExpectedVersion() != request.expectedVersion()) {
        throw CustomerBookingChangeChargeStore.conflict(
            "IDEMPOTENCY_KEY_REUSED", "Ключ уже использован для другого уведомления");
      }
      return;
    }
    if (change.getVersion() != request.expectedVersion())
      throw CustomerBookingChangeChargeStore.conflict(
          "CUSTOMER_CHANGE_ALERT_VERSION_CONFLICT", "Уведомление изменилось. Обновите список");
    if (acknowledgements.findByManagerIdAndMutationId(actor.subjectId(), mutationId).isPresent())
      return;
    acknowledgements.saveAndFlush(
        CustomerBookingChangeAlertAcknowledgement.create(
            mutationId,
            actor.subjectId(),
            key,
            request.expectedVersion(),
            clock.instant().atOffset(ZoneOffset.UTC)));
  }

  private static void requireManager(OrderActor actor) {
    if (!"RENTAL_MANAGER".equals(actor.role()) || !actor.rentalAccess()) {
      throw new AccessDeniedException("Rental manager access is required");
    }
  }
}
