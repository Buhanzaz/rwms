package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class DriverTaskApiModels {
  private DriverTaskApiModels() {}

  public record CreateDriverTaskRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID cabinId,
      UUID repairId,
      @NotNull DriverTaskSourceType sourceType,
      @NotNull UUID sourceId,
      @NotNull DriverTaskKind kind,
      @NotNull DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate,
      @NotNull @Min(1) @Max(5) Integer priority,
      boolean activateNow,
      @Size(max = 1000) String comment) {
    @AssertTrue(message = "scheduledDate must be set only for FIXED_DATE")
    public boolean isPlanningValid() {
      return planningMode == null
          || (planningMode == DriverTaskPlanningMode.FIXED_DATE) == (scheduledDate != null);
    }

    @AssertTrue(message = "Repair delivery/removal requires repairId")
    public boolean isRepairContextValid() {
      return kind == null
          || (!kind.consumesRepairPlace() && !kind.releasesRepairPlace())
          || repairId != null;
    }

    @AssertTrue(message = "MANUAL source is allowed only for GENERAL_MOVEMENT and requires comment")
    public boolean isManualMovementValid() {
      if (sourceType == null || kind == null) return true;
      boolean manualMovement = sourceType == DriverTaskSourceType.MANUAL;
      if (manualMovement != (kind == DriverTaskKind.GENERAL_MOVEMENT)) return false;
      return !manualMovement || (comment != null && !comment.isBlank());
    }
  }

  public record DriverTaskResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      DriverTaskSourceType sourceType,
      UUID sourceId,
      DriverTaskKind kind,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate,
      int priority,
      String comment,
      String unitNumber,
      UUID driverQueueDefinitionId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      Long taskBoardTaskVersion,
      UUID taskBoardEntryId,
      String taskBoardEntryStatus,
      OffsetDateTime taskBoardDoneAt,
      DriverTaskState state,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion,
      UUID completionMediaId,
      Long completionMediaGeneration,
      boolean coverApplied,
      boolean repairPlaceEffectApplied,
      String failureCode,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      OffsetDateTime completedAt) {}
}
