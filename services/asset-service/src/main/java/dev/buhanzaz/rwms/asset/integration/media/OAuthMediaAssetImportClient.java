package dev.buhanzaz.rwms.asset.integration.media;

import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OAuthMediaAssetImportClient implements MediaAssetImportClient {
  private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final Set<String> REQUIRED_JOB_FIELDS =
      Set.of(
          "jobId",
          "assetImportId",
          "warehouseId",
          "status",
          "preflightAttempts",
          "activationAttempts",
          "results");
  private static final Set<String> OPTIONAL_JOB_FIELDS = Set.of("failureCode");
  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "sourceRowId",
          "prepared",
          "downloading",
          "imported",
          "skipped",
          "failed",
          "mediaIds",
          "warningCodes");

  private final MediaAssetImportProperties.Validated properties;
  private final ObjectMapper mapper;
  private final HttpClient http;

  OAuthMediaAssetImportClient(
      MediaAssetImportProperties.Validated properties,
      ObjectMapper mapper,
      HttpClient http) {
    this.properties = properties;
    this.mapper =
        mapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    this.http = http;
  }

  @Override
  public MediaAssetImportJob preflight(
      UUID assetImportId,
      UUID warehouseId,
      List<MediaAssetImportSource> sources,
      UUID idempotencyKey) {
    if (assetImportId == null
        || warehouseId == null
        || sources == null
        || sources.isEmpty()
        || sources.size() > MAX_SOURCES
        || idempotencyKey == null
        || sources.stream().map(MediaAssetImportSource::sourceRowId).distinct().count()
            != sources.size()) {
      throw new IllegalArgumentException("Media import preflight is invalid");
    }
    return command(
        "/api/internal/media/v1/asset-imports/preflight",
        idempotencyKey,
        new PreflightRequest(assetImportId, warehouseId, List.copyOf(sources)));
  }

  @Override
  public MediaAssetImportJob get(UUID jobId) {
    requireId(jobId, "Media import job ID is required");
    return exchangeJob(
        request(
                URI.create(
                    properties.baseUrl()
                        + "/api/internal/media/v1/asset-imports/"
                        + jobId),
                accessToken())
            .GET()
            .build());
  }

  @Override
  public MediaAssetImportJob activate(
      UUID jobId,
      List<MediaAssetImportBinding> bindings,
      UUID idempotencyKey) {
    requireId(jobId, "Media import job ID is required");
    if (bindings == null
        || bindings.isEmpty()
        || bindings.size() > MAX_SOURCES
        || idempotencyKey == null
        || bindings.stream().map(MediaAssetImportBinding::sourceRowId).distinct().count()
            != bindings.size()) {
      throw new IllegalArgumentException("Media import activation is invalid");
    }
    return command(
        "/api/internal/media/v1/asset-imports/" + jobId + "/activate",
        idempotencyKey,
        new ActivationRequest(List.copyOf(bindings)));
  }

  @Override
  public MediaAssetImportJob replacePreflightSources(
      UUID jobId,
      List<MediaAssetImportSource> sources,
      UUID idempotencyKey) {
    requireId(jobId, "Media import job ID is required");
    if (sources == null
        || sources.isEmpty()
        || sources.size() > MAX_SOURCES
        || idempotencyKey == null
        || sources.stream().map(MediaAssetImportSource::sourceRowId).distinct().count()
            != sources.size()) {
      throw new IllegalArgumentException("Media import source replacement is invalid");
    }
    return command(
        "/api/internal/media/v1/asset-imports/" + jobId + "/replace-sources",
        idempotencyKey,
        new ReplaceSourcesRequest(List.copyOf(sources)));
  }

  @Override
  public MediaAssetImportJob retry(UUID jobId, UUID idempotencyKey) {
    requireId(jobId, "Media import job ID is required");
    requireId(idempotencyKey, "Media import retry idempotency key is required");
    HttpRequest request =
        request(
                URI.create(
                    properties.baseUrl()
                        + "/api/internal/media/v1/asset-imports/"
                        + jobId
                        + "/retry"),
                accessToken())
            .header("Idempotency-Key", idempotencyKey.toString())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return exchangeJob(request);
  }

  private MediaAssetImportJob command(
      String path, UUID idempotencyKey, Object body) {
    requireId(idempotencyKey, "Media import idempotency key is required");
    byte[] bytes;
    try {
      bytes = mapper.writeValueAsBytes(body);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Media import command cannot be serialized", exception);
    }
    HttpRequest request =
        request(URI.create(properties.baseUrl() + path), accessToken())
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey.toString())
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    return exchangeJob(request);
  }

  private String accessToken() {
    String credentials =
        URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8)
            + ":"
            + URLEncoder.encode(
                properties.clientSecret(), StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(properties.tokenUri())
            .timeout(properties.readTimeout())
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(
                            credentials.getBytes(StandardCharsets.UTF_8)))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=media.asset-import"))
            .build();
    BoundedResponse response = exchange(request, "OAuth token endpoint");
    if (response.status() >= 500 || response.status() == 429) {
      throw unavailable("OAuth token endpoint is unavailable");
    }
    if (response.status() < 200 || response.status() >= 300) {
      throw badGateway(
          "OAuth token endpoint rejected asset-service credentials");
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
          || !Set.of("media.asset-import").equals(scopes(scope.textValue()))) {
        throw badGateway(
            "OAuth token response must grant exactly media.asset-import");
      }
      return accessToken.textValue();
    } catch (AssetDependencyException exception) {
      throw exception;
    } catch (JacksonException exception) {
      throw badGateway("OAuth token response is malformed", exception);
    }
  }

  private MediaAssetImportJob exchangeJob(HttpRequest request) {
    BoundedResponse response = exchange(request, "Media Service");
    if (response.status() == 404) {
      throw new AssetNotFoundException("Media import job was not found");
    }
    if (response.status() == 409) {
      throw new AssetConflictException(
          "Media import command is not ready or conflicts with current state");
    }
    if (response.status() >= 500 || response.status() == 429) {
      throw unavailable("Media import dependency is unavailable");
    }
    if (response.status() < 200 || response.status() >= 300) {
      throw badGateway("Media Service rejected the asset import command");
    }
    requireJson(response, "Media Service");
    if (!Arrays.stream(response.cacheControl().split(","))
        .map(String::trim)
        .anyMatch(value -> "no-store".equalsIgnoreCase(value))) {
      throw badGateway("Media Service response must be no-store");
    }
    return parseJob(response.body());
  }

  private MediaAssetImportJob parseJob(byte[] body) {
    try {
      JsonNode json = mapper.readTree(body);
      if (json == null || !json.isObject()) throw malformed();
      Set<String> fields = new HashSet<>(json.propertyNames());
      if (!fields.containsAll(REQUIRED_JOB_FIELDS)
          || fields.stream()
              .anyMatch(
                  field ->
                      !REQUIRED_JOB_FIELDS.contains(field)
                          && !OPTIONAL_JOB_FIELDS.contains(field))) {
        throw malformed();
      }
      UUID jobId = uuid(json, "jobId");
      UUID assetImportId = uuid(json, "assetImportId");
      UUID warehouseId = uuid(json, "warehouseId");
      MediaAssetImportJob.Status status =
          MediaAssetImportJob.Status.valueOf(text(json, "status"));
      int preflightAttempts = count(json, "preflightAttempts");
      int activationAttempts = count(json, "activationAttempts");
      String failureCode =
          json.has("failureCode") ? text(json, "failureCode") : null;
      JsonNode resultNodes = json.get("results");
      if (resultNodes == null
          || !resultNodes.isArray()
          || resultNodes.size() > MAX_SOURCES) {
        throw malformed();
      }
      List<MediaAssetImportJob.SourceResult> results =
          java.util.stream.StreamSupport.stream(
                  resultNodes.spliterator(), false)
              .map(this::sourceResult)
              .toList();
      if (results.stream()
              .map(MediaAssetImportJob.SourceResult::sourceRowId)
              .distinct()
              .count()
          != results.size()) {
        throw malformed();
      }
      return new MediaAssetImportJob(
          jobId,
          assetImportId,
          warehouseId,
          status,
          preflightAttempts,
          activationAttempts,
          failureCode,
          results);
    } catch (AssetDependencyException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw badGateway("Media import response is malformed", exception);
    }
  }

  private MediaAssetImportJob.SourceResult sourceResult(JsonNode json) {
    if (json == null
        || !json.isObject()
        || !RESULT_FIELDS.equals(new HashSet<>(json.propertyNames()))) {
      throw malformed();
    }
    return new MediaAssetImportJob.SourceResult(
        uuid(json, "sourceRowId"),
        count(json, "prepared"),
        count(json, "downloading"),
        count(json, "imported"),
        count(json, "skipped"),
        count(json, "failed"),
        uuidList(json, "mediaIds", 1000),
        codeList(json, "warningCodes", 1000));
  }

  private List<UUID> uuidList(JsonNode json, String field, int maximum) {
    JsonNode values = json.get(field);
    if (values == null || !values.isArray() || values.size() > maximum) {
      throw malformed();
    }
    List<UUID> result =
        java.util.stream.StreamSupport.stream(values.spliterator(), false)
            .map(
                value -> {
                  if (!value.isTextual()) throw malformed();
                  return canonicalUuid(value.textValue());
                })
            .toList();
    if (result.stream().distinct().count() != result.size()) throw malformed();
    return result;
  }

  private List<String> codeList(JsonNode json, String field, int maximum) {
    JsonNode values = json.get(field);
    if (values == null || !values.isArray() || values.size() > maximum) {
      throw malformed();
    }
    List<String> result =
        java.util.stream.StreamSupport.stream(values.spliterator(), false)
            .map(
                value -> {
                  if (!value.isTextual()
                      || !value.textValue().matches("^[A-Z_]{1,64}$")) {
                    throw malformed();
                  }
                  return value.textValue();
                })
            .toList();
    if (result.stream().distinct().count() != result.size()) throw malformed();
    return result;
  }

  private static UUID uuid(JsonNode json, String field) {
    return canonicalUuid(text(json, field));
  }

  private static UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) throw malformed();
    return parsed;
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw malformed();
    }
    return value.textValue();
  }

  private static int count(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < 0) {
      throw malformed();
    }
    return value.intValue();
  }

  private HttpRequest.Builder request(URI uri, String token) {
    return HttpRequest.newBuilder(uri)
        .timeout(properties.readTimeout())
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + token);
  }

  private BoundedResponse exchange(HttpRequest request, String dependency) {
    try {
      HttpResponse<InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
          throw badGateway(dependency + " response is too large");
        }
        return new BoundedResponse(
            response.statusCode(),
            response
                .headers()
                .firstValue("Content-Type")
                .orElse(""),
            response
                .headers()
                .firstValue("Cache-Control")
                .orElse(""),
            bytes);
      }
    } catch (java.net.http.HttpTimeoutException | ConnectException exception) {
      throw unavailable(dependency + " timed out or refused the connection", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable(dependency + " request was interrupted", exception);
    } catch (IOException exception) {
      throw unavailable(dependency + " is unavailable", exception);
    }
  }

  private static Set<String> scopes(String value) {
    if (value == null || value.isBlank()) return Set.of();
    return new HashSet<>(Arrays.asList(value.trim().split("\\s+")));
  }

  private static void requireJson(BoundedResponse response, String dependency) {
    if (!response
        .contentType()
        .toLowerCase(java.util.Locale.ROOT)
        .matches("application/json(?:\\s*;.*)?")) {
      throw badGateway(dependency + " response must be application/json");
    }
  }

  private static void requireId(UUID value, String message) {
    if (value == null) throw new IllegalArgumentException(message);
  }

  private static AssetDependencyException malformed() {
    return badGateway("Media import response is malformed");
  }

  private static AssetDependencyException badGateway(String message) {
    return new AssetDependencyException(HttpStatus.BAD_GATEWAY, message);
  }

  private static AssetDependencyException badGateway(
      String message, Throwable cause) {
    return new AssetDependencyException(HttpStatus.BAD_GATEWAY, message, cause);
  }

  private static AssetDependencyException unavailable(String message) {
    return new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  private static AssetDependencyException unavailable(
      String message, Throwable cause) {
    return new AssetDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE, message, cause);
  }

  static HttpClient httpClient(Duration timeout) {
    return HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  private record PreflightRequest(
      UUID assetImportId,
      UUID warehouseId,
      List<MediaAssetImportSource> sources) {}

  private record ActivationRequest(List<MediaAssetImportBinding> bindings) {}

  private record ReplaceSourcesRequest(List<MediaAssetImportSource> sources) {}

  private record BoundedResponse(
      int status, String contentType, String cacheControl, byte[] body) {}
}
