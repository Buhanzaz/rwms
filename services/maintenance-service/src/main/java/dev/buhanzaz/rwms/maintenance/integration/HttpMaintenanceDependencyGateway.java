package dev.buhanzaz.rwms.maintenance.integration;

import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Service-to-service gateway. OAuth client registrations intentionally have disjoint, exact
 * scopes; an incoming user token is never forwarded to a dependency.
 */
final class HttpMaintenanceDependencyGateway implements MaintenanceDependencyGateway {
  private static final String ASSET_CLIENT = "maintenance-asset";
  private static final String TASK_CLIENT = "maintenance-task-board";
  private static final String TASK_REGISTRY_CLIENT = "maintenance-task-board-registry";
  private static final String MEDIA_CLIENT = "maintenance-media";
  private static final String LOGISTICS_CLIENT = "maintenance-logistics";
  private static final String ASSET_SCOPE = "asset.maintenance";
  private static final String TASK_SCOPE = "task-board.task-sync";
  private static final String TASK_REGISTRY_SCOPE = "queue-registry.write";
  private static final String MEDIA_SCOPE = "media.maintenance";
  private static final String LOGISTICS_SCOPE = "logistics.maintenance";

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String assetBase;
  private final String taskBase;
  private final String taskCatalogRoutingPreflightUrl;
  private final String taskRoutingPreflightUrl;
  private final String taskWarehouseInternalBase;
  private final String taskRegistryBase;
  private final String mediaOwnerProofUrl;
  private final String driverTaskIntakeUrl;
  private final String propertyEquipmentMovementTaskUrl;

  HttpMaintenanceDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      MaintenanceDependencyProperties.Validated properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    assetBase = strip(properties.assetBaseUrl().toString())
        + "/api/internal/asset/v1/maintenance";
    taskBase = strip(properties.taskBoardBaseUrl().toString())
        + "/api/internal/task-board/v1/tasks";
    taskCatalogRoutingPreflightUrl = strip(properties.taskBoardBaseUrl().toString())
        + "/api/internal/task-board/v1/maintenance/catalog-routing-preflight";
    taskRoutingPreflightUrl = strip(properties.taskBoardBaseUrl().toString())
        + "/api/internal/task-board/v1/maintenance/routing-preflight";
    taskWarehouseInternalBase = strip(properties.taskBoardBaseUrl().toString())
        + "/api/internal/task-board/v1/warehouses";
    taskRegistryBase = strip(properties.taskBoardBaseUrl().toString())
        + "/api/internal/queue-definitions";
    mediaOwnerProofUrl = strip(properties.mediaBaseUrl().toString())
        + "/api/internal/media/v1/owner-proofs";
    driverTaskIntakeUrl =
        strip(properties.logisticsBaseUrl().toString())
            + "/api/internal/logistics/v1/maintenance/driver-tasks";
    propertyEquipmentMovementTaskUrl =
        strip(properties.logisticsBaseUrl().toString())
            + "/api/internal/logistics/v1/maintenance/equipment-movement-tasks";
  }

  @Override
  public AssetSnapshot getRentalItemSnapshot(UUID rentalItemId) {
    try {
      RentalItemSnapshotResponse response = client.get()
          .uri(assetBase + "/rental-items/" + rentalItemId + "/snapshot")
          .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .body(RentalItemSnapshotResponse.class);
      if (response == null
          || response.id() == null
          || !rentalItemId.equals(response.id())
          || response.version() == null
          || response.version() < 0
          || response.warehouseId() == null
          || response.number() == null
          || response.number().isBlank()
          || response.status() == null
          || response.status().isBlank()) {
        throw malformed("Asset-service returned malformed rental-item snapshot truth");
      }
      return new AssetSnapshot(
          response.id(), response.version(), response.warehouseId(), response.number(),
          response.status());
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public PropertyAssetSnapshot getPropertyAssetSnapshot(
      PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    if (assetKind == null || assetId == null || warehouseId == null) {
      throw new IllegalArgumentException("Property asset snapshot identity is required");
    }
    try {
      PropertyAssetSnapshotResponse response = client.get()
          .uri(
              assetBase
                  + "/property-assets/"
                  + assetKind.name()
                  + "/"
                  + assetId
                  + "/snapshot?warehouseId="
                  + warehouseId)
          .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .body(PropertyAssetSnapshotResponse.class);
      return propertyAssetSnapshot(response, assetKind, assetId, warehouseId);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public PropertyDispositionFence preparePropertyDisposition(
      UUID key, UUID decisionId, PropertyDispositionPreparation request) {
    if (key == null || decisionId == null || request == null) {
      throw new IllegalArgumentException("Property disposition preparation identity is required");
    }
    PropertyDispositionFenceResponse response = post(
        assetBase + "/property-dispositions/" + decisionId + "/prepare",
        key,
        new PreparePropertyDispositionRequest(
            request.warehouseId(),
            request.assetKind().name(),
            request.assetId(),
            request.disposition().name(),
            request.expectedAssetVersion(),
            request.expectedSourceBalanceVersion(),
            request.quantity(),
            request.contentsMode() == null ? null : request.contentsMode().name(),
            request.contents().stream()
                .map(
                    line ->
                        new PreparePropertyContentRequest(
                            line.equipmentId(),
                            line.expectedBalanceVersion(),
                            line.currentQuantity(),
                            line.moveQuantity()))
                .toList(),
            request.authorizedMaintenanceLease() == null
                ? null
                : new PropertyDispositionLeaseProofRequest(
                    request.authorizedMaintenanceLease().leaseId(),
                    request.authorizedMaintenanceLease().fencingToken(),
                    request.authorizedMaintenanceLease().ownerType(),
                    request.authorizedMaintenanceLease().ownerId())),
        PropertyDispositionFenceResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    return propertyFence(response, decisionId, request);
  }

  @Override
  public PropertyDispositionEffect applyPropertyDisposition(
      UUID key, UUID decisionId, UUID completedMovementTaskId) {
    if (key == null || decisionId == null) {
      throw new IllegalArgumentException("Property disposition effect identity is required");
    }
    PropertyDispositionEffectResponse response = post(
        assetBase + "/property-dispositions/" + decisionId + "/apply",
        key,
        new ApplyPropertyDispositionRequest(completedMovementTaskId),
        PropertyDispositionEffectResponse.class,
        ASSET_CLIENT,
        ASSET_SCOPE);
    if (response == null
        || response.effectId() == null
        || !decisionId.equals(response.decisionId())
        || response.assetKind() == null
        || response.assetId() == null
        || response.disposition() == null
        || response.appliedAt() == null) {
      throw malformed("Asset-service returned malformed property disposition effect");
    }
    try {
      return new PropertyDispositionEffect(
          response.effectId(),
          response.decisionId(),
          PropertyAssetKind.valueOf(response.assetKind()),
          response.assetId(),
          PropertyDispositionKind.valueOf(response.disposition()),
          response.assetVersion(),
          response.appliedAt());
    } catch (IllegalArgumentException exception) {
      throw malformed("Asset-service returned unknown property disposition effect truth");
    }
  }

  @Override
  public PropertyEquipmentMovementTask createPropertyEquipmentMovementTask(
      UUID key, PropertyEquipmentMovementCommand command) {
    if (key == null || command == null) {
      throw new IllegalArgumentException("Property equipment movement identity is required");
    }
    PropertyEquipmentMovementTaskResponse response = post(
        propertyEquipmentMovementTaskUrl,
        key,
        new CreatePropertyEquipmentMovementTaskRequest(
            command.decisionId(),
            command.warehouseId(),
            command.unitNumber(),
            command.plannedDurationMinutes(),
            command.deadlineAt(),
            command.lines().stream()
                .map(
                    line ->
                        new PropertyEquipmentMovementLineRequest(
                            line.equipmentId(),
                            line.sourceRentalItemId(),
                            line.expectedSourceBalanceVersion(),
                            line.quantity()))
                .toList()),
        PropertyEquipmentMovementTaskResponse.class,
        LOGISTICS_CLIENT,
        LOGISTICS_SCOPE);
    return propertyEquipmentMovementTask(response, command.warehouseId());
  }

  @Override
  public PropertyEquipmentMovementTask getPropertyEquipmentMovementTask(UUID taskId) {
    if (taskId == null) {
      throw new IllegalArgumentException("Property equipment movement task ID is required");
    }
    try {
      PropertyEquipmentMovementTaskResponse response = client.get()
          .uri(propertyEquipmentMovementTaskUrl + "/" + taskId)
          .header(HttpHeaders.AUTHORIZATION, bearer(LOGISTICS_CLIENT, LOGISTICS_SCOPE))
          .retrieve()
          .body(PropertyEquipmentMovementTaskResponse.class);
      if (response == null || !taskId.equals(response.id())) {
        throw malformed("Logistics-service returned another property equipment movement task");
      }
      return propertyEquipmentMovementTask(response, response.warehouseId());
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      UUID catalogNodeId, String equipmentName) {
    if (catalogNodeId == null) {
      throw new IllegalArgumentException("Catalog node identity is required for furniture equipment");
    }
    String canonicalName = canonicalEquipmentName(equipmentName);
    try {
      FurnitureEquipmentSnapshot response = client.post()
          .uri(assetBase + "/equipment-catalog")
          .header("Idempotency-Key", catalogNodeId.toString())
          .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
          .body(new EnsureFurnitureEquipmentRequest(catalogNodeId, canonicalName))
          .retrieve()
          .body(MaintenanceFurnitureEquipmentResponse.class);
      if (response == null
          || !catalogNodeId.equals(response.externalReferenceId())
          || response.equipmentId() == null
          || !canonicalName.equals(response.equipmentName())) {
        throw malformed("Asset-service returned mismatched furniture equipment truth");
      }
      return new FurnitureEquipmentSnapshot(response.equipmentId(), response.equipmentName());
    } catch (RuntimeException exception) {
      throw furnitureDependencyFailure(exception);
    }
  }

  @Override
  public List<CabinCharacteristicSnapshot> cabinCharacteristics() {
    try {
      CabinCharacteristicResponse[] response =
          client
              .get()
              .uri(assetBase + "/cabin-characteristics")
              .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
              .retrieve()
              .body(CabinCharacteristicResponse[].class);
      if (response == null) {
        throw malformed("Asset-service returned empty cabin characteristic truth");
      }
      List<CabinCharacteristicSnapshot> result =
          java.util.Arrays.stream(response)
              .map(
                  value ->
                      new CabinCharacteristicSnapshot(
                          value.id(), value.name()))
              .toList();
      if (result.stream().map(CabinCharacteristicSnapshot::characteristicId).distinct().count()
          != result.size()) {
        throw malformed("Asset-service returned duplicate cabin characteristic identities");
      }
      return result;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public AppliedCabinCharacteristic applyCabinCharacteristic(
      UUID key, UUID rentalItemId, UUID characteristicId) {
    AppliedCabinCharacteristic response;
    try {
      response =
          client
              .put()
              .uri(
                  assetBase
                      + "/rental-items/"
                      + rentalItemId
                      + "/characteristics/"
                      + characteristicId)
              .header("Idempotency-Key", key.toString())
              .header(
                  HttpHeaders.AUTHORIZATION,
                  bearer(ASSET_CLIENT, ASSET_SCOPE))
              .retrieve()
              .body(AppliedCabinCharacteristic.class);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
    if (response == null) {
      throw malformed("Asset-service returned an empty characteristic result");
    }
    if (!rentalItemId.equals(response.rentalItemId())
        || !characteristicId.equals(response.characteristicId())) {
      throw malformed("Asset-service returned another applied cabin characteristic");
    }
    return response;
  }

  @Override
  public LeaseSnapshot acquireLease(
      UUID key, UUID rentalItemId, long expectedVersion, String ownerType, String ownerId) {
    LeaseResponse response = post(assetBase + "/operation-leases", key,
        new AcquireLeaseRequest(rentalItemId, ownerType, UUID.fromString(ownerId), expectedVersion),
        LeaseResponse.class, ASSET_CLIENT, ASSET_SCOPE);
    return lease(response, rentalItemId, ownerType, ownerId);
  }

  @Override
  public LeaseSnapshot renewLease(
      UUID key, UUID leaseId, long expectedVersion, long fencingToken,
      String ownerType, String ownerId) {
    LeaseResponse response = put(assetBase + "/operation-leases/" + leaseId + "/renew", key,
        new LeaseCommand(expectedVersion, fencingToken, ownerType, UUID.fromString(ownerId)),
        LeaseResponse.class, ASSET_CLIENT, ASSET_SCOPE);
    validateLease(response, response.rentalItemId(), ownerType, ownerId, "ACTIVE");
    if (!leaseId.equals(response.id())
        || response.version() != Math.addExact(expectedVersion, 1)
        || response.fencingToken() != fencingToken) {
      throw malformed("Asset-service returned malformed renewed lease truth");
    }
    return snapshot(response);
  }

  @Override
  public AssetSnapshot fencedStatus(
      UUID key, UUID rentalItemId, UUID warehouseId, long expectedVersion,
      UUID leaseId, long fencingToken,
      String ownerType, String ownerId, String transition, boolean linkedReturn) {
    return fencedStatus(
        key,
        rentalItemId,
        warehouseId,
        expectedVersion,
        leaseId,
        fencingToken,
        ownerType,
        ownerId,
        transition,
        linkedReturn,
        null,
        List.of());
  }

  @Override
  public AssetSnapshot fencedStatus(
      UUID key, UUID rentalItemId, UUID warehouseId, long expectedVersion,
      UUID leaseId, long fencingToken,
      String ownerType, String ownerId, String transition, boolean linkedReturn,
      UUID estimateId, List<FurnitureLoss> furnitureLosses) {
    String action = switch (transition) {
      case "EMPTY_ESTIMATE_TO_FREE" -> "COMPLETE_EMPTY_ESTIMATE";
      case "EMPTY_REPAIR_TO_FREE" -> "COMPLETE_EMPTY_REPAIR";
      case "QUEUE_TO_REPAIR" -> "QUEUE_FOR_REPAIR";
      case "QUEUE_TO_CAPITAL_REPAIR" -> "QUEUE_FOR_CAPITAL_REPAIR";
      case "PENDING_ACCEPTANCE" -> "MARK_PENDING_ACCEPTANCE";
      case "ACCEPT_TO_FREE" -> "ACCEPT_REPAIR";
      case "WRITE_OFF" -> "WRITE_OFF";
      default -> throw new IllegalArgumentException("Unsupported asset transition");
    };
    UUID owner = UUID.fromString(ownerId);
    RentalItemResponse response = put(
        assetBase + "/rental-items/" + rentalItemId + "/fenced-status", key,
        new FencedStatusRequest(expectedVersion, action, leaseId, fencingToken, ownerType, owner,
            linkedReturn ? owner : null, estimateId, List.copyOf(furnitureLosses)),
        RentalItemResponse.class, ASSET_CLIENT, ASSET_SCOPE);
    String expectedStatus = switch (transition) {
      case "EMPTY_ESTIMATE_TO_FREE", "EMPTY_REPAIR_TO_FREE", "ACCEPT_TO_FREE" -> "FREE";
      case "QUEUE_TO_REPAIR" -> "REPAIR";
      case "QUEUE_TO_CAPITAL_REPAIR" -> "CAPITAL_REPAIR";
      case "PENDING_ACCEPTANCE" -> "WAITING_REPAIR_CHECK";
      case "WRITE_OFF" -> "WRITTEN_OFF";
      default -> throw new IllegalArgumentException("Unsupported asset transition");
    };
    long advancedVersion = Math.addExact(expectedVersion, 1);
    boolean versionMatches =
        response.version() == advancedVersion
            || (("QUEUE_TO_REPAIR".equals(transition)
                    || "QUEUE_TO_CAPITAL_REPAIR".equals(transition))
                && response.version() == expectedVersion);
    if (!rentalItemId.equals(response.id())
        || !warehouseId.equals(response.warehouseId())
        || !versionMatches
        || !expectedStatus.equals(response.status())) {
      throw malformed("Asset-service returned malformed fenced rental-item truth");
    }
    return new AssetSnapshot(
        response.id(), response.version(), response.warehouseId(), response.number(),
        response.status());
  }

  @Override
  public void releaseLease(
      UUID key, UUID leaseId, long expectedVersion, long fencingToken,
      String ownerType, String ownerId) {
    LeaseResponse response = put(assetBase + "/operation-leases/" + leaseId + "/release", key,
        new LeaseCommand(expectedVersion, fencingToken, ownerType, UUID.fromString(ownerId)),
        LeaseResponse.class, ASSET_CLIENT, ASSET_SCOPE);
    validateLease(response, response.rentalItemId(), ownerType, ownerId, "RELEASED");
    if (!leaseId.equals(response.id())
        || response.version() != Math.addExact(expectedVersion, 1)
        || response.fencingToken() != fencingToken) {
      throw malformed("Asset-service did not confirm lease release");
    }
  }

  @Override
  public TaskSnapshot registerTask(
      UUID key, UUID externalTaskId, UUID sourceRepairId, UUID warehouseId, UUID rentalItemId,
      String unitNumber, LocalDate scheduledDate, int priority, int dailyCapacity,
      List<TaskStage> stages) {
    String canonicalUnitNumber = requireTaskUnitNumber(unitNumber);
    TaskResponse response = post(taskBase, key,
        new RegisterTaskRequest(warehouseId, externalTaskId,
            sourceRepairId == null
                ? null
                : new TaskSourceReference("MAINTENANCE_REPAIR", sourceRepairId),
            "Maintenance repair",
            canonicalUnitNumber, null, duration(stages), commonDeadline(stages), route(stages),
            scheduledDate, priority, dailyCapacity),
        TaskResponse.class, TASK_CLIENT, TASK_SCOPE);
    TaskSnapshot snapshot = task(response, externalTaskId, stages.size());
    if (response.scheduledDate() == null
        || response.scheduledDate().isBefore(scheduledDate)
        || priority != response.priority()
        || !canonicalUnitNumber.equals(response.unitNumber())) {
      throw malformed("Task-board returned another schedule, priority, or rental-item number");
    }
    return snapshot;
  }

  @Override
  public TaskSnapshot updatePreStartTask(
      UUID key, UUID externalTaskId, long expectedVersion, String unitNumber,
      List<TaskStage> stages) {
    String canonicalUnitNumber = requireTaskUnitNumber(unitNumber);
    TaskResponse response = put(taskBase + "/" + externalTaskId, key,
        new UpdateTaskRequest(expectedVersion, "Maintenance repair", canonicalUnitNumber, null,
            duration(stages), commonDeadline(stages), route(stages)),
        TaskResponse.class, TASK_CLIENT, TASK_SCOPE);
    TaskSnapshot snapshot = task(response, externalTaskId, stages.size());
    if (!canonicalUnitNumber.equals(response.unitNumber())) {
      throw malformed("Task-board returned another rental-item number");
    }
    return snapshot;
  }

  @Override
  public TaskSnapshot getTask(UUID externalTaskId) {
    try {
      TaskResponse response = client.get().uri(taskBase + "/" + externalTaskId)
          .header(HttpHeaders.AUTHORIZATION, bearer(TASK_CLIENT, TASK_SCOPE))
          .retrieve().body(TaskResponse.class);
      return task(response, externalTaskId, -1);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public TaskSnapshot cancelTask(UUID key, UUID externalTaskId, long expectedVersion) {
    CancelTaskResponse response = post(taskBase + "/" + externalTaskId + "/cancel", key,
        new CancelTaskRequest(expectedVersion, "maintenance-command"),
        CancelTaskResponse.class, TASK_CLIENT, TASK_SCOPE);
    if (!externalTaskId.equals(response.externalTaskId())) {
      throw malformed("Task-board returned another task");
    }
    return new TaskSnapshot(
        externalTaskId, response.taskVersion(), response.status(), List.of());
  }

  @Override
  public PreStartTaskCancellation cancelTaskIfPreStart(
      UUID key, UUID externalTaskId, long expectedVersion) {
    PreStartCancellationResponse response = post(
        taskBase + "/" + externalTaskId + "/cancel-if-pre-start",
        key,
        new CancelTaskRequest(expectedVersion, "inventory-publication-replacement"),
        PreStartCancellationResponse.class,
        TASK_CLIENT,
        TASK_SCOPE);
    if (response == null
        || response.outcome() == null
        || response.taskId() == null
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskVersion() < 0
        || response.status() == null
        || response.status().isBlank()) {
      throw malformed("Task-board returned malformed pre-start cancellation truth");
    }
    try {
      return new PreStartTaskCancellation(
          PreStartTaskCancellationOutcome.valueOf(response.outcome()),
          response.taskId(),
          response.externalTaskId(),
          response.taskVersion(),
          response.status(),
          response.cancelledAt());
    } catch (IllegalArgumentException exception) {
      throw malformed("Task-board returned unknown pre-start cancellation outcome");
    }
  }

  @Override
  public TaskSnapshot relocateTask(
      UUID key, UUID externalTaskId, long expectedVersion, UUID targetWarehouseId) {
    TaskResponse response =
        post(
            taskBase + "/" + externalTaskId + "/relocate",
            key,
            new RelocateTaskRequest(expectedVersion, targetWarehouseId),
            TaskResponse.class,
            TASK_CLIENT,
            TASK_SCOPE);
    if (!targetWarehouseId.equals(response.warehouseId())) {
      throw malformed("Task-board returned another warehouse after task relocation");
    }
    return task(response, externalTaskId, -1);
  }

  @Override
  public DriverTaskSnapshot createDriverTask(
      UUID key, DriverTaskCommand command) {
    DriverTaskResponse response =
        post(
            driverTaskIntakeUrl,
            key,
            command,
            DriverTaskResponse.class,
            LOGISTICS_CLIENT,
            LOGISTICS_SCOPE);
    if (response.id() == null
        || response.version() < 0
        || !command.warehouseId().equals(response.warehouseId())
        || !command.cabinId().equals(response.cabinId())
        || !command.repairId().equals(response.repairId())
        || !command.sourceType().equals(response.sourceType())
        || !command.sourceId().equals(response.sourceId())
        || !command.kind().equals(response.kind())
        || command.planningMode() != response.planningMode()
        || response.scheduledDate() == null
        || (command.planningMode()
                    == dev.buhanzaz.rwms.maintenance.domain
                        .RepairLogisticsPlanningMode.FIXED_DATE
            && !command
                .scheduledDate()
                .equals(response.scheduledDate()))
        || command.priority() != response.priority()
        || response.state() == null
        || response.state().isBlank()) {
      throw malformed(
          "Logistics-service returned another driver-task truth");
    }
    return new DriverTaskSnapshot(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.cabinId(),
        response.repairId(),
        response.sourceType(),
        response.sourceId(),
        response.kind(),
        response.planningMode(),
        response.scheduledDate(),
        response.priority(),
        response.state());
  }

  @Override
  public MaintenanceDriverTaskCompensation maintenanceDriverTaskCompensation(
      UUID repairId, MaintenanceDriverTaskKind kind) {
    try {
      MaintenanceDriverTaskCompensationResponse response = client.get()
          .uri(
              driverTaskIntakeUrl + "/repairs/{repairId}?kind={kind}",
              repairId,
              kind.name())
          .header(HttpHeaders.AUTHORIZATION, bearer(LOGISTICS_CLIENT, LOGISTICS_SCOPE))
          .retrieve()
          .body(MaintenanceDriverTaskCompensationResponse.class);
      return compensation(response, repairId, kind);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public MaintenanceDriverTaskCompensation cancelMaintenanceDriverTaskCompensation(
      UUID key, UUID repairId, MaintenanceDriverTaskKind kind) {
    try {
      MaintenanceDriverTaskCompensationResponse response = client.post()
          .uri(
              driverTaskIntakeUrl + "/repairs/{repairId}/cancel?kind={kind}",
              repairId,
              kind.name())
          .header("Idempotency-Key", key.toString())
          .header(HttpHeaders.AUTHORIZATION, bearer(LOGISTICS_CLIENT, LOGISTICS_SCOPE))
          .retrieve()
          .body(MaintenanceDriverTaskCompensationResponse.class);
      return compensation(response, repairId, kind);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public CatalogRoutingPreflight preflightCatalogRouting(
      List<CatalogRoutingQueueRequirement> queues) {
    if (queues == null || queues.isEmpty()) {
      throw new IllegalArgumentException("Catalog routing preflight is required");
    }
    Set<UUID> requestedDefinitionIds =
        queues.stream()
            .map(CatalogRoutingQueueRequirement::queueDefinitionId)
            .collect(java.util.stream.Collectors.toSet());
    if (requestedDefinitionIds.size() != queues.size()) {
      throw new IllegalArgumentException(
          "Catalog routing queue-definition identifiers must be unique");
    }
    try {
      CatalogRoutingPreflight response =
          client
              .post()
              .uri(taskCatalogRoutingPreflightUrl)
              .header(HttpHeaders.AUTHORIZATION, bearer(TASK_CLIENT, TASK_SCOPE))
              .body(new CatalogRoutingPreflightRequest(List.copyOf(queues)))
              .retrieve()
              .body(CatalogRoutingPreflight.class);
      validateCatalogRoutingPreflight(response, requestedDefinitionIds);
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public RoutingPreflight preflightMaintenanceRouting(
      UUID warehouseId, List<RoutingQueueRequirement> queues) {
    if (warehouseId == null || queues == null || queues.isEmpty()) {
      throw new IllegalArgumentException("Maintenance routing preflight is required");
    }
    Set<UUID> requestedQueueIds = queues.stream()
        .map(RoutingQueueRequirement::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    if (requestedQueueIds.size() != queues.size()) {
      throw new IllegalArgumentException("Maintenance routing queue identifiers must be unique");
    }
    try {
      RoutingPreflight response = client.post()
          .uri(taskRoutingPreflightUrl)
          .header(HttpHeaders.AUTHORIZATION, bearer(TASK_CLIENT, TASK_SCOPE))
          .body(new RoutingPreflightRequest(warehouseId, List.copyOf(queues)))
          .retrieve()
          .body(RoutingPreflight.class);
      validateRoutingPreflight(response, warehouseId, requestedQueueIds);
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public QueueCapabilities queueCapabilities(UUID warehouseId) {
    try {
      QueueCapabilities response =
          client
              .get()
              .uri(taskWarehouseInternalBase + "/" + warehouseId + "/queue-capabilities")
              .header(HttpHeaders.AUTHORIZATION, bearer(TASK_CLIENT, TASK_SCOPE))
              .retrieve()
              .body(QueueCapabilities.class);
      if (response == null || !warehouseId.equals(response.warehouseId())) {
        throw malformed("Task-board returned another warehouse queue capability truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public CatalogPositionReference registerCatalogPosition(
      UUID queueId, String externalReferenceId) {
    if (queueId == null
        || externalReferenceId == null
        || externalReferenceId.isBlank()
        || externalReferenceId.length() > 128) {
      throw new IllegalArgumentException("Catalog-position reference is required");
    }
    try {
      CatalogPositionReference response = client.post()
          .uri(taskRegistryBase + "/" + queueId + "/references")
          .header(HttpHeaders.AUTHORIZATION, bearer(TASK_REGISTRY_CLIENT, TASK_REGISTRY_SCOPE))
          .body(new QueueReferenceRequest("CATALOG_POSITION", externalReferenceId))
          .retrieve()
          .body(CatalogPositionReference.class);
      if (response == null
          || !queueId.equals(response.queueDefinitionId())
          || !"CATALOG_POSITION".equals(response.type())
          || !externalReferenceId.equals(response.externalReferenceId())) {
        throw malformed("Task-board returned mismatched catalog-position reference truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public void deleteCatalogPosition(String externalReferenceId, long expectedVersion) {
    if (externalReferenceId == null
        || externalReferenceId.isBlank()
        || externalReferenceId.length() > 128
        || expectedVersion < 0) {
      throw new IllegalArgumentException("Catalog-position deletion is required");
    }
    try {
      client.delete()
          .uri(taskRegistryBase + "/references/CATALOG_POSITION/" + externalReferenceId
              + "?expectedVersion=" + expectedVersion)
          .header(HttpHeaders.AUTHORIZATION, bearer(TASK_REGISTRY_CLIENT, TASK_REGISTRY_SCOPE))
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound replayed) {
      // At-least-once delivery can replay after task-board committed but maintenance did not.
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public MediaOwnerProof upsertMediaOwnerProof(MediaOwnerProof proof) {
    if (proof == null) throw new IllegalArgumentException("Media owner proof is required");
    try {
      MediaOwnerProof response = client.post()
          .uri(mediaOwnerProofUrl)
          .header(HttpHeaders.AUTHORIZATION, bearer(MEDIA_CLIENT, MEDIA_SCOPE))
          .body(proof)
          .retrieve()
          .body(MediaOwnerProof.class);
      if (!proof.equals(response)) {
        throw malformed("Media-service returned mismatched owner proof truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T post(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response = client.post().uri(uri).header("Idempotency-Key", key.toString())
          .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
          .body(body).retrieve().body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T put(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response = client.put().uri(uri).header("Idempotency-Key", key.toString())
          .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
          .body(body).retrieve().body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private String bearer(String registration, String requiredScope) {
    var request = OAuth2AuthorizeRequest.withClientRegistrationId(registration)
        .principal("maintenance-service:" + registration).build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(requiredScope))) {
      throw malformed("Dependency token does not have its one exact approved scope");
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private static LeaseSnapshot lease(
      LeaseResponse response, UUID rentalItemId, String ownerType, String ownerId) {
    validateLease(response, rentalItemId, ownerType, ownerId, "ACTIVE");
    return snapshot(response);
  }

  private static MaintenanceDriverTaskCompensation compensation(
      MaintenanceDriverTaskCompensationResponse response,
      UUID repairId,
      MaintenanceDriverTaskKind kind) {
    if (response == null
        || !repairId.equals(response.repairId())
        || response.kind() == null
        || response.outcome() == null) {
      throw malformed("Logistics-service returned malformed driver-task compensation truth");
    }
    try {
      if (kind != MaintenanceDriverTaskKind.valueOf(response.kind())) {
        throw malformed("Logistics-service returned another driver-task compensation kind");
      }
      return new MaintenanceDriverTaskCompensation(
          response.repairId(),
          kind,
          MaintenanceDriverTaskCompensationOutcome.valueOf(response.outcome()),
          response.taskId(),
          response.taskVersion(),
          response.state(),
          response.externalTaskId(),
          response.taskBoardTaskId(),
          response.taskBoardTaskVersion(),
          response.repairPlaceAllocationId(),
          response.repairPlaceAllocationVersion());
    } catch (IllegalArgumentException exception) {
      throw malformed("Logistics-service returned unknown driver-task compensation truth");
    }
  }

  private static void validateLease(
      LeaseResponse response,
      UUID rentalItemId,
      String ownerType,
      String ownerId,
      String state) {
    if (response == null || response.id() == null
        || !rentalItemId.equals(response.rentalItemId())
        || !ownerType.equals(response.ownerType())
        || !ownerId.equals(response.ownerId())
        || response.version() < 0
        || response.fencingToken() < 1
        || response.expiresAt() == null
        || !state.equals(response.state())) {
      throw malformed("Asset-service returned malformed lease truth");
    }
  }

  private static LeaseSnapshot snapshot(LeaseResponse response) {
    return new LeaseSnapshot(
        response.id(), response.version(), response.rentalItemId(), response.ownerType(),
        UUID.fromString(response.ownerId()), response.fencingToken(), response.expiresAt());
  }

  private static PropertyAssetSnapshot propertyAssetSnapshot(
      PropertyAssetSnapshotResponse response,
      PropertyAssetKind expectedKind,
      UUID expectedAssetId,
      UUID expectedWarehouseId) {
    if (response == null
        || response.assetKind() == null
        || response.assetId() == null
        || response.assetDisplayName() == null
        || response.warehouseId() == null
        || response.version() == null
        || response.contents() == null
        || !expectedAssetId.equals(response.assetId())
        || !expectedWarehouseId.equals(response.warehouseId())) {
      throw malformed("Asset-service returned malformed property asset snapshot");
    }
    try {
      PropertyAssetKind actualKind = PropertyAssetKind.valueOf(response.assetKind());
      if (actualKind != expectedKind) {
        throw malformed("Asset-service returned another property asset kind");
      }
      List<PropertyAssetContentSnapshot> contents = response.contents().stream()
          .map(
              line ->
                  new PropertyAssetContentSnapshot(
                      line.equipmentId(),
                      line.equipmentName(),
                      line.equipmentFormat(),
                      requiredNonNegative(line.balanceVersion(), "Property content balance version"),
                      requiredPositive(line.quantity(), "Property content quantity")))
          .toList();
      return new PropertyAssetSnapshot(
          actualKind,
          response.assetId(),
          response.assetDisplayName(),
          response.warehouseId(),
          requiredNonNegative(response.version(), "Property asset version"),
          response.status(),
          response.quantity(),
          response.sourceBalanceVersion(),
          contents,
          response.activeReservation(),
          response.activeHold(),
          response.activeLease(),
          response.dispositionAllowed());
    } catch (IllegalArgumentException exception) {
      if (exception instanceof MaintenanceDependencyException dependency) {
        throw dependency;
      }
      throw malformed("Asset-service returned invalid property asset snapshot truth");
    }
  }

  private static PropertyDispositionFence propertyFence(
      PropertyDispositionFenceResponse response,
      UUID expectedDecisionId,
      PropertyDispositionPreparation request) {
    if (response == null
        || !expectedDecisionId.equals(response.decisionId())
        || !request.warehouseId().equals(response.warehouseId())
        || !request.assetId().equals(response.assetId())
        || response.assetKind() == null
        || response.disposition() == null
        || response.contents() == null
        || response.preparedAt() == null) {
      throw malformed("Asset-service returned malformed property disposition fence");
    }
    try {
      PropertyAssetKind assetKind = PropertyAssetKind.valueOf(response.assetKind());
      PropertyDispositionKind disposition = PropertyDispositionKind.valueOf(response.disposition());
      if (assetKind != request.assetKind() || disposition != request.disposition()) {
        throw malformed("Asset-service returned another property disposition fence");
      }
      List<PropertyDispositionContent> contents = response.contents().stream()
          .map(
              line ->
                  new PropertyDispositionContent(
                      line.equipmentId(),
                      requiredNonNegative(line.expectedBalanceVersion(), "Prepared content balance version"),
                      requiredPositive(line.currentQuantity(), "Prepared content quantity"),
                      requiredNonNegative(line.moveQuantity(), "Prepared content movement quantity")))
          .toList();
      return new PropertyDispositionFence(
          response.decisionId(),
          response.state(),
          response.requestSha256(),
          response.warehouseId(),
          assetKind,
          response.assetId(),
          disposition,
          contents,
          response.preparedAt(),
          response.appliedAt());
    } catch (IllegalArgumentException exception) {
      if (exception instanceof MaintenanceDependencyException dependency) {
        throw dependency;
      }
      throw malformed("Asset-service returned invalid property disposition fence truth");
    }
  }

  private static PropertyEquipmentMovementTask propertyEquipmentMovementTask(
      PropertyEquipmentMovementTaskResponse response, UUID expectedWarehouseId) {
    if (response == null
        || response.id() == null
        || response.warehouseId() == null
        || !expectedWarehouseId.equals(response.warehouseId())
        || response.state() == null
        || response.state().isBlank()) {
      throw malformed("Logistics-service returned malformed equipment movement task truth");
    }
    return new PropertyEquipmentMovementTask(
        response.id(),
        response.warehouseId(),
        response.state(),
        response.terminalState(),
        response.taskBoardDoneAt());
  }

  private static long requiredNonNegative(Long value, String field) {
    if (value == null || value < 0) {
      throw malformed(field + " is invalid");
    }
    return value;
  }

  private static long requiredPositive(Long value, String field) {
    if (value == null || value < 1) {
      throw malformed(field + " is invalid");
    }
    return value;
  }

  private static TaskSnapshot task(
      TaskResponse response, UUID externalTaskId, int expectedRouteSize) {
    if (response == null || !externalTaskId.equals(response.externalTaskId())
        || response.taskVersion() < 0 || response.route() == null
        || (expectedRouteSize >= 0 && response.route().size() != expectedRouteSize)) {
      throw malformed("Task-board returned malformed registration truth");
    }
    List<TaskStageSnapshot> route = response.route().stream()
        .map(stage -> new TaskStageSnapshot(
            stage.routeIndex(), stage.entryId(), stage.entryVersion()))
        .toList();
    for (int index = 0; index < route.size(); index++) {
      if (route.get(index).routeIndex() != index) {
        throw malformed("Task-board route indices are not canonical");
      }
    }
    return new TaskSnapshot(
        externalTaskId, response.taskVersion(), response.status(), route);
  }

  private static void validateRoutingPreflight(
      RoutingPreflight response, UUID warehouseId, Set<UUID> requestedQueueIds) {
    if (response == null
        || !warehouseId.equals(response.warehouseId())
        || response.missingQueueDefinitionIds() == null
        || response.missingWarehouseBindingDefinitionIds() == null
        || response.mismatches() == null
        || response.queues() == null
        || response.ready()
            != (response.missingQueueDefinitionIds().isEmpty()
                && response.missingWarehouseBindingDefinitionIds().isEmpty()
                && response.mismatches().isEmpty())) {
      throw malformed("Task-board returned malformed maintenance routing truth");
    }
    Set<UUID> missingDefinitions = Set.copyOf(response.missingQueueDefinitionIds());
    Set<UUID> missingBindings =
        Set.copyOf(response.missingWarehouseBindingDefinitionIds());
    Set<UUID> mismatched = response.mismatches().stream()
        .map(RoutingMismatch::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    Set<UUID> returned = response.queues().stream()
        .map(RoutingQueueSnapshot::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    if (missingDefinitions.size() != response.missingQueueDefinitionIds().size()
        || missingBindings.size() != response.missingWarehouseBindingDefinitionIds().size()
        || mismatched.size() != response.mismatches().size()
        || returned.size() != response.queues().size()
        || !requestedQueueIds.containsAll(missingDefinitions)
        || !requestedQueueIds.containsAll(missingBindings)
        || !requestedQueueIds.containsAll(mismatched)
        || !requestedQueueIds.containsAll(returned)
        || missingDefinitions.stream().anyMatch(mismatched::contains)
        || missingBindings.stream().anyMatch(mismatched::contains)
        || java.util.stream.Stream.of(
                missingDefinitions, missingBindings, mismatched, returned)
            .flatMap(Set::stream)
            .collect(java.util.stream.Collectors.toSet())
            .size()
            != requestedQueueIds.size()) {
      throw malformed("Task-board returned unrelated maintenance routing truth");
    }
  }

  private static void validateCatalogRoutingPreflight(
      CatalogRoutingPreflight response, Set<UUID> requestedDefinitionIds) {
    if (response == null
        || response.missingQueueDefinitionIds() == null
        || response.mismatches() == null
        || response.resolvedDefinitions() == null
        || response.ready()
            != (response.missingQueueDefinitionIds().isEmpty()
                && response.mismatches().isEmpty())) {
      throw malformed("Task-board returned malformed catalog routing truth");
    }
    Set<UUID> missing = Set.copyOf(response.missingQueueDefinitionIds());
    Set<UUID> mismatched =
        response.mismatches().stream()
            .map(CatalogRoutingMismatch::queueDefinitionId)
            .collect(java.util.stream.Collectors.toSet());
    Set<UUID> returned =
        response.resolvedDefinitions().stream()
            .map(QueueDefinitionSnapshot::queueDefinitionId)
            .collect(java.util.stream.Collectors.toSet());
    if (missing.size() != response.missingQueueDefinitionIds().size()
        || mismatched.size() != response.mismatches().size()
        || returned.size() != response.resolvedDefinitions().size()
        || !requestedDefinitionIds.containsAll(missing)
        || !requestedDefinitionIds.containsAll(mismatched)
        || !requestedDefinitionIds.containsAll(returned)
        || java.util.stream.Stream.of(missing, mismatched, returned)
            .flatMap(Set::stream)
            .collect(java.util.stream.Collectors.toSet())
            .size()
            != requestedDefinitionIds.size()) {
      throw malformed("Task-board returned unrelated catalog routing truth");
    }
  }

  private static List<RouteStep> route(List<TaskStage> stages) {
    return stages.stream().map(stage -> {
      UUID queueId = stage.queueId();
      if (queueId == null) {
        throw new MaintenanceDependencyException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Repair stage queue identity is required");
      }
      return new RouteStep(
          queueId,
          stage.title(),
          stage.plannedDurationMinutes(),
          stage.works(),
          stage.materials(),
          stage.comments(),
          stage.sourceMedia());
    }).toList();
  }

  private static Integer duration(List<TaskStage> stages) {
    if (stages == null || stages.isEmpty()) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Repair task requires at least one route stage");
    }
    int total = 0;
    boolean present = false;
    try {
      for (TaskStage stage : stages) {
        if (stage == null) {
          throw new MaintenanceDependencyException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "Repair route stage is required");
        }
        if (stage.plannedDurationMinutes() == null) {
          continue;
        }
        if (stage.plannedDurationMinutes() < 1) {
          throw new MaintenanceDependencyException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "Repair route stage planned duration must be positive when present");
        }
        present = true;
        total = Math.addExact(total, stage.plannedDurationMinutes());
      }
    } catch (ArithmeticException exception) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Repair task planned duration exceeds the supported range",
          exception);
    }
    return present ? total : null;
  }

  private static OffsetDateTime commonDeadline(List<TaskStage> stages) {
    List<OffsetDateTime> deadlines = stages.stream()
        .map(TaskStage::taskDeadline)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (deadlines.size() > 1) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Repair plan stage deadlines must be absent or one identical timestamp");
    }
    return deadlines.isEmpty() ? null : deadlines.getFirst();
  }

  private static RuntimeException dependencyFailure(RuntimeException exception) {
    if (exception instanceof MaintenanceDependencyException known) return known;
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      return new MaintenanceDependencyException(
          status == null ? HttpStatus.SERVICE_UNAVAILABLE : status,
          "Maintenance dependency rejected the command", exception);
    }
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE, "Maintenance dependency outcome is unknown", exception);
  }

  private static RuntimeException furnitureDependencyFailure(RuntimeException exception) {
    if (exception instanceof MaintenanceDependencyException known) return known;
    if (exception instanceof RestClientResponseException response
        && response.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
      return new MaintenanceDependencyException(
          HttpStatus.CONFLICT,
          "Asset-service rejected the furniture equipment identity",
          exception);
    }
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Furniture equipment synchronization outcome is unknown",
        exception);
  }

  private static MaintenanceDependencyException malformed(String message) {
    return new MaintenanceDependencyException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static String canonicalEquipmentName(String value) {
    String canonical = value == null ? "" : value.trim();
    if (canonical.isEmpty() || canonical.length() > 255) {
      throw new IllegalArgumentException("Furniture equipment name is invalid");
    }
    return canonical;
  }

  private static String requireTaskUnitNumber(String value) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException("Rental-item number is invalid for task synchronization");
    }
    return value;
  }

  private record AcquireLeaseRequest(
      UUID rentalItemId, String ownerType, UUID ownerId, long expectedRentalItemVersion) {}
  private record EnsureFurnitureEquipmentRequest(UUID externalReferenceId, String equipmentName) {}
  private record MaintenanceFurnitureEquipmentResponse(
      UUID externalReferenceId, UUID equipmentId, String equipmentName) {}
  private record LeaseCommand(
      long expectedVersion, long fencingToken, String ownerType, UUID ownerId) {}
  private record FencedStatusRequest(
      long expectedVersion, String action, UUID leaseId, long fencingToken,
      String ownerType, UUID ownerId, UUID linkedReturnEstimateId, UUID estimateId,
      List<FurnitureLoss> furnitureLosses) {}
  private record LeaseResponse(
      UUID id, long version, UUID rentalItemId, String ownerType, String ownerId,
      long fencingToken, String state, OffsetDateTime expiresAt, OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
  private record RentalItemSnapshotResponse(
      UUID id, Long version, UUID warehouseId, String number, String status) {}
  private record RentalItemResponse(UUID id, long version, UUID warehouseId, String number, String status) {}
  private record PropertyAssetContentSnapshotResponse(
      UUID equipmentId,
      String equipmentName,
      String equipmentFormat,
      Long balanceVersion,
      Long quantity) {}
  private record PropertyAssetSnapshotResponse(
      String assetKind,
      UUID assetId,
      String assetDisplayName,
      UUID warehouseId,
      Long version,
      String status,
      Long quantity,
      Long sourceBalanceVersion,
      List<PropertyAssetContentSnapshotResponse> contents,
      boolean activeReservation,
      boolean activeHold,
      boolean activeLease,
      boolean dispositionAllowed) {}
  private record PreparePropertyContentRequest(
      UUID equipmentId,
      long expectedBalanceVersion,
      long currentQuantity,
      long moveQuantity) {}
  private record PropertyDispositionLeaseProofRequest(
      UUID leaseId, long fencingToken, String ownerType, UUID ownerId) {}
  private record PreparePropertyDispositionRequest(
      UUID warehouseId,
      String assetKind,
      UUID assetId,
      String disposition,
      Long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      Long quantity,
      String contentsMode,
      List<PreparePropertyContentRequest> contents,
      PropertyDispositionLeaseProofRequest authorizedMaintenanceLease) {}
  private record PropertyDispositionPreparedContentResponse(
      UUID equipmentId,
      UUID sourceBalanceId,
      Long expectedBalanceVersion,
      Long currentQuantity,
      Long moveQuantity,
      Long dispositionQuantity) {}
  private record PropertyDispositionFenceResponse(
      UUID decisionId,
      String state,
      String requestSha256,
      UUID warehouseId,
      String assetKind,
      UUID assetId,
      String disposition,
      List<PropertyDispositionPreparedContentResponse> contents,
      Instant preparedAt,
      Instant appliedAt) {}
  private record ApplyPropertyDispositionRequest(UUID completedMovementTaskId) {}
  private record PropertyDispositionEffectResponse(
      UUID effectId,
      UUID decisionId,
      String assetKind,
      UUID assetId,
      String disposition,
      Long assetVersion,
      Instant appliedAt) {}
  private record PropertyEquipmentMovementLineRequest(
      UUID equipmentId,
      UUID sourceRentalItemId,
      long expectedSourceBalanceVersion,
      long quantity) {}
  private record CreatePropertyEquipmentMovementTaskRequest(
      UUID decisionId,
      UUID warehouseId,
      String unitNumber,
      int plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<PropertyEquipmentMovementLineRequest> lines) {}
  private record PropertyEquipmentMovementTaskResponse(
      UUID id,
      Long version,
      UUID warehouseId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      Long taskBoardTaskVersion,
      OffsetDateTime taskBoardDoneAt,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      String state,
      String terminalState,
      String failureCode,
      List<Object> lines,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
  private record CabinCharacteristicResponse(UUID id, String name) {}
  private record RouteStep(
      UUID queueDefinitionId,
      String taskText,
      Integer plannedDurationMinutes,
      List<TaskWork> works,
      List<TaskMaterial> materials,
      List<TaskComment> comments,
      List<TaskSourceMedia> sourceMedia) {}
  private record RoutingPreflightRequest(
      UUID warehouseId, List<RoutingQueueRequirement> queues) {}
  private record CatalogRoutingPreflightRequest(
      List<CatalogRoutingQueueRequirement> queues) {}
  private record QueueReferenceRequest(String type, String externalReferenceId) {}
  private record TaskSourceReference(String type, UUID sourceId) {}
  private record RegisterTaskRequest(
      UUID warehouseId, UUID externalTaskId, TaskSourceReference source, String title,
      String unitNumber, String description,
      Integer plannedDurationMinutes, OffsetDateTime deadlineAt, List<RouteStep> route,
      LocalDate scheduledDate, int priority, int dailyCapacity) {}
  private record UpdateTaskRequest(
      long expectedTaskVersion, String title, String unitNumber, String description,
      Integer plannedDurationMinutes, OffsetDateTime deadlineAt, List<RouteStep> route) {}
  private record RouteResponse(
      UUID entryId,
      long entryVersion,
      UUID queueDefinitionId,
      UUID workQueueId,
      int routeIndex) {}
  private record TaskResponse(
      UUID taskId, long taskVersion, UUID warehouseId, UUID externalTaskId, String title,
      String unitNumber, String description, String status, Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt, LocalDate scheduledDate, int priority, boolean pinned,
      OffsetDateTime doneAt, List<RouteResponse> route) {}
  private record CancelTaskRequest(long expectedTaskVersion, String reason) {}
  private record PreStartCancellationResponse(
      String outcome,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}
  private record RelocateTaskRequest(long expectedTaskVersion, UUID targetWarehouseId) {}
  private record DriverTaskResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      String sourceType,
      UUID sourceId,
      String kind,
      dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode
          planningMode,
      LocalDate scheduledDate,
      int priority,
      String state) {}
  private record MaintenanceDriverTaskCompensationResponse(
      UUID repairId,
      String kind,
      String outcome,
      UUID taskId,
      Long taskVersion,
      String state,
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
  private record CancelTaskResponse(
      UUID taskId, UUID externalTaskId, long taskVersion, String status,
      OffsetDateTime cancelledAt) {}
}
