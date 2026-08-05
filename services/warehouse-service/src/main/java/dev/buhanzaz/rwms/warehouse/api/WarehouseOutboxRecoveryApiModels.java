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

  public record WarehouseOutboxRecoveryRequest(
      @NotNull @Min(0) Long expectedReviewVersion,
      @NotBlank @Size(max = 2000) String reason) {}

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
