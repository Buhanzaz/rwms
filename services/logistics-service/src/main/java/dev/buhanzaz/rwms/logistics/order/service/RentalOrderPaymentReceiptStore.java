package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitReservation;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData.Kind;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData.Line;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderPaymentReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingStore;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Issues the initial non-fiscal bill under the caller's authorized order lock, without HTTP.
 * Cabin prices are original offers; furniture uses one detached tariff revision. Reads never
 * reconstruct or reprice a missing receipt. Confirmation and real money collection are separate.
 */
@Service
@RequiredArgsConstructor
public class RentalOrderPaymentReceiptStore {
  private final RentalOrderPaymentReceiptRepository receipts;
  private final RentalOrderUnitTermRepository terms;
  private final RentalOrderEquipmentRequirementRepository requirements;
  private final RentalPricingStore pricing;
  private final CustomerDeliverySlotRepository deliverySlots;
  private final ObjectMapper json;

  /** Caller must own the order write lock and validate the complete active unit snapshot first. */
  @Transactional(propagation = Propagation.MANDATORY)
  public RentalOrderReceiptData capture(RentalOrder order, List<OrderUnitReservation> activeUnits) {
    var existing = receipts.findByOrder_Id(order.getId());
    if (existing.isPresent()) return decode(existing.get());
    if (activeUnits == null || activeUnits.isEmpty()) throw unavailable();
    Map<UUID, OrderUnitReservation> units = new LinkedHashMap<>();
    for (var unit : activeUnits) {
      if (unit == null
          || unit.unitId() == null
          || unit.unit() == null
          || !order.getId().equals(unit.orderId())
          || !"ACTIVE".equals(unit.state())
          || !unit.unitId().equals(unit.unit().id())
          || unit.unit().number() == null
          || unit.unit().number().isBlank()
          || units.putIfAbsent(unit.unitId(), unit) != null) throw unavailable();
    }
    Map<UUID, RentalOrderUnitTerm> termByCabin =
        terms
            .findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(order.getId(), units.keySet())
            .stream()
            .collect(Collectors.toMap(RentalOrderUnitTerm::getRentalItemId, term -> term));
    if (!termByCabin.keySet().equals(units.keySet())
        || termByCabin.values().stream()
            .anyMatch(
                term -> term.getPricingVersion() == null || term.getMonthlyPriceRubles() == null))
      throw unavailable();
    var tariff = pricing.readEquipmentForReceipt();
    List<Line> lines = new ArrayList<>();
    var equipment =
        requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
            order.getId());
    for (var unit :
        units.values().stream()
            .sorted(Comparator.comparing(OrderUnitReservation::unitId))
            .toList()) {
      var term = termByCabin.get(unit.unitId());
      lines.add(
          line(
              Kind.CABIN,
              unit.unitId(),
              null,
              "Бытовка № " + unit.unit().number(),
              1,
              term.getRentalMonths(),
              term.getMonthlyPriceRubles(),
              term.getPricingVersion()));
      for (var item : equipment) {
        if (item.getRentalItemId().equals(unit.unitId()) && item.getQuantity() > 0) {
          lines.add(
              line(
                  Kind.FURNITURE,
                  unit.unitId(),
                  item.getEquipmentId(),
                  item.getEquipmentName(),
                  item.getQuantity(),
                  term.getRentalMonths(),
                  tariff.equipmentRates().getOrDefault(item.getEquipmentId(), 0L),
                  tariff.version()));
        }
      }
    }
    var delivery = deliverySlots.findCheckoutQuoteByOrderId(order.getId());
    if (delivery.isEmpty() && "CUSTOMER".equals(order.getCreatedByRole())) throw unavailable();
    delivery.ifPresent(
        slot -> {
          if (!Set.of(
                      CustomerDeliverySlotState.CHECKOUT_PENDING,
                      CustomerDeliverySlotState.CONFIRMED)
                  .contains(slot.getState())
              || !order.getWarehouseId().equals(slot.getWarehouseId())
              || slot.getCabinCount() != units.size()
              || slot.getDeliveryPriceRubles() == null
              || slot.getDeliveryPriceRubles() < 0) throw unavailable();
          lines.add(
              line(
                  Kind.DELIVERY,
                  null,
                  null,
                  "Доставка",
                  1,
                  null,
                  slot.getDeliveryPriceRubles(),
                  null));
        });
    OffsetDateTime issuedAt = receipts.currentDatabaseTimestamp().atOffset(ZoneOffset.UTC);
    String total =
        lines.stream()
            .map(value -> new BigInteger(value.amountRubles()))
            .reduce(BigInteger.ZERO, BigInteger::add)
            .toString();
    var data =
        new RentalOrderReceiptData(
            1,
            order.getId(),
            order.getOrderNumber(),
            issuedAt,
            "RUB",
            delivery.isPresent(),
            lines,
            total);
    receipts.saveAndFlush(
        RentalOrderPaymentReceipt.issue(order, issuedAt, json.writeValueAsString(data)));
    return data;
  }

  /** Read-only lookup for an already-authorized order; historical missing bills remain absent. */
  @Transactional(readOnly = true)
  public Optional<RentalOrderReceiptData> find(UUID orderId) {
    return receipts.findByOrder_Id(orderId).map(this::decode);
  }

  private RentalOrderReceiptData decode(RentalOrderPaymentReceipt receipt) {
    var data = json.readValue(receipt.getReceiptJson(), RentalOrderReceiptData.class);
    if (!data.orderId().equals(receipt.getOrder().getId())
        || !data.issuedAt().isEqual(receipt.getIssuedAt())) {
      throw new IllegalStateException("Stored payment receipt identity is inconsistent");
    }
    return data;
  }

  private static Line line(
      Kind kind,
      UUID cabinId,
      UUID equipmentId,
      String label,
      long quantity,
      Long months,
      long unitPrice,
      Long pricingVersion) {
    String amount =
        BigInteger.valueOf(quantity)
            .multiply(BigInteger.valueOf(unitPrice))
            .multiply(BigInteger.valueOf(months == null ? 1 : months))
            .toString();
    return new Line(
        kind,
        cabinId,
        equipmentId,
        label,
        Long.toString(quantity),
        months,
        Long.toString(unitPrice),
        amount,
        pricingVersion);
  }

  private static OrderProblemException unavailable() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "ORDER_RECEIPT_PRICE_UNAVAILABLE",
        "Нельзя выставить чек: цена или согласованный состав заказа не зафиксированы");
  }
}
