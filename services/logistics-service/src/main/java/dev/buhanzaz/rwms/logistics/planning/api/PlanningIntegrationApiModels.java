package dev.buhanzaz.rwms.logistics.planning.api;

import dev.buhanzaz.rwms.logistics.domain.CustomerDeliveryPurpose;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsRetentionDataset;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Transport models for the private, service-authenticated route-planning boundary. */
public final class PlanningIntegrationApiModels {
  private PlanningIntegrationApiModels() {}

  /** Explicit planner intent for a concrete driver or a future warehouse-driver pool task. */
  public enum PlanningDriverAudienceMode {
    ASSIGNED_DRIVER,
    WAREHOUSE_DRIVERS
  }

  /** Distinguishes automatic route output from an explicit dispatcher contractor hand-off. */
  public enum PlanningAssignmentType {
    ROUTE_PLAN,
    CONTRACTOR_HANDOFF
  }

  /** Human outcome that authorizes one dispatcher-owned customer commitment change. */
  public enum PlanningRescheduleDecisionCode {
    CUSTOMER_AGREED_RECOMMENDED,
    CUSTOMER_AGREED_ALTERNATIVE
  }

  /** Task-board-owned employment category relevant to route-resource selection. */
  public enum PlanningDriverEmploymentType {
    STAFF,
    CONTRACTOR
  }

  /** Provenance of guaranteed or incoming operational availability at the requested warehouse. */
  public enum PlanningDriverAvailabilityKind {
    HOME,
    ACTIVE_ASSIGNMENT,
    INCOMING
  }

  /** Distinguishes primary outbound deliveries from secondary return-leg pickups. */
  public enum PlanningCapacityTaskType {
    DELIVERY,
    PICKUP
  }

  /** Exceptional polygon semantics accepted by customer delivery planning. */
  public enum PlanningCapacityRestrictionKind {
    FORBIDDEN,
    NO_TRAILER
  }

  /**
   * One client-approved delivery day exported to the route planner. Fixed CustomerApp bookings
   * carry hard bounds; a DURING_DAY booking and manager-entered date-only choices remain soft and
   * omit bounds. Customer bookings retain their informational depot travel-time ring in either
   * form.
   */
  public record PlanningDateOption(
      LocalDate date,
      int priority,
      boolean isHard,
      LocalTime windowStart,
      LocalTime windowEnd,
      Integer travelZoneHours) {
    /** Preserves the existing date-only constructor for non-customer planning sources. */
    public PlanningDateOption(LocalDate date, int priority, boolean isHard) {
      this(date, priority, isHard, null, null, null);
    }
  }

  /** Concrete reserved cabin and its owner-authoritative physical warehouse before shipment. */
  public record PlanningUnitReservation(
      @NotNull UUID unitId, @NotNull UUID inventorySourceWarehouseId) {}

  /**
   * One unscheduled rental-order remainder exported with the minimum customer contact facts needed
   * for dispatcher actions. Confirmed CustomerApp delivery pricing is included as nullable
   * planning metadata; the logistics owner remains authoritative for both the amount and its
   * isochrone tier.
   */
  public record PlanningRequestResponse(
      UUID orderId,
      long orderVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceRevision,
      @NotNull CustomerDeliveryPurpose customerDeliveryPurpose,
      String orderNumber,
      String clientName,
      @NotNull ClientType clientType,
      String contactName,
      String contactPhone,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      int quantity,
      List<UUID> unitIds,
      List<PlanningUnitReservation> unitReservations,
      List<PlanningDateOption> dateOptions,
      Boolean trailerAccessAllowed,
      Long deliveryPriceRubles,
      Integer priceIsochroneMinutes,
      OffsetDateTime createdAt) {}

  /** Warehouse-scoped deterministic planning feed and its authoritative timezone. */
  public record PlanningRequestFeedResponse(
      UUID warehouseId,
      String timeZone,
      OffsetDateTime generatedAt,
      List<PlanningRequestResponse> requests) {}

  /** Minimum normalized customer facts shown while a dispatcher coordinates a reschedule. */
  public record PlanningCustomerContact(
      @NotNull ClientType clientType, String clientName, String contactName, String contactPhone) {}

  /** Current confirmed slot or one short-lived route-feasible replacement offer. */
  public record PlanningRescheduleSlot(
      UUID slotId,
      long slotVersion,
      LocalDate date,
      String kind,
      LocalTime windowStart,
      LocalTime windowEnd,
      Long deliveryPriceRubles,
      OffsetDateTime expiresAt) {}

  /** Fenced order commitment plus all currently feasible replacement offers. */
  public record PlanningOrderRescheduleOptionsResponse(
      UUID orderId,
      long orderVersion,
      UUID sessionId,
      long sessionVersion,
      UUID bookingId,
      UUID warehouseId,
      PlanningCustomerContact customer,
      PlanningRescheduleSlot currentSlot,
      List<PlanningRescheduleSlot> options) {}

