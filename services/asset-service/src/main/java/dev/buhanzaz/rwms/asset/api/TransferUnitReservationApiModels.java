package dev.buhanzaz.rwms.asset.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Transport-only models for asset-owned cabin reservations of one inter-warehouse transfer.
 */
public final class TransferUnitReservationApiModels {
  private TransferUnitReservationApiModels() {}

  /** One exact cabin plus the authoritative planned composition it must satisfy at confirmation. */
  public record ConfirmTransferUnitReservationLine(
      @NotNull UUID lineId,
      @NotNull UUID rentalItemId,
      @Min(0) long expectedRentalItemVersion,
      @NotNull UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      @NotNull @Size(max = 100) List<@NotNull UUID> characteristicIds,
      Boolean linoleum) {}

  /** Confirms every selected cabin for one transfer as one all-or-nothing asset command. */
  public record ConfirmTransferUnitReservationsRequest(
      @NotNull UUID transferId,
      @NotNull UUID sourceWarehouseId,
      @NotEmpty @Size(max = 100) List<@Valid ConfirmTransferUnitReservationLine> lines) {}

  /** Fenced release input for one exact transfer-owned reservation row. */
  public record ReleaseTransferUnitReservationLine(
      @NotNull UUID reservationId,
      @NotNull UUID lineId,
      @NotNull UUID rentalItemId,
      @Min(0) long expectedReservationVersion) {}

  /** Releases a confirmed transfer batch before departure without changing cabin warehouses. */
  public record ReleaseTransferUnitReservationsRequest(
      @NotNull UUID transferId,
      @NotEmpty @Size(max = 100) List<@Valid ReleaseTransferUnitReservationLine> lines) {}

  /** Closed reservation lifecycle returned to logistics without exposing asset persistence details. */
  public enum TransferUnitReservationStateResponse {
    ACTIVE,
    RELEASED,
    CONSUMED
  }

  /** Current receipt for one transfer line and its canonical rental-item revision. */
  public record TransferUnitReservationLineReceipt(
      UUID reservationId,
      long version,
      UUID lineId,
      UUID rentalItemId,
      long currentRentalItemVersion,
      TransferUnitReservationStateResponse state) {}

  /** Deterministic batch receipt for one transfer command or its subject-bound replay. */
  public record TransferUnitReservationReceipt(
      UUID transferId, List<TransferUnitReservationLineReceipt> lines) {}
}
