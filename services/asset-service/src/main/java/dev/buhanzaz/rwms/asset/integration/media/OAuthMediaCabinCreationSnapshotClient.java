package dev.buhanzaz.rwms.asset.integration.media;

import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Client-credential implementation of the exact asset-to-media cabin proof boundary. */
final class OAuthMediaCabinCreationSnapshotClient
    implements MediaCabinCreationSnapshotClient {
  private static final int MAX_RESPONSE_BYTES = 128 * 1024;
  private static final Set<String> SNAPSHOT_FIELDS =
      Set.of(
          "cabinId",
          "warehouseId",
          "activeFolderId",
          "coverMediaId",
          "photoCount",
          "readyPhotos");
  private static final Set<String> READY_PHOTO_FIELDS =
      Set.of(
          "mediaId",
          "generation",
          "photoIndex",
          "checksumSha256",
          "contentType",
          "contentLength");
  private static final Set<String> PHOTO_CONTENT_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");

  private final MediaAssetImportProperties.Validated properties;
  private final ObjectMapper mapper;
  private final HttpClient http;

  OAuthMediaCabinCreationSnapshotClient(
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
  public Optional<CabinCreationSnapshot> read(UUID warehouseId, UUID cabinId) {
    requireId(warehouseId, "Warehouse ID is required");
    requireId(cabinId, "Cabin ID is required");
    byte[] requestBody;
    try {
      requestBody =
          mapper.writeValueAsBytes(
              new SnapshotRequest(warehouseId, List.of(cabinId)));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Cabin photo verification request cannot be serialized", exception);
    }
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    properties.baseUrl()
                        + "/api/internal/media/v1/assets/cabin-creation-snapshots"))
            .timeout(properties.readTimeout())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + accessToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .build();
    BoundedResponse response = exchange(request, "Media Service");
    if (response.status() >= 500 || response.status() == 429) {
      throw unavailable("Cabin photo verification is temporarily unavailable");
    }
    if (response.status() < 200 || response.status() >= 300) {
      throw badGateway("Media Service rejected cabin photo verification");
    }
    requireJson(response, "Media Service");
    requireNoStore(response, "Media Service");
    return parseSnapshot(response.body(), warehouseId, cabinId);
  }

  private String accessToken() {
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
                    "grant_type=client_credentials&scope=media.asset"))
            .build();
    BoundedResponse response = exchange(request, "OAuth token endpoint");
    if (response.status() >= 500 || response.status() == 429) {
      throw unavailable("OAuth token endpoint is unavailable");
    }
    if (response.status() < 200 || response.status() >= 300) {
      throw badGateway("OAuth token endpoint rejected asset-service credentials");
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
          || !Set.of("media.asset").equals(scopes(scope.textValue()))) {
        throw badGateway("OAuth token response must grant exactly media.asset");
      }
      return accessToken.textValue();
    } catch (AssetDependencyException exception) {
      throw exception;
    } catch (JacksonException exception) {
      throw badGateway("OAuth token response is malformed", exception);
    }
  }

  private Optional<CabinCreationSnapshot> parseSnapshot(
      byte[] body, UUID expectedWarehouseId, UUID expectedCabinId) {
    try {
      JsonNode json = mapper.readTree(body);
      if (json == null
          || !json.isObject()
          || !Set.of("items").equals(new HashSet<>(json.propertyNames()))) {
        throw malformed();
      }
      JsonNode items = json.get("items");
      if (items == null || !items.isArray() || items.size() > 1) {
        throw malformed();
      }
      if (items.isEmpty()) {
        return Optional.empty();
      }
      JsonNode item = items.get(0);
      if (item == null
          || !item.isObject()
          || !SNAPSHOT_FIELDS.equals(new HashSet<>(item.propertyNames()))) {
        throw malformed();
      }
      UUID cabinId = uuid(item, "cabinId");
      UUID warehouseId = uuid(item, "warehouseId");
      if (!expectedCabinId.equals(cabinId) || !expectedWarehouseId.equals(warehouseId)) {
        throw malformed();
      }
      UUID activeFolderId = nullableUuid(item, "activeFolderId");
      UUID coverMediaId = nullableUuid(item, "coverMediaId");
      long photoCount = count(item, "photoCount");
      JsonNode readyNodes = item.get("readyPhotos");
      if (readyNodes == null || !readyNodes.isArray() || readyNodes.size() > 100) {
        throw malformed();
      }
      List<ReadyPhoto> readyPhotos =
          java.util.stream.StreamSupport.stream(readyNodes.spliterator(), false)
              .map(this::readyPhoto)
              .toList();
      if (readyPhotos.stream().map(ReadyPhoto::mediaId).distinct().count()
          != readyPhotos.size()) {
        throw malformed();
      }
      return Optional.of(
          new CabinCreationSnapshot(
              cabinId,
              warehouseId,
              activeFolderId,
              coverMediaId,
              photoCount,
              readyPhotos));
    } catch (AssetDependencyException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw badGateway("Media cabin photo response is malformed", exception);
    }
  }

  private ReadyPhoto readyPhoto(JsonNode value) {
    if (value == null
        || !value.isObject()
        || !READY_PHOTO_FIELDS.equals(new HashSet<>(value.propertyNames()))) {
      throw malformed();
    }
    JsonNode generation = value.get("generation");
    if (generation == null
        || !generation.isIntegralNumber()
        || !generation.canConvertToInt()
        || generation.intValue() < 1) {
      throw malformed();
    }
    long photoIndex = count(value, "photoIndex");
    String checksumSha256 = text(value, "checksumSha256");
    if (!checksumSha256.matches("^[0-9a-f]{64}$")) {
      throw malformed();
    }
    String contentType = text(value, "contentType");
    if (!PHOTO_CONTENT_TYPES.contains(contentType)) {
      throw malformed();
    }
    long contentLength = count(value, "contentLength");
    if (contentLength < 1) {
      throw malformed();
    }
    return new ReadyPhoto(
        uuid(value, "mediaId"),
        generation.intValue(),
        photoIndex,
        checksumSha256,
        contentType,
        contentLength);
  }

  private static UUID uuid(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isTextual()) throw malformed();
    UUID parsed = UUID.fromString(value.textValue());
    if (!parsed.toString().equals(value.textValue())) throw malformed();
    return parsed;
  }

  private static UUID nullableUuid(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null) throw malformed();
    return value.isNull() ? null : uuid(json, field);
  }

  private static long count(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw malformed();
    }
    return value.longValue();
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw malformed();
    }
    return value.textValue();
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
            response.headers().firstValue("Content-Type").orElse(""),
            response.headers().firstValue("Cache-Control").orElse(""),
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

  private static void requireNoStore(BoundedResponse response, String dependency) {
    if (!Arrays.stream(response.cacheControl().split(","))
        .map(String::trim)
        .anyMatch(value -> "no-store".equalsIgnoreCase(value))) {
      throw badGateway(dependency + " response must be no-store");
    }
  }

  private static void requireId(UUID value, String message) {
    if (value == null) throw new IllegalArgumentException(message);
  }

  private static AssetDependencyException malformed() {
    return badGateway("Media cabin photo response is malformed");
  }

  private static AssetDependencyException badGateway(String message) {
    return new AssetDependencyException(HttpStatus.BAD_GATEWAY, message);
  }

  private static AssetDependencyException badGateway(String message, Throwable cause) {
    return new AssetDependencyException(HttpStatus.BAD_GATEWAY, message, cause);
  }

  private static AssetDependencyException unavailable(String message) {
    return new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  private static AssetDependencyException unavailable(String message, Throwable cause) {
    return new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
  }

  static HttpClient httpClient(Duration timeout) {
    return HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /** Serialized request for one bounded warehouse-scoped proof read. */
  private record SnapshotRequest(UUID warehouseId, List<UUID> cabinIds) {}

  /** Bounded dependency response retained only for validation and decoding. */
  private record BoundedResponse(
      int status, String contentType, String cacheControl, byte[] body) {}
}
