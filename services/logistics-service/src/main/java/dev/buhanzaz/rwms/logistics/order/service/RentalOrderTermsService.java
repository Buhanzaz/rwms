package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ExtendOrderRentalTermsRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderRentalTermExtensionInput;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns the post-shipment rental-term extension command. Initial terms are fixed by the normal
 * client-presentation confirmation before a cabin can enter shipment planning.
 */
@Service
@RequiredArgsConstructor
class RentalOrderTermsService {
  private static final String EXTEND_RENTAL_TERMS = "EXTEND_RENTAL_TERMS";

  private final RentalOrderCommandStore store;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final OrderAuditService audit;
  private final OrderAuthorizer access;
  private final RentalOrderReadService reads;
  private final LogisticsDocumentService documents;

  RentalOrderCommandOutcome extendRentalTerms(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      ExtendOrderRentalTermsRequest request) {
    Map<UUID, Long> requested = rentalTermExtensionValues(request.terms());
    List<String> checksumValues = new ArrayList<>();
    checksumValues.add(orderId.toString());
    checksumValues.add(Long.toString(request.expectedVersion()));
    requested.forEach(
        (unitId, months) -> {
          checksumValues.add(unitId.toString());
          checksumValues.add(Long.toString(months));
        });
    String checksum = OrderCommandChecksum.sha256(EXTEND_RENTAL_TERMS, checksumValues);
    OrderCommandReceipt replay =
        store.replay(actor, EXTEND_RENTAL_TERMS, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }

    RentalOrder order = store.lockedOrder(orderId);
    access.requireRentalTermExtension(actor, order);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    List<RentalOrderUnitTerm> terms =
        rentalTerms.findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(
            orderId, requested.keySet());
    if (terms.size() != requested.size()) {
      throw RentalOrderProblems.conflict(
          "ORDER_RENTAL_TERM_NOT_FOUND", "Срок аренды бытовки не найден");
    }
    Map<UUID, RentalOrderUnitTerm> termsByUnit =
        terms.stream()
            .collect(Collectors.toMap(RentalOrderUnitTerm::getRentalItemId, value -> value));
    for (Map.Entry<UUID, Long> entry : requested.entrySet()) {
      RentalOrderUnitTerm term = termsByUnit.get(entry.getKey());
      if (term == null
          || term.getRentalShipmentId() == null
          || term.getShipmentDate() == null
          || term.getReturnDate() == null
          || !documents.isRentalShipmentShipped(term.getRentalShipmentId())) {
        throw RentalOrderProblems.conflict(
            "ORDER_RENTAL_TERM_NOT_SHIPPED",
            "Продлить можно только уже отгруженную бытовку");
      }
      term.extend(entry.getValue());
    }
    rentalTerms.saveAllAndFlush(terms);
    order.recordRentalTermExtension();
    store.persist(order);
    changed(order, actor, "rentalTerms");
    store.remember(actor, EXTEND_RENTAL_TERMS, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(
        reads.detail(order, actor, reads.readUnits(order)), false);
  }

  private static Map<UUID, Long> rentalTermExtensionValues(
      List<OrderRentalTermExtensionInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new IllegalArgumentException("Rental term extensions are required");
    }
    Map<UUID, Long> values = new LinkedHashMap<>();
    for (OrderRentalTermExtensionInput input : inputs) {
      if (input == null
          || input.unitId() == null
          || input.additionalMonths() == null
          || input.additionalMonths() < 1
          || values.putIfAbsent(input.unitId(), input.additionalMonths()) != null) {
        throw new IllegalArgumentException("Rental term extensions are invalid");
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
}
