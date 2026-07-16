package dev.buhanzaz.rwms.asset.integration.warehouse;

import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OAuthWarehouseRegistryClient implements WarehouseRegistryClient {
  private static final Set<String> FIELDS = Set.of("id", "version", "active");
  private final WarehouseRegistryProperties.Validated properties;
  private final ObjectMapper mapper;
  private final HttpClient http;

  OAuthWarehouseRegistryClient(WarehouseRegistryProperties.Validated properties, ObjectMapper mapper, HttpClient http) {
    this.properties = properties;
    this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    this.http = http;
  }

  @Override
  public void requireActive(UUID warehouseId) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    String token = accessToken();
    URI uri = URI.create(properties.baseUrl() + "/api/internal/warehouse/v1/warehouses/asset/" + warehouseId + "/existence");
    HttpResponse<String> response = exchange(HttpRequest.newBuilder(uri).timeout(properties.readTimeout())
        .header("Accept", "application/json").header("Authorization", "Bearer " + token).GET().build(), "Warehouse Service");
    if (response.statusCode() == 404) throw new AssetDependencyException(HttpStatus.UNPROCESSABLE_CONTENT, "Warehouse does not exist");
    if (response.statusCode() >= 500 || response.statusCode() == 429) throw new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, "Warehouse registry is unavailable");
    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new AssetDependencyException(HttpStatus.BAD_GATEWAY, "Warehouse registry rejected the asset-service lookup");
    requireJson(response, "Warehouse registry");
    validate(warehouseId, response.body());
  }

  private String accessToken() {
    String credentials = URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8) + ":"
        + URLEncoder.encode(properties.clientSecret(), StandardCharsets.UTF_8);
    HttpRequest request = HttpRequest.newBuilder(properties.tokenUri()).timeout(properties.readTimeout())
        .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=warehouse.read")).build();
    HttpResponse<String> response = exchange(request, "OAuth token endpoint");
    if (response.statusCode() >= 500) throw new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, "OAuth token endpoint is unavailable");
    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new AssetDependencyException(HttpStatus.BAD_GATEWAY, "OAuth token endpoint rejected asset-service credentials");
    requireJson(response, "OAuth token endpoint");
    try {
      JsonNode body = mapper.readTree(response.body());
      JsonNode accessToken = body.get("access_token");
      JsonNode type = body.get("token_type");
      JsonNode scope = body.get("scope");
      if (accessToken == null || !accessToken.isTextual() || accessToken.textValue().isBlank()
          || type == null || !type.isTextual() || !"bearer".equalsIgnoreCase(type.textValue())
          || scope == null || !scope.isTextual() || !Set.of("warehouse.read").equals(scopes(scope.textValue()))) {
        throw new AssetDependencyException(HttpStatus.BAD_GATEWAY, "OAuth token response must grant exactly warehouse.read");
      }
      return accessToken.textValue();
    } catch (AssetDependencyException exception) {
      throw exception;
    } catch (tools.jackson.core.JacksonException exception) {
      throw new AssetDependencyException(HttpStatus.BAD_GATEWAY, "OAuth token response is malformed", exception);
    }
  }

  private void validate(UUID requestedId, String body) {
    try {
      JsonNode json = mapper.readTree(body);
      if (json == null || !json.isObject() || json.size() != FIELDS.size()) throw invalid("Warehouse registry response has invalid fields");
      Set<String> fields = new HashSet<>(json.propertyNames());
      JsonNode id = json.get("id");
      JsonNode version = json.get("version");
      JsonNode active = json.get("active");
      if (!FIELDS.equals(fields) || id == null || !id.isTextual() || version == null || !version.isIntegralNumber()
          || !version.canConvertToLong() || version.longValue() < 0 || active == null || !active.isBoolean()) throw invalid("Warehouse registry response has invalid fields");
      UUID returned = UUID.fromString(id.textValue());
      if (!returned.toString().equals(id.textValue()) || !requestedId.equals(returned)) throw invalid("Warehouse registry returned a different warehouse");
      if (!active.booleanValue()) throw new AssetDependencyException(HttpStatus.CONFLICT, "Warehouse is inactive");
    } catch (AssetDependencyException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AssetDependencyException(HttpStatus.BAD_GATEWAY, "Warehouse registry response is malformed", exception);
    }
  }

  private AssetDependencyException invalid(String detail) { return new AssetDependencyException(HttpStatus.BAD_GATEWAY, detail); }

  private HttpResponse<String> exchange(HttpRequest request, String dependency) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.net.http.HttpTimeoutException | ConnectException exception) {
      throw new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, dependency + " timed out or refused the connection", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, dependency + " request was interrupted", exception);
    } catch (IOException exception) {
      throw new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, dependency + " is unavailable", exception);
    }
  }

  private static Set<String> scopes(String value) {
    if (value == null || value.isBlank()) return Set.of();
    return new HashSet<>(java.util.Arrays.asList(value.trim().split("\\s+")));
  }

  private static void requireJson(HttpResponse<?> response, String dependency) {
    String value = response.headers().firstValue("Content-Type").orElse("");
    if (!value.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
      throw new AssetDependencyException(HttpStatus.BAD_GATEWAY, dependency + " response must be application/json");
    }
  }

  static HttpClient httpClient(Duration timeout) { return HttpClient.newBuilder().connectTimeout(timeout).build(); }
}
