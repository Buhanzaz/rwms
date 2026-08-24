package dev.buhanzaz.rwms.logistics.planning.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Transport models for the private, service-authenticated route-planning boundary. */
public final class PlanningIntegrationApiModels {
  private PlanningIntegrationApiModels() {}

  /** Explicit planner intent for a concrete driver or a future warehouse-driver pool task. */
  public enum PlanningDriverAudienceMode {
    ASSIGNED_DRIVER,
    WAREHOUSE_DRIVERS
  }

  /** One client-approved delivery day exported to the route planner. */
  public record PlanningDateOption(LocalDate date, int priority, boolean isHard) {}

  /** One unscheduled rental-order remainder exported without contact or furniture details. */
  public record PlanningRequestResponse(
      UUID orderId,
      long orderVersion,
      String orderNumber,
      String clientName,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      int quantity,
      List<UUID> unitIds,
      List<PlanningDateOption> dateOptions,
      OffsetDateTime createdAt) {}

  /** Warehouse-scoped deterministic planning feed and its authoritative timezone. */
  public record PlanningRequestFeedResponse(
      UUID warehouseId,
      String timeZone,
      OffsetDateTime generatedAt,
      List<PlanningRequestResponse> requests) {}

  /** One planner-selected shipment bound to an exact order version and concrete cabins. */
  public record PlanningAssignmentRequest(
      @NotNull UUID orderId,
      @NotNull @Min(0) Long expectedOrderVersion,
      @NotNull LocalDate scheduledDate,
      PlanningDriverAudienceMode driverAudienceMode,
      UUID driverWorkerId,
      @NotBlank @Size(max = 512) String driverName,
      @NotNull @Size(min = 1, max = 2) List<@NotNull UUID> unitIds) {
    public PlanningAssignmentRequest {
      if (driverAudienceMode == null) {
        driverAudienceMode = PlanningDriverAudienceMode.ASSIGNED_DRIVER;
      }
    }

    public PlanningAssignmentRequest(
        UUID orderId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          expectedOrderVersion,
          scheduledDate,
          PlanningDriverAudienceMode.ASSIGNED_DRIVER,
          driverWorkerId,
          driverName,
          unitIds);
    }
  }

  /** Idempotent plan application command; plan identity participates in stable command keys. */
  public record ApplyPlanningAssignmentsRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID planId,
      @NotNull @Min(1) Long planVersion,
      @NotNull @Size(max = 500) List<@NotNull @Valid PlanningAssignmentRequest> assignments) {}

  /** Successfully created or replayed logistics document for one planned order part. */
  public record AppliedPlanningAssignment(UUID orderId, UUID documentId, boolean replayed) {}

  /** Current RWMS assignment and driver-task projection for one planner-created shipment part. */
  public record PlanningAssignmentStatus(
      UUID orderId,
      UUID documentId,
      LocalDate scheduledDate,
      List<UUID> unitIds,
      PlanningDriverAudienceMode driverAudienceMode,
      UUID driverWorkerId,
      String driverName,
      String taskState) {}

  /** Date-bounded assignment status snapshot consumed by the standalone planning UI. */
  public record PlanningAssignmentStatusResponse(
      UUID warehouseId, LocalDate date, List<PlanningAssignmentStatus> assignments) {}

  /** Rejected assignment with a stable machine code and safe operator-facing message. */
  public record RejectedPlanningAssignment(UUID orderId, String code, String message) {}

  /** Complete non-silent result of applying a planner batch. */
  public record ApplyPlanningAssignmentsResponse(
      List<AppliedPlanningAssignment> applied, List<RejectedPlanningAssignment> rejected) {}
}
