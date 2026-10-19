package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityPriceZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityRestrictionZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the short final delivery-slot transaction after Valhalla routing has completed. It locks the
 * cart, offer and local-day workload, rejects a changed workload fingerprint, and atomically
 * replaces the cart's former hold.
 */
@Service
@RequiredArgsConstructor
class CustomerDeliverySlotHoldStore {
  private final CustomerRentalSessionStore sessions;
  private final CustomerDeliverySlotRepository slots;
  private final WarehouseCapacityJobRepository warehouseCapacityJobs;
  private final WarehouseCapacityShiftRepository warehouseCapacityShifts;
  private final WarehouseCapacityIsochroneTariffRepository warehouseCapacityIsochroneTariffs;
  private final WarehouseCapacityPriceZoneRepository warehouseCapacityPriceZones;
  private final WarehouseCapacityRestrictionZoneRepository warehouseCapacityRestrictionZones;
  private final WarehouseCapacitySnapshotRepository warehouseCapacitySnapshots;
  private final DriverLogisticsTaskRepository driverTasks;
  private final CustomerDeliveryCapacityFence capacityFence;
  private final Clock clock;

  /** Commits a route decision only if every local capacity input still matches its calculation. */
  @Transactional
  HeldSlot hold(HoldCommand command) {
    capacityFence.acquireDayAndWarehouseCapacity(command.warehouseId(), command.deliveryDate());
    CustomerRentalSession session =
        sessions.selectSlot(
            command.subjectId(),
            command.inquiryId(),
            command.expectedCartVersion(),
            command.slotId());
    CustomerDeliverySlot offered =
        slots
            .findByIdForUpdate(command.slotId())
            .orElseThrow(CustomerDeliverySlotHoldStore::notFound);
    OffsetDateTime now = now();
    if (!command.subjectId().equals(offered.getCustomerSubjectId())
        || !command.inquiryId().equals(offered.getInquiryId())
        || !command.warehouseId().equals(offered.getWarehouseId())
        || !command.deliveryDate().equals(offered.getDeliveryDate())) {
      throw notFound();
    }
    if (offered.getVersion() != command.expectedSlotVersion()
        || offered.getState() != CustomerDeliverySlotState.OFFERED
        || !offered.getExpiresAt().isAfter(now)) {
      throw expired();
    }
    List<CustomerDeliverySlot> workload =
        CustomerCapacityWorkloadFingerprint.capacitySlots(
            slots.findCapacityWorkloadForUpdate(
                command.warehouseId(),
                command.deliveryDate(),
                CustomerDeliverySlotState.CONFIRMED,
                CustomerDeliverySlotState.CHECKOUT_PENDING,
                CustomerDeliverySlotState.HELD,
                now),
            command.subjectId(),
            command.inquiryId());
    List<WarehouseCapacityJob> generated =
        warehouseCapacityJobs
            .findCapacityWorkload(command.warehouseId(), command.deliveryDate())
            .stream()
            .filter(job -> job.getTaskType() == WarehouseCapacityTaskType.DELIVERY)
            .toList();
    List<WarehouseCapacityShift> shifts =
        warehouseCapacityShifts.findCapacityShifts(command.warehouseId(), command.deliveryDate());
    List<WarehouseCapacityIsochroneTariff> isochroneTariffs =
        warehouseCapacityIsochroneTariffs.findTariffs(command.warehouseId());
    List<WarehouseCapacityPriceZone> priceZones =
        warehouseCapacityPriceZones.findTariffZones(command.warehouseId());
    List<WarehouseCapacityRestrictionZone> restrictionZones =
        warehouseCapacityRestrictionZones.findRestrictionZones(command.warehouseId());
    WarehouseCapacitySnapshot snapshot =
        warehouseCapacitySnapshots.findByWarehouseId(command.warehouseId()).orElse(null);
    long reservations =
        driverTasks.countWholeDayDeliveryReservations(
            command.warehouseId(), command.deliveryDate());
    String currentFingerprint =
        CustomerCapacityWorkloadFingerprint.sha256(
            workload,
            generated,
            shifts,
            snapshot,
            isochroneTariffs,
            priceZones,
            restrictionZones,
            reservations);
    if (!command.workloadSha256().equals(currentFingerprint)) throw taken();

    for (CustomerDeliverySlot prior :
        slots.findHeldForUpdate(
            command.inquiryId(), CustomerDeliverySlotState.HELD)) {
      if (!prior.getId().equals(offered.getId())
          && command.subjectId().equals(prior.getCustomerSubjectId())) {
        prior.release();
        slots.save(prior);
      }
    }
    offered.hold(
        now.plus(command.holdLifetime()),
        command.capacityRemaining(),
        command.privateSiteAccessConfirmed(),
        command.failedTripChargeAcknowledged());
    return new HeldSlot(session, slots.saveAndFlush(offered));
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_DELIVERY_SLOT_NOT_FOUND", "Слот доставки не найден");
  }

  private static OrderProblemException expired() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_DELIVERY_SLOT_EXPIRED",
        "Слот уже изменился; выберите доступное время заново");
  }

  private static OrderProblemException taken() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_DELIVERY_SLOT_TAKEN",
        "Нагрузка изменилась; выберите доступное время заново");
  }

  /** Immutable inputs from one externally calculated route decision. */
  record HoldCommand(
      UUID subjectId,
      UUID inquiryId,
      UUID slotId,
      long expectedSlotVersion,
      long expectedCartVersion,
      UUID warehouseId,
      LocalDate deliveryDate,
      String workloadSha256,
      int capacityRemaining,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged,
      Duration holdLifetime) {}

  /** Atomically persisted cart and slot returned to the customer boundary. */
  record HeldSlot(CustomerRentalSession session, CustomerDeliverySlot slot) {}
}
