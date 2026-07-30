package dev.buhanzaz.rwms.assistant.integration;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.config.AssistantLogisticsProperties;
import dev.buhanzaz.rwms.assistant.service.AssistantInquiryArchivedException;
import dev.buhanzaz.rwms.assistant.service.AssistantUpstreamException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deliberately small private REST client. It forwards the current user bearer
 * token only to logistics and never writes request bodies to logs.
 */
@Component
public class HttpLogisticsClient implements LogisticsClient {
  private final AssistantLogisticsProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public HttpLogisticsClient(AssistantLogisticsProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public InquiryBootstrap createRentalInquiry(
      UUID conversationId,
      UUID clientId,
      AssistantApiModels.NewClientRequest newClient,
      String bearerToken) {
    if ((clientId == null) == (newClient == null)) {
      throw new IllegalArgumentException("Exactly one of clientId or newClient is required");
    }
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    request.put("conversationId", conversationId.toString());
    if (clientId != null) {
      request.put("clientId", clientId.toString());
    } else {
      ObjectNode client = request.putObject("newClient");
      client.put("clientType", newClient.clientType());
      client.put("displayName", newClient.displayName());
      client.put("phone", newClient.phone());
      if (newClient.email() != null && !newClient.email().isBlank()) {
        client.put("email", newClient.email().trim());
      }
    }
    JsonNode response =
        exchange(
            "POST",
            "/api/logistics/v1/rental-inquiries",
            request,
            bearerToken,
            conversationId.toString());
    UUID inquiryId =
        firstUuid(
            response.path("rentalInquiryId"),
            response.path("inquiry").path("id"),
            response.path("id"));
    UUID resolvedClientId =
        firstUuid(
            response.path("client").path("id"),
            response.path("clientId"),
            clientId == null ? null : JsonNodeFactory.instance.textNode(clientId.toString()));
    if (inquiryId == null || resolvedClientId == null) {
      throw new AssistantUpstreamException("Logistics returned an invalid rental inquiry response");
    }
    return new InquiryBootstrap(
        inquiryId,
        resolvedClientId,
        firstText(
            response.path("inquiry").path("status"),
            response.path("status"),
            response.path("state")),
        firstText(response.path("client").path("clientType"), response.path("client").path("type")),
        firstText(response.path("client").path("displayName"), response.path("client").path("name")));
  }

  @Override
  public JsonNode listAvailableCabinFacets(UUID rentalInquiryId, String bearerToken) {
    return exchange(
        "GET",
        "/api/logistics/v1/rental-inquiries/" + rentalInquiryId + "/cabin-facets",
        null,
        bearerToken,
        null);
  }

  @Override
  public JsonNode searchAvailableCabins(
      UUID rentalInquiryId, CabinSearch search, String bearerToken) {
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    ArrayNode groups = request.putArray("groups");
    for (CabinSearchGroup group : search.groups()) {
      ObjectNode item = groups.addObject();
      if (group.cabinType() != null) item.put("cabinType", group.cabinType());
      if (group.finish() != null) item.put("finish", group.finish());
      if (group.dimensions() != null) item.put("dimensions", group.dimensions());
      if (group.category() != null) item.put("category", group.category());
      if (group.characteristics() != null) {
        item.put("characteristics", group.characteristics());
      }
      if (group.linoleum() != null) item.put("linoleum", group.linoleum());
      item.put("quantity", group.quantity());
    }
    if (search.warehouseId() != null) {
      request.put("warehouseId", search.warehouseId().toString());
    }
    return exchange(
        "POST",
        "/api/logistics/v1/rental-inquiries/" + rentalInquiryId + "/cabin-searches",
        request,
        bearerToken,
        UUID.randomUUID().toString());
  }

  @Override
  public Optional<ClientPresentation> findClientPresentation(
      UUID rentalInquiryId, String bearerToken) {
    JsonNode response =
        exchange(
            "GET",
            "/api/logistics/v1/rental-inquiries/" + rentalInquiryId + "/client-presentation",
            null,
            bearerToken,
            null,
            true);
    if (response != null && response.isNull()) return Optional.empty();
    if (response == null) {
      throw new AssistantUpstreamException("Logistics returned an invalid client presentation");
    }

    UUID responseInquiryId = firstUuid(response.path("inquiryId"));
    String state = firstText(response.path("state"));
    if (!rentalInquiryId.equals(responseInquiryId)
        || state == null
        || !("ACTIVE".equals(state)
            || "BOOKING_PENDING".equals(state)
            || "BOOKED".equals(state)
            || "REVOKED".equals(state))) {
      throw new AssistantUpstreamException("Logistics returned an invalid client presentation");
    }
    return Optional.of(new ClientPresentation(responseInquiryId, state));
  }

  private JsonNode exchange(
      String method,
      String path,
      JsonNode payload,
      String bearerToken,
      String idempotencyKey) {
    return exchange(method, path, payload, bearerToken, idempotencyKey, false);
  }

  /** A 404 is a normal answer only for an inquiry that has not published a presentation yet. */
  private JsonNode exchange(
      String method,
      String path,
      JsonNode payload,
      String bearerToken,
      String idempotencyKey,
      boolean notFoundIsEmpty) {
    if (bearerToken == null || bearerToken.isBlank()) {
      throw new AssistantUpstreamException("Current bearer token is required for logistics");
    }
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(uri(path))
              .timeout(properties.requestTimeout())
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + bearerToken);
      if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
      if ("GET".equals(method)) {
        request.GET();
      } else {
        request
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
      }
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        if (notFoundIsEmpty && response.statusCode() == 404) {
          return JsonNodeFactory.instance.nullNode();
        }
        if (response.statusCode() == 409 && isInquiryArchivedProblem(response.body())) {
          throw new AssistantInquiryArchivedException();
        }
        throw new AssistantUpstreamException("Logistics request failed");
      }
      return mapper.readTree(response.body());
    } catch (IOException exception) {
      throw new AssistantUpstreamException("Logistics request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssistantUpstreamException("Logistics request interrupted", exception);
    }
  }

  private URI uri(String path) {
    String base = properties.baseUrl();
    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return URI.create(base + path);
  }

  /** Never surface an upstream problem body, but retain this one safe lifecycle code. */
  private boolean isInquiryArchivedProblem(String body) {
    if (body == null || body.isBlank()) return false;
    try {
      JsonNode problem = mapper.readTree(body);
      return "INQUIRY_ARCHIVED".equals(problem.path("code").asText())
          || "INQUIRY_ARCHIVED".equals(problem.path("properties").path("code").asText());
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static UUID firstUuid(JsonNode... candidates) {
    for (JsonNode candidate : candidates) {
      if (candidate == null || !candidate.isTextual()) continue;
      try {
        return UUID.fromString(candidate.asText());
      } catch (IllegalArgumentException ignored) {
        // Try the next documented response location.
      }
    }
    return null;
  }

  private static String firstText(JsonNode... candidates) {
    for (JsonNode candidate : candidates) {
      if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
        String value = candidate.asText().trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
      }
    }
    return null;
  }
}
