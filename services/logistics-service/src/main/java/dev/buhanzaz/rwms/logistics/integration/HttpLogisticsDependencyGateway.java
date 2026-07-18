package dev.buhanzaz.rwms.logistics.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Direct client-credentials transport for only the approved Stage 8 private
 * boundaries. It intentionally has no access to the incoming user JWT.
 */
final class HttpLogisticsDependencyGateway implements LogisticsDependencyGateway {
  private static final String ASSET_CLIENT = "logistics-asset";
  private static final String WAREHOUSE_CLIENT = "logistics-warehouse";
  private static final String MAINTENANCE_CLIENT = "logistics-maintenance";
  private static final String MEDIA_CLIENT = "logistics-media";
  private static final String TASK_BOARD_CLIENT = "logistics-task-board";
  private static final String ASSET_SCOPE = "asset.logistics";
  private static final String WAREHOUSE_SCOPE = "warehouse.logistics";
  private static final String MAINTENANCE_SCOPE = "maintenance.logistics";
  private static final String MEDIA_SCOPE = "media.logistics";
  private static final String TASK_BOARD_SCOPE = "task-board.logistics";

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String assetBase;
  private final String warehouseBase;
  private final String maintenanceBase;
  private final String mediaBase;
  private final String taskBoardBase;

  HttpLogisticsDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      LogisticsDependencyProperties.Validated properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    assetBase = strip(properties.assetBaseUrl().toString()) + "/api/internal/asset/v1/logistics";
    warehouseBase =
        strip(properties.warehouseBaseUrl().toString()) + "/api/internal/warehouse/v1/warehouses/logistics";
    maintenanceBase =
        strip(properties.maintenanceBaseUrl().toString()) + "/api/internal/maintenance/v1/logistics/returns";
    mediaBase = strip(properties.mediaBaseUrl().toString()) + "/api/internal/media/v1/logistics";
    taskBoardBase =
        strip(properties.taskBoardBaseUrl().toString())
            + "/api/internal/task-board/v1/logistics/preparation-tasks";
  }

  @Override
  public WarehouseIdentity readWarehouseIdentity(UUID warehouseId) {
    WarehouseIdentityResponse response =
        get(
            warehouseBase + "/" + warehouseId + "/identity",
            WarehouseIdentityResponse.class,
            WAREHOUSE_CLIENT,
            WAREHOUSE_SCOPE);
    if (response == null) throw malformed("Warehouse-service returned an empty identity");
    return new WarehouseIdentity(response.id(), response.version(), response.active(), response.timeZone());
  }

  @Override
  public RentalItemSnapshot readRentalItemSnapshot(UUID assetId) {
    RentalItemSnapshotResponse response =
        get(
            assetBase + "/rental-items/" + assetId + "/snapshot",
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    return acquireOperationLease(
        idempotencyKey,
        LogisticsOwnerType.LOGISTICS_RETURN,
        assetId,
        expectedAssetVersion,
        documentId,
        lineId);
  }

  @Override
  public OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    if (ownerType == null) throw malformed("Logistics operation-lease owner type is required");
    OperationLeaseResponse response =
        post(
            assetBase + "/operation-leases",
            idempotencyKey,
            new AcquireLeaseRequest(
                assetId, ownerType.name(), documentId, lineId, expectedAssetVersion),
            OperationLeaseResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (response == null) throw malformed("Asset-service returned an empty operation lease");
    return new OperationLease(
        response.leaseId(),
        response.version(),
        response.rentalItemId(),
        response.fencingToken(),
        response.state(),
        response.expiresAt());
  }

  @Override
  public RentalItemSnapshot applyReturnIntake(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId) {
    RentalItemSnapshotResponse response =
        put(
            assetBase + "/rental-items/" + assetId + "/effects",
            idempotencyKey,
            new FencedEffectRequest(
                expectedAssetVersion,
                "RETURN_INTAKE",
                leaseId,
                fencingToken,
                "LOGISTICS_RETURN",
                documentId,
                lineId,
                null),
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public RentalItemSnapshot settleReturn(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId,
      boolean shortage) {
    RentalItemSnapshotResponse response =
        put(
            assetBase + "/rental-items/" + assetId + "/effects",
            idempotencyKey,
            new FencedEffectRequest(
                expectedAssetVersion,
                shortage ? "RETURN_SETTLE_SHORTAGE" : "RETURN_SETTLE_FREE",
                leaseId,
                fencingToken,
                LogisticsOwnerType.LOGISTICS_RETURN.name(),
                documentId,
                lineId,
                null),
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public RentalItemSnapshot applyFencedEffect(
      UUID idempotencyKey,
      AssetEffect action,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId) {
    if (action == null || ownerType == null) {
      throw malformed("Logistics asset effect action and owner type are required");
    }
    RentalItemSnapshotResponse response =
        put(
            assetBase + "/rental-items/" + assetId + "/effects",
            idempotencyKey,
            new FencedEffectRequest(
                expectedAssetVersion,
                action.name(),
                leaseId,
                fencingToken,
                ownerType.name(),
                documentId,
                lineId,
                destinationWarehouseId),
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId) {
    OperationLeaseResponse response =
        put(
            assetBase + "/operation-leases/" + leaseId + "/release",
            idempotencyKey,
            new LeaseCommandRequest(
                expectedLeaseVersion, fencingToken, ownerType.name(), documentId, lineId),
            OperationLeaseResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (response == null) throw malformed("Asset-service returned an empty released operation lease");
    return new OperationLease(
        response.leaseId(),
        response.version(),
        response.rentalItemId(),
        response.fencingToken(),
        response.state(),
        response.expiresAt());
  }

  @Override
  public MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references) {
    MediaValidationResponse response =
        postWithoutIdempotency(
            mediaBase + "/references/validate",
            new MediaValidationRequest(
                ownerType.name(),
                documentId,
                lineId,
                warehouseId,
                references.stream().map(reference -> new MediaReferenceRequest(reference.mediaId(), reference.generation())).toList()),
            MediaValidationResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE);
    if (response == null
        || ownerType.name() == null
        || !ownerType.name().equals(response.ownerType())
        || !documentId.equals(response.documentId())
        || !lineId.equals(response.lineId())
        || !warehouseId.equals(response.warehouseId())
        || response.references() == null) {
      throw malformed("Media-service returned an invalid logistics validation");
    }
    List<MediaReference> validated =
        response.references().stream()
            .map(reference -> new MediaReference(reference.mediaId(), reference.generation()))
            .toList();
    if (!Set.copyOf(validated).equals(Set.copyOf(references))) {
      throw malformed("Media-service returned mismatched logistics references");
    }
    return new MediaValidation(ownerType, documentId, lineId, warehouseId, validated);
  }

  @Override
  public ReturnShortageSource upsertReturnShortage(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      List<EquipmentShortage> shortages) {
    ReturnShortageSourceResponse response =
        putWithoutIdempotency(
            maintenanceBase + "/" + returnId + "/lines/" + lineId + "/shortage",
            new UpsertReturnShortageRequest(
                warehouseId,
                rentalItemId,
                rentalItemVersion,
                shortages.stream().map(value -> new EquipmentShortageRequest(value.equipmentId(), value.missingQuantity())).toList()),
            ReturnShortageSourceResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    if (response == null || response.shortages() == null) {
      throw malformed("Maintenance-service returned an empty shortage source");
    }
    return new ReturnShortageSource(
        response.returnId(),
        response.lineId(),
        response.sourceVersion(),
        response.warehouseId(),
        response.rentalItemId(),
        response.rentalItemVersion(),
        response.shortages().stream().map(value -> new EquipmentShortage(value.equipmentId(), value.missingQuantity())).toList(),
        response.snapshotSha256(),
        response.receivedAt());
  }

  @Override
  public EquipmentHold acquireEquipmentHold(
      UUID idempotencyKey,
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion) {
    EquipmentHoldResponse response =
        post(
            assetBase + "/equipment-holds",
            idempotencyKey,
            new AcquireEquipmentHoldRequest(
                equipmentId,
                warehouseId,
                shipmentId,
                shipmentLineId,
                quantity,
                expectedStockVersion),
            EquipmentHoldResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return hold(response);
  }

  @Override
  public EquipmentHold commandEquipmentHold(
      UUID idempotencyKey,
      EquipmentHoldAction action,
      UUID holdId,
      long expectedHoldVersion,
      UUID shipmentId,
      UUID shipmentLineId) {
    if (action == null || holdId == null) {
      throw malformed("Logistics equipment-hold action and identifier are required");
    }
    String segment = action == EquipmentHoldAction.COMMIT ? "commit" : "release";
    EquipmentHoldResponse response =
        put(
            assetBase + "/equipment-holds/" + holdId + "/" + segment,
            idempotencyKey,
            new EquipmentHoldCommandRequest(expectedHoldVersion, shipmentId, shipmentLineId),
            EquipmentHoldResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return hold(response);
  }

  @Override
  public PreparationTask registerPreparationTask(
      UUID warehouseId, UUID externalTaskId, Integer plannedDurationMinutes, OffsetDateTime deadlineAt) {
    PreparationTaskResponse response =
        postWithoutIdempotency(
            taskBoardBase,
            new RegisterPreparationTaskRequest(
                warehouseId, externalTaskId, plannedDurationMinutes, deadlineAt),
            PreparationTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return task(response);
  }

  @Override
  public PreparationTask readPreparationTask(UUID externalTaskId) {
    PreparationTaskResponse response =
        get(
            taskBoardBase + "/" + externalTaskId,
            PreparationTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return task(response);
  }

  @Override
  public PreparationTask cancelPreparationTask(UUID externalTaskId, long expectedTaskVersion) {
    PreparationTaskResponse response =
        postWithoutIdempotency(
            taskBoardBase + "/" + externalTaskId + "/cancel",
            new CancelPreparationTaskRequest(expectedTaskVersion),
            PreparationTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return task(response);
  }

  private <T> T get(String uri, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T post(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T postWithoutIdempotency(
      String uri, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T put(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T putWithoutIdempotency(
      String uri, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .put()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private String bearer(String registration, String requiredScope) {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(registration)
            .principal("logistics-service:" + registration)
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(requiredScope))) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.CONFIGURATION,
          "Dependency token does not have its one exact approved scope");
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private static RentalItemSnapshot snapshot(RentalItemSnapshotResponse response) {
    if (response == null || response.contents() == null) {
      throw malformed("Asset-service returned an empty rental-item snapshot");
    }
    return new RentalItemSnapshot(
        response.assetId(),
        response.version(),
        response.warehouseId(),
        response.status(),
        response.contents().stream()
            .map(content -> new EquipmentContent(content.equipmentId(), content.quantity()))
            .toList());
  }

  private static EquipmentHold hold(EquipmentHoldResponse response) {
    if (response == null
        || response.holdId() == null
        || response.version() < 0
        || response.state() == null
        || response.expiresAt() == null) {
      throw malformed("Asset-service returned an invalid logistics equipment hold");
    }
    return new EquipmentHold(
        response.holdId(), response.version(), response.state(), response.expiresAt(), response.committedAt());
  }

  private static PreparationTask task(PreparationTaskResponse response) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.warehouseId() == null
        || response.externalTaskId() == null
        || response.status() == null) {
      throw malformed("Task-board returned an invalid logistics preparation task");
    }
    return new PreparationTask(
        response.taskId(),
        response.taskVersion(),
        response.warehouseId(),
        response.externalTaskId(),
        response.status(),
        response.doneAt());
  }

  private static LogisticsDependencyException dependencyFailure(RuntimeException exception) {
    if (exception instanceof LogisticsDependencyException known) return known;
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      if (status != null
          && status.is4xxClientError()
          && status != HttpStatus.TOO_MANY_REQUESTS) {
        return new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            "Dependency rejected the logistics command",
            exception);
      }
    }
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.TRANSIENT,
        "Dependency outcome is unknown",
        exception);
  }

  private static LogisticsDependencyException malformed(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private record WarehouseIdentityResponse(UUID id, long version, boolean active, String timeZone) {}

  private record EquipmentContentResponse(UUID equipmentId, long quantity) {}

  private record RentalItemSnapshotResponse(
      UUID assetId,
      long version,
      UUID warehouseId,
      String status,
      List<EquipmentContentResponse> contents) {}

  private record AcquireLeaseRequest(
      UUID rentalItemId,
      String ownerType,
      UUID documentId,
      UUID lineId,
      long expectedRentalItemVersion) {}

  private record OperationLeaseResponse(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}

  private record FencedEffectRequest(
      long expectedVersion,
      String action,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId) {}

  private record LeaseCommandRequest(
      long expectedVersion,
      long fencingToken,
      String ownerType,
      UUID documentId,
      UUID lineId) {}

  private record MediaReferenceRequest(UUID mediaId, long generation) {}

  private record MediaValidationRequest(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReferenceRequest> references) {}

  private record MediaValidationResponse(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReferenceRequest> references) {}

  private record EquipmentShortageRequest(UUID equipmentId, long missingQuantity) {}

  private record UpsertReturnShortageRequest(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      List<EquipmentShortageRequest> shortages) {}

  private record ReturnShortageSourceResponse(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      List<EquipmentShortageRequest> shortages,
      String snapshotSha256,
      OffsetDateTime receivedAt) {}

  private record AcquireEquipmentHoldRequest(
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion) {}

  private record EquipmentHoldCommandRequest(
      long expectedVersion, UUID shipmentId, UUID shipmentLineId) {}

  private record EquipmentHoldResponse(
      UUID holdId,
      long version,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime committedAt) {}

  private record RegisterPreparationTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt) {}

  private record CancelPreparationTaskRequest(long expectedTaskVersion) {}

  private record PreparationTaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String status,
      OffsetDateTime doneAt) {}
}
