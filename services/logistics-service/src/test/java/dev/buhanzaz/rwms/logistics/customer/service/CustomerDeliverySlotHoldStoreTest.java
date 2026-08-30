package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
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
            CustomerDeliverySlotKind.FIXED_WINDOW,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            "Москва",
            BigDecimal.valueOf(55.75),
            BigDecimal.valueOf(37.61),
            1,
            1_800,
            1,
            1,
            2,
            10_000L,
            60,
            false,
            false,
            4.0,
            2.55,
            12.0,
            18.0,
            10.0,
            3,
            OffsetDateTime.parse("2026-08-27T08:10:00Z"));
    ReflectionTestUtils.setField(offered, "id", slotId);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
    WarehouseCapacityJobRepository generated = mock(WarehouseCapacityJobRepository.class);
    WarehouseCapacityShiftRepository shifts = mock(WarehouseCapacityShiftRepository.class);
    WarehouseCapacityIsochroneTariffRepository isochroneTariffs =
        mock(WarehouseCapacityIsochroneTariffRepository.class);
    WarehouseCapacitySnapshotRepository snapshots =
        mock(WarehouseCapacitySnapshotRepository.class);
    DriverLogisticsTaskRepository drivers = mock(DriverLogisticsTaskRepository.class);
    CustomerDeliveryCapacityFence capacityFence = mock(CustomerDeliveryCapacityFence.class);
    CustomerDeliverySlotHoldStore store =
        new CustomerDeliverySlotHoldStore(
            sessions,
            slots,
            generated,
            shifts,
            isochroneTariffs,
            snapshots,
            drivers,
            capacityFence,
            CLOCK);
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
    when(shifts.findCapacityShifts(warehouseId, date)).thenReturn(List.of());
    WarehouseCapacitySnapshot snapshot =
        WarehouseCapacitySnapshot.create(
            warehouseId,
            1,
            "a".repeat(64),
            List.of(),
            List.of(),
            tariffFacts(10_000),
            OffsetDateTime.parse("2026-08-27T07:00:00Z"));
    when(isochroneTariffs.findTariffs(warehouseId))
        .thenReturn(snapshot.getIsochroneTariffs());
    when(snapshots.findByWarehouseId(warehouseId)).thenReturn(Optional.of(snapshot));
    when(slots.findHeldForUpdate(inquiryId, CustomerDeliverySlotState.HELD))
        .thenReturn(List.of());
    when(slots.saveAndFlush(offered)).thenReturn(offered);
    String fingerprint =
        CustomerCapacityWorkloadFingerprint.sha256(
            List.of(),
            List.of(),
            List.of(),
            snapshot,
            snapshot.getIsochroneTariffs(),
            0);

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
                true,
                true,
                Duration.ofMinutes(20)));

    assertThat(held.session()).isSameAs(session);
    assertThat(held.slot().getState()).isEqualTo(CustomerDeliverySlotState.HELD);
    assertThat(held.slot().getExpiresAt())
        .isEqualTo(OffsetDateTime.parse("2026-08-27T08:20:00Z"));
    assertThat(held.slot().isPrivateSiteAccessConfirmed()).isTrue();
    assertThat(held.slot().isFailedTripChargeAcknowledged()).isTrue();
    org.mockito.Mockito.verify(capacityFence)
        .acquireDayAndWarehouseCapacity(warehouseId, date);
  }

  @Test
  void isochroneTariffFactsParticipateInTheFinalWorkloadFence() {
    UUID warehouseId = UUID.randomUUID();
    WarehouseCapacitySnapshot defaults =
        WarehouseCapacitySnapshot.create(
            warehouseId,
            1,
            "a".repeat(64),
            List.of(),
            List.of(),
            tariffFacts(10_000),
            OffsetDateTime.parse("2026-08-27T07:00:00Z"));
    WarehouseCapacitySnapshot custom =
        WarehouseCapacitySnapshot.create(
            warehouseId,
            1,
            "a".repeat(64),
            List.of(),
            List.of(),
            tariffFacts(11_000),
            OffsetDateTime.parse("2026-08-27T07:00:00Z"));

    String baseline =
        CustomerCapacityWorkloadFingerprint.sha256(
            List.of(),
            List.of(),
            List.of(),
            defaults,
            defaults.getIsochroneTariffs(),
            0);
    String changedTariff =
        CustomerCapacityWorkloadFingerprint.sha256(
            List.of(),
            List.of(),
            List.of(),
            custom,
            custom.getIsochroneTariffs(),
            0);

    assertThat(changedTariff).isNotEqualTo(baseline);
  }

  private static List<WarehouseCapacityIsochroneTariff.Facts> tariffFacts(long firstPrice) {
    return List.of(
        new WarehouseCapacityIsochroneTariff.Facts(60, firstPrice),
        new WarehouseCapacityIsochroneTariff.Facts(120, 15_000),
        new WarehouseCapacityIsochroneTariff.Facts(180, 20_000),
        new WarehouseCapacityIsochroneTariff.Facts(240, 25_000));
  }
}
