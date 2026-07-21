package dev.buhanzaz.rwms.inventory.integration;

import dev.buhanzaz.rwms.inventory.service.InventoryException;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

final class HttpInventoryDependencyGateway implements InventoryDependencyGateway {
  private static final String WAREHOUSE_CLIENT = "inventory-warehouse";
  private static final String ASSET_CLIENT = "inventory-asset";
  private static final String MAINTENANCE_CLIENT = "inventory-maintenance";
  private static final String WAREHOUSE_SCOPE = "warehouse.read";
  private static final String ASSET_SCOPE = "asset.inventory";
  private static final String MAINTENANCE_SCOPE = "maintenance.inventory";

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String warehouseBase;
  private final String assetBase;
  private final String maintenanceBase;

  HttpInventoryDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      InventoryDependencyProperties.Validated properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    warehouseBase = strip(properties.warehouseBaseUrl().toString());
    assetBase = strip(properties.assetBaseUrl().toString());
    maintenanceBase = strip(properties.maintenanceBaseUrl().toString());
  }

  @Override
  public WarehouseMetadata warehouse(UUID warehouseId) {
    WarehouseMetadata response =
        get(
            warehouseBase
                + "/api/internal/warehouse/v1/warehouses/inventory/"
                + warehouseId
                + "/metadata",
            WarehouseMetadata.class,
            WAREHOUSE_CLIENT,
            WAREHOUSE_SCOPE);
    if (!warehouseId.equals(response.id()) || response.version() < 0 || !response.active()) {
      throw malformed("Warehouse-service returned malformed inventory metadata");
    }
    try {
      ZoneId.of(response.timeZone());
    } catch (ZoneRulesException | NullPointerException exception) {
      throw malformed("Warehouse-service returned invalid IANA timezone");
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
                || !warehouseId.equals(response.asset().warehouseId())))) {
      throw malformed("Asset-service returned malformed number resolution truth");
    }
    return response;
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
                                && item.status() != null)))) {
      throw malformed("Asset-service returned malformed validation truth");
    }
    return response;
  }

  @Override
  public FrozenPlan freezePlan(UUID idempotencyKey, JsonNode request) {
    JsonNode response =
        post(
            maintenanceBase + "/api/internal/maintenance/v1/inventory/plans",
            idempotencyKey,
            request,
            JsonNode.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
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
      if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
        return InventoryException.notFound("Required dependency resource was not found");
      }
      if (response.getStatusCode() == HttpStatus.CONFLICT) {
        return InventoryException.conflict("Dependency rejected stale inventory state");
      }
      if (response.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY
          || response.getStatusCode() == HttpStatus.BAD_REQUEST) {
        return new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_VALIDATION_FAILED",
            "Dependency rejected invalid inventory input");
      }
    }
    return InventoryException.dependency("Mandatory inventory dependency is unavailable");
  }

  private static InventoryException malformed(String message) {
    return InventoryException.dependency(message);
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("^[0-9a-f]{64}$");
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

  private record NumberRequest(UUID warehouseId, String number) {}

  private record ValidationRequest(List<UUID> assetIds) {}
}
