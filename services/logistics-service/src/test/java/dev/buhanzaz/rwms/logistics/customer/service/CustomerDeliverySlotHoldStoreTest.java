package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Covers the short fingerprint-fenced transaction used after route calculation. */
class CustomerDeliverySlotHoldStoreTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-27T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void unchangedWorkloadAtomicallyHoldsTheOfferAndCart() {
    UUID subjectId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 8, 29);
    CustomerDeliverySlot offered =
        CustomerDeliverySlot.offer(
            subjectId,
            inquiryId,
            warehouseId,
            date,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            "Москва",
            BigDecimal.valueOf(55.75),
            BigDecimal.valueOf(37.61),
            1,
            1_800,
            1,
            1,
            OffsetDateTime.parse("2026-08-27T08:10:00Z"));
    ReflectionTestUtils.setField(offered, "id", slotId);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
    ScenarioCapacityJobRepository generated = mock(ScenarioCapacityJobRepository.class);
    DriverLogisticsTaskRepository drivers = mock(DriverLogisticsTaskRepository.class);
    LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
    CustomerDeliverySlotHoldStore store =
        new CustomerDeliverySlotHoldStore(sessions, slots, generated, drivers, locks, CLOCK);
    when(sessions.selectSlot(subjectId, inquiryId, 4, slotId)).thenReturn(session);
    when(slots.findByIdForUpdate(slotId)).thenReturn(Optional.of(offered));
    when(slots.findCapacityWorkloadForUpdate(
            warehouseId,
            date,
            CustomerDeliverySlotState.CONFIRMED,
            CustomerDeliverySlotState.CHECKOUT_PENDING,
            CustomerDeliverySlotState.HELD,
            OffsetDateTime.parse("2026-08-27T08:00:00Z")))
        .thenReturn(List.of());
    when(generated.findCapacityWorkload(warehouseId, date)).thenReturn(List.of());
    when(slots.findHeldForUpdate(inquiryId, CustomerDeliverySlotState.HELD))
        .thenReturn(List.of());
    when(slots.saveAndFlush(offered)).thenReturn(offered);
    String fingerprint =
        CustomerCapacityWorkloadFingerprint.sha256(List.of(), List.of(), 0);

    CustomerDeliverySlotHoldStore.HeldSlot held =
        store.hold(
            new CustomerDeliverySlotHoldStore.HoldCommand(
                subjectId,
                inquiryId,
                slotId,
                0,
                4,
                warehouseId,
                date,
                fingerprint,
                1,
                Duration.ofMinutes(20)));

    assertThat(held.session()).isSameAs(session);
    assertThat(held.slot().getState()).isEqualTo(CustomerDeliverySlotState.HELD);
    assertThat(held.slot().getExpiresAt())
        .isEqualTo(OffsetDateTime.parse("2026-08-27T08:20:00Z"));
  }
}
