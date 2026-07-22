package dev.buhanzaz.rwms.logistics.equipment.api;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLineState;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class EquipmentMovementTaskApiModels {
  private EquipmentMovementTaskApiModels() {}

  public record CreateEquipmentMovementTaskRequest(
      @NotNull UUID warehouseId,
      UUID targetWarehouseId,
      @Size(max = 64) String unitNumber,
      @Min(1) Integer plannedDurationMinutes,
      @NotNull @Future OffsetDateTime deadlineAt,
      @NotNull @Size(min = 1, max = 100) List<@Valid EquipmentMovementLineRequest> lines) {
    public CreateEquipmentMovementTaskRequest(
        UUID warehouseId,
        String unitNumber,
        Integer plannedDurationMinutes,
        OffsetDateTime deadlineAt,
        List<EquipmentMovementLineRequest> lines) {
      this(warehouseId, null, unitNumber, plannedDurationMinutes, deadlineAt, lines);
    }
  }

  public record EquipmentMovementLineRequest(
      @NotNull UUID equipmentId,
      UUID sourceRentalItemId,
      @NotNull EquipmentMovementLocationKind sourceLocationKind,
      @NotNull @Min(0) Long expectedSourceBalanceVersion,
      UUID targetRentalItemId,
      @NotNull EquipmentMovementLocationKind targetLocationKind,
      @NotNull @Min(1) Long quantity) {}

  public record CancelEquipmentMovementTaskRequest(@NotNull @Min(0) Long expectedVersion) {}

  public record EquipmentMovementTaskLineResponse(
      UUID id,
      long version,
      int lineNumber,
      UUID equipmentId,
      String equipmentCode,
      String equipmentName,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      EquipmentMovementLocationKind sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      EquipmentMovementLocationKind targetLocationKind,
      long quantity,
      UUID reservationId,
      Long reservationVersion,
      EquipmentMovementLineState state,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record EquipmentMovementTaskResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      Long taskBoardTaskVersion,
      OffsetDateTime taskBoardDoneAt,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      EquipmentMovementTaskState state,
      EquipmentMovementTaskState terminalState,
      String failureCode,
      List<EquipmentMovementTaskLineResponse> lines,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
}
