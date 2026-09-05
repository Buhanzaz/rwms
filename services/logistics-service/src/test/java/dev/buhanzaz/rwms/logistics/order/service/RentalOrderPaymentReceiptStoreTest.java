package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderRentalItem;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitReservation;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderQuotedPrice;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData.Kind;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderPaymentReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.pricing.service.EquipmentRentalPriceSnapshot;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class RentalOrderPaymentReceiptStoreTest {
  private static final UUID ORDER = UUID.randomUUID();
  private static final UUID WAREHOUSE = UUID.randomUUID();
  private static final UUID CABIN = UUID.randomUUID();
  private static final UUID EQUIPMENT = UUID.randomUUID();
  private final RentalOrderPaymentReceiptRepository receipts =
      mock(RentalOrderPaymentReceiptRepository.class);
  private final RentalOrderUnitTermRepository terms = mock(RentalOrderUnitTermRepository.class);
  private final RentalOrderEquipmentRequirementRepository requirements =
      mock(RentalOrderEquipmentRequirementRepository.class);
  private final RentalPricingStore pricing = mock(RentalPricingStore.class);
  private final CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
  private final RentalOrder order = mock(RentalOrder.class);
  private final OrderUnitReservation unit = mock(OrderUnitReservation.class);
  private final RentalOrderPaymentReceiptStore store =
      new RentalOrderPaymentReceiptStore(
          receipts,
          terms,
          requirements,
          pricing,
          slots,
          JsonMapper.builder().findAndAddModules().build());

  @BeforeEach
  void setUp() {
    when(order.getId()).thenReturn(ORDER);
    when(order.getOrderNumber()).thenReturn("ORD-1");
    when(order.getWarehouseId()).thenReturn(WAREHOUSE);
    when(order.getCreatedByRole()).thenReturn("RENTAL_MANAGER");
    when(unit.orderId()).thenReturn(ORDER);
    when(unit.unitId()).thenReturn(CABIN);
    when(unit.state()).thenReturn("ACTIVE");
    OrderRentalItem item = mock(OrderRentalItem.class);
    when(item.id()).thenReturn(CABIN);
    when(item.number()).thenReturn("БК-1");
    when(unit.unit()).thenReturn(item);
    when(terms.findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(eq(ORDER), any()))
        .thenReturn(
            List.of(
                RentalOrderUnitTerm.create(
                    order, CABIN, 3, new RentalOrderQuotedPrice(2L, 1000L))));
    when(requirements.findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(ORDER))
        .thenReturn(
            List.of(RentalOrderEquipmentRequirement.create(order, CABIN, EQUIPMENT, "Стул", 2)));
    when(pricing.readEquipmentForReceipt())
        .thenReturn(new EquipmentRentalPriceSnapshot(7, Map.of(EQUIPMENT, 200L)));
    when(receipts.currentDatabaseTimestamp()).thenReturn(Instant.parse("2026-09-05T16:01:00Z"));
  }

  @Test
  void combinesQuotedCabinWithPerUnitMonthlyFurnitureAndDoesNotInventDelivery() {
    var data = store.capture(order, List.of(unit));
    assertThat(data.totalRubles()).isEqualTo("4200");
    assertThat(data.deliveryIncluded()).isFalse();
    assertThat(data.lines())
        .extracting(line -> line.kind())
        .containsExactly(Kind.CABIN, Kind.FURNITURE);
    assertThat(data.lines().getFirst().pricingVersion()).isEqualTo(2);
    assertThat(data.lines().getLast().pricingVersion()).isEqualTo(7);
    assertThat(data.lines().getLast().quantity()).isEqualTo("2");
    assertThat(data.lines().getLast().rentalMonths()).isEqualTo(3);
    assertThat(data.lines().getLast().amountRubles()).isEqualTo("1200");
  }

  @Test
  void includesAcceptedCustomerDeliveryOnceWithoutMultiplyingByMonths() {
    when(order.getCreatedByRole()).thenReturn("CUSTOMER");
    CustomerDeliverySlot slot = mock(CustomerDeliverySlot.class);
    when(slot.getState()).thenReturn(CustomerDeliverySlotState.CHECKOUT_PENDING);
    when(slot.getWarehouseId()).thenReturn(WAREHOUSE);
    when(slot.getCabinCount()).thenReturn(1);
    when(slot.getDeliveryPriceRubles()).thenReturn(5000L);
    when(slots.findCheckoutQuoteByOrderId(ORDER)).thenReturn(Optional.of(slot));
    var data = store.capture(order, List.of(unit));
    assertThat(data.totalRubles()).isEqualTo("9200");
    assertThat(data.deliveryIncluded()).isTrue();
    assertThat(data.lines().getLast().rentalMonths()).isNull();
    assertThat(data.lines().getLast().amountRubles()).isEqualTo("5000");
    when(slot.getState()).thenReturn(CustomerDeliverySlotState.RELEASED);
    assertUnavailable();
  }

  @Test
  void replayReadsStoredBillWithoutConsultingTariffsOrCurrentComposition() {
    var captured = store.capture(order, List.of(unit));
    var argument = ArgumentCaptor.forClass(RentalOrderPaymentReceipt.class);
    verify(receipts).saveAndFlush(argument.capture());
    when(receipts.findByOrder_Id(ORDER)).thenReturn(Optional.of(argument.getValue()));
    org.mockito.Mockito.clearInvocations(pricing, terms, requirements, slots);
    assertThat(store.capture(order, List.of())).isEqualTo(captured);
    assertThat(store.find(ORDER)).contains(captured);
    org.mockito.Mockito.verifyNoInteractions(pricing, terms, requirements, slots);
  }

  @Test
  void missingCabinPriceOrCustomerDeliveryCannotIssueAZeroBill() {
    when(order.getCreatedByRole()).thenReturn("CUSTOMER");
    assertUnavailable();
    when(order.getCreatedByRole()).thenReturn("RENTAL_MANAGER");
    when(terms.findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(eq(ORDER), any()))
        .thenReturn(
            List.of(
                RentalOrderUnitTerm.create(
                    order, CABIN, 3, new RentalOrderQuotedPrice(null, null))));
    assertUnavailable();
    verify(receipts, never()).saveAndFlush(any());
  }

  @Test
  void unknownReceiptIsAbsentAndWrongOrDuplicateUnitsCannotBeBilled() {
    assertThat(store.find(ORDER)).isEmpty();
    assertThatThrownBy(() -> store.capture(order, List.of(unit, unit)))
        .isInstanceOf(OrderProblemException.class);
    when(unit.orderId()).thenReturn(UUID.randomUUID());
    assertUnavailable();
    verify(receipts, never()).saveAndFlush(any());
  }

  private void assertUnavailable() {
    assertThatThrownBy(() -> store.capture(order, List.of(unit)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("ORDER_RECEIPT_PRICE_UNAVAILABLE"));
  }
}
