package dev.buhanzaz.rwms.inventory.integration;

import dev.buhanzaz.rwms.inventory.service.InventoryException;
import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Client-credential HTTP implementation of the inventory private dependency boundary.
 */
final class HttpInventoryDependencyGateway implements InventoryDependencyGateway {
  private static final String WAREHOUSE_TIME_ZONE_CLIENT = "inventory-warehouse-timezone";
  private static final String WAREHOUSE_OPERATION_CLIENT = "inventory-warehouse-operation";
  private static final String WAREHOUSE_LIFECYCLE_READ_CLIENT =
      "inventory-warehouse-lifecycle-read";
  private static final String WAREHOUSE_LIFECYCLE_CONFIRM_CLIENT =
      "inventory-warehouse-lifecycle-confirm";
  private static final String TASK_BOARD_CALENDAR_CLIENT = "inventory-task-board-calendar";
  private static final String ASSET_CLIENT = "inventory-asset";
  private static final String MAINTENANCE_CLIENT = "inventory-maintenance";
  private static final String LOGISTICS_CLIENT = "inventory-logistics";
  private static final String MEDIA_CLIENT = "inventory-media";
  private static final String WAREHOUSE_TIME_ZONE_SCOPE = "warehouse.timezone.read";
  private static final String WAREHOUSE_OPERATION_SCOPE = "warehouse.operation.mark";
  private static final String WAREHOUSE_LIFECYCLE_READ_SCOPE = "warehouse.lifecycle.read";
  private static final String WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE =
      "warehouse.lifecycle.confirm";
  private static final String TASK_BOARD_CALENDAR_SCOPE = "task-board.inventory-calendar.read";
  private static final String ASSET_SCOPE = "asset.inventory";
  private static final String MAINTENANCE_SCOPE = "maintenance.inventory";
  private static final String LOGISTICS_SCOPE = "logistics.inventory";
  private static final String MEDIA_SCOPE = "media.inventory";

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String warehouseBase;
  private final String taskBoardBase;
  private final String assetBase;
  private final String maintenanceBase;
  private final String logisticsBase;
  private final String mediaBase;

  HttpInventoryDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      InventoryDependencyProperties.Validated properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    warehouseBase = strip(properties.warehouseBaseUrl().toString());
    taskBoardBase = strip(properties.taskBoardBaseUrl().toString());
    assetBase = strip(properties.assetBaseUrl().toString());
    maintenanceBase = strip(properties.maintenanceBaseUrl().toString());
    logisticsBase = strip(properties.logisticsBaseUrl().toString());
    mediaBase = strip(properties.mediaBaseUrl().toString());
  }

  @Override
  public WarehouseOperation beginWarehouseOperation(
      UUID warehouseId,
      UUID operationId,
      OffsetDateTime occurredAt,
      WarehouseOperationDirection direction) {
    if (warehouseId == null || operationId == null || occurredAt == null || direction == null) {
      throw new IllegalArgumentException("Warehouse operation identity is incomplete");
    }
    WarehouseAdmission admission = warehouseAdmission(warehouseId, direction);

    postNoContent(
        warehouseBase
            + "/api/internal/warehouse/v1/warehouses/"
            + warehouseId
            + "/operation-marks",
        new WarehouseOperationMarkRequest(operationId, occurredAt),
        WAREHOUSE_OPERATION_CLIENT,
        WAREHOUSE_OPERATION_SCOPE);

    String timeZoneUri =
        UriComponentsBuilder.fromUriString(
                warehouseBase
                    + "/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/time-zone")
            .queryParam("at", occurredAt)
            .build()
            .encode()
            .toUriString();
    WarehouseTimeZoneAt timeZone =
        get(
            timeZoneUri,
            WarehouseTimeZoneAt.class,
            WAREHOUSE_TIME_ZONE_CLIENT,
            WAREHOUSE_TIME_ZONE_SCOPE);
    if (!warehouseId.equals(timeZone.warehouseId())
        || timeZone.effectiveFrom() == null
        || timeZone.effectiveFrom().isAfter(occurredAt)) {
      throw malformed("Warehouse-service returned malformed effective timezone");
    }
    try {
      ZoneId.of(timeZone.timeZone());
    } catch (ZoneRulesException | NullPointerException exception) {
      throw malformed("Warehouse-service returned invalid IANA timezone");
    }
    return new WarehouseOperation(
        warehouseId,
        admission.warehouseVersion(),
        admission.lifecycleState(),
        direction,
        timeZone.timeZone(),
        timeZone.effectiveFrom());
  }

  @Override
  public WarehouseAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    if (warehouseId == null || direction == null) {
      throw new IllegalArgumentException("Warehouse admission identity is incomplete");
    }
    String admissionUri =
        UriComponentsBuilder.fromUriString(
                warehouseBase
                    + "/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/admission")
            .queryParam("direction", direction.name())
            .build()
            .encode()
            .toUriString();
    WarehouseAdmission admission =
        get(
            admissionUri,
            WarehouseAdmission.class,
            WAREHOUSE_LIFECYCLE_READ_CLIENT,
            WAREHOUSE_LIFECYCLE_READ_SCOPE);
    if (!warehouseId.equals(admission.warehouseId())
        || admission.warehouseVersion() < 0
        || admission.direction() != direction
        || admission.lifecycleState() == null
        || !Set.of("ACTIVE", "DRAINING", "INACTIVE").contains(admission.lifecycleState())) {
      throw malformed("Warehouse-service returned malformed operation admission");
    }
    if (!admission.admitted()) {
      throw InventoryException.conflict(
          "Warehouse lifecycle does not admit this inventory operation");
    }
    return admission;
  }

  @Override
  public WorkCalendarSnapshot workCalendarSnapshot(UUID warehouseId, LocalDate from, LocalDate through) {
    if (warehouseId == null || from == null || through == null || through.isBefore(from)) {
      throw new IllegalArgumentException("Work-calendar range is invalid");
    }
    if (from.plusDays(365).isBefore(through)) {
      throw new IllegalArgumentException("Work-calendar range must not exceed 366 days");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                taskBoardBase
                    + "/api/internal/task-board/v1/inventory/warehouses/"
                    + warehouseId
                    + "/work-calendar")
            .queryParam("from", from)
            .queryParam("through", through)
            .build()
            .encode()
            .toUriString();
    WorkCalendarSnapshot snapshot =
        get(uri, WorkCalendarSnapshot.class, TASK_BOARD_CALENDAR_CLIENT, TASK_BOARD_CALENDAR_SCOPE);
    validateWorkCalendarSnapshot(warehouseId, from, through, snapshot);
    return snapshot;
  }

  @Override
  public WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Warehouse readiness page size must be from 1 to 500");
    }
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUriString(
                warehouseBase + "/api/internal/warehouse/v1/lifecycle/readiness-work")
            .queryParam("limit", limit);
    if (after != null) {
      uri.queryParam("after", after);
    }
    WarehouseLifecycleReadinessWorkPage page =
        get(
            uri.build().encode().toUriString(),
            WarehouseLifecycleReadinessWorkPage.class,
            WAREHOUSE_LIFECYCLE_READ_CLIENT,
            WAREHOUSE_LIFECYCLE_READ_SCOPE);
    if (page.items() == null) {
      throw malformed("Warehouse-service returned malformed lifecycle readiness work");
    }
    Set<UUID> ids = new java.util.HashSet<>();
    for (WarehouseLifecycleReadinessWork item : page.items()) {
      if (item == null
          || item.warehouseId() == null
          || item.warehouseVersion() < 0
          || !"DRAINING".equals(item.lifecycleState())
          || !ids.add(item.warehouseId())) {
        throw malformed("Warehouse-service returned malformed lifecycle readiness item");
      }
    }
    if (page.nextAfter() != null
        && (page.items().isEmpty()
            || !page.nextAfter().equals(page.items().getLast().warehouseId()))) {
      throw malformed("Warehouse-service returned an invalid lifecycle readiness cursor");
    }
    return page;
  }

  @Override
  public WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    if (warehouseId == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness fence is invalid");
    }
    WarehouseLifecycleReadinessConfirmation response =
        post(
            warehouseBase
                + "/api/internal/warehouse/v1/warehouses/"
                + warehouseId
                + "/lifecycle-readiness",
            null,
            new WarehouseLifecycleReadinessRequest(expectedVersion),
            WarehouseLifecycleReadinessConfirmation.class,
            WAREHOUSE_LIFECYCLE_CONFIRM_CLIENT,
            WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE);
    if (!warehouseId.equals(response.warehouseId())
        || response.warehouseVersion() < 0
        || !"DRAINING".equals(response.lifecycleState())
        || !"INVENTORY".equals(response.readinessOwner())
        || response.confirmedAt() == null) {
      throw malformed("Warehouse-service returned malformed inventory readiness confirmation");
    }
    return response;
  }

  @Override
  public Capture createCapture(UUID idempotencyKey, CaptureRequest request) {
    Capture response =
        post(
            assetBase + "/api/internal/asset/v1/inventory/captures",
            idempotencyKey,
            request,
            Capture.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (!request.operationId().equals(response.operationId())
        || !request.warehouseId().equals(response.warehouseId())
        || request.technicalAttempt() != response.technicalAttempt()
        || response.totalCount() < 0
        || !sha256(response.membershipDigest())
        || response.captureId() == null
        || response.createdAt() == null
        || response.expiresAt() == null
        || !response.expiresAt().isAfter(response.createdAt())) {
      throw malformed("Asset-service returned malformed capture metadata");
    }
    return response;
  }

  @Override
  public CapturePage readCapture(UUID captureId, String cursor, int size) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromUriString(
                assetBase
                    + "/api/internal/asset/v1/inventory/captures/"
                    + captureId
                    + "/members")
            .queryParam("size", size);
    if (cursor != null) uriBuilder.queryParam("cursor", cursor);
    String uri = uriBuilder.build().encode().toUriString();
    CapturePage response = get(uri, CapturePage.class, ASSET_CLIENT, ASSET_SCOPE);
    if (!captureId.equals(response.captureId()) || response.content() == null) {
      throw malformed("Asset-service returned malformed capture page");
    }
    return response;
  }

  @Override
  public void releaseCapture(UUID captureId) {
    try {
      client
          .delete()
          .uri(assetBase + "/api/internal/asset/v1/inventory/captures/" + captureId)
          .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public NumberResolution resolveNumber(UUID warehouseId, String number) {
    NumberResolution response =
        post(
            assetBase + "/api/internal/asset/v1/inventory/number-resolutions",
            null,
            new NumberRequest(warehouseId, number),
            NumberResolution.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (response.displayCanonicalNumber() == null
        || response.displayCanonicalNumber().isBlank()
        || response.identityMatchKey() == null
        || response.identityMatchKey().isBlank()
        || response.found() != (response.asset() != null)
        || (response.asset() != null
            && (!response
                    .displayCanonicalNumber()
                    .equals(response.asset().displayCanonicalNumber())
                || !response.identityMatchKey().equals(response.asset().identityMatchKey())
                || response.asset().warehouseId() == null
                || response.asset().version() < 0))) {
      throw malformed("Asset-service returned malformed number resolution truth");
    }
    return response;
  }

  @Override
  public Optional<LiveAssetSnapshot> currentAsset(UUID assetId) {
    LiveAssetSnapshot response;
    try {
      response =
          client
              .get()
              .uri(assetBase + "/api/internal/asset/v1/inventory/assets/" + assetId)
              .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
              .retrieve()
              .body(LiveAssetSnapshot.class);
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
      throw dependencyFailure(exception);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
    if (response == null
        || !assetId.equals(response.assetId())
        || response.version() < 0
        || response.warehouseId() == null
        || response.status() == null
        || response.status().isBlank()
        || response.displayCanonicalNumber() == null
        || response.displayCanonicalNumber().isBlank()
        || response.identityMatchKey() == null
        || response.identityMatchKey().isBlank()
        || response.passportSnapshot() == null
        || !response.passportSnapshot().isObject()
        || response.contentsSnapshot() == null
        || !response.contentsSnapshot().isArray()) {
      throw malformed("Asset-service returned malformed live inventory asset truth");
    }
    return Optional.of(response);
  }

  @Override
  public NormalReturnInspection normalReturnInspection(UUID returnId) {
    if (returnId == null) {
      throw new IllegalArgumentException("Return identity is required");
    }
    NormalReturnInspection response =
        get(
            logisticsBase
                + "/api/internal/logistics/v1/inventory/returns/"
                + returnId
                + "/inspection",
            NormalReturnInspection.class,
            LOGISTICS_CLIENT,
            LOGISTICS_SCOPE);
    validateNormalReturnInspection(returnId, response);
    return response;
  }

  @Override
  public Optional<CompletedReturnEstimateProof> completedReturnEstimate(UUID estimateId) {
    if (estimateId == null) {
      throw new IllegalArgumentException("Estimate identity is required");
    }
    CompletedReturnEstimateProof response;
    try {
      response =
          client
              .get()
              .uri(
                  maintenanceBase
                      + "/api/internal/maintenance/v1/inventory/return-estimates/"
                      + estimateId)
              .header(
                  HttpHeaders.AUTHORIZATION,
                  bearer(MAINTENANCE_CLIENT, MAINTENANCE_SCOPE))
              .retrieve()
              .body(CompletedReturnEstimateProof.class);
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
      throw dependencyFailure(exception);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
    validateCompletedReturnEstimate(estimateId, response);
    return Optional.of(response);
  }

  @Override
  public SourceAsset createSourceAsset(UUID idempotencyKey, JsonNode request) {
    SourceAsset response =
        post(
            assetBase + "/api/internal/asset/v1/inventory/source-assets",
            idempotencyKey,
            request,
            SourceAsset.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    UUID inventoryId = uuid(request, "inventoryId");
    UUID findingId = uuid(request, "findingId");
    UUID warehouseId = uuid(request, "warehouseId");
    if (!inventoryId.equals(response.inventoryId())
        || !findingId.equals(response.findingId())
        || response.asset() == null
        || !warehouseId.equals(response.asset().warehouseId())
        || response.asset().version() < 0
        || response.asset().displayCanonicalNumber() == null
        || response.asset().identityMatchKey() == null) {
      throw malformed("Asset-service returned malformed permanent source truth");
    }
    return response;
  }

  @Override
  public Validation validateAssets(List<UUID> assetIds) {
    Validation response =
        post(
            assetBase + "/api/internal/asset/v1/inventory/validations",
            null,
            new ValidationRequest(assetIds),
            Validation.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    List<UUID> expected = assetIds.stream().sorted().toList();
    if (response.validatedAt() == null
        || !sha256(response.validationDigest())
        || response.assets() == null
        || !response.assets().stream().map(ValidationItem::assetId).toList().equals(expected)
        || response.assets().stream()
            .anyMatch(
                item ->
                    item.assetId() == null
                        || (item.found()
                            != (item.version() != null
                                && item.warehouseId() != null
                                && item.status() != null
                                && item.displayCanonicalNumber() != null
                                && item.identityMatchKey() != null
                                && item.passportSnapshot() != null
                                && item.passportSnapshot().isObject()
                                && item.contentsSnapshot() != null
                                && item.contentsSnapshot().isArray()))
                        || (!item.found()
                            && (!absent(item.passportSnapshot())
                                || !absent(item.contentsSnapshot()))))) {
      throw malformed("Asset-service returned malformed validation truth");
    }
    return response;
  }

  @Override
  public RepairSnapshots repairSnapshots(List<UUID> assetIds) {
    RepairSnapshots response =
        post(
            maintenanceBase + "/api/internal/maintenance/v1/inventory/repair-snapshots",
            null,
            new RepairSnapshotRequest(assetIds),
            RepairSnapshots.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    List<UUID> expected = assetIds.stream().sorted().toList();
    if (response.assets() == null
        || !response.assets().stream().map(RepairAssetSnapshot::assetId).toList().equals(expected)
        || response.assets().stream().anyMatch(this::malformedRepairAssetSnapshot)) {
      throw malformed("Maintenance-service returned malformed inventory repair truth");
    }
    return response;
  }

  @Override
  public FurnitureSnapshot furnitureSnapshot(UUID warehouseId, List<UUID> assetIds) {
    FurnitureSnapshot response =
        post(
            assetBase + "/api/internal/asset/v1/inventory/furniture-snapshots",
            null,
            new FurnitureSnapshotRequest(warehouseId, assetIds),
            FurnitureSnapshot.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (!warehouseId.equals(response.warehouseId())
        || !sha256(response.snapshotSha256())
        || response.items() == null) {
      throw malformed("Asset-service returned malformed furniture snapshot");
    }
    Set<UUID> requested = Set.copyOf(assetIds);
    Set<UUID> equipmentIds = new java.util.HashSet<>();
    for (FurnitureSnapshotItem item : response.items()) {
      if (item == null
          || item.equipmentId() == null
          || item.catalogVersion() < 0
          || item.equipmentName() == null
          || item.equipmentName().isBlank()
          || item.currentStockQuantity() < 0
          || (item.stockBalanceVersion() != null && item.stockBalanceVersion() < 0)
          || (item.currentStockQuantity() > 0 && item.stockBalanceVersion() == null)
          || item.cabins() == null
          || !equipmentIds.add(item.equipmentId())) {
        throw malformed("Asset-service returned malformed furniture snapshot item");
      }
      Set<UUID> cabinIds = new java.util.HashSet<>();
      for (FurnitureSnapshotCabin cabin : item.cabins()) {
        if (cabin == null
            || cabin.assetId() == null
            || !requested.contains(cabin.assetId())
            || cabin.assetVersion() < 0
            || cabin.displayCanonicalNumber() == null
            || cabin.displayCanonicalNumber().isBlank()
            || cabin.status() == null
            || cabin.status().isBlank()
            || cabin.currentQuantity() < 0
            || !cabinIds.add(cabin.assetId())) {
          throw malformed("Asset-service returned malformed furniture cabin snapshot");
        }
      }
    }
    return response;
  }

  @Override
  public void reconcileFurniture(
      UUID inventoryId, UUID idempotencyKey, FurnitureReconciliationRequest request) {
    if (inventoryId == null
        || idempotencyKey == null
        || request == null
        || request.warehouseId() == null
        || !sha256(request.expectedSnapshotSha256())
        || !sha256(request.reviewSha256())
        || request.items() == null) {
      throw malformed("Furniture reconciliation request is incomplete");
    }
    try {
      client
          .put()
          .uri(
              assetBase
                  + "/api/internal/asset/v1/inventory/furniture-reconciliations/"
                  + inventoryId)
          .header("Idempotency-Key", idempotencyKey.toString())
          .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
          .body(request)
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public InventoryLossDisposition createInventoryLossDisposition(
      UUID idempotencyKey, InventoryLossDispositionRequest request) {
    if (idempotencyKey == null
        || request == null
        || request.inventorySessionId() == null
        || request.findingId() == null
        || request.warehouseId() == null
        || request.equipmentId() == null
        || request.equipmentName() == null
        || request.equipmentName().isBlank()
        || request.expectedAssetVersion() < 0
        || request.quantity() < 1
        || request.expectedSourceBalanceVersion() < 0
        || request.reason() == null
        || request.reason().isBlank()) {
      throw malformed("Inventory loss disposition request is incomplete");
    }
    InventoryLossDisposition response =
        post(
            maintenanceBase + "/api/internal/maintenance/v1/inventory/dispositions",
            idempotencyKey,
            request,
            InventoryLossDisposition.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    if (response.id() == null
        || !request.inventorySessionId().equals(response.inventorySessionId())
        || !request.findingId().equals(response.findingId())
        || !request.warehouseId().equals(response.warehouseId())
        || !request.equipmentId().equals(response.assetId())
        || !"LOSS".equals(response.disposition())
        || response.state() == null
        || response.state().isBlank()) {
      throw malformed("Maintenance-service returned malformed inventory loss decision");
    }
    return response;
  }

  @Override
  public InventoryCabinWriteOffOutcome createInventoryCabinWriteOff(
      UUID idempotencyKey, InventoryCabinWriteOffRequest request) {
    JsonNode response =
        postIdempotentWithSingleTransientRetry(
            maintenanceBase + "/api/internal/maintenance/v1/inventory/cabin-write-offs",
            idempotencyKey,
            request,
            JsonNode.class,
            bearer(MAINTENANCE_CLIENT, MAINTENANCE_SCOPE));
    try {
      UUID id = uuid(response, "id");
      long version = response.path("version").asLong(-1);
      UUID inventorySessionId = uuid(response, "inventorySessionId");
      UUID findingId = uuid(response, "findingId");
      UUID warehouseId = uuid(response, "warehouseId");
      UUID cabinId = uuid(response, "assetId");
      String disposition = response.path("disposition").asText();
      String state = response.path("state").asText();
      if (version < 0
          || !"CABIN".equals(response.path("assetKind").asText())
          || !"WRITE_OFF".equals(disposition)
          || !"INVENTORY".equals(response.path("source").asText())
          || state.isBlank()) {
        throw new IllegalArgumentException();
      }
      return new InventoryCabinWriteOffOutcome(
          id,
          version,
          inventorySessionId,
          findingId,
          warehouseId,
          cabinId,
          disposition,
          state);
    } catch (RuntimeException exception) {
      if (exception instanceof InventoryException inventoryException) {
        throw inventoryException;
      }
      throw malformed("Maintenance-service returned malformed cabin write-off decision");
    }
  }

  private boolean malformedRepairAssetSnapshot(RepairAssetSnapshot asset) {
    if (asset.assetId() == null || asset.repairs() == null) return true;
    UUID previous = null;
    for (RepairRegistryFact repair : asset.repairs()) {
      if (repair == null
          || repair.repairId() == null
          || repair.rootRepairId() == null
          || repair.origin() == null
          || repair.origin().isBlank()
          || repair.kind() == null
          || repair.kind().isBlank()
          || repair.executionState() == null
          || repair.executionState().isBlank()
          || repair.acceptanceState() == null
          || repair.acceptanceState().isBlank()
          || !sha256(repair.planFingerprintSha256())
          || (previous != null && previous.compareTo(repair.repairId()) >= 0)) {
        return true;
      }
      previous = repair.repairId();
    }
    return false;
  }

  @Override
  public FrozenPlan freezePlan(UUID idempotencyKey, JsonNode request) {
    String authorization = bearer(MAINTENANCE_CLIENT, MAINTENANCE_SCOPE);
    JsonNode response =
        postIdempotentWithSingleTransientRetry(
            maintenanceBase + "/api/internal/maintenance/v1/inventory/plans",
            idempotencyKey,
            request,
            JsonNode.class,
            authorization);
    JsonNode snapshot = response.get("snapshot");
    String fingerprint = response.path("fingerprint").asText();
    UUID warehouseId = uuid(response, "warehouseId");
    UUID inventoryId = uuid(response, "inventoryId");
    UUID findingId = uuid(response, "findingId");
    long sourceRevision = response.path("sourceRevision").asLong(-1);
    if (snapshot == null || !snapshot.isObject() || !sha256(fingerprint) || sourceRevision < 1) {
      throw malformed("Maintenance-service returned malformed frozen plan");
    }
    return new FrozenPlan(
        warehouseId, inventoryId, findingId, sourceRevision, snapshot, fingerprint);
  }

  /**
   * Executes an explicitly idempotent maintenance request with one immediate retry after a
   * transport failure or HTTP 502/503/504. The request body, idempotency key, and bearer token
   * deliberately remain unchanged across both attempts.
   */
  private <T> T postIdempotentWithSingleTransientRetry(
      String uri, UUID key, Object body, Class<T> type, String authorization) {
    try {
      return postIdempotentAttempt(uri, key, body, type, authorization);
    } catch (RuntimeException firstFailure) {
      if (!retryableTransientFailure(firstFailure)) {
        throw dependencyFailure(firstFailure);
      }
    }
    try {
      return postIdempotentAttempt(uri, key, body, type, authorization);
    } catch (RuntimeException retryFailure) {
      throw dependencyFailure(retryFailure);
    }
  }

  private <T> T postIdempotentAttempt(
      String uri, UUID key, Object body, Class<T> type, String authorization) {
    RestClient.RequestBodySpec request =
        client
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, authorization);
    if (key != null) {
      request.header("Idempotency-Key", key.toString());
    }
    T value = request.body(body).retrieve().body(type);
    if (value == null) {
      throw malformed("Dependency returned an empty response");
    }
    return value;
  }

  private static boolean retryableTransientFailure(RuntimeException failure) {
    if (failure instanceof ResourceAccessException) {
      return true;
    }
    if (failure instanceof RestClientResponseException response) {
      int status = response.getStatusCode().value();
      return status == 502 || status == 503 || status == 504;
    }
    return false;
  }

  @Override
  public JsonNode preflightReconciliation(UUID idempotencyKey, JsonNode request) {
    String authorization = bearer(MAINTENANCE_CLIENT, MAINTENANCE_SCOPE);
    JsonNode response =
        postIdempotentWithSingleTransientRetry(
            maintenanceBase
                + "/api/internal/maintenance/v1/inventory/reconciliations/preflight",
            idempotencyKey,
            request,
            JsonNode.class,
            authorization);
    if (!response.isObject()
        || !sha256(response.path("finalPlanSha256").asText())
        || response.path("finalPlanVersion").asLong(-1) < 1
        || !response.path("findings").isArray()) {
      throw malformed("Maintenance-service returned malformed reconciliation preflight");
    }
    return response;
  }

  @Override
  public JsonNode applyReconciliation(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request) {
    JsonNode response =
        put(
            maintenanceBase
                + "/api/internal/maintenance/v1/inventory/reconciliations/"
                + inventoryId
                + "/findings/"
                + findingId,
            idempotencyKey,
            request,
            JsonNode.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    if (!validReconciliationResult(response)) {
      throw malformed("Maintenance-service returned malformed reconciliation result");
    }
    return response;
  }

  @Override
  public JsonNode applyNoWorkDisposition(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request) {
    JsonNode response =
        put(
            maintenanceBase
                + "/api/internal/maintenance/v1/inventory/outcomes/"
                + inventoryId
                + "/findings/"
                + findingId
                + "/no-work",
            idempotencyKey,
            request,
            JsonNode.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    try {
      List<UUID> estimateIds = uuidList(response, "supersededEstimateIds");
      List<UUID> repairIds = uuidList(response, "supersededRepairIds");
      List<UUID> externalTaskIds = uuidList(response, "cancelledExternalTaskIds");
      List<UUID> driverTaskIds = uuidList(response, "cancelledDriverTaskIds");
      List<UUID> leaseIds = uuidList(response, "releasedLeaseIds");
      if (!response.path("replay").isBoolean()
          || hasDuplicates(estimateIds)
          || hasDuplicates(repairIds)
          || hasDuplicates(externalTaskIds)
          || hasDuplicates(driverTaskIds)
          || hasDuplicates(leaseIds)) {
        throw new IllegalArgumentException();
      }
      uuid(response, "inventoryId");
      uuid(response, "findingId");
      uuid(response, "assetId");
      return response;
    } catch (RuntimeException exception) {
      if (exception instanceof InventoryException inventoryException) {
        throw inventoryException;
      }
      throw malformed("Maintenance-service returned malformed no-work inventory outcome");
    }
  }

  @Override
  public InventoryAssetOutcome applyInventoryOutcome(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request) {
    JsonNode response =
        put(
            assetBase
                + "/api/internal/asset/v1/inventory/outcomes/"
                + inventoryId
                + "/findings/"
                + findingId,
            idempotencyKey,
            request,
            JsonNode.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    try {
      List<UUID> orderReservations = uuidList(response, "releasedOrderUnitReservationIds");
      List<UUID> operationLeases = uuidList(response, "releasedOperationLeaseIds");
      List<UUID> presentationHolds = uuidList(response, "releasedPresentationHoldIds");
      if (!response.path("transferSuperseded").isBoolean()
          || response.path("assetVersion").asLong(-1) < 0
          || response.path("status").asText().isBlank()) {
        throw new IllegalArgumentException();
      }
      return new InventoryAssetOutcome(
          uuid(response, "inventoryId"),
          uuid(response, "findingId"),
          uuid(response, "assetId"),
          response.path("assetVersion").asLong(),
          response.path("status").asText(),
          orderReservations,
          operationLeases,
          presentationHolds,
          response.path("transferSuperseded").asBoolean(),
          response);
    } catch (RuntimeException exception) {
      if (exception instanceof InventoryException inventoryException) {
        throw inventoryException;
      }
      throw malformed("Asset-service returned malformed inventory outcome");
    }
  }

  @Override
  public InventoryCabinPhotoOutcome publishInventoryCabinPhotos(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request) {
    JsonNode response =
        put(
            mediaBase
                + "/api/internal/media/v1/inventory/outcomes/"
                + inventoryId
                + "/findings/"
                + findingId
                + "/cabin-photos",
            idempotencyKey,
            request,
            JsonNode.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE);
    try {
      long photoCount = response.path("photoCount").asLong(-1);
      long libraryVersion = response.path("libraryVersion").asLong(-1);
      if (photoCount < 1 || libraryVersion < 1 || !response.path("replay").isBoolean()) {
        throw new IllegalArgumentException();
      }
      return new InventoryCabinPhotoOutcome(
          uuid(response, "inventoryId"),
          uuid(response, "findingId"),
          uuid(response, "cabinId"),
          uuid(response, "folderId"),
          uuid(response, "coverMediaId"),
          photoCount,
          libraryVersion,
          response.path("replay").asBoolean());
    } catch (RuntimeException exception) {
      if (exception instanceof InventoryException inventoryException) {
        throw inventoryException;
      }
      throw malformed("Media-service returned malformed inventory cabin photo result");
    }
  }

  @Override
  public JsonNode applyLogisticsOutcomes(
      UUID inventoryId, UUID idempotencyKey, JsonNode request) {
    JsonNode response =
        put(
            logisticsBase
                + "/api/internal/logistics/v1/inventory/outcomes/"
                + inventoryId,
            idempotencyKey,
            request,
            JsonNode.class,
            LOGISTICS_CLIENT,
            LOGISTICS_SCOPE);
    try {
      List<UUID> documentIds = uuidList(response, "supersededDocumentIds");
      List<UUID> rentalOrderIds = uuidList(response, "supersededRentalOrderIds");
      List<UUID> driverTaskIds = uuidList(response, "cancelledDriverTaskIds");
      if (response.path("finalPlanVersion").asLong(-1) < 1
          || response.path("supersededLineCount").asLong(-1) < 0
          || response.path("supersededRentalUnitCount").asLong(-1) < 0
          || !response.path("replay").isBoolean()
          || hasDuplicates(documentIds)
          || hasDuplicates(rentalOrderIds)
          || hasDuplicates(driverTaskIds)) {
        throw new IllegalArgumentException();
      }
      uuid(response, "inventoryId");
      return response;
    } catch (RuntimeException exception) {
      if (exception instanceof InventoryException inventoryException) {
        throw inventoryException;
      }
      throw malformed("Logistics-service returned malformed inventory outcomes");
    }
  }

  @Override
  public RepairUpsert upsertRepair(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request) {
    JsonNode response =
        put(
            maintenanceBase
                + "/api/internal/maintenance/v1/inventory/sources/"
                + inventoryId
                + "/findings/"
                + findingId,
            idempotencyKey,
            request,
            JsonNode.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    try {
      return new RepairUpsert(
          UUID.fromString(response.path("repair").path("id").asText()), response);
    } catch (IllegalArgumentException exception) {
      throw malformed("Maintenance-service returned malformed repair source response");
    }
  }

  private <T> T get(String uri, Class<T> type, String registration, String scope) {
    try {
      T value =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .retrieve()
              .body(type);
      if (value == null) throw malformed("Dependency returned an empty response");
      return value;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T post(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      RestClient.RequestBodySpec request =
          client.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(registration, scope));
      if (key != null) request.header("Idempotency-Key", key.toString());
      T value = request.body(body).retrieve().body(type);
      if (value == null) throw malformed("Dependency returned an empty response");
      return value;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private void postNoContent(
      String uri, Object body, String registration, String scope) {
    try {
      client
          .post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T put(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T value =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (value == null) throw malformed("Dependency returned an empty response");
      return value;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private String bearer(String registration, String requiredScope) {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(registration)
            .principal("inventory-service:" + registration)
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(requiredScope))) {
      throw malformed("Dependency token does not have its one exact approved scope");
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private static RuntimeException dependencyFailure(RuntimeException failure) {
    if (failure instanceof InventoryException exception) return exception;
    if (failure instanceof RestClientResponseException response) {
      int status = response.getStatusCode().value();
      InventoryException structured = structuredRejection(response, status);
      if (structured != null) return structured;
      if (status == 404) {
        return InventoryException.notFound("Required dependency resource was not found");
      }
      if (status == 409) {
        return InventoryException.conflict("Dependency rejected stale inventory state");
      }
      if (status == 422 || status == 400) {
        return new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_VALIDATION_FAILED",
            "Dependency rejected invalid inventory input");
      }
    }
    return InventoryException.dependency("Mandatory inventory dependency is unavailable");
  }

  /**
   * Retains only the bounded stable code and human-safe detail from an RWMS Problem Details body.
   * Invalid or non-RWMS response bodies deliberately fall back to the generic mapping above.
   */
  private static InventoryException structuredRejection(
      RestClientResponseException response, int responseStatus) {
    if (responseStatus < 400 || responseStatus >= 500) return null;
    try {
      ApiProblem problem = response.getResponseBodyAs(ApiProblem.class);
      if (problem == null
          || problem.status() != responseStatus
          || problem.code() == null
          || !problem.code().matches("^[A-Z][A-Z0-9_]{0,63}$")
          || problem.detail() == null) {
        return null;
      }
      String detail = problem.detail().trim().replaceAll("[\\p{Z}\\s]+", " ");
      if (detail.isEmpty() || detail.length() > 512) return null;
      HttpStatus status = HttpStatus.resolve(responseStatus);
      if (status == null) return null;
      return new InventoryException(status, problem.code(), detail);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static InventoryException malformed(String message) {
    return InventoryException.dependency(message);
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("^[0-9a-f]{64}$");
  }

  private static void validateNormalReturnInspection(
      UUID returnId, NormalReturnInspection response) {
    if (!returnId.equals(response.returnId())
        || response.documentVersion() < 0
        || response.warehouseId() == null
        || response.arrivedAt() == null
        || response.completedAt() == null
        || response.completedAt().isBefore(response.arrivedAt())
        || !Set.of("ACCEPTED", "ESTIMATE_REQUESTED").contains(response.terminalState())
        || response.lines() == null
        || response.lines().isEmpty()
        || response.lines().size() > 100) {
      throw malformed("Logistics-service returned malformed normal-return inspection proof");
    }
    Set<UUID> lineIds = new java.util.HashSet<>();
    Set<UUID> assetIds = new java.util.HashSet<>();
    String expectedStatus =
        "ACCEPTED".equals(response.terminalState())
            ? "FREE"
            : "WAITING_ESTIMATE_CONFIRMATION";
    for (NormalReturnInspectionLine line : response.lines()) {
      if (line == null
          || line.lineId() == null
          || !lineIds.add(line.lineId())
          || line.assetId() == null
          || !assetIds.add(line.assetId())
          || line.assetVersion() < 0
          || !expectedStatus.equals(line.status())
          || line.media() == null
          || line.media().isEmpty()
          || line.media().size() > 20) {
        throw malformed("Logistics-service returned malformed normal-return inspection line");
      }
      Set<UUID> mediaIds = new java.util.HashSet<>();
      for (NormalReturnInspectionMedia media : line.media()) {
        if (media == null
            || media.mediaId() == null
            || !mediaIds.add(media.mediaId())
            || media.generation() < 1
            || !"LOGISTICS_RETURN".equals(media.ownerType())
            || media.ownerVerifiedAt() == null
            || media.ownerVerifiedAt().isAfter(response.completedAt())) {
          throw malformed("Logistics-service returned malformed normal-return media proof");
        }
      }
    }
  }

  private static void validateCompletedReturnEstimate(
      UUID estimateId, CompletedReturnEstimateProof response) {
    if (response == null
        || !estimateId.equals(response.estimateId())
        || response.estimateVersion() < 0
        || response.estimateRevision() < 1
        || response.returnId() == null
        || response.lineId() == null
        || response.warehouseId() == null
        || response.assetId() == null
        || response.assetVersion() < 0
        || response.arrivedAt() == null
        || response.completedAt() == null
        || response.completedAt().isBefore(response.arrivedAt())
        || !("EMPTY".equals(response.completionKind()) && response.repairId() == null
            || "NON_EMPTY".equals(response.completionKind()) && response.repairId() != null)) {
      throw malformed("Maintenance-service returned malformed completed return estimate proof");
    }
  }

  private static void validateWorkCalendarSnapshot(
      UUID warehouseId, LocalDate from, LocalDate through, WorkCalendarSnapshot snapshot) {
    if (snapshot == null
        || !warehouseId.equals(snapshot.warehouseId())
        || !from.equals(snapshot.from())
        || !through.equals(snapshot.through())
        || !sha256(snapshot.calendarFingerprint())
        || snapshot.dates() == null) {
      throw malformed("Task-board returned malformed work-calendar evidence");
    }
    LocalDate expected = from;
    for (WorkCalendarDate date : snapshot.dates()) {
      if (date == null
          || !expected.equals(date.date())
          || date.timeZone() == null
          || date.timeZoneEffectiveFrom() == null
          || !validCalendarSchedule(date)) {
        throw malformed("Task-board returned malformed work-calendar date");
      }
      try {
        ZoneId zone = ZoneId.of(date.timeZone());
        if (!zone.getId().equals(date.timeZone())) {
          throw new IllegalArgumentException();
        }
      } catch (ZoneRulesException | NullPointerException | IllegalArgumentException exception) {
        throw malformed("Task-board returned invalid work-calendar timezone");
      }
      expected = expected.plusDays(1);
    }
    if (!expected.equals(through.plusDays(1))) {
      throw malformed("Task-board returned incomplete work-calendar evidence");
    }
  }

  private static boolean validCalendarSchedule(WorkCalendarDate date) {
    boolean absent =
        date.scheduleId() == null
            && date.scheduleVersion() == null
            && date.scheduleEffectiveFrom() == null;
    if (absent) {
      return !date.working();
    }
    return date.scheduleId() != null
        && date.scheduleVersion() != null
        && date.scheduleVersion() >= 0
        && date.scheduleEffectiveFrom() != null
        && !date.scheduleEffectiveFrom().isAfter(date.date());
  }

  private static boolean validReconciliationResult(JsonNode response) {
    if (!response.isObject()
        || !response.path("source").isObject()
        || !response.path("delta").path("lines").isArray()
        || !response.has("successor")) {
      return false;
    }
    String outcome = response.path("outcome").asText();
    return switch (outcome) {
      case "MATCHED" ->
          explicitNull(response, "targetKind")
              && explicitNull(response, "targetId")
              && explicitNull(response, "estimateId")
              && explicitNull(response, "repairId")
              && explicitNull(response, "successor");
      case "CREATED" -> explicitNull(response, "successor") && validCreatedTarget(response);
      case "SUCCESSOR" -> response.path("successor").isObject() && validRepairTarget(response);
      default -> false;
    };
  }

  private static boolean validCreatedTarget(JsonNode response) {
    return switch (response.path("targetKind").asText()) {
      case "ESTIMATE" -> validTargetPair(response, "estimateId", "repairId");
      case "REPAIR" -> validTargetPair(response, "repairId", "estimateId");
      default -> false;
    };
  }

  private static boolean validRepairTarget(JsonNode response) {
    return "REPAIR".equals(response.path("targetKind").asText())
        && validTargetPair(response, "repairId", "estimateId");
  }

  private static boolean validTargetPair(JsonNode response, String matchingId, String absentId) {
    if (!text(response, "targetId")
        || !text(response, matchingId)
        || !explicitNull(response, absentId)) {
      return false;
    }
    try {
      return UUID.fromString(response.path("targetId").asText())
          .equals(UUID.fromString(response.path(matchingId).asText()));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean text(JsonNode response, String field) {
    JsonNode value = response.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank();
  }

  private static boolean explicitNull(JsonNode response, String field) {
    return response.has(field) && response.get(field).isNull();
  }

  private static boolean absent(JsonNode value) {
    return value == null || value.isNull();
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return UUID.fromString(node.path(field).asText());
    } catch (IllegalArgumentException exception) {
      throw malformed("Dependency response has invalid " + field);
    }
  }

  private static List<UUID> uuidList(JsonNode node, String field) {
    JsonNode values = node.get(field);
    if (values == null || !values.isArray()) {
      throw malformed("Dependency response has invalid " + field);
    }
    java.util.ArrayList<UUID> result = new java.util.ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) throw malformed("Dependency response has invalid " + field);
      try {
        result.add(UUID.fromString(value.asText()));
      } catch (IllegalArgumentException exception) {
        throw malformed("Dependency response has invalid " + field);
      }
    }
    return List.copyOf(result);
  }

  private static boolean hasDuplicates(List<UUID> values) {
    return Set.copyOf(values).size() != values.size();
  }

  private record FurnitureSnapshotRequest(UUID warehouseId, List<UUID> assetIds) {}

  private record NumberRequest(UUID warehouseId, String number) {}

  private record ValidationRequest(List<UUID> assetIds) {}

  private record RepairSnapshotRequest(List<UUID> assetIds) {}

  private record WarehouseOperationMarkRequest(UUID operationId, OffsetDateTime occurredAt) {}

  private record WarehouseTimeZoneAt(
      UUID warehouseId, String timeZone, OffsetDateTime effectiveFrom) {}

  private record WarehouseLifecycleReadinessRequest(long expectedVersion) {}
}
