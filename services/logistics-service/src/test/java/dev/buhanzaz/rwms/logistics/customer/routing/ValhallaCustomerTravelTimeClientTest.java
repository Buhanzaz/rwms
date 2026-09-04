package dev.buhanzaz.rwms.logistics.customer.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Covers truck-only Valhalla requests, versioned bounded caching and fail-closed route errors. */
class ValhallaCustomerTravelTimeClientTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000713");
  private static final List<GeoPoint> POINTS =
      List.of(new GeoPoint(59.90, 30.30), new GeoPoint(59.95, 30.40));

  private HttpServer server;
  private final ObjectMapper json = new ObjectMapper();
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private final List<ValhallaCustomerTravelTimeClient> clients = new CopyOnWriteArrayList<>();
  private volatile int responseStatus;
  private volatile String responseBody;
  private volatile long responseDelayMillis;
  private volatile boolean dynamicResponse;

  @BeforeEach
  void startServer() throws IOException {
    responseStatus = 200;
    responseDelayMillis = 0;
    dynamicResponse = false;
    responseBody =
        """
        {"sources_to_targets":[
          [{"time":0},{"time":600}],
          [{"time":700},{"time":0}]
        ]}
        """;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/sources_to_targets", this::respond);
    server.start();
  }

  @AfterEach
  void stopServer() {
    clients.forEach(ValhallaCustomerTravelTimeClient::close);
    server.stop(0);
  }

  @Test
  void sendsTruckParametersAndSeparatesSoloAndTrailerCacheKeys() {
    CustomerDeliveryProperties.Validated configuration = configuration();
    ValhallaCustomerTravelTimeClient client = client();
    CustomerVehicleRouteProfile solo =
        CustomerVehicleRouteProfile.forTripCapacity(configuration, 1);
    CustomerVehicleRouteProfile trailer =
        CustomerVehicleRouteProfile.forTripCapacity(configuration, 2);

    var first =
        client.matrix(
            POINTS, LocalDate.of(2026, 8, 29), LocalTime.of(9, 1), configuration, solo);
    var cached =
        client.matrix(
            POINTS, LocalDate.of(2026, 8, 29), LocalTime.of(9, 14), configuration, solo);
    client.matrix(
        POINTS, LocalDate.of(2026, 8, 29), LocalTime.of(9, 1), configuration, trailer);

    assertThat(first).isSameAs(cached);
    assertThat(first.travelSeconds(0, 1)).isEqualTo(600);
    assertThat(first.travelSeconds(1, 0)).isEqualTo(700);
    assertThat(requestBodies).hasSize(2);
    assertThat(requestBodies)
        .allSatisfy(
            body -> {
              assertThat(body).contains("\"costing\":\"truck\"");
              assertThat(body).doesNotContain("\"costing\":\"auto\"");
              assertThat(body).contains("\"ignore_restrictions\":false");
              assertThat(body).contains("\"date_time\"");
            });
    assertThat(requestBodies.get(0)).contains("\"length\":10.0");
    assertThat(requestBodies.get(1)).contains("\"length\":12.0");
  }

  @Test
  void routingDataVersionChangeMissesTheCache() {
    ValhallaCustomerTravelTimeClient client = client();
    CustomerDeliveryProperties.Validated firstGraph =
        configuration(Duration.ofSeconds(1), "graph-v1", Duration.ofMinutes(15));

    CustomerTravelTimeMatrix first =
        client.matrix(
            POINTS,
            LocalDate.of(2026, 8, 29),
            LocalTime.of(10, 0),
            firstGraph,
            CustomerVehicleRouteProfile.forTripCapacity(firstGraph, 1));
    responseBody = matrixResponse(900, 950);
    CustomerDeliveryProperties.Validated secondGraph =
        configuration(Duration.ofSeconds(1), "graph-v2", Duration.ofMinutes(15));
    CustomerTravelTimeMatrix refreshed =
        client.matrix(
            POINTS,
            LocalDate.of(2026, 8, 29),
            LocalTime.of(10, 0),
            secondGraph,
            CustomerVehicleRouteProfile.forTripCapacity(secondGraph, 1));

    assertThat(refreshed).isNotSameAs(first);
    assertThat(refreshed.travelSeconds(0, 1)).isEqualTo(900);
    assertThat(requestBodies).hasSize(2);
  }

  @Test
  void expiredEntryIsRefreshedAtItsMonotonicTtl() {
    AtomicLong nowNanos = new AtomicLong();
    ValhallaCustomerTravelTimeClient client = client(512, nowNanos::get);
    CustomerDeliveryProperties.Validated configuration =
        configuration(Duration.ofSeconds(1), "graph-v1", Duration.ofNanos(10));
    CustomerVehicleRouteProfile profile =
        CustomerVehicleRouteProfile.forTripCapacity(configuration, 1);

    CustomerTravelTimeMatrix first =
        client.matrix(
            POINTS, LocalDate.of(2026, 8, 29), LocalTime.of(11, 0), configuration, profile);
    nowNanos.set(9);
    CustomerTravelTimeMatrix stillCached =
        client.matrix(
            POINTS, LocalDate.of(2026, 8, 29), LocalTime.of(11, 14), configuration, profile);
    responseBody = matrixResponse(800, 850);
    nowNanos.set(10);
    CustomerTravelTimeMatrix refreshed =
        client.matrix(
            POINTS, LocalDate.of(2026, 8, 29), LocalTime.of(11, 1), configuration, profile);

    assertThat(stillCached).isSameAs(first);
    assertThat(refreshed).isNotSameAs(first);
    assertThat(refreshed.travelSeconds(0, 1)).isEqualTo(800);
    assertThat(requestBodies).hasSize(2);
  }

  @Test
  void capacityEvictsOnlyTheLeastRecentlyUsedEntry() {
    ValhallaCustomerTravelTimeClient client = client(2, () -> 0L);
    CustomerDeliveryProperties.Validated configuration = configuration();
    CustomerVehicleRouteProfile profile =
        CustomerVehicleRouteProfile.forTripCapacity(configuration, 1);
    LocalDate date = LocalDate.of(2026, 8, 29);

    client.matrix(POINTS, date, LocalTime.of(9, 0), configuration, profile);
    client.matrix(POINTS, date, LocalTime.of(9, 15), configuration, profile);
    client.matrix(POINTS, date, LocalTime.of(9, 1), configuration, profile);
    client.matrix(POINTS, date, LocalTime.of(9, 30), configuration, profile);
    client.matrix(POINTS, date, LocalTime.of(9, 2), configuration, profile);

    assertThat(requestBodies).hasSize(3);

    client.matrix(POINTS, date, LocalTime.of(9, 16), configuration, profile);

    assertThat(requestBodies).hasSize(4);
  }

  @Test
  void concurrentMissesShareOneInFlightTruckRequest() throws Exception {
    responseDelayMillis = 100;
    ValhallaCustomerTravelTimeClient client = client();
    CustomerDeliveryProperties.Validated configuration = configuration();
    CustomerVehicleRouteProfile profile =
        CustomerVehicleRouteProfile.forTripCapacity(configuration, 1);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(8)) {
      var results =
          IntStream.range(0, 8)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            start.await();
                            return client.matrix(
                                POINTS,
                                LocalDate.of(2026, 8, 29),
                                LocalTime.of(13, 0),
                                configuration,
                                profile);
                          }))
              .toList();
      start.countDown();
      CustomerTravelTimeMatrix first = results.getFirst().get();
      for (var result : results) {
        assertThat(result.get()).isSameAs(first);
      }
    }

    assertThat(requestBodies).hasSize(1);
    assertThat(requestBodies.getFirst()).contains("\"costing\":\"truck\"");
  }

  @Test
  void assemblesThirtyThreePointsFromBoundedDirectedBlocks() throws Exception {
    dynamicResponse = true;
    List<GeoPoint> points =
        IntStream.range(0, 33)
            .mapToObj(index -> new GeoPoint(50.0 + index / 1_000.0, 30.0 + index / 1_000.0))
            .toList();
    CustomerDeliveryProperties.Validated configuration = configuration();
    ValhallaCustomerTravelTimeClient client = client();

    CustomerTravelTimeMatrix matrix =
        client.matrix(
            points,
            LocalDate.of(2026, 8, 29),
            LocalTime.of(14, 0),
            configuration,
            CustomerVehicleRouteProfile.forTripCapacity(configuration, 1));
    CustomerTravelTimeMatrix cached =
        client.matrix(
            points,
            LocalDate.of(2026, 8, 29),
            LocalTime.of(14, 14),
            configuration,
            CustomerVehicleRouteProfile.forTripCapacity(configuration, 1));

    assertThat(matrix.points()).containsExactlyElementsOf(points);
    assertThat(matrix.seconds()).hasSize(33).allSatisfy(row -> assertThat(row).hasSize(33));
    assertThat(matrix.travelSeconds(0, 32)).isEqualTo(encodedTime(points.get(0), points.get(32)));
    assertThat(matrix.travelSeconds(32, 0)).isEqualTo(encodedTime(points.get(32), points.get(0)));
    assertThat(matrix.travelSeconds(17, 17)).isZero();
    assertThat(cached).isSameAs(matrix);
    assertThat(requestBodies).hasSize(4);
    for (String requestBody : requestBodies) {
      var request = json.readTree(requestBody);
      assertThat(request.required("sources").size()).isBetween(1, 32);
      assertThat(request.required("targets").size()).isBetween(1, 32);
    }
  }

  @Test
  void workloadBeyondBoundedMatrixReturnsTypedDiagnostic() {
    List<GeoPoint> points =
        IntStream.range(0, 129)
            .mapToObj(index -> new GeoPoint(50.0 + index / 1_000.0, 30.0))
            .toList();
    CustomerDeliveryProperties.Validated configuration = configuration();

    assertThatThrownBy(
            () ->
                client()
                    .matrix(
                        points,
                        LocalDate.of(2026, 8, 29),
                        LocalTime.of(14, 0),
                        configuration,
                        CustomerVehicleRouteProfile.forTripCapacity(configuration, 1)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code()).isEqualTo("CUSTOMER_DELIVERY_WORKLOAD_LIMIT"));
    assertThat(requestBodies).isEmpty();
  }

  @Test
  void missingTruckRouteNeverFallsBackToCarOrStraightLine() {
    responseBody =
        """
        {"sources_to_targets":[
          [{"time":0},{"time":null}],
          [{"time":700},{"time":0}]
        ]}
        """;
    CustomerDeliveryProperties.Validated configuration = configuration();
    ValhallaCustomerTravelTimeClient client = client();

    assertThatThrownBy(
            () ->
                client.matrix(
                    POINTS,
                    LocalDate.of(2026, 8, 29),
                    LocalTime.of(12, 0),
                    configuration,
                    CustomerVehicleRouteProfile.forTripCapacity(configuration, 2)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code()).isEqualTo("CUSTOMER_DELIVERY_ROUTE_NOT_FOUND"));
    assertThat(requestBodies).hasSize(1);
    assertThat(requestBodies.getFirst()).doesNotContain("\"costing\":\"auto\"");
  }

  @Test
  void nonSuccessfulRoutingResponseFailsClosed() {
    responseStatus = 503;
    responseBody = "{}";
    CustomerDeliveryProperties.Validated configuration = configuration();
    ValhallaCustomerTravelTimeClient client = client();

    assertThatThrownBy(
            () ->
                client.matrix(
                    POINTS,
                    LocalDate.of(2026, 8, 29),
                    LocalTime.of(15, 0),
                    configuration,
                    CustomerVehicleRouteProfile.forTripCapacity(configuration, 1)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception -> assertThat(exception.code()).isEqualTo("CUSTOMER_ROUTING_UNAVAILABLE"));
  }

  @Test
  void routingTimeoutFailsClosed() {
    responseDelayMillis = 300;
    CustomerDeliveryProperties.Validated configuration =
        configuration(Duration.ofMillis(100));
    ValhallaCustomerTravelTimeClient client = client();

    assertThatThrownBy(
            () ->
                client.matrix(
                    POINTS,
                    LocalDate.of(2026, 8, 29),
                    LocalTime.of(15, 0),
                    configuration,
                    CustomerVehicleRouteProfile.forTripCapacity(configuration, 1)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception -> assertThat(exception.code()).isEqualTo("CUSTOMER_ROUTING_UNAVAILABLE"));
  }

  private void respond(HttpExchange exchange) throws IOException {
    String requestBody =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requestBodies.add(requestBody);
    if (responseDelayMillis > 0) {
      try {
        Thread.sleep(responseDelayMillis);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
    String response = dynamicResponse ? rectangularMatrixResponse(requestBody) : responseBody;
    byte[] body = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(responseStatus, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private String rectangularMatrixResponse(String requestBody) throws IOException {
    var request = json.readTree(requestBody);
    var sources = request.required("sources");
    var targets = request.required("targets");
    List<List<Map<String, Long>>> rows = new java.util.ArrayList<>(sources.size());
    for (var source : sources) {
      GeoPoint from =
          new GeoPoint(source.required("lat").doubleValue(), source.required("lon").doubleValue());
      List<Map<String, Long>> row = new java.util.ArrayList<>(targets.size());
      for (var target : targets) {
        GeoPoint to =
            new GeoPoint(target.required("lat").doubleValue(), target.required("lon").doubleValue());
        row.add(Map.of("time", encodedTime(from, to)));
      }
      rows.add(List.copyOf(row));
    }
    return json.writeValueAsString(Map.of("sources_to_targets", rows));
  }

  private static long encodedTime(GeoPoint from, GeoPoint to) {
    if (from.equals(to)) return 0;
    return Math.round(from.latitude() * 1_000) * 1_000_000L
        + Math.round(to.latitude() * 1_000);
  }

  private CustomerDeliveryProperties.Validated configuration() {
    return configuration(Duration.ofSeconds(1));
  }

  private CustomerDeliveryProperties.Validated configuration(Duration readTimeout) {
    return configuration(readTimeout, "test-routing-data-v1", Duration.ofMinutes(15));
  }

  private CustomerDeliveryProperties.Validated configuration(
      Duration readTimeout, String routingDataVersion, Duration routingCacheTtl) {
    return new CustomerDeliveryProperties(
            true,
            List.of(
                new CustomerDeliveryProperties.Depot(
                    true,
                    WAREHOUSE.toString(),
                    BigDecimal.valueOf(59.90),
                    BigDecimal.valueOf(30.30))),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            routingDataVersion,
            routingCacheTtl,
            Duration.ofSeconds(1),
            readTimeout,
            30,
            60,
            1,
            31,
            Duration.ofMinutes(10),
            Duration.ofMinutes(10),
            1.15,
            5,
            LocalTime.of(8, 0),
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            LocalTime.of(20, 0),
            180,
            60,
            30,
            45,
            30,
            CustomerDeliveryProperties.DeliveryWindowSemantics.START_WITHIN_SLOT,
            CustomerDeliveryProperties.PickupPolicy.RETURN_LEG_ONLY,
            4.0,
            2.5,
            10.0,
            12.0,
            8.0,
            2,
            4.0,
            2.5,
            12.0,
            20.0,
            8.0,
            3)
        .validated(WAREHOUSE);
  }

  private ValhallaCustomerTravelTimeClient client() {
    return client(512, System::nanoTime);
  }

  private ValhallaCustomerTravelTimeClient client(int maxCacheEntries, LongSupplier nanoTime) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    ValhallaCustomerTravelTimeClient client =
        ValhallaCustomerTravelTimeClient.createForTesting(
            new ObjectMapper(), httpClient, maxCacheEntries, nanoTime);
    clients.add(client);
    return client;
  }

  private static String matrixResponse(long outboundSeconds, long returnSeconds) {
    return """
        {"sources_to_targets":[
          [{"time":0},{"time":%d}],
          [{"time":%d},{"time":0}]
        ]}
        """
        .formatted(outboundSeconds, returnSeconds);
  }
}
