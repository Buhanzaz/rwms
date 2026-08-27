package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacityJob;
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

  /** Returns a stable SHA-256 over exact slots, simulator jobs and whole-day driver reservations. */
  static String sha256(
      List<CustomerDeliverySlot> slots,
      List<ScenarioCapacityJob> generatedJobs,
      long wholeDayDriverReservations) {
    StringBuilder value = new StringBuilder("drivers\u001f").append(wholeDayDriverReservations);
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
                    .append(slot.getExpiresAt()));
    generatedJobs.stream()
        .sorted(Comparator.comparing(ScenarioCapacityJob::getSourceJobId))
        .forEach(
            job ->
                value
                    .append("\nscenario\u001f")
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
                    .append(job.getServiceMinutes()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String decimal(java.math.BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
