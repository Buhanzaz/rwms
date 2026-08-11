package dev.buhanzaz.rwms.assistant.integration;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.config.AssistantLogisticsProperties;
import dev.buhanzaz.rwms.assistant.service.AssistantInquiryArchivedException;
import dev.buhanzaz.rwms.assistant.service.AssistantUpstreamException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deliberately small private REST client. It forwards the current user bearer token only to
 * logistics and never writes request bodies to logs.
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
    return createRentalInquiry(conversationId, clientId, newClient, null, bearerToken);
  }

  @Override
  public InquiryBootstrap createRentalInquiry(
      UUID conversationId,
      UUID clientId,
      AssistantApiModels.NewClientRequest newClient,
      UUID rentalOrderId,
      String bearerToken) {
    if ((clientId == null) == (newClient == null)) {
      throw new IllegalArgumentException("Exactly one of clientId or newClient is required");
    }
    if (rentalOrderId != null && clientId == null) {
      throw new IllegalArgumentException("An order-linked inquiry requires an existing client");
    }
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    request.put("conversationId", conversationId.toString());
    if (rentalOrderId != null) request.put("rentalOrderId", rentalOrderId.toString());
    if (clientId != null) {
      request.put("clientId", clientId.toString());
    } else {
      ObjectNode client = request.putObject("newClient");
      client.put("clientType", newClient.clientType());
      client.put("displayName", newClient.displayName());
      client.put("phone", newClient.phone());
      putOptional(client, "contactPerson", newClient.contactPerson());
      if (newClient.email() != null && !newClient.email().isBlank()) {
        client.put("email", newClient.email().trim());
      }
      putOptional(client, "comment", newClient.comment());
      putOptional(client, "source", newClient.source());
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
    UUID resolvedRentalOrderId = firstUuid(response.path("rentalOrderId"));
    if (inquiryId == null
        || resolvedClientId == null
        || (clientId != null && !clientId.equals(resolvedClientId))
        || !java.util.Objects.equals(rentalOrderId, resolvedRentalOrderId)) {
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
        firstText(
            response.path("client").path("displayName"), response.path("client").path("name")));
  }

  @Override
  public RentalInquiryContext readRentalInquiryContext(UUID rentalInquiryId, String bearerToken) {
    JsonNode response =
        exchange(
            "GET",
            "/api/logistics/v1/rental-inquiries/" + rentalInquiryId,
            null,
            bearerToken,
            null);
    UUID responseInquiryId = firstUuid(response.path("id"), response.path("rentalInquiryId"));
    UUID clientId = firstUuid(response.path("client").path("id"), response.path("clientId"));
    UUID rentalOrderId = firstUuid(response.path("rentalOrderId"));
    UUID warehouseId = firstUuid(response.path("warehouseId"));
    String state = firstText(response.path("state"), response.path("status"));
    if (!rentalInquiryId.equals(responseInquiryId) || clientId == null || state == null) {
      throw new AssistantUpstreamException("Logistics returned an invalid rental inquiry context");
    }
    try {
      return new RentalInquiryContext(
          responseInquiryId, clientId, rentalOrderId, warehouseId, state);
    } catch (IllegalArgumentException invalid) {
      throw new AssistantUpstreamException(
          "Logistics returned an invalid rental inquiry context", invalid);
    }
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
      UUID rentalInquiryId, UUID idempotencyKey, CabinSearch search, String bearerToken) {
    if (idempotencyKey == null) {
      throw new IllegalArgumentException("idempotencyKey is required for a cabin search");
    }
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
    request.put("resultMode", search.resultMode().name());
    return exchange(
        "POST",
        "/api/logistics/v1/rental-inquiries/" + rentalInquiryId + "/cabin-searches",
        request,
        bearerToken,
        idempotencyKey.toString(),
        false,
        true);
  }

  @Override
  public CabinSelection readCabinSelection(UUID rentalInquiryId, String bearerToken) {
    JsonNode response =
        exchange(
            "GET",
            "/api/logistics/v1/rental-inquiries/" + rentalInquiryId + "/cabin-selection",
            null,
            bearerToken,
            null);
    return cabinSelection(response, rentalInquiryId);
  }

  @Override
  public CabinSelection replaceCabinSelection(
      UUID rentalInquiryId,
      UUID idempotencyKey,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      String bearerToken) {
    if (idempotencyKey == null || warehouseId == null || rentalItemIds == null) {
      throw new IllegalArgumentException("Cabin selection command is incomplete");
    }
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    request.put("warehouseId", warehouseId.toString());
    ArrayNode ids = request.putArray("rentalItemIds");
    rentalItemIds.forEach(id -> ids.add(id.toString()));
    JsonNode response =
        exchange(
            "PUT",
            "/api/logistics/v1/rental-inquiries/" + rentalInquiryId + "/cabin-selection",
            request,
            bearerToken,
            idempotencyKey.toString(),
            false,
            true);
    return cabinSelection(response, rentalInquiryId);
  }

  @Override
  public JsonNode lookupCabinCatalog(
      UUID rentalInquiryId,
      UUID warehouseId,
      String query,
      int page,
      int size,
      String bearerToken) {
    if (warehouseId == null || query == null || query.isBlank() || query.length() > 255) {
      throw new IllegalArgumentException("A bounded cabin catalog query is required");
    }
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Cabin catalog page is invalid");
    }
    String path =
        "/api/logistics/v1/rental-inquiries/"
            + rentalInquiryId
            + "/cabin-catalog?warehouseId="
            + warehouseId
            + "&query="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&page="
            + page
            + "&size="
            + size;
    return exchange("GET", path, null, bearerToken, null);
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
      String method, String path, JsonNode payload, String bearerToken, String idempotencyKey) {
    return exchange(method, path, payload, bearerToken, idempotencyKey, false, false);
  }

  /** A 404 is a normal answer only for an inquiry that has not published a presentation yet. */
  private JsonNode exchange(
      String method,
      String path,
      JsonNode payload,
      String bearerToken,
      String idempotencyKey,
      boolean notFoundIsEmpty) {
    return exchange(method, path, payload, bearerToken, idempotencyKey, notFoundIsEmpty, false);
  }

  /**
   * A cabin-search lost-response retry re-sends the same immutable request and caller key once;
   * status responses, validation failures and interruption are never retried.
   */
  private JsonNode exchange(
      String method,
      String path,
      JsonNode payload,
      String bearerToken,
      String idempotencyKey,
      boolean notFoundIsEmpty,
      boolean retryLostResponseOnce) {
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
      } else if ("PUT".equals(method)) {
        request
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
      } else {
        request
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
      }
      HttpRequest immutableRequest = request.build();
      HttpResponse<String> response;
      try {
        response = client.send(immutableRequest, HttpResponse.BodyHandlers.ofString());
      } catch (IOException firstLostResponse) {
        if (!retryLostResponseOnce) throw firstLostResponse;
        response = client.send(immutableRequest, HttpResponse.BodyHandlers.ofString());
      }
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

  private static CabinSelection cabinSelection(JsonNode response, UUID expectedInquiryId) {
    if (response == null || !response.isObject()) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin selection");
    }
    UUID inquiryId = firstUuid(response.path("inquiryId"));
    UUID warehouseId = firstUuid(response.path("warehouseId"));
    OffsetDateTime expiresAt = optionalDateTime(response.get("expiresAt"));
    JsonNode idsNode = response.path("rentalItemIds");
    JsonNode itemsNode = response.path("items");
    if (!expectedInquiryId.equals(inquiryId)
        || !idsNode.isArray()
        || !itemsNode.isArray()
        || idsNode.size() != itemsNode.size()
        || (!idsNode.isEmpty() && warehouseId == null)) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin selection");
    }
    List<UUID> ids = new ArrayList<>();
    List<JsonNode> items = new ArrayList<>();
    for (int index = 0; index < idsNode.size(); index++) {
      UUID id = firstUuid(idsNode.get(index));
      JsonNode item = itemsNode.get(index);
      if (id == null
          || !item.isObject()
          || !id.toString().equals(item.path("id").asText())
          || !"FREE".equals(item.path("status").asText())
          || warehouseId == null
          || !warehouseId.toString().equals(item.path("warehouseId").asText())
          || ids.contains(id)) {
        throw new AssistantUpstreamException("Logistics returned an invalid cabin selection");
      }
      ids.add(id);
      items.add(item.deepCopy());
    }
    try {
      return new CabinSelection(inquiryId, warehouseId, expiresAt, ids, items);
    } catch (IllegalArgumentException invalid) {
      throw new AssistantUpstreamException(
          "Logistics returned an invalid cabin selection", invalid);
    }
  }

  private static OffsetDateTime optionalDateTime(JsonNode value) {
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) {
      throw new AssistantUpstreamException("Logistics returned an invalid cabin selection");
    }
    try {
      return OffsetDateTime.parse(value.asText());
    } catch (DateTimeParseException invalid) {
      throw new AssistantUpstreamException(
          "Logistics returned an invalid cabin selection", invalid);
    }
  }

  private static void putOptional(ObjectNode target, String field, String value) {
    if (value != null && !value.isBlank()) target.put(field, value.trim());
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
