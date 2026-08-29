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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Covers truck-only Valhalla requests, profile-aware caching and fail-closed route errors. */
class ValhallaCustomerTravelTimeClientTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000713");
  private static final List<GeoPoint> POINTS =
      List.of(new GeoPoint(59.90, 30.30), new GeoPoint(59.95, 30.40));

  private HttpServer server;
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private volatile int responseStatus;
  private volatile String responseBody;
  private volatile long responseDelayMillis;

  @BeforeEach
  void startServer() throws IOException {
    responseStatus = 200;
    responseDelayMillis = 0;
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
    server.stop(0);
  }

  @Test
  void sendsTruckParametersAndSeparatesSoloAndTrailerCacheKeys() {
    CustomerDeliveryProperties.Validated configuration = configuration();
    ValhallaCustomerTravelTimeClient client =
        new ValhallaCustomerTravelTimeClient(new ObjectMapper());
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
  void missingTruckRouteNeverFallsBackToCarOrStraightLine() {
    responseBody =
        """
        {"sources_to_targets":[
          [{"time":0},{"time":null}],
          [{"time":700},{"time":0}]
        ]}
        """;
    CustomerDeliveryProperties.Validated configuration = configuration();
    ValhallaCustomerTravelTimeClient client =
        new ValhallaCustomerTravelTimeClient(new ObjectMapper());

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
    ValhallaCustomerTravelTimeClient client =
        new ValhallaCustomerTravelTimeClient(new ObjectMapper());

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
    ValhallaCustomerTravelTimeClient client =
        new ValhallaCustomerTravelTimeClient(new ObjectMapper());

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
    requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    if (responseDelayMillis > 0) {
      try {
        Thread.sleep(responseDelayMillis);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(responseStatus, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private CustomerDeliveryProperties.Validated configuration() {
    return configuration(Duration.ofSeconds(1));
  }

  private CustomerDeliveryProperties.Validated configuration(Duration readTimeout) {
    return new CustomerDeliveryProperties(
            true,
            List.of(
                new CustomerDeliveryProperties.Depot(
                    true,
                    WAREHOUSE.toString(),
                    BigDecimal.valueOf(59.90),
                    BigDecimal.valueOf(30.30))),
            "http://127.0.0.1:" + server.getAddress().getPort(),
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
}
