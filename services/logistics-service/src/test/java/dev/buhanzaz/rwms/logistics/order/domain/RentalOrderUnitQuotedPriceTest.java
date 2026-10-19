package dev.buhanzaz.rwms.logistics.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalOrderUnitQuotedPriceTest {
  @Test
  void priceRemainsExactAcrossDurationChangesAndCabinReplacement() {
    RentalOrderUnitTerm term =
        RentalOrderUnitTerm.create(
            mock(RentalOrder.class),
            UUID.randomUUID(),
            2,
            new RentalOrderQuotedPrice(7L, Long.MAX_VALUE));
    UUID replacement = UUID.randomUUID();
    term.changeRentalMonths(3);
    term.transferToRentalItem(replacement);
    term.assignShipment(UUID.randomUUID(), LocalDate.of(2026, 9, 10));
    term.extend(1);
    assertThat(term.getRentalItemId()).isEqualTo(replacement);
    assertThat(term.getRentalMonths()).isEqualTo(4);
    assertThat(term.getPricingVersion()).isEqualTo(7L);
    assertThat(term.getMonthlyPriceRubles()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void unknownAndExplicitZeroAreDistinct() {
    RentalOrder order = mock(RentalOrder.class);
    RentalOrderUnitTerm unknown =
        RentalOrderUnitTerm.create(
            order, UUID.randomUUID(), 1, new RentalOrderQuotedPrice(null, null));
    RentalOrderUnitTerm zero =
        RentalOrderUnitTerm.create(order, UUID.randomUUID(), 1, new RentalOrderQuotedPrice(0L, 0L));
    assertThat(unknown.getMonthlyPriceRubles()).isNull();
    assertThat(unknown.getPricingVersion()).isNull();
    assertThat(zero.getMonthlyPriceRubles()).isZero();
    assertThat(zero.getPricingVersion()).isZero();
  }

  @Test
  void incompleteOrNegativeQuotesAreRejected() {
    assertThatThrownBy(() -> new RentalOrderQuotedPrice(null, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RentalOrderQuotedPrice(0L, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RentalOrderQuotedPrice(-1L, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RentalOrderQuotedPrice(0L, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> RentalOrderUnitTerm.create(mock(RentalOrder.class), UUID.randomUUID(), 1, null))
        .isInstanceOf(NullPointerException.class);
  }
}
