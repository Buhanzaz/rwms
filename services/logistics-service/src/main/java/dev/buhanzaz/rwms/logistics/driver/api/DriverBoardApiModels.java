package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTripDetailsResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Defines transport models for the authenticated driver board HTTP boundary. */
public final class DriverBoardApiModels {
  private DriverBoardApiModels() {}

  public enum DriverBoardLane {
    SCHEDULED,
    CURRENT
  }

  public record DriverBoardResponse(
      UUID warehouseId,
      LocalDate currentDate,
      UUID queueId,
      long queueVersion,
      int repairPlaceCount,
      long usedRepairPlaceCount,
      long occupiedRepairPlaceCount,
      long availableRepairPlaceCount,
      boolean inboundRepairPlaceAvailable,
      int automaticRefillDelayMinutes,
      boolean repairPlacesOverCapacity,
      List<DriverBoardRepairPlaceCardResponse> repairPlaces,
      List<DriverBoardCardResponse> current,
      List<DriverBoardDateColumnResponse> dates,
      List<CapitalRepairCardResponse> capitalRepairs) {
    public DriverBoardResponse {
      repairPlaces = List.copyOf(repairPlaces);
      current = List.copyOf(current);
      dates = List.copyOf(dates);
      capitalRepairs = List.copyOf(capitalRepairs);
    }
  }

  /** Read-only repair-place fact assembled by logistics for the public driver board. */
  public record DriverBoardRepairPlaceCardResponse(
      UUID repairId,
      UUID cabinId,
      String unitNumber,
      String allocationState,
      String repairStageName,
      String repairStageState,
      int priority) {}

  /** One calendar column containing whole-task board cards in authoritative queue order. */
  public record DriverBoardDateColumnResponse(LocalDate date, List<DriverBoardCardResponse> tasks) {
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
      DriverTaskAudienceResponse driverAudience,
      DriverTaskState workflowState,
      String taskStatus,
      String entryStatus,
      LocalDate scheduledDate,
      String lane,
      int priority,
      boolean pinned,
      int position,
      DriverTripDetailsResponse tripDetails) {}

  /** Planned WorkerApp audience; only ASSIGNED_DRIVER carries a driver identity. */
  public record DriverTaskAudienceResponse(
      DriverTaskAudienceMode mode, UUID workerId, String workerName) {}

  public record CapitalRepairCardResponse(
      UUID repairId,
      long repairVersion,
      UUID cabinId,
      long assetVersion,
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
      @NotNull DriverBoardLane targetLane,
      @NotNull LocalDate targetDate,
      @NotNull @Min(0) Integer targetIndex) {}

  public record PromoteCapitalRepairRequest(@NotNull UUID warehouseId) {}

  public record ScheduleCapitalRepairRequest(
      @NotNull UUID warehouseId,
      @NotNull LocalDate targetDate,
      @NotNull @Min(0) Integer targetIndex) {}

  public record ReturnCapitalRepairRequest(
      @NotNull UUID warehouseId, @NotNull @Min(0) Long expectedTaskVersion) {}
}
