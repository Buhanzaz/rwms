package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
      "rwms.gateway.sse.max-connections=1",
      "rwms.gateway.sse.header-timeout=300ms"
    })
class GatewaySseHeaderTimeoutIntegrationTest {

  private static final byte[] SSE_EVENT = "data: reconnected\n\n".getBytes(StandardCharsets.UTF_8);
  private static final ServerSocket UPSTREAM_SERVER = openUpstreamServer();
  private static final ExecutorService UPSTREAM_EXECUTOR = Executors.newSingleThreadExecutor();
  private static final CountDownLatch FIRST_REQUEST_RECEIVED = new CountDownLatch(1);
  private static final CountDownLatch FIRST_SOCKET_CLOSED = new CountDownLatch(1);
  private static final AtomicInteger REQUESTS = new AtomicInteger();
  private static final AtomicReference<Throwable> UPSTREAM_FAILURE = new AtomicReference<>();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @LocalServerPort int gatewayPort;

  @BeforeAll
  static void startUpstream() {
    UPSTREAM_EXECUTOR.submit(GatewaySseHeaderTimeoutIntegrationTest::serveRequests);
  }

  @AfterAll
  static void stopUpstream() throws IOException {
    UPSTREAM_SERVER.close();
    UPSTREAM_EXECUTOR.shutdownNow();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.public-base-uri", () -> "http://gateway.test");
    registry.add("rwms.gateway.routes.auth-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.task-board-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.warehouse-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add("rwms.gateway.routes.asset-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.maintenance-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add("rwms.gateway.routes.media-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.inventory-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.logistics-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.logistics-planner-uri",
        GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add("rwms.gateway.routes.dossier-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.analytics-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add(
        "rwms.gateway.routes.assistant-uri", GatewaySseHeaderTimeoutIntegrationTest::origin);
    registry.add("rwms.gateway.security.issuer", () -> "http://gateway.test/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void headerTimeoutClosesThePendingExchangeAndReleasesItsOnlySlot() throws Exception {
    HttpResponse<String> timedOut =
        this.client.send(request(), HttpResponse.BodyHandlers.ofString());

    assertThat(FIRST_REQUEST_RECEIVED.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(timedOut.statusCode()).isEqualTo(504);
    assertThat(FIRST_SOCKET_CLOSED.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(UPSTREAM_FAILURE.get()).isNull();

    HttpResponse<InputStream> reconnected =
        this.client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
    try {
      assertThat(reconnected.statusCode()).isEqualTo(200);
      assertThat(reconnected.body().readNBytes(SSE_EVENT.length)).isEqualTo(SSE_EVENT);
      assertThat(REQUESTS).hasValue(2);
    } finally {
      reconnected.body().close();
    }
  }

  private HttpRequest request() {
    return HttpRequest.newBuilder(
            URI.create(
                "http://127.0.0.1:"
                    + this.gatewayPort
                    + "/api/asset/v1/events?warehouseId=warehouse-1"))
        .header(HttpHeaders.HOST, "gateway.test")
        .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
  }

  private static void serveRequests() {
    try (Socket first = UPSTREAM_SERVER.accept()) {
      first.setSoTimeout((int) Duration.ofSeconds(3).toMillis());
      readRequestHeaders(first.getInputStream());
      REQUESTS.incrementAndGet();
      FIRST_REQUEST_RECEIVED.countDown();
      int nextByte = first.getInputStream().read();
      if (nextByte != -1) {
        throw new IOException("Expected EOF after the gateway header timeout");
      }
      FIRST_SOCKET_CLOSED.countDown();

      try (Socket second = UPSTREAM_SERVER.accept()) {
        second.setSoTimeout((int) Duration.ofSeconds(3).toMillis());
        readRequestHeaders(second.getInputStream());
        REQUESTS.incrementAndGet();
        OutputStream output = second.getOutputStream();
        output.write(
            ("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(SSE_EVENT);
        output.flush();
      }
    } catch (Throwable failure) {
      UPSTREAM_FAILURE.compareAndSet(null, failure);
      FIRST_SOCKET_CLOSED.countDown();
    }
  }

  private static void readRequestHeaders(InputStream input) throws IOException {
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    int matched = 0;
    while (headers.size() < 16 * 1024) {
      int value = input.read();
      if (value == -1) {
        throw new IOException("Upstream request closed before its headers completed");
      }
      headers.write(value);
      matched = switch (matched) {
        case 0 -> value == '\r' ? 1 : 0;
        case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
        case 2 -> value == '\r' ? 3 : 0;
        case 3 -> value == '\n' ? 4 : 0;
        default -> matched;
      };
      if (matched == 4) {
        return;
      }
    }
    throw new IOException("Upstream request headers exceeded the test limit");
  }

  private static ServerSocket openUpstreamServer() {
    try {
      ServerSocket server = new ServerSocket();
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      return server;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static String origin() {
    return "http://127.0.0.1:" + UPSTREAM_SERVER.getLocalPort();
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
