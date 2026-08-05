package dev.buhanzaz.rwms.logistics.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Public administrator recovery contract for one exhausted warehouse operation mark. */
public final class WarehouseOperationMarkRecoveryApiModels {
  private WarehouseOperationMarkRecoveryApiModels() {}

  public record WarehouseOperationMarkRecoveryRequest(
      @NotNull @Min(0) Long expectedRecoveryVersion,
      @NotBlank @Size(max = 2000) String reason) {}

  public record WarehouseOperationMarkRecoveryResponse(
      UUID warehouseId,
      UUID operationId,
      String state,
      int attemptCount,
      long recoveryVersion,
      String lastErrorCode,
      UUID recoveredBySubjectId,
      String recoveryReason,
      OffsetDateTime recoveredAt) {}
}
