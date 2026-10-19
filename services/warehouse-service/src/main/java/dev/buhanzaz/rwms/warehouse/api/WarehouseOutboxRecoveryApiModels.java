package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Public administrator contract for reviewed recovery of a terminal warehouse outbox event. */
public final class WarehouseOutboxRecoveryApiModels {
  private WarehouseOutboxRecoveryApiModels() {}

  /**
   * Administrator review request that fences a terminal outbox-event recovery.
   *
   * @param expectedReviewVersion current event review version observed by the reviewer
   * @param reason durable human-readable recovery reason
   */
  public record WarehouseOutboxRecoveryRequest(
      @NotNull @Min(0) Long expectedReviewVersion,
      @NotBlank @Size(max = 2000) String reason) {}

  /**
   * Auditable outcome of a validated terminal outbox-event recovery.
   *
   * @param eventId immutable outbox event identity
   * @param aggregateType aggregate kind, always {@code WAREHOUSE}
   * @param aggregateId warehouse aggregate identity
   * @param aggregateVersion aggregate version carried by the immutable event
   * @param status resulting relay status
   * @param attemptCount current relay-attempt count
   * @param reviewVersion version after the recovery review
   * @param lastErrorCode prior relay failure code, if any
   * @param reviewedBySubjectId administrator who reviewed the recovery
   * @param recoveryReason durable recovery reason
   * @param recoveredAt database timestamp of the recovery
   */
  public record WarehouseOutboxRecoveryResponse(
      UUID eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String status,
      int attemptCount,
      long reviewVersion,
      String lastErrorCode,
      UUID reviewedBySubjectId,
      String recoveryReason,
      OffsetDateTime recoveredAt) {}
}
