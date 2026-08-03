package dev.buhanzaz.rwms.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayJwtValidationIntegrationTest {

  private static final String ISSUER = "http://gateway.test/auth";
  private static final String AUDIENCE = "rwms-services";
  private static final AtomicInteger DOWNSTREAM_REQUESTS = new AtomicInteger();
  private static HttpServer auth;
  private static HttpServer downstream;
  private static KeyPair signingKey;
  private static KeyPair wrongKey;

  @LocalServerPort int gatewayPort;

  @BeforeAll
  static void startServers() throws Exception {
    signingKey = keyPair();
    wrongKey = keyPair();
    auth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    auth.createContext(
        "/oauth2/jwks",
        exchange -> {
          RSAKey publicJwk =
              new RSAKey.Builder((RSAPublicKey) signingKey.getPublic())
                  .keyID("rwms-test-key")
                  .build();
          byte[] body = new JWKSet(publicJwk).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    auth.start();

    downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    downstream.createContext(
        "/",
        exchange -> {
          DOWNSTREAM_REQUESTS.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    downstream.start();
  }

  @AfterAll
  static void stopServers() {
    auth.stop(0);
    downstream.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.public-base-uri", () -> "http://gateway.test");
    registry.add("rwms.gateway.routes.auth-uri", GatewayJwtValidationIntegrationTest::authOrigin);
    registry.add("rwms.gateway.routes.task-board-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.warehouse-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.asset-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.maintenance-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.media-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.inventory-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.logistics-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.dossier-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.analytics-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.routes.assistant-uri", GatewayJwtValidationIntegrationTest::downstreamOrigin);
    registry.add("rwms.gateway.security.issuer", () -> ISSUER);
    registry.add("rwms.gateway.security.audience", () -> AUDIENCE);
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void acceptsValidSignedAccessToken() throws Exception {
    HttpResponse<String> response = send(token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(60), Instant.now().minusSeconds(1), signingKey), null);
    org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(204);
  }

  @Test
  void rejectsMissingMalformedAndInvalidSignedTokensWithCanonicalProblem() throws Exception {
    int before = DOWNSTREAM_REQUESTS.get();
    String correlationId = UUID.randomUUID().toString();

    HttpResponse<String> missing = send(null, correlationId);
    org.assertj.core.api.Assertions.assertThat(missing.statusCode()).isEqualTo(401);
    org.assertj.core.api.Assertions.assertThat(missing.headers().firstValue("X-Correlation-Id")).contains(correlationId);
    org.assertj.core.api.Assertions.assertThat(missing.body()).contains("GATEWAY_UNAUTHORIZED").contains(correlationId);
    assertUnauthorized("not-a-jwt");
    assertUnauthorized(token("https://wrong.example/auth", List.of(AUDIENCE), Instant.now().plusSeconds(60), Instant.now().minusSeconds(1), signingKey));
    assertUnauthorized(token(ISSUER, List.of("wrong-audience"), Instant.now().plusSeconds(60), Instant.now().minusSeconds(1), signingKey));
    assertUnauthorized(token(ISSUER, List.of("rwms-panel"), Instant.now().plusSeconds(60), Instant.now().minusSeconds(1), signingKey));
    assertUnauthorized(token(ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(300), Instant.now().minusSeconds(600), signingKey));
    assertUnauthorized(token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(600), Instant.now().plusSeconds(300), signingKey));
    assertUnauthorized(token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(60), Instant.now().minusSeconds(1), wrongKey));

    org.assertj.core.api.Assertions.assertThat(DOWNSTREAM_REQUESTS.get()).isEqualTo(before);
  }

  private void assertUnauthorized(String token) throws Exception {
    HttpResponse<String> response = send(token, null);
    org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(401);
    org.assertj.core.api.Assertions.assertThat(response.body()).contains("GATEWAY_UNAUTHORIZED");
  }

  private HttpResponse<String> send(String token, String correlationId) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + gatewayPort + "/api/dossier/v1/cabins/cabin-1"))
            .header(HttpHeaders.HOST, "gateway.test")
            .GET();
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, bearer(token));
    }
    if (correlationId != null) {
      request.header("X-Correlation-Id", correlationId);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String token(
      String issuer,
      List<String> audience,
      Instant expiresAt,
      Instant notBefore,
      KeyPair keyPair)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("user-1")
            .audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(1)))
            .notBeforeTime(Date.from(notBefore))
            .expirationTime(Date.from(expiresAt))
            .jwtID(UUID.randomUUID().toString())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rwms-test-key").build(), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
    return jwt.serialize();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String downstreamOrigin() {
    return "http://127.0.0.1:" + downstream.getAddress().getPort();
  }

  private static String authOrigin() {
    return "http://127.0.0.1:" + auth.getAddress().getPort();
  }

}
