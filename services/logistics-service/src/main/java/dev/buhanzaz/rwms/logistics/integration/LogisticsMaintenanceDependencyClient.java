package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Private maintenance-service client for transfer-repair truth and repair-place projections.
 *
 * <p>Transfer preparation and capital-repair reads share maintenance's approved logistics
 * capability, while their response validation stays next to the maintenance wire schema.
 */
final class LogisticsMaintenanceDependencyClient {
  private static final String MAINTENANCE_CLIENT = "logistics-maintenance";
  private static final String MAINTENANCE_SCOPE = "maintenance.logistics";
  private static final Set<String> REPAIR_STAGE_STATES =
      Set.of("PLANNED", "QUEUED", "IN_PROGRESS", "DONE", "CANCELLED");

  private final LogisticsOAuthHttpTransport transport;
  private final String maintenanceBase;

  LogisticsMaintenanceDependencyClient(LogisticsOAuthHttpTransport transport, String maintenanceBase) {
    this.transport = transport;
    this.maintenanceBase = maintenanceBase;
  }

  TransferRepairDeparture prepareTransferDeparture(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    TransferRepairDepartureResponse response =
        transport.post(
            maintenanceTransferLineBase(transferId, lineId) + "/prepare-departure",
            idempotencyKey,
            new TransferRepairContextRequest(rentalItemId, sourceWarehouseId, targetWarehouseId),
            TransferRepairDepartureResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return transferRepairDeparture(response);
  }

  TransferRepairArrivalPreflight preflightTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    TransferRepairArrivalPreflightResponse response =
        transport.postWithoutIdempotency(
            maintenanceTransferLineBase(transferId, lineId) + "/arrival-preflight",
            new TransferRepairContextRequest(rentalItemId, sourceWarehouseId, targetWarehouseId),
            TransferRepairArrivalPreflightResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return transferRepairArrivalPreflight(response);
  }

  TransferRepairArrivalCompletion completeTransferArrival(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority) {
    TransferRepairArrivalCompletionResponse response =
        transport.post(
            maintenanceTransferLineBase(transferId, lineId) + "/complete-arrival",
            idempotencyKey,
            new CompleteTransferRepairArrivalRequest(
                rentalItemId,
                rentalItemVersion,
                sourceWarehouseId,
                targetWarehouseId,
                priority),
            TransferRepairArrivalCompletionResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return transferRepairArrivalCompletion(response);
  }

  ReturnEstimateSource upsertReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReference> mediaReferences) {
    ReturnEstimateSourceResponse response =
        transport.putWithoutIdempotency(
            maintenanceBase + "/returns/" + returnId + "/lines/" + lineId + "/estimate-source",
            new UpsertReturnEstimateSourceRequest(
                warehouseId,
                rentalItemId,
                rentalItemVersion,
                dispatchDate,
                mediaReferences.stream()
                    .map(
                        reference ->
                            new MaintenanceMediaReferenceRequest(
                                reference.mediaId(), reference.generation()))
                    .toList()),
            ReturnEstimateSourceResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null) {
      throw malformed("Maintenance-service returned an empty return estimate source");
    }
    return new ReturnEstimateSource(
        response.returnId(),
        response.lineId(),
        response.sourceVersion(),
        response.warehouseId(),
        response.rentalItemId(),
        response.rentalItemVersion(),
        response.estimateId(),
        response.snapshotSha256(),
        response.receivedAt());
  }

  CapitalRepairPage readCapitalRepairs(UUID warehouseId, int page, int size) {
    String uri =
        UriComponentsBuilder.fromUriString(maintenanceBase + "/repairs/capital")
            .queryParam("warehouseId", warehouseId)
            .queryParam("page", page)
            .queryParam("size", size)
            .build()
            .encode()
            .toUriString();
    CapitalRepairPageResponse response =
        transport.get(
            uri,
            CapitalRepairPageResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.items() == null
        || response.page() != page
        || response.size() != size
        || response.totalElements() < 0) {
      throw malformed("Maintenance-service returned invalid capital-repair page");
    }
    return new CapitalRepairPage(
        response.items().stream().map(LogisticsMaintenanceDependencyClient::capitalRepair).toList(),
        response.page(),
        response.size(),
        response.totalElements());
  }

  CapitalRepair readCapitalRepair(UUID repairId) {
    return capitalRepair(
        transport.get(
            maintenanceBase + "/repairs/capital/" + repairId,
            CapitalRepairResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT));
  }

  RepairPlaceProjection readRepairPlaces(UUID warehouseId) {
    RepairPlaceProjectionResponse response =
        transport.get(
            maintenanceBase + "/repair-places/" + warehouseId,
            RepairPlaceProjectionResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.repairPlaceCount() < 1
        || response.automaticRefillDelayMinutes() < 1
        || response.automaticRefillDelayMinutes() > 1_440
        || response.availableCount() < 0
        || response.allocations() == null) {
      throw malformed("Maintenance-service returned invalid repair-place projection");
    }
    return new RepairPlaceProjection(
        warehouseId,
        response.repairPlaceCount(),
        response.automaticRefillDelayMinutes(),
        response.reservedCount(),
        response.occupiedCount(),
        response.readyToReleaseCount(),
        response.availableCount(),
        response.overCapacity(),
        response.allocations().stream()
            .map(LogisticsMaintenanceDependencyClient::repairPlaceProjectionAllocation)
            .toList());
  }

  RepairPlaceAllocation transitionRepairPlace(
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition) {
    if (!Set.of("reserve", "occupy", "release").contains(transition)) {
      throw new IllegalArgumentException("Unsupported repair-place transition");
    }
    RepairPlaceAllocationResponse response =
        transport.post(
            maintenanceBase
                + "/repair-places/"
                + warehouseId
                + "/allocations/"
                + repairId
                + "/"
                + transition,
            idempotencyKey,
            new RepairPlaceTransitionRequest(expectedVersion),
            RepairPlaceAllocationResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    RepairPlaceAllocation allocation = repairPlaceAllocation(response);
    if (!warehouseId.equals(allocation.warehouseId())
        || !repairId.equals(allocation.repairId())) {
      throw malformed("Maintenance-service returned a mismatched repair-place allocation");
    }
    return allocation;
  }

  private String maintenanceTransferLineBase(UUID transferId, UUID lineId) {
    if (transferId == null || lineId == null) {
      throw malformed("Transfer repair context identifiers are required");
    }
    return maintenanceBase + "/transfers/" + transferId + "/lines/" + lineId;
  }

  private static RepairPlaceAllocation repairPlaceAllocation(
      RepairPlaceAllocationResponse response) {
    if (response == null
        || response.id() == null
        || response.version() < 0
        || response.warehouseId() == null
        || response.repairId() == null
        || response.rentalItemId() == null
        || response.state() == null
        || response.createdAt() == null
        || response.updatedAt() == null) {
      throw malformed("Maintenance-service returned invalid repair-place allocation");
    }
    return new RepairPlaceAllocation(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.repairId(),
        response.rentalItemId(),
        response.state(),
        null,
        null,
        3,
        response.createdAt(),
        response.updatedAt());
  }

  private static RepairPlaceAllocation repairPlaceProjectionAllocation(
      RepairPlaceProjectionAllocationResponse response) {
    if (response == null
        || response.id() == null
        || response.version() < 0
        || response.warehouseId() == null
        || response.repairId() == null
        || response.rentalItemId() == null
        || response.state() == null
        || (response.repairStageName() == null) != (response.repairStageState() == null)
        || (response.repairStageState() != null
            && !REPAIR_STAGE_STATES.contains(response.repairStageState()))
        || response.priority() < 1
        || response.priority() > 5
        || response.createdAt() == null
        || response.updatedAt() == null) {
      throw malformed("Maintenance-service returned invalid repair-place projection allocation");
    }
    return new RepairPlaceAllocation(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.repairId(),
        response.rentalItemId(),
        response.state(),
        response.repairStageName(),
        response.repairStageState(),
        response.priority(),
        response.createdAt(),
        response.updatedAt());
  }

  private static CapitalRepair capitalRepair(CapitalRepairResponse response) {
    if (response == null
        || response.repairId() == null
        || response.rentalItemId() == null
        || response.warehouseId() == null
        || response.priority() < 1
        || response.priority() > 5
        || response.complexity() == null
        || response.version() < 0) {
      throw malformed("Maintenance-service returned invalid capital repair");
    }
    RepairComplexitySnapshotResponse complexity = response.complexity();
    if (!"CAPITAL".equals(complexity.type())
        || complexity.name() == null
        || complexity.name().isBlank()
        || complexity.color() == null
        || !complexity.color().matches("^#[0-9A-F]{6}$")
        || complexity.plannedMinutes() == null
        || complexity.plannedMinutes().isBlank()) {
      throw malformed("Maintenance-service returned invalid capital complexity");
    }
    return new CapitalRepair(
        response.repairId(),
        response.rentalItemId(),
        response.warehouseId(),
        response.priority(),
        new RepairComplexitySnapshot(
            complexity.type(),
            complexity.name(),
            complexity.color(),
            complexity.plannedMinutes(),
            complexity.forcedCapital()),
        response.version());
  }

  private static TransferRepairDeparture transferRepairDeparture(
      TransferRepairDepartureResponse response) {
    if (response == null
        || (!"FREE".equals(response.assetStatus()) && !"REPAIR".equals(response.assetStatus()))
        || ("FREE".equals(response.assetStatus())
            && (response.activeRepairId() != null || response.activeRepairVersion() != null))
        || ("REPAIR".equals(response.assetStatus())
            && (response.activeRepairId() == null
                || response.activeRepairVersion() == null
                || response.activeRepairVersion() < 0))) {
      throw malformed("Maintenance-service returned invalid transfer departure truth");
    }
    return new TransferRepairDeparture(
        response.activeRepairId(), response.activeRepairVersion(), response.assetStatus());
  }

  private static TransferRepairArrivalPreflight transferRepairArrivalPreflight(
      TransferRepairArrivalPreflightResponse response) {
    if (response == null
        || response.missingQueueDefinitionIds() == null
        || response.missingQueueDefinitionIds().stream().anyMatch(java.util.Objects::isNull)
        || response.missingQueueDefinitionIds().size()
            != Set.copyOf(response.missingQueueDefinitionIds()).size()
        || (response.activeRepairId() == null
            && (response.priorityRequired() || !response.missingQueueDefinitionIds().isEmpty()))
        || (response.activeRepairId() != null && !response.priorityRequired())) {
      throw malformed("Maintenance-service returned invalid transfer arrival preflight");
    }
    return new TransferRepairArrivalPreflight(
        response.activeRepairId(),
        response.priorityRequired(),
        List.copyOf(response.missingQueueDefinitionIds()));
  }

  private static TransferRepairArrivalCompletion transferRepairArrivalCompletion(
      TransferRepairArrivalCompletionResponse response) {
    if (response == null
        || response.activeRepairId() == null
        || response.repairVersion() == null
        || response.repairVersion() < 0
        || response.warehouseId() == null) {
      throw malformed("Maintenance-service returned invalid transfer arrival completion");
    }
    return new TransferRepairArrivalCompletion(
        response.activeRepairId(), response.repairVersion(), response.warehouseId());
  }

  /**
   * Shared transfer-repair context sent for departure preparation and arrival preflight without
   * copying maintenance aggregate state into logistics.
   */
  private record TransferRepairContextRequest(
      UUID rentalItemId, UUID sourceWarehouseId, UUID targetWarehouseId) {}

  /**
   * Arrival completion command fenced by the current rental-item version and carrying any required
   * repair priority.
   */
  private record CompleteTransferRepairArrivalRequest(
      UUID rentalItemId,
      long rentalItemVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority) {}

  /**
   * Maintenance decision for departure, including active repair identity and required asset state.
   */
  private record TransferRepairDepartureResponse(
      UUID activeRepairId, Long activeRepairVersion, String assetStatus) {}

  /**
   * Arrival readiness result exposing whether priority is required and which repair queue
   * definitions are still unavailable.
   */
  private record TransferRepairArrivalPreflightResponse(
      UUID activeRepairId, boolean priorityRequired, List<UUID> missingQueueDefinitionIds) {}

  /** Authoritative repair identity, version, and warehouse after transfer arrival completion. */
  private record TransferRepairArrivalCompletionResponse(
      UUID activeRepairId, Long repairVersion, UUID warehouseId) {}

  /** Media reference pinned to an exact generation in a return estimate source snapshot. */
  private record MaintenanceMediaReferenceRequest(UUID mediaId, long generation) {}

  /**
   * Return estimate-source payload transferred to maintenance with the cabin version, dispatch
   * date, warehouse, and immutable media generations.
   */
  private record UpsertReturnEstimateSourceRequest(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MaintenanceMediaReferenceRequest> mediaReferences) {}

  /**
   * Maintenance-owned estimate source result carrying its revision, resulting estimate identity,
   * content hash, and receipt time for reconciliation.
   */
  private record ReturnEstimateSourceResponse(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID estimateId,
      String snapshotSha256,
      OffsetDateTime receivedAt) {}

  /** Optimistically fenced command for changing a repair-place allocation lifecycle state. */
  private record RepairPlaceTransitionRequest(long expectedVersion) {}

  /** Authoritative repair-place allocation returned after a state transition. */
  private record RepairPlaceAllocationResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID repairId,
      UUID rentalItemId,
      String state,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * Read-projection allocation enriched with repair stage and priority for logistics planning
   * displays.
   */
  private record RepairPlaceProjectionAllocationResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID repairId,
      UUID rentalItemId,
      String state,
      String repairStageName,
      String repairStageState,
      int priority,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * Warehouse repair-place capacity projection with aggregate counts, over-capacity signal, and
   * current allocations.
   */
  private record RepairPlaceProjectionResponse(
      UUID warehouseId,
      int repairPlaceCount,
      int automaticRefillDelayMinutes,
      long reservedCount,
      long occupiedCount,
      long readyToReleaseCount,
      long availableCount,
      boolean overCapacity,
      List<RepairPlaceProjectionAllocationResponse> allocations) {}

  /** Immutable repair-complexity snapshot embedded in a capital-repair projection item. */
  private record RepairComplexitySnapshotResponse(
      String type, String name, String color, String plannedMinutes, boolean forcedCapital) {}

  /** Compact maintenance-owned capital-repair projection consumed by logistics reads. */
  private record CapitalRepairResponse(
      UUID repairId,
      UUID rentalItemId,
      UUID warehouseId,
      int priority,
      RepairComplexitySnapshotResponse complexity,
      long version) {}

  /** Paginated capital-repair result preserving maintenance-service page metadata. */
  private record CapitalRepairPageResponse(
      List<CapitalRepairResponse> items, int page, int size, long totalElements) {}
}
