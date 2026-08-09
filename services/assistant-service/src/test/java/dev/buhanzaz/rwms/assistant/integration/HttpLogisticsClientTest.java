package dev.buhanzaz.rwms.assistant.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.config.AssistantLogisticsProperties;
import dev.buhanzaz.rwms.assistant.service.AssistantInquiryArchivedException;
import dev.buhanzaz.rwms.assistant.service.AssistantUpstreamException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Verifies the private logistics wire contract and safe error mapping. */
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
                    "INDIVIDUAL",
                    "Ada Client",
                    "+7 900 000 00 00",
                    null,
                    null,
                    null,
                    null),
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
  void forwardsEverySupportedSoleProprietorClientField() throws Exception {
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
              {"id":"%s","state":"ACTIVE","client":{"id":"%s","type":"SOLE_PROPRIETOR","displayName":"ИП Север"}}
              """
                  .formatted(inquiryId, clientId));
        });

    client()
        .createRentalInquiry(
            conversationId,
            null,
            new AssistantApiModels.NewClientRequest(
                "SOLE_PROPRIETOR",
                "ИП Север",
                "+79990000000",
                "Иван Петров",
                "client@example.test",
                "Приоритетный клиент",
                "Рекомендация"),
            "current-user-bearer");

    JsonNode newClient = received.getFirst().body().path("newClient");
    assertThat(newClient.path("clientType").asText()).isEqualTo("SOLE_PROPRIETOR");
    assertThat(newClient.path("contactPerson").asText()).isEqualTo("Иван Петров");
    assertThat(newClient.path("email").asText()).isEqualTo("client@example.test");
    assertThat(newClient.path("comment").asText()).isEqualTo("Приоритетный клиент");
    assertThat(newClient.path("source").asText()).isEqualTo("Рекомендация");
  }

  @Test
  void forwardsOnlyBoundedFacetAndCabinSearchCalls() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID searchKey = UUID.randomUUID();
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
                searchKey,
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
    assertThat(received.get(1).header("Idempotency-Key")).isEqualTo(searchKey.toString());
    assertThat(received.get(1).body().path("warehouseId").asText()).isEqualTo(warehouseId.toString());
    assertThat(received.get(1).body().path("resultMode").asText()).isEqualTo("REPLACE");
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
  void readsAndReplacesSelectionAndPerformsBoundedReadOnlyCatalogLookup() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    AtomicInteger selectionReads = new AtomicInteger();
    start(
        exchange -> {
          RecordedRequest request = record(exchange);
          received.add(request);
          if (request.path().endsWith("/cabin-catalog")) {
            reply(
                exchange,
                200,
                """
                {"warehouseId":"%s","content":[{"id":"%s","number":"CAB-1"}],
                 "page":0,"size":20,"totalElements":1,"totalPages":1}
                """
                    .formatted(warehouseId, rentalItemId));
          } else if (request.method().equals("GET") && selectionReads.getAndIncrement() == 0) {
            reply(
                exchange,
                200,
                """
                {"inquiryId":"%s","warehouseId":null,"expiresAt":null,
                 "rentalItemIds":[],"items":[]}
                """
                    .formatted(inquiryId));
          } else {
            reply(
                exchange,
                200,
                """
                {"inquiryId":"%s","warehouseId":"%s","expiresAt":"2030-08-09T12:00:00Z",
                 "rentalItemIds":["%s"],"items":[{"id":"%s","version":0,
                 "warehouseId":"%s","number":"CAB-1","status":"FREE",
                 "updatedAt":"2030-08-09T11:00:00Z"}]}
                """
                    .formatted(
                        inquiryId,
                        warehouseId,
                        rentalItemId,
                        rentalItemId,
                        warehouseId));
          }
        });
    HttpLogisticsClient client = client();

    LogisticsClient.CabinSelection empty =
        client.readCabinSelection(inquiryId, "current-user-bearer");
    LogisticsClient.CabinSelection replaced =
        client.replaceCabinSelection(
            inquiryId,
            idempotencyKey,
            warehouseId,
            List.of(rentalItemId),
            "current-user-bearer");
    JsonNode catalog =
        client.lookupCabinCatalog(
            inquiryId, warehouseId, "CAB 1/Линолеум", 0, 20, "current-user-bearer");

    assertThat(empty.warehouseId()).isNull();
    assertThat(empty.rentalItemIds()).isEmpty();
    assertThat(replaced.rentalItemIds()).containsExactly(rentalItemId);
    assertThat(catalog.path("content").get(0).path("number").asText()).isEqualTo("CAB-1");
    assertThat(received.get(1).method()).isEqualTo("PUT");
    assertThat(received.get(1).header("Idempotency-Key")).isEqualTo(idempotencyKey.toString());
    assertThat(received.get(1).body().path("warehouseId").asText())
        .isEqualTo(warehouseId.toString());
    assertThat(received.get(1).body().path("rentalItemIds"))
        .extracting(JsonNode::asText)
        .containsExactly(rentalItemId.toString());
    assertThat(received.get(2).method()).isEqualTo("GET");
    assertThat(received.get(2).rawQuery())
        .contains(
            "warehouseId=" + warehouseId,
            "query=CAB+1%2F%D0%9B%D0%B8%D0%BD%D0%BE%D0%BB%D0%B5%D1%83%D0%BC",
            "page=0",
            "size=20");
  }

  @Test
  void rejectsANonFreeCabinInTheAuthoritativeSelection() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    start(
        exchange ->
            reply(
                exchange,
                200,
                """
                {"inquiryId":"%s","warehouseId":"%s","expiresAt":"2030-08-09T12:00:00Z",
                 "rentalItemIds":["%s"],"items":[{"id":"%s","version":0,
                 "warehouseId":"%s","number":"CAB-1","status":"RENTED",
                 "updatedAt":"2030-08-09T11:00:00Z"}]}
                """
                    .formatted(
                        inquiryId,
                        warehouseId,
                        rentalItemId,
                        rentalItemId,
                        warehouseId)));

    assertThatThrownBy(
            () -> client().readCabinSelection(inquiryId, "current-user-bearer"))
        .isInstanceOf(AssistantUpstreamException.class)
        .hasMessage("Logistics returned an invalid cabin selection");
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
                        UUID.randomUUID(),
                        new LogisticsClient.CabinSearch(
                            List.of(
                                new LogisticsClient.CabinSearchGroup(
                                    "БК-1", null, null, null, null, null, 1)),
                            warehouseId),
                        "current-user-bearer"))
        .isInstanceOf(AssistantInquiryArchivedException.class)
        .hasMessage("The rental inquiry is archived");
  }

  @Test
  void retriesOneLostResponseWithTheExactCallerKeyAndARecreatedClientReusesIt()
      throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID callerKey = UUID.randomUUID();
    AtomicInteger attempts = new AtomicInteger();
    List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    start(
        exchange -> {
          received.add(record(exchange));
          if (attempts.getAndIncrement() == 0) {
            exchange.close();
            return;
          }
          reply(exchange, 200, "{\"warehouseId\":\"" + warehouseId + "\",\"groups\":[]}");
        });
    LogisticsClient.CabinSearch request =
        new LogisticsClient.CabinSearch(
            List.of(
                new LogisticsClient.CabinSearchGroup(
                    "БК-1", null, null, null, null, null, 1)),
            warehouseId);

    client().searchAvailableCabins(inquiryId, callerKey, request, "current-user-bearer");
    client().searchAvailableCabins(inquiryId, callerKey, request, "current-user-bearer");

    assertThat(received).hasSize(3);
    assertThat(received)
        .allSatisfy(
            recorded ->
                assertThat(recorded.header("Idempotency-Key"))
                    .isEqualTo(callerKey.toString()));
    assertThat(received)
        .extracting(RecordedRequest::body)
        .allSatisfy(body -> assertThat(body).isEqualTo(received.getFirst().body()));
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
        exchange.getRequestURI().getRawQuery(),
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

  /** Local HTTP exchange callback used by each wire-contract scenario. */
  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }

  /** Captured request evidence including the raw encoded query string. */
  private record RecordedRequest(
      String method,
      String path,
      String rawQuery,
      com.sun.net.httpserver.Headers headers,
      JsonNode body) {
    String header(String name) {
      return headers.getFirst(name);
    }
  }
}
