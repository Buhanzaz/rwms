package dev.buhanzaz.rwms.asset.integration.warehouse;

import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseLifecycleReadinessWork;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseLifecycleReadinessWorkPage;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseOperationDirection;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseTimeZoneAt;
import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scope-separated private warehouse client. A token for one narrow route is
 * never reused for a different lifecycle or timezone boundary.
 */
final class OAuthWarehouseRegistryClient implements WarehouseRegistryClient {
  private static final String LEGACY_READ_SCOPE = "warehouse.read";
  private static final String LIFECYCLE_READ_SCOPE = "warehouse.lifecycle.read";
  private static final String LIFECYCLE_CONFIRM_SCOPE = "warehouse.lifecycle.confirm";
  private static final String OPERATION_MARK_SCOPE = "warehouse.operation.mark";
  private static final String TIME_ZONE_READ_SCOPE = "warehouse.timezone.read";
  private static final Set<String> LEGACY_FIELDS = Set.of("id", "version", "active");
  private static final Set<String> ADMISSION_FIELDS =
      Set.of("warehouseId", "warehouseVersion", "lifecycleState", "direction", "admitted");
  private static final Set<String> TIME_ZONE_FIELDS =
      Set.of("warehouseId", "timeZone", "effectiveFrom");
  private static final Set<String> READINESS_PAGE_FIELDS = Set.of("items", "nextAfter");
  private static final Set<String> READINESS_WORK_FIELDS =
      Set.of("warehouseId", "warehouseVersion", "lifecycleState");
  private static final Set<String> READINESS_CONFIRMATION_FIELDS =
      Set.of("warehouseId", "warehouseVersion", "lifecycleState", "readinessOwner", "confirmedAt");
  private static final Duration TOKEN_EXPIRY_SKEW = Duration.ofSeconds(30);

  private final WarehouseRegistryProperties.Validated properties;
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final Clock clock;
  private final Object accessTokenLock = new Object();
  private final Map<String, AccessToken> cachedAccessTokens = new ConcurrentHashMap<>();

  OAuthWarehouseRegistryClient(
      WarehouseRegistryProperties.Validated properties, ObjectMapper mapper, HttpClient http) {
    this(properties, mapper, http, Clock.systemUTC());
  }

  OAuthWarehouseRegistryClient(
      WarehouseRegistryProperties.Validated properties,
      ObjectMapper mapper,
      HttpClient http,
      Clock clock) {
    this.properties = properties;
    this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    this.http = http;
    this.clock = clock;
  }

  /** Legacy active-only boundary retained while callers migrate to a direction. */
  @Override
  public void requireActive(UUID warehouseId) {
    requireWarehouseId(warehouseId);
    URI uri =
        URI.create(
            properties.baseUrl()
                + "/api/internal/warehouse/v1/warehouses/asset/"
                + warehouseId
                + "/existence");
    HttpResponse<String> response = warehouseGet(uri, LEGACY_READ_SCOPE);
    requireSuccess(response, "Warehouse registry lookup");
    validateLegacyActive(warehouseId, response.body());
  }

  @Override
  public void requireIncoming(UUID warehouseId) {
    requireAdmission(warehouseId, WarehouseOperationDirection.INCOMING);
  }

  @Override
  public void requireOutgoing(UUID warehouseId) {
    requireAdmission(warehouseId, WarehouseOperationDirection.OUTGOING);
  }

  @Override
  public void markOperation(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    requireWarehouseId(warehouseId);
    if (operationId == null || occurredAt == null) {
      throw new IllegalArgumentException("Warehouse operation identity is incomplete");
    }
    URI uri =
        URI.create(
            properties.baseUrl()
                + "/api/internal/warehouse/v1/warehouses/"
                + warehouseId
                + "/operation-marks");
    String body =
        "{\"operationId\":\""
            + operationId
            + "\",\"occurredAt\":\""
            + occurredAt
            + "\"}";
    HttpResponse<String> response = warehousePost(uri, OPERATION_MARK_SCOPE, body);
    requireSuccess(response, "Warehouse operation mark");
    if (response.statusCode() != 204) {
      throw invalid("Warehouse registry returned an invalid operation-mark response");
    }
  }

