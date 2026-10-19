package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Canonicalizes every capacity-relevant local fact so a route matrix calculated outside a database
 * transaction is accepted only while its underlying workload is unchanged.
 */
final class CustomerCapacityWorkloadFingerprint {
  private CustomerCapacityWorkloadFingerprint() {}

  /** Excludes the current cart's replaceable hold because the final command releases it atomically. */
  static List<CustomerDeliverySlot> capacitySlots(
      List<CustomerDeliverySlot> slots, UUID subjectId, UUID inquiryId) {
    return slots.stream()
        .filter(
            slot ->
                slot.getState() != CustomerDeliverySlotState.HELD
                    || !subjectId.equals(slot.getCustomerSubjectId())
                    || !inquiryId.equals(slot.getInquiryId()))
        .toList();
  }

  /** Excludes the booking's current confirmed slot while evaluating an atomic replacement. */
  static List<CustomerDeliverySlot> capacitySlots(
      List<CustomerDeliverySlot> slots, UUID excludedSlotId) {
    return slots.stream()
        .filter(slot -> !slot.getId().equals(excludedSlotId))
        .toList();
  }

  /** Returns a stable SHA-256 over exact slots, simulator jobs and whole-day driver reservations. */
  static String sha256(
      List<CustomerDeliverySlot> slots,
      List<WarehouseCapacityJob> generatedJobs,
      List<WarehouseCapacityShift> shifts,
      WarehouseCapacitySnapshot snapshot,
      List<WarehouseCapacityIsochroneTariff> isochroneTariffs,
      List<WarehouseCapacityPriceZone> priceZones,
      List<WarehouseCapacityRestrictionZone> restrictionZones,
      long wholeDayDriverReservations) {
    StringBuilder value = new StringBuilder("drivers\u001f").append(wholeDayDriverReservations);
    if (snapshot == null) {
      value.append("\nsnapshot\u001fabsent");
    } else {
      value
          .append("\nsnapshot\u001f")
          .append(snapshot.getVersion())
          .append('\u001f')
          .append(snapshot.getSourceGeneration())
          .append('\u001f')
          .append(snapshot.getSourceRevision());
    }
    slots.stream()
        .sorted(Comparator.comparing(CustomerDeliverySlot::getId))
        .forEach(
            slot ->
                value
                    .append("\nslot\u001f")
                    .append(slot.getId())
                    .append('\u001f')
                    .append(slot.getVersion())
                    .append('\u001f')
                    .append(slot.getState())
                    .append('\u001f')
                    .append(slot.getDeliveryDate())
                    .append('\u001f')
                    .append(slot.getWindowStart())
                    .append('\u001f')
                    .append(slot.getWindowEnd())
                    .append('\u001f')
                    .append(decimal(slot.getLatitude()))
                    .append('\u001f')
                    .append(decimal(slot.getLongitude()))
                    .append('\u001f')
                    .append(slot.getCabinCount())
                    .append('\u001f')
                    .append(slot.getOneWayTravelSeconds())
                    .append('\u001f')
                    .append(slot.getSiteCabinCapacity())
                    .append('\u001f')
                    .append(slot.getDeliveryPriceRubles())
                    .append('\u001f')
                    .append(slot.getPriceZoneId())
                    .append('\u001f')
                    .append(slot.getPriceIsochroneMinutes())
                    .append('\u001f')
                    .append(slot.getExpiresAt()));
    generatedJobs.stream()
        .sorted(Comparator.comparing(WarehouseCapacityJob::getSourceJobId))
        .forEach(
            job ->
                value
                    .append("\nwarehouse-capacity\u001f")
                    .append(job.getSourceJobId())
                    .append('\u001f')
                    .append(job.getDeliveryDate())
                    .append('\u001f')
                    .append(job.getWindowStart())
                    .append('\u001f')
                    .append(job.getWindowEnd())
                    .append('\u001f')
                    .append(decimal(job.getLatitude()))
                    .append('\u001f')
                    .append(decimal(job.getLongitude()))
                    .append('\u001f')
                    .append(job.getCabinCount())
                    .append('\u001f')
                    .append(job.getServiceMinutes())
                    .append('\u001f')
                    .append(job.getTaskType())
                    .append('\u001f')
                    .append(job.isTrailerAccessAllowed())
                    .append('\u001f')
                    .append(job.getPriority())
                    .append('\u001f')
                    .append(job.isMandatory()));
    shifts.stream()
        .sorted(Comparator.comparing(WarehouseCapacityShift::getSourceShiftId))
        .forEach(
            shift ->
                value
                    .append("\nshift\u001f")
                    .append(shift.getSourceShiftId())
                    .append('\u001f')
                    .append(shift.getDeliveryDate())
                    .append('\u001f')
                    .append(shift.getShiftStart())
                    .append('\u001f')
                    .append(shift.getShiftEnd())
                    .append('\u001f')
                    .append(shift.getBreakMinutes())
                    .append('\u001f')
                    .append(shift.getCabinCapacity()));
    isochroneTariffs.stream()
        .sorted(Comparator.comparingInt(WarehouseCapacityIsochroneTariff::getTravelMinutes))
        .forEach(
            tariff ->
                value
                    .append("\nisochrone-tariff\u001f")
                    .append(tariff.getTravelMinutes())
                    .append('\u001f')
                    .append(tariff.getPriceRubles()));
    priceZones.stream()
        .sorted(Comparator.comparing(WarehouseCapacityPriceZone::getSourceZoneId))
        .forEach(
            zone ->
                value
                    .append("\nprice-zone\u001f")
                    .append(zone.getSourceZoneId())
                    .append('\u001f')
                    .append(zone.getSourceZoneVersion())
                    .append('\u001f')
                    .append(zone.getDeliveryPriceRubles())
                    .append('\u001f')
                    .append(zone.getPickupPriceRubles())
                    .append('\u001f')
                    .append(zone.getGeometryJson()));
    restrictionZones.stream()
        .sorted(Comparator.comparing(WarehouseCapacityRestrictionZone::getSourceZoneId))
        .forEach(
            zone ->
                value
                    .append("\nrestriction-zone\u001f")
                    .append(zone.getSourceZoneId())
                    .append('\u001f')
                    .append(zone.getSourceZoneVersion())
                    .append('\u001f')
                    .append(zone.getKind())
                    .append('\u001f')
                    .append(zone.getGeometryJson()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Preserves callers that predate exceptional delivery policy zones. */
  static String sha256(
      List<CustomerDeliverySlot> slots,
      List<WarehouseCapacityJob> generatedJobs,
      List<WarehouseCapacityShift> shifts,
      WarehouseCapacitySnapshot snapshot,
      List<WarehouseCapacityIsochroneTariff> isochroneTariffs,
      long wholeDayDriverReservations) {
    return sha256(
        slots,
        generatedJobs,
        shifts,
        snapshot,
        isochroneTariffs,
        List.of(),
        List.of(),
        wholeDayDriverReservations);
  }

  private static String decimal(java.math.BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
