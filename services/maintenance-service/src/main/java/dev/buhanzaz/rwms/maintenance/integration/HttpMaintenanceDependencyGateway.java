package dev.buhanzaz.rwms.maintenance.integration;

import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
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
  private static final String ASSET_SCOPE = "asset.maintenance";
  private static final String TASK_SCOPE = "task-board.task-sync";
  private static final String TASK_REGISTRY_SCOPE = "queue-registry.write";
  private static final String MEDIA_SCOPE = "media.maintenance";

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String assetBase;
  private final String taskBase;
  private final String taskRoutingPreflightUrl;
  private final String taskRegistryBase;
  private final String mediaOwnerProofUrl;

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
    taskRoutingPreflightUrl = strip(properties.taskBoardBaseUrl().toString())
        + "/api/internal/task-board/v1/maintenance/routing-preflight";
    taskRegistryBase = strip(properties.taskBoardBaseUrl().toString())
        + "/api/internal/work-queues";
    mediaOwnerProofUrl = strip(properties.mediaBaseUrl().toString())
        + "/api/internal/media/v1/owner-proofs";
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
          || response.status() == null
          || response.status().isBlank()) {
        throw malformed("Asset-service returned malformed rental-item snapshot truth");
      }
      return new AssetSnapshot(
          response.id(), response.version(), response.warehouseId(), response.status());
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      String equipmentCode, String equipmentName) {
    String canonicalCode = canonicalEquipmentCode(equipmentCode);
    String canonicalName = canonicalEquipmentName(equipmentName);
    try {
      FurnitureEquipmentSnapshot response = client.put()
          .uri(assetBase + "/equipment-catalog/" + canonicalCode)
          .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
          .body(new EnsureFurnitureEquipmentRequest(canonicalName))
          .retrieve()
          .body(FurnitureEquipmentSnapshot.class);
      if (response == null
          || !canonicalCode.equals(response.equipmentCode())
          || !canonicalName.equals(response.equipmentName())) {
        throw malformed("Asset-service returned mismatched furniture equipment truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw furnitureDependencyFailure(exception);
    }
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
      case "QUEUE_TO_REPAIR" -> "QUEUE_FOR_REPAIR";
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
      case "EMPTY_ESTIMATE_TO_FREE", "ACCEPT_TO_FREE" -> "FREE";
      case "QUEUE_TO_REPAIR" -> "REPAIR";
      case "PENDING_ACCEPTANCE" -> "WAITING_REPAIR_CHECK";
      case "WRITE_OFF" -> "WRITTEN_OFF";
      default -> throw new IllegalArgumentException("Unsupported asset transition");
    };
    if (!rentalItemId.equals(response.id())
        || !warehouseId.equals(response.warehouseId())
        || response.version() != Math.addExact(expectedVersion, 1)
        || !expectedStatus.equals(response.status())) {
      throw malformed("Asset-service returned malformed fenced rental-item truth");
    }
    return new AssetSnapshot(
        response.id(), response.version(), response.warehouseId(), response.status());
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
      UUID key, UUID externalTaskId, UUID warehouseId, UUID rentalItemId,
      List<TaskStage> stages) {
    TaskResponse response = post(taskBase, key,
        new RegisterTaskRequest(warehouseId, externalTaskId, "Maintenance repair",
            rentalItemId.toString(), null, duration(stages), commonDeadline(stages), route(stages)),
        TaskResponse.class, TASK_CLIENT, TASK_SCOPE);
    return task(response, externalTaskId, stages.size());
  }

  @Override
  public TaskSnapshot updatePreStartTask(
      UUID key, UUID externalTaskId, long expectedVersion, List<TaskStage> stages) {
    TaskResponse response = put(taskBase + "/" + externalTaskId, key,
        new UpdateTaskRequest(expectedVersion, "Maintenance repair", null, null,
            duration(stages), commonDeadline(stages), route(stages)),
        TaskResponse.class, TASK_CLIENT, TASK_SCOPE);
    return task(response, externalTaskId, stages.size());
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
  public RoutingPreflight preflightMaintenanceRouting(
      UUID warehouseId, List<RoutingQueueRequirement> queues) {
    if (warehouseId == null || queues == null || queues.isEmpty()) {
      throw new IllegalArgumentException("Maintenance routing preflight is required");
    }
    Set<UUID> requestedQueueIds = queues.stream()
        .map(RoutingQueueRequirement::queueId)
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
          || !queueId.equals(response.queueId())
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
        || response.missingQueueIds() == null
        || response.mismatches() == null
        || response.ready()
            != (response.missingQueueIds().isEmpty() && response.mismatches().isEmpty())) {
      throw malformed("Task-board returned malformed maintenance routing truth");
    }
    Set<UUID> missing = Set.copyOf(response.missingQueueIds());
    Set<UUID> mismatched = response.mismatches().stream()
        .map(RoutingMismatch::queueId)
        .collect(java.util.stream.Collectors.toSet());
    if (missing.size() != response.missingQueueIds().size()
        || mismatched.size() != response.mismatches().size()
        || !requestedQueueIds.containsAll(missing)
        || !requestedQueueIds.containsAll(mismatched)
        || missing.stream().anyMatch(mismatched::contains)) {
      throw malformed("Task-board returned unrelated maintenance routing truth");
    }
  }

  private static List<RouteStep> route(List<TaskStage> stages) {
    return stages.stream().map(stage -> {
      UUID queueId;
      try {
        queueId = UUID.fromString(stage.queueRef());
      } catch (IllegalArgumentException | NullPointerException exception) {
        throw new MaintenanceDependencyException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Repair stage queueRef must be an opaque queue UUID");
      }
      return new RouteStep(queueId, null, stage.title(), null);
    }).toList();
  }

  private static Integer duration(List<TaskStage> stages) { return null; }

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

  private static String canonicalEquipmentCode(String value) {
    String canonical = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!canonical.matches("^[A-Z0-9][A-Z0-9_-]{0,63}$")) {
      throw new IllegalArgumentException("Furniture equipment code is invalid");
    }
    return canonical;
  }

  private static String canonicalEquipmentName(String value) {
    String canonical = value == null ? "" : value.trim();
    if (canonical.isEmpty() || canonical.length() > 255) {
      throw new IllegalArgumentException("Furniture equipment name is invalid");
    }
    return canonical;
  }

  private record AcquireLeaseRequest(
      UUID rentalItemId, String ownerType, UUID ownerId, long expectedRentalItemVersion) {}
  private record EnsureFurnitureEquipmentRequest(String equipmentName) {}
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
      UUID id, Long version, UUID warehouseId, String status) {}
  private record RentalItemResponse(UUID id, long version, UUID warehouseId, String number, String status) {}
  private record RouteStep(
      UUID queueId, String queueCode, String taskText, Integer plannedDurationMinutes) {}
  private record RoutingPreflightRequest(
      UUID warehouseId, List<RoutingQueueRequirement> queues) {}
  private record QueueReferenceRequest(String type, String externalReferenceId) {}
  private record RegisterTaskRequest(
      UUID warehouseId, UUID externalTaskId, String title, String unitNumber, String description,
      Integer plannedDurationMinutes, OffsetDateTime deadlineAt, List<RouteStep> route) {}
  private record UpdateTaskRequest(
      long expectedTaskVersion, String title, String unitNumber, String description,
      Integer plannedDurationMinutes, OffsetDateTime deadlineAt, List<RouteStep> route) {}
  private record RouteResponse(
      UUID entryId, long entryVersion, UUID queueId, String queueCode, int routeIndex) {}
  private record TaskResponse(
      UUID taskId, long taskVersion, UUID warehouseId, UUID externalTaskId, String title,
      String unitNumber, String description, String status, Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt, OffsetDateTime doneAt, List<RouteResponse> route) {}
  private record CancelTaskRequest(long expectedTaskVersion, String reason) {}
  private record CancelTaskResponse(
      UUID taskId, UUID externalTaskId, long taskVersion, String status,
      OffsetDateTime cancelledAt) {}
}
