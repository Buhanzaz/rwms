package dev.buhanzaz.rwms.logistics.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RentalOrderPaymentReservationTest {
  private static final OffsetDateTime START = OffsetDateTime.parse("2026-09-05T13:00:00Z");

  @Test
  void newWindowIsExactlyFiveMinutesAndRetriesNeverProlongIt() {
    RentalOrder order = saved();
    assertThat(order.startPaymentReservation(START)).isTrue();
    assertThat(order.startPaymentReservation(START.plusMinutes(1))).isFalse();
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.PENDING);
    assertThat(order.getPaymentStartedAt()).isEqualTo(START);
    assertThat(order.getPaymentExpiresAt()).isEqualTo(START.plusMinutes(5));
    assertThat(order.getPaymentResolvedAt()).isNull();
    assertThat(order.isPaymentConfirmedOrNotRequired()).isFalse();
    assertThatThrownBy(order::fulfill).hasMessage("Order payment is not confirmed");
  }

  @ParameterizedTest
  @EnumSource(RentalOrderPaymentSource.class)
  void explicitConfirmationWinsBeforeDeadlineAndCannotBeExpired(RentalOrderPaymentSource source) {
    RentalOrder order = saved();
    UUID actorId = UUID.randomUUID();
    order.startPaymentReservation(START);
    OffsetDateTime confirmedAt = START.plusMinutes(5).minusNanos(1_000);
    order.confirmPayment(source, actorId, confirmedAt);
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.CONFIRMED);
    assertThat(order.getPaymentResolvedAt()).isEqualTo(confirmedAt);
    assertThat(order.getPaymentSource()).isEqualTo(source);
    assertThat(order.getPaymentConfirmedBySubjectId()).isEqualTo(actorId);
    assertThat(order.beginPaymentExpiry(START.plusMinutes(6))).isFalse();
    assertThat(order.isPaymentConfirmedOrNotRequired()).isTrue();
    assertThat(order.fulfill()).isTrue();
  }

  @Test
  void deadlineRejectsConfirmationEvenBeforeAnExpiryWorkerClaimsTheOrder() {
    RentalOrder order = saved();
    order.startPaymentReservation(START);
    assertThatThrownBy(
            () ->
                order.confirmPayment(
                    RentalOrderPaymentSource.CUSTOMER_TEST,
                    UUID.randomUUID(),
                    START.plusMinutes(5)))
        .hasMessage("Payment reservation cannot be confirmed");
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.PENDING);
    assertThat(order.getPaymentResolvedAt()).isNull();
  }

  @Test
  void expiryFencesPaymentAndEditsBeforeRecordingActualReleaseCompletion() {
    RentalOrder order = saved();
    order.startPaymentReservation(START);
    assertThat(order.beginPaymentExpiry(START.plusMinutes(4))).isFalse();
    assertThat(order.beginPaymentExpiry(START.plusMinutes(5))).isTrue();
    assertThat(order.beginPaymentExpiry(START.plusMinutes(6))).isFalse();
    assertThat(order.getStatus()).isEqualTo(RentalOrderStatus.SAVED);
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.EXPIRING);
    assertThat(order.getPaymentResolvedAt()).isNull();
    assertThatThrownBy(order::requireEditable).hasMessageContaining("being released");
    assertThatThrownBy(
            () ->
                order.confirmPayment(
                    RentalOrderPaymentSource.MANAGER_CONFIRMATION,
                    UUID.randomUUID(),
                    START.plusMinutes(4)))
        .hasMessage("Payment reservation cannot be confirmed");
    order.completePaymentExpiry(START.plusMinutes(6));
    assertThat(order.getStatus()).isEqualTo(RentalOrderStatus.CANCELLED);
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.EXPIRED);
    assertThat(order.getPaymentResolvedAt()).isEqualTo(START.plusMinutes(6));
    assertThat(order.getPaymentSource()).isNull();
    assertThat(order.getPaymentConfirmedBySubjectId()).isNull();
  }

  @Test
  void expiryCannotClaimAReleaseBeforeItWasPrepared() {
    RentalOrder order = saved();
    order.startPaymentReservation(START);
    assertThatThrownBy(() -> order.completePaymentExpiry(START.plusMinutes(6)))
        .hasMessage("Payment reservation expiry is not pending");
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.PENDING);
  }

  @Test
  void ordinaryCancellationDoesNotInventAnExpiryNotificationOrPayment() {
    RentalOrder order = saved();
    order.startPaymentReservation(START);
    order.cancelSavedCustomerBooking();
    assertThat(order.getPaymentState()).isEqualTo(RentalOrderPaymentState.CANCELLED);
    assertThat(order.beginPaymentExpiry(START.plusMinutes(6))).isFalse();
    assertThat(order.getPaymentSource()).isNull();
  }

  @Test
  void historicalSavedOrderRetainsItsAdmissionWithoutInventingPaymentEvidence() {
    RentalOrder order = saved();
    assertThat(order.getPaymentState()).isNull();
    assertThat(order.getPaymentExpiresAt()).isNull();
    assertThat(order.beginPaymentExpiry(START.plusDays(1))).isFalse();
    assertThat(order.isPaymentConfirmedOrNotRequired()).isTrue();
    assertThat(order.fulfill()).isTrue();
    assertThat(order.getPaymentSource()).isNull();
  }

  private static RentalOrder saved() {
    UUID actor = UUID.randomUUID();
    RentalOrder order =
        RentalOrder.create(
            "PAY-" + actor.toString().substring(0, 8),
            mock(OrderClient.class),
            actor,
            "Manager",
            actor,
            "Manager",
            "RENTAL_MANAGER",
            "+79991234567",
            null,
            UUID.randomUUID(),
            "a".repeat(64));
    order.selectWarehouse(UUID.randomUUID());
    order.replaceClientDeliveryDetails("Delivery address", null, null, List.of());
    order.saveForFulfillment();
    return order;
  }
}
