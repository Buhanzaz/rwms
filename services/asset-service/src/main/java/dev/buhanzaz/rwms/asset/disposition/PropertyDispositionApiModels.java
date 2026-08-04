package dev.buhanzaz.rwms.asset.disposition;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MovementResponse;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Private maintenance boundary for an approved, physically fenced property disposition. */
public final class PropertyDispositionApiModels {
  private PropertyDispositionApiModels() {}

  public enum PropertyAssetKind {
    CABIN,
    EQUIPMENT
  }

  public enum PropertyDispositionKind {
    WRITE_OFF,
    LOSS
  }

  public enum PropertyDispositionContentsMode {
    MOVE_SELECTED_TO_STOCK,
    DISPOSE_WITH_CABIN
  }

  public enum PropertyDispositionFenceState {
    PREPARED,
    APPLIED
  }

  public record MaintenancePropertyContentSnapshot(
      UUID equipmentId,
      String equipmentName,
      String equipmentFormat,
      long balanceVersion,
      long quantity) {}

  public record MaintenancePropertyAssetSnapshot(
      PropertyAssetKind assetKind,
      UUID assetId,
      String assetDisplayName,
      UUID warehouseId,
      long version,
      Long sourceBalanceVersion,
      RentalItemStatus status,
      Long quantity,
      List<MaintenancePropertyContentSnapshot> contents,
      boolean activeReservation,
      boolean activeHold,
      boolean activeLease,
      boolean dispositionAllowed) {}

  public record MaintenancePropertyDispositionLeaseProof(
      @NotNull UUID leaseId,
      @NotNull @Min(1) Long fencingToken,
      @NotNull MaintenanceLeaseOwnerType ownerType,
      @NotNull UUID ownerId) {}

  public record PrepareMaintenancePropertyContentRequest(
      @NotNull UUID equipmentId,
      @NotNull @Min(0) Long expectedBalanceVersion,
      @NotNull @Min(1) Long currentQuantity,
      @NotNull @Min(0) Long moveQuantity) {}

  public record PrepareMaintenancePropertyDispositionRequest(
      @NotNull UUID warehouseId,
      @NotNull PropertyAssetKind assetKind,
      @NotNull UUID assetId,
      @NotNull PropertyDispositionKind disposition,
      @Min(0) Long expectedAssetVersion,
      @Min(0) Long expectedSourceBalanceVersion,
      @Min(1) Long quantity,
      PropertyDispositionContentsMode contentsMode,
      @NotNull
          @Size(max = 1000)
          List<@NotNull @Valid PrepareMaintenancePropertyContentRequest> contents,
      @Valid MaintenancePropertyDispositionLeaseProof authorizedMaintenanceLease) {}

  public record MaintenancePropertyDispositionPreparedContent(
      UUID equipmentId,
      UUID sourceBalanceId,
      long expectedBalanceVersion,
      long currentQuantity,
      long moveQuantity,
      long dispositionQuantity) {}

  public record MaintenancePropertyDispositionFence(
      UUID decisionId,
      PropertyDispositionFenceState state,
      String requestSha256,
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
      List<MaintenancePropertyDispositionPreparedContent> contents,
      OffsetDateTime preparedAt,
      OffsetDateTime appliedAt) {}

  public record ApplyMaintenancePropertyDispositionRequest(UUID completedMovementTaskId) {}

  public record MaintenancePropertyDispositionEffect(
      UUID effectId,
      UUID decisionId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
      Long assetVersion,
      List<MovementResponse> equipmentMovements,
      OffsetDateTime appliedAt) {}

  /** Internal result only; controllers map it to 201 or a permanent 200 replay. */
  public record CommandResult<T>(T response, boolean replayed) {}
}