  @Override
  public WarehouseTimeZoneAt timeZoneAt(UUID warehouseId, OffsetDateTime at) {
    requireWarehouseId(warehouseId);
    if (at == null) throw new IllegalArgumentException("Warehouse timezone as-of timestamp is required");
    URI uri =
        URI.create(
            properties.baseUrl()
                + "/api/internal/warehouse/v1/warehouses/"
                + warehouseId
                + "/time-zone?at="
                + URLEncoder.encode(at.toString(), StandardCharsets.UTF_8));
    HttpResponse<String> response = warehouseGet(uri, TIME_ZONE_READ_SCOPE);
    requireSuccess(response, "Warehouse timezone lookup");
    return validateTimeZone(warehouseId, at, response.body());
  }

  @Override
  public WarehouseLifecycleReadinessWorkPage lifecycleReadinessWork(UUID after, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Warehouse readiness page size must be between 1 and 500");
    }
    String query = "?limit=" + limit;
    if (after != null) query += "&after=" + after;
    URI uri =
        URI.create(
            properties.baseUrl() + "/api/internal/warehouse/v1/lifecycle/readiness-work" + query);
    HttpResponse<String> response = warehouseGet(uri, LIFECYCLE_READ_SCOPE);
    requireSuccess(response, "Warehouse lifecycle readiness work lookup");
    return validateReadinessWorkPage(response.body());
  }

  @Override
  public void confirmLifecycleReadiness(UUID warehouseId, long expectedVersion) {
    requireWarehouseId(warehouseId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("Warehouse lifecycle readiness version must not be negative");
    }
    URI uri =
        URI.create(
            properties.baseUrl()
                + "/api/internal/warehouse/v1/warehouses/"
                + warehouseId
                + "/lifecycle-readiness");
    HttpResponse<String> response =
        warehousePost(
            uri,
            LIFECYCLE_CONFIRM_SCOPE,
            "{\"expectedVersion\":" + expectedVersion + "}");
    requireSuccess(response, "Warehouse lifecycle readiness confirmation");
    validateReadinessConfirmation(warehouseId, response.body());
  }

  @Override
  public boolean lifecycleIntegrationEnabled() {
    return true;
  }

  private void requireAdmission(UUID warehouseId, WarehouseOperationDirection direction) {
    requireWarehouseId(warehouseId);
    URI uri =
        URI.create(
            properties.baseUrl()
                + "/api/internal/warehouse/v1/warehouses/"
                + warehouseId
                + "/admission?direction="
                + direction.name());
    HttpResponse<String> response = warehouseGet(uri, LIFECYCLE_READ_SCOPE);
    requireSuccess(response, "Warehouse lifecycle admission lookup");
    Admission admission = validateAdmission(warehouseId, direction, response.body());
    if (!admission.admitted()) {
      throw new AssetDependencyException(
          HttpStatus.CONFLICT,
          "Warehouse " + admission.lifecycleState() + " does not admit " + direction + " operations");
    }
  }

  private HttpResponse<String> warehouseGet(URI uri, String scope) {
    AccessToken token = accessToken(scope);
    HttpResponse<String> response =
        exchange(
            HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token.value())
                .GET()
                .build(),
            "Warehouse Service");
    if (response.statusCode() != 401) return response;
    invalidate(scope, token);
    token = accessToken(scope);
    return exchange(
        HttpRequest.newBuilder(uri)
            .timeout(properties.readTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token.value())
            .GET()
            .build(),
        "Warehouse Service");
  }

  private HttpResponse<String> warehousePost(URI uri, String scope, String body) {
    AccessToken token = accessToken(scope);
    HttpResponse<String> response = post(uri, token, body);
    if (response.statusCode() != 401) return response;
    invalidate(scope, token);
    return post(uri, accessToken(scope), body);
  }

  private HttpResponse<String> post(URI uri, AccessToken token, String body) {
    return exchange(
        HttpRequest.newBuilder(uri)
            .timeout(properties.readTimeout())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token.value())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        "Warehouse Service");
  }

  private AccessToken accessToken(String scope) {
    AccessToken token = cachedAccessTokens.get(scope);
    if (token != null && token.isUsableAt(clock.instant())) return token;
    synchronized (accessTokenLock) {
      token = cachedAccessTokens.get(scope);
      if (token != null && token.isUsableAt(clock.instant())) return token;
      AccessToken refreshed = requestAccessToken(scope);
      cachedAccessTokens.put(scope, refreshed);
      return refreshed;
    }
  }

  private void invalidate(String scope, AccessToken token) {
    synchronized (accessTokenLock) {
      cachedAccessTokens.remove(scope, token);
    }
  }

  private AccessToken requestAccessToken(String requiredScope) {
    String credentials =
        URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8)
            + ":"
            + URLEncoder.encode(properties.clientSecret(), StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(properties.tokenUri())
            .timeout(properties.readTimeout())
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope="
                        + URLEncoder.encode(requiredScope, StandardCharsets.UTF_8)))
            .build();
    HttpResponse<String> response = exchange(request, "OAuth token endpoint");
    if (response.statusCode() >= 500) {
      throw new AssetDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE, "OAuth token endpoint is unavailable");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AssetDependencyException(
          HttpStatus.BAD_GATEWAY, "OAuth token endpoint rejected asset-service credentials");
    }
    requireJson(response, "OAuth token endpoint");
    try {
      JsonNode body = mapper.readTree(response.body());
      JsonNode accessToken = body.get("access_token");
      JsonNode type = body.get("token_type");
      JsonNode scope = body.get("scope");
      if (accessToken == null
          || !accessToken.isTextual()
          || accessToken.textValue().isBlank()
          || type == null
          || !type.isTextual()
          || !"bearer".equalsIgnoreCase(type.textValue())
          || scope == null
          || !scope.isTextual()
          || !Set.of(requiredScope).equals(scopes(scope.textValue()))) {
        throw new AssetDependencyException(
            HttpStatus.BAD_GATEWAY,
            "OAuth token response must grant exactly " + requiredScope);
      }
      JsonNode expiresIn = body.get("expires_in");
      if (expiresIn == null
          || !expiresIn.isIntegralNumber()
          || !expiresIn.canConvertToLong()
          || expiresIn.longValue() <= 0) {
        throw new AssetDependencyException(
            HttpStatus.BAD_GATEWAY,
            "OAuth token response must include a positive expires_in");
      }
      return new AccessToken(accessToken.textValue(), cacheUntil(expiresIn.longValue()));
    } catch (AssetDependencyException exception) {
      throw exception;
    } catch (tools.jackson.core.JacksonException exception) {
      throw new AssetDependencyException(
          HttpStatus.BAD_GATEWAY, "OAuth token response is malformed", exception);
    }
  }

  private Instant cacheUntil(long expiresInSeconds) {
    try {
      Duration lifetime = Duration.ofSeconds(expiresInSeconds);
      Duration cacheLifetime =
          lifetime.compareTo(TOKEN_EXPIRY_SKEW) > 0
              ? lifetime.minus(TOKEN_EXPIRY_SKEW)
              : Duration.ZERO;
      return clock.instant().plus(cacheLifetime);
    } catch (DateTimeException | ArithmeticException exception) {
      throw new AssetDependencyException(
          HttpStatus.BAD_GATEWAY,
          "OAuth token response has an invalid expires_in",
          exception);
    }
  }

  private void requireSuccess(HttpResponse<String> response, String operation) {
    if (response.statusCode() == 404) {
      throw new AssetDependencyException(HttpStatus.UNPROCESSABLE_CONTENT, "Warehouse does not exist");
    }
    if (response.statusCode() == 409) {
      throw new AssetDependencyException(HttpStatus.CONFLICT, operation + " conflicts with warehouse state");
    }
    if (response.statusCode() >= 500 || response.statusCode() == 429) {
      throw new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, "Warehouse registry is unavailable");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AssetDependencyException(
          HttpStatus.BAD_GATEWAY, "Warehouse registry rejected the asset-service lookup");
    }
    if (response.statusCode() != 204) requireJson(response, "Warehouse registry");
  }

  private void validateLegacyActive(UUID requestedId, String body) {
    JsonNode json = parseJson(body, "Warehouse registry response");
    requireExactFields(json, LEGACY_FIELDS, "Warehouse registry response has invalid fields");
    UUID returned = requiredUuid(json.get("id"), "Warehouse registry response has invalid id");
    JsonNode version = json.get("version");
    JsonNode active = json.get("active");
    if (!requestedId.equals(returned)
        || version == null
        || !version.isIntegralNumber()
        || !version.canConvertToLong()
        || version.longValue() < 0
        || active == null
        || !active.isBoolean()) {
      throw invalid("Warehouse registry response has invalid fields");
    }
    if (!active.booleanValue()) {
      throw new AssetDependencyException(HttpStatus.CONFLICT, "Warehouse is inactive");
    }
  }

  private Admission validateAdmission(
      UUID requestedId, WarehouseOperationDirection expectedDirection, String body) {
    JsonNode json = parseJson(body, "Warehouse lifecycle admission");
    requireExactFields(json, ADMISSION_FIELDS, "Warehouse lifecycle admission has invalid fields");
    UUID warehouseId = requiredUuid(json.get("warehouseId"), "Warehouse lifecycle admission has invalid warehouseId");
    long version = requiredNonNegativeLong(json.get("warehouseVersion"), "warehouseVersion");
    String state = requiredText(json.get("lifecycleState"), "lifecycleState");
    String direction = requiredText(json.get("direction"), "direction");
    JsonNode admitted = json.get("admitted");
    if (!requestedId.equals(warehouseId)
        || !expectedDirection.name().equals(direction)
        || admitted == null
        || !admitted.isBoolean()
        || !Set.of("ACTIVE", "DRAINING", "INACTIVE").contains(state)) {
      throw invalid("Warehouse lifecycle admission has invalid fields");
    }
    boolean expectedAdmission =
        switch (state) {
          case "ACTIVE" -> true;
          case "DRAINING" -> expectedDirection == WarehouseOperationDirection.OUTGOING;
          case "INACTIVE" -> false;
          default -> throw invalid("Warehouse lifecycle admission has invalid lifecycleState");
        };
    if (admitted.booleanValue() != expectedAdmission) {
      throw invalid("Warehouse lifecycle admission is inconsistent");
    }
    return new Admission(version, state, admitted.booleanValue());
  }

  private WarehouseTimeZoneAt validateTimeZone(UUID requestedId, OffsetDateTime at, String body) {
    JsonNode json = parseJson(body, "Warehouse timezone response");
    requireExactFields(json, TIME_ZONE_FIELDS, "Warehouse timezone response has invalid fields");
    UUID warehouseId = requiredUuid(json.get("warehouseId"), "Warehouse timezone response has invalid warehouseId");
    String timeZone = requiredText(json.get("timeZone"), "timeZone");
    OffsetDateTime effectiveFrom = requiredOffsetDateTime(json.get("effectiveFrom"), "effectiveFrom");
    if (!requestedId.equals(warehouseId) || effectiveFrom.isAfter(at)) {
      throw invalid("Warehouse timezone response has invalid fields");
    }
    try {
      ZoneId.of(timeZone);
    } catch (DateTimeException exception) {
      throw invalid("Warehouse timezone response has invalid IANA timezone", exception);
    }
    return new WarehouseTimeZoneAt(warehouseId, timeZone, effectiveFrom);
  }

  private WarehouseLifecycleReadinessWorkPage validateReadinessWorkPage(String body) {
    JsonNode json = parseJson(body, "Warehouse lifecycle readiness work");
    requireExactFields(json, READINESS_PAGE_FIELDS, "Warehouse lifecycle readiness work has invalid fields");
    JsonNode items = json.get("items");
    JsonNode nextAfter = json.get("nextAfter");
    if (items == null || !items.isArray() || nextAfter == null) {
      throw invalid("Warehouse lifecycle readiness work has invalid fields");
    }
    List<WarehouseLifecycleReadinessWork> parsed = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (JsonNode item : items) {
      requireExactFields(item, READINESS_WORK_FIELDS, "Warehouse lifecycle readiness item has invalid fields");
      UUID warehouseId =
          requiredUuid(item.get("warehouseId"), "Warehouse lifecycle readiness item has invalid warehouseId");
      long version = requiredNonNegativeLong(item.get("warehouseVersion"), "warehouseVersion");
      String state = requiredText(item.get("lifecycleState"), "lifecycleState");
      if (!"DRAINING".equals(state) || !seen.add(warehouseId)) {
        throw invalid("Warehouse lifecycle readiness item has invalid fields");
      }
      parsed.add(new WarehouseLifecycleReadinessWork(warehouseId, version, state));
    }
    UUID cursor = nextAfter.isNull() ? null : requiredUuid(nextAfter, "nextAfter");
    if (cursor != null && (parsed.isEmpty() || !cursor.equals(parsed.getLast().warehouseId()))) {
      throw invalid("Warehouse lifecycle readiness cursor is invalid");
    }
    return new WarehouseLifecycleReadinessWorkPage(List.copyOf(parsed), cursor);
  }

  private void validateReadinessConfirmation(UUID requestedId, String body) {
    JsonNode json = parseJson(body, "Warehouse lifecycle readiness confirmation");
    requireExactFields(
        json,
        READINESS_CONFIRMATION_FIELDS,
        "Warehouse lifecycle readiness confirmation has invalid fields");
    UUID warehouseId =
        requiredUuid(json.get("warehouseId"), "Warehouse lifecycle readiness confirmation has invalid warehouseId");
    requiredNonNegativeLong(json.get("warehouseVersion"), "warehouseVersion");
    String state = requiredText(json.get("lifecycleState"), "lifecycleState");
    String owner = requiredText(json.get("readinessOwner"), "readinessOwner");
    requiredOffsetDateTime(json.get("confirmedAt"), "confirmedAt");
    if (!requestedId.equals(warehouseId) || !"DRAINING".equals(state) || !"ASSET".equals(owner)) {
      throw invalid("Warehouse lifecycle readiness confirmation has invalid fields");
    }
  }

  private JsonNode parseJson(String body, String dependency) {
    try {
      return mapper.readTree(body);
    } catch (tools.jackson.core.JacksonException exception) {
      throw invalid(dependency + " is malformed", exception);
    }
  }

  private static void requireExactFields(JsonNode json, Set<String> expected, String message) {
    if (json == null || !json.isObject() || json.size() != expected.size()) {
      throw invalid(message);
    }
    Set<String> actual = new HashSet<>();
    actual.addAll(json.propertyNames());
    if (!expected.equals(actual)) throw invalid(message);
  }

  private static UUID requiredUuid(JsonNode node, String message) {
    if (node == null || !node.isTextual()) throw invalid(message);
    try {
      UUID parsed = UUID.fromString(node.textValue());
      if (!parsed.toString().equals(node.textValue())) throw invalid(message);
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw invalid(message, exception);
    }
  }

  private static long requiredNonNegativeLong(JsonNode node, String name) {
    if (node == null
        || !node.isIntegralNumber()
        || !node.canConvertToLong()
        || node.longValue() < 0) {
      throw invalid("Warehouse registry response has invalid " + name);
    }
    return node.longValue();
  }

  private static String requiredText(JsonNode node, String name) {
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw invalid("Warehouse registry response has invalid " + name);
    }
    return node.textValue();
  }

  private static OffsetDateTime requiredOffsetDateTime(JsonNode node, String name) {
    if (node == null || !node.isTextual()) {
      throw invalid("Warehouse registry response has invalid " + name);
    }
    try {
      return OffsetDateTime.parse(node.textValue());
    } catch (DateTimeException exception) {
      throw invalid("Warehouse registry response has invalid " + name, exception);
    }
  }

  private static void requireWarehouseId(UUID warehouseId) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
  }

  private static AssetDependencyException invalid(String detail) {
    return new AssetDependencyException(HttpStatus.BAD_GATEWAY, detail);
  }

  private static AssetDependencyException invalid(String detail, Throwable cause) {
    return new AssetDependencyException(HttpStatus.BAD_GATEWAY, detail, cause);
  }

  private HttpResponse<String> exchange(HttpRequest request, String dependency) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.net.http.HttpTimeoutException | ConnectException exception) {
      throw new AssetDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          dependency + " timed out or refused the connection",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssetDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE, dependency + " request was interrupted", exception);
    } catch (IOException exception) {
      throw new AssetDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE, dependency + " is unavailable", exception);
    }
  }

  private static Set<String> scopes(String value) {
    if (value == null || value.isBlank()) return Set.of();
    return new HashSet<>(java.util.Arrays.asList(value.trim().split("\\s+")));
  }

  private static void requireJson(HttpResponse<?> response, String dependency) {
    String value = response.headers().firstValue("Content-Type").orElse("");
    if (!value.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
      throw new AssetDependencyException(
          HttpStatus.BAD_GATEWAY, dependency + " response must be application/json");
    }
  }

  private record AccessToken(String value, Instant cacheUntil) {
    boolean isUsableAt(Instant now) {
      return now.isBefore(cacheUntil);
    }
  }

  private record Admission(long warehouseVersion, String lifecycleState, boolean admitted) {}

  static HttpClient httpClient(Duration timeout) {
    return HttpClient.newBuilder().connectTimeout(timeout).build();
  }
}
