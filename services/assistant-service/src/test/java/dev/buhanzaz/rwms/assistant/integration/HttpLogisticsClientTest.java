package dev.buhanzaz.rwms.assistant.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.config.AssistantLogisticsProperties;
import dev.buhanzaz.rwms.assistant.service.AssistantInquiryArchivedException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HttpLogisticsClientTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void createsInquiryWithCurrentBearerConversationIdempotencyAndNoOptionalEmail() throws Exception {
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    start(
        exchange -> {
          received.add(record(exchange));
          reply(
              exchange,
              201,
              """
              {"id":"%s","state":"ACTIVE","client":{"id":"%s","type":"INDIVIDUAL","displayName":"Ada Client"}}
              """.formatted(inquiryId, clientId));
        });

    LogisticsClient.InquiryBootstrap result =
        client()
            .createRentalInquiry(
                conversationId,
                null,
                new AssistantApiModels.NewClientRequest(
                    "INDIVIDUAL", "Ada Client", "+7 900 000 00 00", null),
                "current-user-bearer");

    assertThat(result)
        .isEqualTo(
            new LogisticsClient.InquiryBootstrap(
                inquiryId, clientId, "ACTIVE", "INDIVIDUAL", "Ada Client"));
    assertThat(received).hasSize(1);
    RecordedRequest request = received.getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/api/logistics/v1/rental-inquiries");
    assertThat(request.header("Authorization")).isEqualTo("Bearer current-user-bearer");
    assertThat(request.header("Idempotency-Key")).isEqualTo(conversationId.toString());
    assertThat(request.body().path("conversationId").asText()).isEqualTo(conversationId.toString());
    assertThat(request.body().path("newClient").path("clientType").asText())
        .isEqualTo("INDIVIDUAL");
    assertThat(request.body().path("newClient").has("email")).isFalse();
  }

  @Test
  void forwardsOnlyBoundedFacetAndCabinSearchCalls() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    String exactDimensions = " 6x2.4 ";
    List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    start(
        exchange -> {
          received.add(record(exchange));
          if (exchange.getRequestMethod().equals("GET")) {
            reply(
                exchange,
                200,
                """
                {"warehouses":[{"warehouseId":"%s","name":"Moscow","city":"Moscow","cabinTypes":["6m"],"finishes":["basic"],"dimensions":["6x2.4"],"categories":["Новая","ИТР"]}]}
                """.formatted(warehouseId));
          } else {
            reply(
                exchange,
                200,
                """
                {"warehouseId":"%s","groups":[]}
                """.formatted(warehouseId));
          }
        });

    JsonNode facets = client().listAvailableCabinFacets(inquiryId, "current-user-bearer");
    JsonNode search =
        client()
            .searchAvailableCabins(
                inquiryId,
                new LogisticsClient.CabinSearch(
                    List.of(
                        new LogisticsClient.CabinSearchGroup(
                            "6m",
                            "basic",
                            exactDimensions,
                            "Новая",
                            "с тамбуром",
                            false,
                            2)),
                    warehouseId),
                "current-user-bearer");

    assertThat(facets.path("warehouses").get(0).path("warehouseId").asText())
        .isEqualTo(warehouseId.toString());
    assertThat(facets.path("warehouses").get(0).has("code")).isFalse();
    assertThat(search.path("warehouseId").asText()).isEqualTo(warehouseId.toString());
    assertThat(received).hasSize(2);
    assertThat(received.get(0).method()).isEqualTo("GET");
    assertThat(received.get(0).path())
        .isEqualTo("/api/logistics/v1/rental-inquiries/" + inquiryId + "/cabin-facets");
    assertThat(received.get(0).header("Authorization")).isEqualTo("Bearer current-user-bearer");
    assertThat(received.get(1).method()).isEqualTo("POST");
    assertThat(received.get(1).path())
        .isEqualTo("/api/logistics/v1/rental-inquiries/" + inquiryId + "/cabin-searches");
    assertThat(received.get(1).body().path("warehouseId").asText()).isEqualTo(warehouseId.toString());
    assertThat(received.get(1).body().path("groups").get(0).path("quantity").asInt()).isEqualTo(2);
    assertThat(received.get(1).body().path("groups").get(0).path("category").asText())
        .isEqualTo("Новая");
    assertThat(received.get(1).body().path("groups").get(0).has("condition")).isFalse();
    assertThat(received.get(1).body().path("groups").get(0).path("dimensions").asText())
        .isEqualTo(exactDimensions);
    assertThat(received.get(1).body().path("groups").get(0).path("characteristics").asText())
        .isEqualTo("с тамбуром");
    assertThat(received.get(1).body().path("groups").get(0).path("linoleum").booleanValue())
        .isFalse();
  }

  @Test
  void readsTheExistingClientPresentationAndTreatsOnly404AsAbsent() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    start(
        exchange -> {
          received.add(record(exchange));
          reply(
              exchange,
              200,
              """
              {"inquiryId":"%s","state":"ACTIVE"}
              """
                  .formatted(inquiryId));
        });

    assertThat(client().findClientPresentation(inquiryId, "current-user-bearer"))
        .contains(new LogisticsClient.ClientPresentation(inquiryId, "ACTIVE"));
    assertThat(received)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.method()).isEqualTo("GET");
              assertThat(request.path())
                  .isEqualTo(
                      "/api/logistics/v1/rental-inquiries/"
                          + inquiryId
                          + "/client-presentation");
              assertThat(request.header("Authorization")).isEqualTo("Bearer current-user-bearer");
            });

    stopServer();
    start(exchange -> reply(exchange, 404, "{}"));
    assertThat(client().findClientPresentation(inquiryId, "current-user-bearer")).isEmpty();
  }

  @Test
  void mapsOnlyTheSafeArchivedInquiryProblemToATerminalLifecycleException() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    start(
        exchange ->
            reply(
                exchange,
                409,
                """
                {"type":"urn:rwms:problem:inquiry-archived","status":409,
                 "detail":"Internal lifecycle detail must not escape","code":"INQUIRY_ARCHIVED"}
                """));

    assertThatThrownBy(
            () ->
                client()
                    .searchAvailableCabins(
                        inquiryId,
                        new LogisticsClient.CabinSearch(
                            List.of(
                                new LogisticsClient.CabinSearchGroup(
                                    "БК-1", null, null, null, null, null, 1)),
                            warehouseId),
                        "current-user-bearer"))
        .isInstanceOf(AssistantInquiryArchivedException.class)
        .hasMessage("The rental inquiry is archived");
  }

  private HttpLogisticsClient client() {
    return new HttpLogisticsClient(
        new AssistantLogisticsProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5)),
        mapper);
  }

  private void start(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/logistics/v1/rental-inquiries", exchange -> handler.handle(exchange));
    server.createContext("/api/logistics/v1/rental-inquiries/", exchange -> handler.handle(exchange));
    server.start();
  }

  private RecordedRequest record(HttpExchange exchange) throws IOException {
    return new RecordedRequest(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getPath(),
        exchange.getRequestHeaders(),
        mapper.readTree(exchange.getRequestBody().readAllBytes()));
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] data = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, data.length);
    exchange.getResponseBody().write(data);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private record RecordedRequest(
      String method,
      String path,
      com.sun.net.httpserver.Headers headers,
      JsonNode body) {
    String header(String name) {
      return headers.getFirst(name);
    }
  }
}
