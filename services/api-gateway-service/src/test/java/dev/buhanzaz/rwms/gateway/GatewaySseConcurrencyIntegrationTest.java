package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.tomcat.threads.max=2",
      "server.tomcat.threads.min-spare=1",
      "rwms.gateway.sse.max-connections=2",
      "rwms.gateway.sse.header-timeout=2s"
    })
class GatewaySseConcurrencyIntegrationTest {

  private static final ExecutorService DOWNSTREAM_EXECUTOR = Executors.newFixedThreadPool(8);
  private static final AtomicInteger CONNECTED_STREAMS = new AtomicInteger();
  private static final AtomicInteger CLOSED_STREAMS = new AtomicInteger();
  private static final AtomicInteger CLOSED_HEARTBEAT_STREAMS = new AtomicInteger();
  private static final CountDownLatch TWO_STREAMS_CONNECTED = new CountDownLatch(2);
  private static final List<String> DOWNSTREAM_COOKIES = new CopyOnWriteArrayList<>();
  private static final List<String> DOWNSTREAM_LAST_EVENT_IDS = new CopyOnWriteArrayList<>();
  private static final List<String> DOWNSTREAM_AUTHORIZATIONS = new CopyOnWriteArrayList<>();
  private static final byte[] SSE_EVENT =
      ("event: changed\ndata: {\"revision\":1,\"payload\":\""
              + "x".repeat(16 * 1024)
              + "\"}\n\n")
          .getBytes(StandardCharsets.UTF_8);
  private static final byte[] SSE_HEARTBEAT = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8);
  private static HttpServer downstream;

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @LocalServerPort int gatewayPort;

  @BeforeAll
  static void startDownstream() throws IOException {
    downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    downstream.setExecutor(DOWNSTREAM_EXECUTOR);
    downstream.createContext("/api/asset/v1/events", GatewaySseConcurrencyIntegrationTest::stream);
    downstream.createContext("/health", GatewaySseConcurrencyIntegrationTest::healthy);
    downstream.start();
  }

  @AfterAll
  static void stopDownstream() {
    if (downstream != null) {
      downstream.stop(0);
    }
    DOWNSTREAM_EXECUTOR.shutdownNow();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.public-base-uri", () -> "http://gateway.test");
    registry.add("rwms.gateway.routes.auth-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.task-board-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.warehouse-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.asset-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.maintenance-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.media-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.inventory-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.logistics-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.logistics-planner-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.dossier-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.analytics-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.routes.assistant-uri", GatewaySseConcurrencyIntegrationTest::origin);
    registry.add("rwms.gateway.security.issuer", () -> "http://gateway.test/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void streamsDoNotOccupyServletWorkersAndTheirSlotsAreReleasedAfterDisconnect() throws Exception {
    HttpResponse<InputStream> first = streamRequest("cursor-1");
    HttpResponse<InputStream> second = streamRequest("cursor-2");
    try {
      assertThat(first.statusCode()).isEqualTo(200);
      assertThat(second.statusCode()).isEqualTo(200);
      assertThat(TWO_STREAMS_CONNECTED.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(DOWNSTREAM_COOKIES).allSatisfy(cookie -> assertThat(cookie).isNull());
      assertThat(DOWNSTREAM_LAST_EVENT_IDS).containsExactlyInAnyOrder("cursor-1", "cursor-2");
      assertThat(DOWNSTREAM_AUTHORIZATIONS).containsOnly("Bearer valid");

      HttpResponse<String> ordinary =
          this.client.send(
              request("/auth/health").GET().build(), HttpResponse.BodyHandlers.ofString());
      assertThat(ordinary.statusCode()).isEqualTo(200);
      assertThat(ordinary.body()).isEqualTo("healthy");

      HttpResponse<InputStream> rejected = streamRequest("cursor-3");
      try {
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue(HttpHeaders.RETRY_AFTER)).contains("1");
      } finally {
        rejected.body().close();
      }

      first.body().read();
      second.body().read();
      first.body().close();
      second.body().close();
      awaitAtMost(Duration.ofSeconds(3), () -> CLOSED_STREAMS.get() >= 2);

      HttpResponse<InputStream> reconnected = streamRequest("cursor-reconnected");
      try {
        assertThat(reconnected.statusCode()).isEqualTo(200);
        awaitAtMost(Duration.ofSeconds(2), () -> CONNECTED_STREAMS.get() >= 3);
      } finally {
        reconnected.body().close();
      }
    } finally {
      closeQuietly(first.body());
      closeQuietly(second.body());
    }
  }

  @Test
  void flushesSmallUpstreamHeartbeatBeforeTheServletResponseBufferFills() throws Exception {
    CompletableFuture<HttpResponse<InputStream>> responseFuture =
        this.client.sendAsync(
            request("/api/asset/v1/events?warehouseId=warehouse-1&mode=heartbeat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    HttpResponse<InputStream> response = responseFuture.get(2, TimeUnit.SECONDS);
    int closedBefore = CLOSED_HEARTBEAT_STREAMS.get();
    try {
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
      CompletableFuture<byte[]> heartbeat =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return response.body().readNBytes(SSE_HEARTBEAT.length);
                } catch (IOException exception) {
                  throw new IllegalStateException(exception);
                }
              });
      assertThat(heartbeat.get(2, TimeUnit.SECONDS)).isEqualTo(SSE_HEARTBEAT);
    } finally {
      closeQuietly(response.body());
      awaitAtMost(
          Duration.ofSeconds(3), () -> CLOSED_HEARTBEAT_STREAMS.get() > closedBefore);
    }
  }

  private HttpResponse<InputStream> streamRequest(String lastEventId) throws Exception {
    return this.client.send(
        request("/api/asset/v1/events?warehouseId=warehouse-1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
            .header(HttpHeaders.COOKIE, "AUTH_SESSION=must-not-reach-downstream")
            .header("Last-Event-ID", lastEventId)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.gatewayPort + path))
        .header(HttpHeaders.HOST, "gateway.test");
  }

  private static void stream(HttpExchange exchange) throws IOException {
    boolean heartbeat = exchange.getRequestURI().getQuery().contains("mode=heartbeat");
    if (!heartbeat) {
      DOWNSTREAM_COOKIES.add(exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE));
      DOWNSTREAM_LAST_EVENT_IDS.add(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
      DOWNSTREAM_AUTHORIZATIONS.add(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }
    exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
    exchange.getResponseHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache");
    exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
    exchange.sendResponseHeaders(200, 0);
    if (!heartbeat) {
      CONNECTED_STREAMS.incrementAndGet();
      TWO_STREAMS_CONNECTED.countDown();
    }
    try (OutputStream body = exchange.getResponseBody()) {
      while (!Thread.currentThread().isInterrupted()) {
        body.write(heartbeat ? SSE_HEARTBEAT : SSE_EVENT);
        body.flush();
        Thread.sleep(heartbeat ? 50 : 20);
      }
    } catch (IOException ignored) {
      // The gateway closed the upstream response after the browser disconnected.
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      if (!heartbeat) {
        CLOSED_STREAMS.incrementAndGet();
      } else {
        CLOSED_HEARTBEAT_STREAMS.incrementAndGet();
      }
      exchange.close();
    }
  }

  private static void healthy(HttpExchange exchange) throws IOException {
    byte[] body = "healthy".getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static String origin() {
    return "http://127.0.0.1:" + downstream.getAddress().getPort();
  }

  private static void awaitAtMost(Duration timeout, CheckedCondition condition) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (condition.matches()) {
        return;
      }
      Thread.sleep(25);
    }
    assertThat(condition.matches()).isTrue();
  }

  private static void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The client stream was already closed by the test.
    }
  }

  @FunctionalInterface
  private interface CheckedCondition {
    boolean matches() throws Exception;
  }

  @TestConfiguration
  static class DecoderConfiguration {
    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
      return token ->
          Jwt.withTokenValue(token)
              .header("alg", "none")
              .subject("test-user")
              .audience(List.of("rwms-services"))
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(60))
              .build();
    }
  }
}