  /** Applies a customer-confirmed replacement selected from the current planning offer set. */
  public record PlanningOrderRescheduleRequest(
      @NotNull @Min(0) Long expectedOrderVersion,
      @NotNull @Min(0) Long expectedSessionVersion,
      @NotNull UUID slotId,
      @NotNull @Min(0) Long slotVersion,
      @NotNull PlanningRescheduleDecisionCode decisionCode,
      @NotNull UUID decisionActorSubjectId,
      @NotBlank @Size(max = 2_000) String decisionReason,
      @Valid PlanningPublishedAssignmentWithdrawalRequest publishedPlanWithdrawal) {
    /** Preserves the ordinary unassigned-booking command used before published-plan withdrawal. */
    public PlanningOrderRescheduleRequest(
        Long expectedOrderVersion,
        Long expectedSessionVersion,
        UUID slotId,
        Long slotVersion,
        PlanningRescheduleDecisionCode decisionCode,
        UUID decisionActorSubjectId,
        String decisionReason) {
      this(
          expectedOrderVersion,
          expectedSessionVersion,
          slotId,
          slotVersion,
          decisionCode,
          decisionActorSubjectId,
          decisionReason,
          null);
    }
  }

  /** Exact old-day planner shipment removed only through the durable pre-start recovery saga. */
  public record PlanningPublishedAssignmentRemovalRequest(
      @NotNull UUID documentId,
      @NotNull UUID externalTaskId,
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull UUID serviceWarehouseId,
      @NotNull LocalDate scheduledDate,
      @NotNull @Size(min = 1, max = 2) List<@NotNull UUID> unitIds) {
    public PlanningPublishedAssignmentRemovalRequest {
      unitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
    }
  }

  /**
   * Complete old-day lineage revision retained after one member moves to a new customer date or is
   * authoritatively cancelled. A reschedule target remains unassigned demand and is never silently
   * published by this command.
   */
  public record PlanningPublishedAssignmentWithdrawalRequest(
      @NotNull UUID sourcePlanId,
      @NotNull @Min(1) Long expectedSourcePlanVersion,
      @NotNull @Min(2) Long replacementPlanVersion,
      @NotNull UUID warehouseId,
      @NotNull LocalDate date,
      @NotNull @Valid PlanningPublishedAssignmentRemovalRequest removedAssignment,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningAssignmentReplacementRequest> remainingAssignments,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningDriverShiftPlanRequest> driverShiftPlans) {
    public PlanningPublishedAssignmentWithdrawalRequest {
      remainingAssignments =
          remainingAssignments == null ? List.of() : List.copyOf(remainingAssignments);
      driverShiftPlans = driverShiftPlans == null ? List.of() : List.copyOf(driverShiftPlans);
    }
  }

  /** Durable old-day tombstone confirmed before either recovery endpoint returns success. */
  public record PlanningPublishedAssignmentWithdrawalResult(
      UUID sourcePlanId,
      long sourcePlanVersion,
      UUID removedExternalTaskId,
      long removedTaskVersion,
      String state) {}

  /** Resulting server-owned commitment after an idempotent dispatcher reschedule. */
  public record PlanningOrderRescheduleResponse(
      UUID orderId,
      long orderVersion,
      UUID sessionId,
      long sessionVersion,
      UUID bookingId,
      UUID warehouseId,
      PlanningRescheduleSlot confirmedSlot,
      PlanningPublishedAssignmentWithdrawalResult publishedPlanWithdrawal) {
    /** Preserves ordinary unassigned-booking responses without a published-plan tombstone. */
    public PlanningOrderRescheduleResponse(
        UUID orderId,
        long orderVersion,
        UUID sessionId,
        long sessionVersion,
        UUID bookingId,
        UUID warehouseId,
        PlanningRescheduleSlot confirmedSlot) {
      this(
          orderId,
          orderVersion,
          sessionId,
          sessionVersion,
          bookingId,
          warehouseId,
          confirmedSlot,
          null);
    }
  }

  /** Existing shared warehouse work that may fill otherwise idle time at the base. */
  public record PlanningBaseTaskResponse(
      UUID taskId,
      UUID externalTaskId,
      DriverTaskKind kind,
      String unitNumber,
      String summary,
      LocalDate scheduledDate,
      int priority,
      DriverTaskState state) {}

