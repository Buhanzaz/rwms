package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityShiftRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningCapacitySnapshotRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Produces the canonical fingerprint used to fence capacity-snapshot command replays. */
final class WarehouseCapacityChecksum {
  private WarehouseCapacityChecksum() {}

  /** Hashes the path owner and every sorted anonymous delivery fact with length delimiters. */
  static String sha256(
      UUID warehouseId,
      ReplacePlanningCapacitySnapshotRequest request,
      List<PlanningCapacityJobRequest> sortedJobs,
      List<PlanningCapacityShiftRequest> sortedShifts,
      List<PlanningCapacityIsochroneTariff> sortedTariffs) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      append(digest, "REPLACE_WAREHOUSE_CAPACITY");
      append(digest, warehouseId.toString());
      append(digest, Long.toString(request.sourceGeneration()));
      append(digest, request.sourceRevision());
      for (PlanningCapacityJobRequest job : sortedJobs) {
        append(digest, job.sourceJobId().toString());
        append(digest, job.taskType().name());
        append(digest, job.deliveryDate().toString());
        append(digest, decimal(job.latitude()));
        append(digest, decimal(job.longitude()));
        append(digest, Integer.toString(job.cabinCount()));
        append(digest, job.windowStart().toString());
        append(digest, job.windowEnd().toString());
        append(digest, Integer.toString(job.serviceMinutes()));
        append(digest, Boolean.toString(job.trailerAccessAllowed()));
        append(digest, Integer.toString(job.priority()));
        append(digest, Boolean.toString(job.mandatory()));
      }
      for (PlanningCapacityShiftRequest shift : sortedShifts) {
        append(digest, shift.sourceShiftId().toString());
        append(digest, shift.deliveryDate().toString());
        append(digest, shift.shiftStart().toString());
        append(digest, shift.shiftEnd().toString());
        append(digest, Integer.toString(shift.breakMinutes()));
        append(digest, Integer.toString(shift.cabinCapacity()));
      }
      for (PlanningCapacityIsochroneTariff tariff : sortedTariffs) {
        append(digest, Integer.toString(tariff.travelMinutes()));
        append(digest, Long.toString(tariff.priceRubles()));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String decimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static void append(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }
}
