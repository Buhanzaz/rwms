package dev.buhanzaz.rwms.logistics.customer.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies persisted delivery feasibility and configured isochrone prices at the domain boundary. */
class CustomerDeliverySlotTest {

  @Test
  void acceptsAnyPositiveInformationalTravelZoneWithoutAPlanningCeiling() {
    assertDoesNotThrow(() -> offer(1));
    assertDoesNotThrow(() -> offer(4));
    assertDoesNotThrow(() -> offer(5));
    assertThrows(IllegalArgumentException.class, () -> offer(0));
  }

  @Test
  void provisionalOfferRequiresRouteAttestationsOnlyWhenHeld() {
    CustomerDeliverySlot offer = offer(1, false, false);

    assertThrows(
        IllegalStateException.class,
        () -> offer.hold(OffsetDateTime.now().plusHours(1), 0, false, false));

    CustomerDeliverySlot held = offer(1, false, false);
    held.hold(OffsetDateTime.now().plusHours(1), 0, true, true);

    assertDoesNotThrow(() -> held.protectCheckout(UUID.randomUUID(), null));
  }

  @Test
  void persistsTheExplicitFullDayChoiceWithoutMakingItsBoundsNullable() {
    CustomerDeliverySlot offer =
        CustomerDeliverySlot.offer(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 28),
            CustomerDeliverySlotKind.DURING_DAY,
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            "Москва, Тестовая улица, 1",
            new BigDecimal("55.750000"),
            new BigDecimal("37.610000"),
            1,
            3_600,
            1,
            0,
            2,
            10_000L,
            60,
            true,
            true,
            4.0,
            2.55,
            12.0,
            18.0,
            10.0,
            3,
            OffsetDateTime.parse("2026-08-27T09:00:00Z"));

    assertEquals(CustomerDeliverySlotKind.DURING_DAY, offer.getKind());
    assertEquals(LocalTime.of(9, 0), offer.getWindowStart());
    assertEquals(LocalTime.of(18, 0), offer.getWindowEnd());
    assertEquals(60, offer.getPriceIsochroneMinutes());
    assertEquals(10_000L, offer.getDeliveryPriceRubles());
  }

  @Test
  void acceptsConfiguredHourlyTierAndRejectsNonHourlyTier() {
    CustomerDeliverySlot ordinary = offerWithPrice(30_000L, 300);

    assertEquals(300, ordinary.getPriceIsochroneMinutes());
    assertEquals(null, ordinary.getPriceZoneId());
    assertThrows(IllegalArgumentException.class, () -> offerWithPrice(7_500L, 90));
  }

  private static CustomerDeliverySlot offer(int travelZoneHours) {
    return offer(travelZoneHours, true, true);
  }

  private static CustomerDeliverySlot offer(
      int travelZoneHours,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged) {
    return CustomerDeliverySlot.offer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 8, 28),
        CustomerDeliverySlotKind.FIXED_WINDOW,
        LocalTime.of(9, 0),
        LocalTime.of(12, 0),
        "Москва, Тестовая улица, 1",
        new BigDecimal("55.750000"),
        new BigDecimal("37.610000"),
        1,
        3_600,
        travelZoneHours,
        0,
        2,
        10_000L,
        60,
        privateSiteAccessConfirmed,
        failedTripChargeAcknowledged,
        4.0,
        2.55,
        12.0,
        18.0,
        10.0,
        3,
        OffsetDateTime.parse("2026-08-27T09:00:00Z"));
  }

  private static CustomerDeliverySlot offerWithPrice(
      Long deliveryPriceRubles, Integer priceIsochroneMinutes) {
    return CustomerDeliverySlot.offer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 8, 28),
        CustomerDeliverySlotKind.FIXED_WINDOW,
        LocalTime.of(9, 0),
        LocalTime.of(12, 0),
        "Москва, Тестовая улица, 1",
        new BigDecimal("55.750000"),
        new BigDecimal("37.610000"),
        1,
        7_200,
        2,
        0,
        2,
        deliveryPriceRubles,
        priceIsochroneMinutes,
        true,
        true,
        4.0,
        2.55,
        12.0,
        18.0,
        10.0,
        3,
        OffsetDateTime.parse("2026-08-27T09:00:00Z"));
  }
}
