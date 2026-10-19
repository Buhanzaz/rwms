package dev.buhanzaz.rwms.logistics.inquiry.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Administrator request and durable receipt for rental-inquiry event delivery recovery. */
public final class RentalInquiryOutboxRecoveryApiModels {
  private RentalInquiryOutboxRecoveryApiModels() {}

  /** Identifies a reviewed command by its prior recovery version and reason. */
  public record RentalInquiryOutboxRecoveryRequest(
      @NotNull @Min(0) Long expectedRecoveryVersion, @NotBlank String reason) {}

  /** Preserves the accepted PENDING receipt even when an exact retry follows publication. */
  public record RentalInquiryOutboxRecoveryResponse(
      UUID eventId,
      String status,
      int attemptCount,
      long recoveryVersion,
      String lastErrorCode,
      UUID reviewedBySubjectId,
      String reason,
      OffsetDateTime reviewedAt) {}
}
