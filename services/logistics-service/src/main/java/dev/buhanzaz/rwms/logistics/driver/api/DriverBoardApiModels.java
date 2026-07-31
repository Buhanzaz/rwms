package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DriverBoardApiModels {
  private DriverBoardApiModels() {}

  public record DriverBoardResponse(
      UUID warehouseId,
      UUID queueId,
      long queueVersion,
      int repairPlaceCount,
      long occupiedRepairPlaceCount,
      long availableRepairPlaceCount,
      boolean repairPlacesOverCapacity,
      List<DriverBoardCardResponse> current,
      List<DriverBoardDateColumnResponse> dates,
      List<CapitalRepairCardResponse> capitalRepairs) {
    public DriverBoardResponse {
      current = List.copyOf(current);
      dates = List.copyOf(dates);
      capitalRepairs = List.copyOf(capitalRepairs);
    }
  }

  public record DriverBoardDateColumnResponse(
      LocalDate date, List<DriverBoardCardResponse> tasks) {
    public DriverBoardDateColumnResponse {
      tasks = List.copyOf(tasks);
    }
  }

  public record DriverBoardCardResponse(
      UUID driverTaskId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      long taskBoardTaskVersion,
      UUID taskBoardEntryId,
      long taskBoardEntryVersion,
      String title,
      String taskText,
      String unitNumber,
      DriverTaskKind kind,
      DriverTaskState workflowState,
      String taskStatus,
      String entryStatus,
      LocalDate scheduledDate,
      String lane,
      int priority,
      boolean pinned,
      int position) {}

  public record CapitalRepairCardResponse(
      UUID repairId,
      long repairVersion,
      UUID cabinId,
      String unitNumber,
      int priority,
      String complexityName,
      String complexityColor,
      String plannedMinutes,
      boolean forcedCapital) {}

  public record MoveDriverBoardTaskRequest(
      @NotNull UUID warehouseId,
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull @Min(0) Long expectedEntryVersion,
      @NotNull LocalDate targetDate,
      @NotNull @Min(0) Integer targetIndex) {}

  public record PromoteCapitalRepairRequest(@NotNull UUID warehouseId) {}
}