  /** Version-fenced refresh or invalidation of an existing shared-task ETA preview. */
  public record PlanningProvisionalEtaUpdateRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull UUID sourcePlanId,
      @NotNull @Min(1) Long sourcePlanVersion,
      OffsetDateTime provisionalEta) {}

  /** Current durable version and optional preview after a planner refresh. */
  public record PlanningProvisionalEtaResponse(
      UUID taskId,
      long taskVersion,
      UUID externalTaskId,
      OffsetDateTime provisionalEta,
      UUID sourcePlanId,
      Long sourcePlanVersion) {}

  /** One terminal online dataset count produced without deleting or archiving any row. */
  public record PlanningRetentionCandidateCount(
      LogisticsRetentionDataset dataset, OffsetDateTime cutoff, long rowCount, long activeHolds) {}

  /** Approved policy and current terminal-row counts from a non-destructive retention dry run. */
  public record PlanningRetentionDryRunResponse(
      OffsetDateTime generatedAt,
      long businessAuditProofDays,
      long eventOnlineDays,
      long eventArchiveDays,
      long gpsTelemetryDays,
      boolean deletionEnabled,
      boolean dualApprovalRequired,
      List<PlanningRetentionCandidateCount> candidates) {}

  /** Places a durable legal hold over a complete dataset or one bounded scope key. */
  public record PlacePlanningRetentionLegalHoldRequest(
      @NotNull LogisticsRetentionDataset dataset,
      @Size(max = 256) String scopeKey,
      @NotBlank @Size(max = 2_000) String reason) {}

  /** Releases one legal hold under its optimistic fence while retaining the full history. */
  public record ReleasePlanningRetentionLegalHoldRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 2_000) String reason) {}

  /** Current durable legal-hold projection. */
  public record PlanningRetentionLegalHoldResponse(
      UUID id,
      long version,
      LogisticsRetentionDataset dataset,
      String scopeKey,
      String reason,
      UUID placedBySubjectId,
      OffsetDateTime placedAt,
      UUID releasedBySubjectId,
      OffsetDateTime releasedAt,
      String releaseReason) {}

  /** Records an already-created immutable encrypted object in the private archive. */
  public record RecordPlanningArchiveManifestRequest(
      @NotNull LogisticsRetentionDataset dataset,
      @NotNull OffsetDateTime periodStart,
      @NotNull OffsetDateTime periodEnd,
      @NotBlank @Size(max = 1_000) String objectKey,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
      @NotNull @Min(0) Long rowCount) {}

  /** Independently verifies one stored archive checksum under its manifest fence. */
  public record VerifyPlanningArchiveManifestRequest(
      @NotNull @Min(0) Long expectedVersion) {}

  /** Durable archive object identity; it never exposes a public download URL. */
  public record PlanningArchiveManifestResponse(
      UUID id,
      long version,
      LogisticsRetentionDataset dataset,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      String sha256,
      long rowCount,
      String state,
      UUID createdBySubjectId,
      OffsetDateTime createdAt,
      UUID verifiedBySubjectId,
      OffsetDateTime verifiedAt) {}

  /** One anonymous simulator transport task that participates in an exact local-day schedule. */
  public record PlanningCapacityJobRequest(
      @NotNull UUID sourceJobId,
      @NotNull PlanningCapacityTaskType taskType,
      @NotNull LocalDate deliveryDate,
      @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") @Digits(integer = 2, fraction = 6)
          BigDecimal latitude,
      @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") @Digits(integer = 3, fraction = 6)
          BigDecimal longitude,
      @Min(1) int cabinCount,
      @NotNull LocalTime windowStart,
      @NotNull LocalTime windowEnd,
      @Min(1) int serviceMinutes,
      boolean trailerAccessAllowed,
      @Min(0) int priority,
      boolean mandatory) {
    public PlanningCapacityJobRequest {
      if (windowStart != null && windowEnd != null && !windowStart.isBefore(windowEnd)) {
        throw new IllegalArgumentException("Planning capacity window is invalid");
      }
    }

  }

  /** One anonymous simulator driver shift available to serve an exact warehouse-local day. */
  public record PlanningCapacityShiftRequest(
      @NotNull UUID sourceShiftId,
      @NotNull LocalDate deliveryDate,
      @NotNull LocalTime shiftStart,
      @NotNull LocalTime shiftEnd,
      @Min(0) @Max(720) int breakMinutes,
      @Min(1) @Max(2) int cabinCapacity) {
    public PlanningCapacityShiftRequest {
      if (shiftStart != null && shiftEnd != null && !shiftStart.isBefore(shiftEnd)) {
        throw new IllegalArgumentException("Planning capacity shift is invalid");
      }
      if (shiftStart != null
          && shiftEnd != null
          && breakMinutes >= java.time.Duration.between(shiftStart, shiftEnd).toMinutes()) {
        throw new IllegalArgumentException("Planning capacity shift break consumes the shift");
      }
    }
  }

  /** One configured hourly road-time tariff; the greatest tier is the delivery boundary. */
  public record PlanningCapacityIsochroneTariff(
      @Min(60) @Max(720) int travelMinutes, @Min(0) long priceRubles) {
    public PlanningCapacityIsochroneTariff {
      if (travelMinutes < 60 || travelMinutes > 720 || travelMinutes % 60 != 0) {
        throw new IllegalArgumentException(
            "Planning isochrone travel minutes must be a whole hour from 60 to 720");
      }
      if (priceRubles < 0) {
        throw new IllegalArgumentException("Planning isochrone price must be non-negative");
      }
    }
  }

  /** Strict GeoJSON MultiPolygon shared by tariff and route-restriction facts. */
  public record PlanningGeoJsonMultiPolygon(
      @NotBlank String type,
      @NotNull List<List<List<List<Double>>>> coordinates) {
    public PlanningGeoJsonMultiPolygon {
      if (!"MultiPolygon".equals(type)) {
        throw new IllegalArgumentException("Planning zone geometry must be a MultiPolygon");
      }
      validateCoordinates(coordinates);
    }

    private static void validateCoordinates(List<List<List<List<Double>>>> polygons) {
      if (polygons == null || polygons.isEmpty()) {
        throw new IllegalArgumentException("Planning zone geometry is empty");
      }
      for (List<List<List<Double>>> polygon : polygons) {
        if (polygon == null || polygon.isEmpty()) {
          throw new IllegalArgumentException("Planning zone polygon is empty");
        }
        for (List<List<Double>> ring : polygon) {
          if (ring == null || ring.size() < 4) {
            throw new IllegalArgumentException("Planning zone ring is invalid");
          }
          for (List<Double> position : ring) {
            if (position == null
                || position.size() != 2
                || position.get(0) == null
                || position.get(1) == null) {
              throw new IllegalArgumentException("Planning zone position is invalid");
            }
            double longitude = position.get(0);
            double latitude = position.get(1);
            if (!Double.isFinite(longitude)
                || !Double.isFinite(latitude)
                || longitude < -180
                || longitude > 180
                || latitude < -90
                || latitude > 90) {
              throw new IllegalArgumentException("Planning zone coordinate is invalid");
            }
          }
          if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
            throw new IllegalArgumentException("Planning zone ring must be closed");
          }
        }
      }
    }
  }

  /** One versioned tariff polygon that can override a proven in-boundary delivery price. */
  public record PlanningCapacityPriceZoneRequest(
      @NotNull UUID sourceZoneId,
      @Min(0) long sourceZoneVersion,
      @Min(0) long deliveryPriceRubles,
      @Min(0) long pickupPriceRubles,
      @NotNull @Valid PlanningGeoJsonMultiPolygon geometry) {}

  /** One versioned exceptional route policy copied from the warehouse planner. */
  public record PlanningCapacityRestrictionZoneRequest(
      @NotNull UUID sourceZoneId,
      @Min(0) long sourceZoneVersion,
      @NotNull PlanningCapacityRestrictionKind kind,
      @NotNull @Valid PlanningGeoJsonMultiPolygon geometry) {}

  /** Complete replacement of one warehouse's active, simulator-only capacity projection. */
  public record ReplacePlanningCapacitySnapshotRequest(
      @Min(1) long sourceGeneration,
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sourceRevision,
      @NotNull @Size(max = 1_000) List<@NotNull @Valid PlanningCapacityJobRequest> jobs,
      @NotNull @Size(max = 2_000) List<@NotNull @Valid PlanningCapacityShiftRequest> shifts,
      @NotNull @Size(min = 1, max = 12)
          List<@NotNull @Valid PlanningCapacityIsochroneTariff> isochroneTariffs,
      @Size(max = 500) List<@NotNull @Valid PlanningCapacityPriceZoneRequest> priceZones,
      @Size(max = 500)
          List<@NotNull @Valid PlanningCapacityRestrictionZoneRequest> restrictionZones) {
    public ReplacePlanningCapacitySnapshotRequest {
      priceZones = priceZones == null ? List.of() : List.copyOf(priceZones);
      restrictionZones = restrictionZones == null ? List.of() : List.copyOf(restrictionZones);
      if (jobs != null
          && jobs.stream().map(PlanningCapacityJobRequest::sourceJobId).distinct().count()
              != jobs.size()) {
        throw new IllegalArgumentException("Planning capacity source job IDs must be unique");
      }
      if (shifts != null
          && shifts.stream().map(PlanningCapacityShiftRequest::sourceShiftId).distinct().count()
              != shifts.size()) {
        throw new IllegalArgumentException("Planning capacity source shift IDs must be unique");
      }
      if (isochroneTariffs == null) {
        throw new IllegalArgumentException("Planning requires isochrone tariffs");
      }
      if (isochroneTariffs.isEmpty() || isochroneTariffs.size() > 12) {
        throw new IllegalArgumentException("Planning requires between one and twelve tariffs");
      }
      for (int index = 0; index < isochroneTariffs.size(); index++) {
        PlanningCapacityIsochroneTariff tariff = isochroneTariffs.get(index);
        if (tariff == null || tariff.travelMinutes() != (index + 1) * 60) {
          throw new IllegalArgumentException(
              "Planning isochrone tariffs must be contiguous hourly tiers starting at 60 minutes");
        }
      }
      isochroneTariffs = List.copyOf(isochroneTariffs);
      if (priceZones.stream()
              .map(PlanningCapacityPriceZoneRequest::sourceZoneId)
              .distinct()
              .count()
          != priceZones.size()) {
        throw new IllegalArgumentException("Planning capacity price-zone IDs must be unique");
      }
      if (restrictionZones.stream()
              .map(PlanningCapacityRestrictionZoneRequest::sourceZoneId)
              .distinct()
              .count()
          != restrictionZones.size()) {
        throw new IllegalArgumentException(
            "Planning capacity restriction-zone IDs must be unique");
      }
    }

    /** Preserves requests produced before exceptional policy zones were added. */
    public ReplacePlanningCapacitySnapshotRequest(
        long sourceGeneration,
        String sourceRevision,
        List<PlanningCapacityJobRequest> jobs,
        List<PlanningCapacityShiftRequest> shifts,
        List<PlanningCapacityIsochroneTariff> isochroneTariffs) {
      this(
          sourceGeneration,
          sourceRevision,
          jobs,
          shifts,
          isochroneTariffs,
          List.of(),
          List.of());
    }
  }

  /** Applied active-capacity revision returned to the standalone simulator. */
  public record PlanningCapacitySnapshotResponse(
      UUID warehouseId,
      long sourceGeneration,
      long version,
      String sourceRevision,
      int jobCount,
      int shiftCount,
      int isochroneTariffCount,
      int priceZoneCount,
      int restrictionZoneCount,
      boolean replayed,
      OffsetDateTime updatedAt) {
    /** Preserves callers compiled before policy-zone result counts were added. */
    public PlanningCapacitySnapshotResponse(
        UUID warehouseId,
        long sourceGeneration,
        long version,
        String sourceRevision,
        int jobCount,
        int shiftCount,
        int isochroneTariffCount,
        boolean replayed,
        OffsetDateTime updatedAt) {
      this(
          warehouseId,
          sourceGeneration,
          version,
          sourceRevision,
          jobCount,
          shiftCount,
          isochroneTariffCount,
          0,
          0,
          replayed,
          updatedAt);
    }
  }

  /** Active RWMS warehouse identity available to the standalone planner. */
  public record PlanningWarehouseResource(
      UUID warehouseId,
      long warehouseVersion,
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String timeZone,
      boolean representative,
      boolean routingReady) {
    /** Preserves source compatibility for pre-coordinate display-only callers. */
    public PlanningWarehouseResource(
        UUID warehouseId, String name, String city, String address, String timeZone) {
      this(
          warehouseId,
          0,
          name,
          city,
          address,
          null,
          null,
          timeZone,
          false,
          false);
    }
  }

  /** Calendar-filtered directed support edge available to route candidate generation. */
  public record PlanningWarehouseSupportLinkResource(
      UUID supportLinkId,
      long supportLinkVersion,
      PlanningWarehouseResource supportWarehouse,
      PlanningWarehouseResource servedWarehouse,
      int priority,
      boolean allowDrivers,
      boolean allowVehicles,
      boolean allowInventory,
      boolean allowDirectFulfillment,
      boolean allowInterwarehouseTransfer,
      boolean allowContractorFallback,
      Set<DayOfWeek> allowedWeekdays,
      Set<LocalDate> allowedDates,
      Set<LocalDate> excludedDates,
      LocalTime serviceStart,
      LocalTime serviceEnd) {}

  /** Active qualified task-board driver availability exposed to the standalone planner. */
  public record PlanningDriverResource(
      UUID workerId,
      String displayName,
      PlanningDriverEmploymentType employmentType,
      String phone,
      UUID operationalWarehouseId,
      OffsetDateTime availableFrom,
      OffsetDateTime availableUntil,
      PlanningDriverAvailabilityKind availabilityKind) {
    /** Preserves source compatibility for tests and adapters that only display a staff identity. */
    public PlanningDriverResource(UUID workerId, String displayName) {
      this(
          workerId,
          displayName,
          PlanningDriverEmploymentType.STAFF,
          null,
          null,
          null,
          null,
          PlanningDriverAvailabilityKind.HOME);
    }
  }

  /** One planner-selected shipment bound to an exact order version and concrete cabins. */
  public record PlanningAssignmentRequest(
      @NotNull UUID orderId,
      UUID serviceWarehouseId,
      UUID inventorySourceWarehouseId,
      @NotNull @Min(0) Long expectedOrderVersion,
      @NotNull LocalDate scheduledDate,
      PlanningAssignmentType assignmentType,
      PlanningDriverAudienceMode driverAudienceMode,
      UUID driverWorkerId,
      @NotBlank @Size(max = 512) String driverName,
      @NotNull @Size(min = 1, max = 2) List<@NotNull UUID> unitIds,
      OffsetDateTime provisionalEta) {
    public PlanningAssignmentRequest {
      if (assignmentType == null) {
        assignmentType = PlanningAssignmentType.ROUTE_PLAN;
      }
      if (driverAudienceMode == null) {
        driverAudienceMode = PlanningDriverAudienceMode.ASSIGNED_DRIVER;
      }
      if (provisionalEta != null
          && driverAudienceMode != PlanningDriverAudienceMode.WAREHOUSE_DRIVERS) {
        throw new IllegalArgumentException(
            "Provisional ETA is allowed only for shared future driver work");
      }
    }

    /** Preserves the complete constructor used before provisional shared-task ETA was exported. */
    public PlanningAssignmentRequest(
        UUID orderId,
        UUID serviceWarehouseId,
        UUID inventorySourceWarehouseId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        PlanningAssignmentType assignmentType,
        PlanningDriverAudienceMode driverAudienceMode,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          serviceWarehouseId,
          inventorySourceWarehouseId,
          expectedOrderVersion,
          scheduledDate,
          assignmentType,
          driverAudienceMode,
          driverWorkerId,
          driverName,
          unitIds,
          null);
    }

    /** Preserves the complete constructor used before physical inventory source selection. */
    public PlanningAssignmentRequest(
        UUID orderId,
        UUID serviceWarehouseId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        PlanningAssignmentType assignmentType,
        PlanningDriverAudienceMode driverAudienceMode,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          serviceWarehouseId,
          null,
          expectedOrderVersion,
          scheduledDate,
          assignmentType,
          driverAudienceMode,
          driverWorkerId,
          driverName,
          unitIds,
          null);
    }

    /** Preserves the original complete constructor for route-planner callers. */
    public PlanningAssignmentRequest(
        UUID orderId,
        UUID serviceWarehouseId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        PlanningDriverAudienceMode driverAudienceMode,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          serviceWarehouseId,
          null,
          expectedOrderVersion,
          scheduledDate,
          PlanningAssignmentType.ROUTE_PLAN,
          driverAudienceMode,
          driverWorkerId,
          driverName,
          unitIds,
          null);
    }

    public PlanningAssignmentRequest(
        UUID orderId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        PlanningDriverAudienceMode driverAudienceMode,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          null,
          null,
          expectedOrderVersion,
          scheduledDate,
          PlanningAssignmentType.ROUTE_PLAN,
          driverAudienceMode,
          driverWorkerId,
          driverName,
          unitIds,
          null);
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
          null,
          null,
          expectedOrderVersion,
          scheduledDate,
          PlanningAssignmentType.ROUTE_PLAN,
          PlanningDriverAudienceMode.ASSIGNED_DRIVER,
          driverWorkerId,
          driverName,
          unitIds,
          null);
    }

    /** Constructor for an explicit regional service warehouse within a root planning group. */
    public PlanningAssignmentRequest(
        UUID orderId,
        UUID serviceWarehouseId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          serviceWarehouseId,
          null,
          expectedOrderVersion,
          scheduledDate,
          PlanningAssignmentType.ROUTE_PLAN,
          PlanningDriverAudienceMode.ASSIGNED_DRIVER,
          driverWorkerId,
          driverName,
          unitIds,
          null);
    }
  }

  /** Vehicle configuration that selects the server-owned inspection template in Driver Up. */
  public enum PlanningDriverShiftVehicleConfiguration {
    TRUCK,
    TRUCK_WITH_TRAILER,
    TRUCK_WITH_CRANE
  }

  /** Immutable vehicle identity transported from the standalone planner without fleet ownership. */
  public record PlanningDriverShiftVehicleRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String registrationNumber,
      @Size(max = 64) String vehicleType,
      @Size(max = 100) String manufacturer,
      @Size(max = 100) String model,
      @NotNull PlanningDriverShiftVehicleConfiguration configurationType,
      @Min(1) @Max(2) Integer cabinCapacity,
      @Min(0) Long startOdometer) {
    /** Preserves source compatibility for vehicle snapshots produced before cabin capacity. */
    public PlanningDriverShiftVehicleRequest(
        UUID id,
        String name,
        String registrationNumber,
        String vehicleType,
        String manufacturer,
        String model,
        PlanningDriverShiftVehicleConfiguration configurationType,
        Long startOdometer) {
      this(
          id,
          name,
          registrationNumber,
          vehicleType,
          manufacturer,
          model,
          configurationType,
          null,
          startOdometer);
    }
  }

  /** Optional immutable trailer identity transported with one assigned vehicle snapshot. */
  public record PlanningDriverShiftTrailerRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String registrationNumber) {}

  /** Stable executable operation discriminator shared with task-board and DriverApp. */
  public enum PlanningDriverShiftRouteOperationKind {
    ORIGIN_START,
    TRANSFER_LOAD,
    INBOUND_POSITIONING,
    TRANSFER_UNLOAD,
    DEPOT_LOAD,
    DELIVERY,
    PICKUP,
    DEPOT_UNLOAD,
    DEPOT_RETURN,
    RETURN_POSITIONING
  }

  /** One exact planner stop or positioning leg in the immutable driver workday order. */
  public record PlanningDriverShiftRouteOperationRequest(
      @Min(1) int sequence,
      @NotNull PlanningDriverShiftRouteOperationKind kind,
      UUID warehouseId,
      UUID sourceTaskId,
      UUID sourceTransferId,
      @NotBlank @Size(max = 500) String locationLabel,
      @NotNull OffsetDateTime plannedArrival,
      @NotNull OffsetDateTime plannedDeparture,
      @Min(0) int loadBefore,
      @Min(0) int loadAfter) {
    /** Preserves source compatibility for operations produced before transfer cargo identity. */
    public PlanningDriverShiftRouteOperationRequest(
        int sequence,
        PlanningDriverShiftRouteOperationKind kind,
        UUID warehouseId,
        UUID sourceTaskId,
        String locationLabel,
        OffsetDateTime plannedArrival,
        OffsetDateTime plannedDeparture,
        int loadBefore,
        int loadAfter) {
      this(
          sequence,
          kind,
          warehouseId,
          sourceTaskId,
          null,
          locationLabel,
          plannedArrival,
          plannedDeparture,
          loadBefore,
          loadAfter);
    }
  }

  /**
   * One exact planner shift, assigned vehicle, and unrounded int64 route-meter summary for a Driver
   * Up workday.
   */
  public record PlanningDriverShiftPlanRequest(
      @NotNull UUID sourceShiftId,
      @NotNull UUID sourcePlanId,
      @NotNull @Min(1) Long sourcePlanVersion,
      @NotNull UUID warehouseId,
      UUID routeOriginWarehouseId,
      UUID supportWarehouseLinkId,
      @NotNull UUID driverId,
      @NotBlank @Size(max = 256) String driverName,
      @NotNull LocalDate workDate,
      @NotNull @Valid PlanningDriverShiftVehicleRequest vehicle,
      @Valid PlanningDriverShiftTrailerRequest trailer,
      @Min(0) int tripCount,
      @Min(0) long routeDistanceMeters,
      @NotNull @Size(max = 1000)
          List<@NotNull @Valid PlanningDriverShiftRouteOperationRequest> operations) {
    public PlanningDriverShiftPlanRequest {
      if (operations == null) operations = List.of();
      operations = List.copyOf(operations);
    }

    /** Preserves the explicit-origin constructor used before executable route operations. */
    public PlanningDriverShiftPlanRequest(
        UUID sourceShiftId,
        UUID sourcePlanId,
        Long sourcePlanVersion,
        UUID warehouseId,
        UUID routeOriginWarehouseId,
        UUID supportWarehouseLinkId,
        UUID driverId,
        String driverName,
        LocalDate workDate,
        PlanningDriverShiftVehicleRequest vehicle,
        PlanningDriverShiftTrailerRequest trailer,
        int tripCount,
        long routeDistanceMeters) {
      this(
          sourceShiftId,
          sourcePlanId,
          sourcePlanVersion,
          warehouseId,
          routeOriginWarehouseId,
          supportWarehouseLinkId,
          driverId,
          driverName,
          workDate,
          vehicle,
          trailer,
          tripCount,
          routeDistanceMeters,
          List.of());
    }

    /** Preserves the local-route constructor used before explicit cross-warehouse origin. */
    public PlanningDriverShiftPlanRequest(
        UUID sourceShiftId,
        UUID sourcePlanId,
        Long sourcePlanVersion,
        UUID warehouseId,
        UUID driverId,
        String driverName,
        LocalDate workDate,
        PlanningDriverShiftVehicleRequest vehicle,
        PlanningDriverShiftTrailerRequest trailer,
        int tripCount,
        long routeDistanceMeters) {
      this(
          sourceShiftId,
          sourcePlanId,
          sourcePlanVersion,
          warehouseId,
          null,
          null,
          driverId,
          driverName,
          workDate,
          vehicle,
          trailer,
          tripCount,
          routeDistanceMeters,
          List.of());
    }
  }

  /** Idempotent plan application command; plan identity participates in stable command keys. */
  public record ApplyPlanningAssignmentsRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID planId,
      @NotNull @Min(1) Long planVersion,
      @NotNull @Size(max = 500) List<@NotNull @Valid PlanningAssignmentRequest> assignments,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningDriverShiftPlanRequest> driverShiftPlans) {
    public ApplyPlanningAssignmentsRequest {
      if (driverShiftPlans == null) driverShiftPlans = List.of();
      driverShiftPlans = List.copyOf(driverShiftPlans);
    }

    /** Preserves source and JSON compatibility for callers created before shift publication. */
    public ApplyPlanningAssignmentsRequest(
        UUID warehouseId,
        UUID planId,
        Long planVersion,
        List<PlanningAssignmentRequest> assignments) {
      this(warehouseId, planId, planVersion, assignments, List.of());
    }
  }

  /** Successfully created or replayed logistics document for one planned order part. */
  public record AppliedPlanningAssignment(
      UUID orderId,
      UUID documentId,
      UUID externalTaskId,
      long taskVersion,
      boolean replayed,
      long orderVersion) {
    /** Preserves the former in-process constructor for source-compatible focused tests. */
    public AppliedPlanningAssignment(
        UUID orderId,
        UUID documentId,
        UUID externalTaskId,
        long taskVersion,
        boolean replayed) {
      this(orderId, documentId, externalTaskId, taskVersion, replayed, -1);
    }
  }

  /** Current RWMS assignment and driver-task projection for one planner-created shipment part. */
  public record PlanningAssignmentStatus(
      UUID orderId,
      long orderVersion,
      UUID documentId,
      UUID externalTaskId,
      long taskVersion,
      UUID sourcePlanId,
      Long sourcePlanVersion,
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

  /** Outcome of a complete atomic revision replacement or its exact durable replay. */
  public enum PlanningReplacementOutcome {
    APPLIED,
    REPLAYED
  }

  /** One exact existing shipment assignment and its desired pre-start driver projection. */
  public record PlanningAssignmentReplacementRequest(
      @NotNull UUID orderId,
      @NotNull @Min(0) Long expectedOrderVersion,
      @NotNull UUID documentId,
      @NotNull UUID externalTaskId,
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull UUID serviceWarehouseId,
      @NotNull LocalDate scheduledDate,
      @NotNull @Size(min = 1, max = 2) List<@NotNull UUID> unitIds,
      @NotNull PlanningDriverAudienceMode driverAudienceMode,
      UUID driverWorkerId,
      @Size(max = 512) String driverName,
      @NotNull @Min(0) Integer targetQueuePosition,
      OffsetDateTime provisionalEta) {
    public PlanningAssignmentReplacementRequest {
      unitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
    }
  }

  /** Complete existing membership and strictly newer revision for one planner warehouse day. */
  public record ReplacePlanningAssignmentsRequest(
      @NotNull UUID warehouseId,
      @NotNull LocalDate date,
      @NotNull @Min(1) Long expectedSourcePlanVersion,
      @NotNull @Min(2) Long replacementPlanVersion,
      @NotNull @Size(min = 1, max = 500)
          List<@NotNull @Valid PlanningAssignmentReplacementRequest> assignments,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningDriverShiftPlanRequest> driverShiftPlans) {
    public ReplacePlanningAssignmentsRequest {
      assignments = assignments == null ? List.of() : List.copyOf(assignments);
      driverShiftPlans = driverShiftPlans == null ? List.of() : List.copyOf(driverShiftPlans);
    }
  }

  /** Authoritative local and task-board fences after one assignment replacement. */
  public record ReplacedPlanningAssignment(
      UUID orderId,
      long orderVersion,
      UUID documentId,
      UUID externalTaskId,
      long taskVersion,
      long taskBoardTaskVersion,
      UUID taskBoardEntryId,
      long taskBoardEntryVersion,
      int queuePosition) {}

  /** Authoritative task-board shift aggregate revision after replacement. */
  public record ReplacedPlanningDriverShift(
      UUID sourceShiftId, long taskBoardShiftPlanVersion, long sourcePlanVersion) {}

  /** Complete owner result for one atomic replacement revision. */
  public record ReplacePlanningAssignmentsResponse(
      PlanningReplacementOutcome outcome,
      UUID sourcePlanId,
      long sourcePlanVersion,
      UUID warehouseId,
      LocalDate date,
      List<ReplacedPlanningAssignment> assignments,
      List<ReplacedPlanningDriverShift> driverShiftPlans) {
    public ReplacePlanningAssignmentsResponse {
      assignments = List.copyOf(assignments);
      driverShiftPlans = List.copyOf(driverShiftPlans);
    }
  }
}
