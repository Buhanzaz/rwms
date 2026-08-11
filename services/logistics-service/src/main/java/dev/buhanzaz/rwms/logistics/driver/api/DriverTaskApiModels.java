package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AdditionalContactResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowResponse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Defines transport models for authenticated driver-task commands and views. */
public final class DriverTaskApiModels {
  private DriverTaskApiModels() {}

  /** Requested furniture line for one cabin in a driver trip. */
  public record DriverTripDesiredEquipmentResponse(
      UUID equipmentId, String equipmentName, long quantity) {}

  /** Current physical furniture line observed for one cabin in a driver trip. */
  public record DriverTripActualEquipmentResponse(
      UUID equipmentId, String equipmentName, long quantity, String locationKind) {}

  /** One immutable cabin member plus its live furniture preparation state. */
  public record DriverTripCabinResponse(
      UUID cabinId,
      String unitNumber,
      List<DriverTripDesiredEquipmentResponse> desiredContents,
      List<DriverTripActualEquipmentResponse> actualContents,
      boolean movementTaskCreated,
      boolean movementTaskCompleted,
      Boolean contentReady) {}

  /** Structured order and preparation facts needed to execute one grouped trip. */
  public record DriverTripDetailsResponse(
      String taskNumber,
      int tripNumber,
      String operationType,
      String clientName,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String primaryContactName,
      String primaryContactPhone,
      List<AdditionalContactResponse> additionalContacts,
      String comment,
      List<DesiredDeliveryWindowResponse> desiredDeliveryWindows,
      LocalDate scheduledDate,
      List<DriverTripCabinResponse> cabins) {}

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
      DriverTaskAudienceMode driverAudienceMode,
      UUID plannedDriverWorkerId,
      String plannedDriverNameSnapshot,
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
      OffsetDateTime completedAt,
      DriverTripDetailsResponse tripDetails) {}

  /**
   * Maintenance-facing result of looking up or compensating a repair movement.
   *
   * <p>The outcome is intentionally coarser than the workflow state: it tells maintenance whether
   * replacement/merge can continue, while the snapshot keeps the logistics and task-board fencing
   * values needed for a recoverable retry.
   */
  public enum MaintenanceDriverTaskCompensationOutcome {
    ABSENT,
    PENDING,
    CANCELLED,
    STARTED,
    COMPLETED,
    RECONCILIATION_REQUIRED
  }

  /**
   * Strict private snapshot for one repair-scoped driver task. Fields from a task-board read are
   * null when no external registration is known or no safe remote reconciliation was attempted.
   */
  public record MaintenanceDriverTaskCompensationResponse(
      UUID repairId,
      DriverTaskKind kind,
      MaintenanceDriverTaskCompensationOutcome outcome,
      UUID taskId,
      Long taskVersion,
      DriverTaskState state,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      Long taskBoardTaskVersion,
      UUID taskBoardEntryId,
      Long taskBoardEntryVersion,
      String taskBoardEntryStatus,
      String taskBoardStatus,
      String taskBoardLane,
      OffsetDateTime taskBoardDoneAt,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) {}
}
