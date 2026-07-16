package dev.buhanzaz.rwms.taskboard;

import static org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TaskBoardJwtValidationIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");
  private static final KeyMaterial TRUSTED = key("trusted");
  private static final KeyMaterial UNTRUSTED = key("trusted");
  private static final HttpServer JWKS_SERVER;
  private static final String ISSUER;

  static {
    try {
      POSTGRES.start();
      JWKS_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ISSUER = "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort();
      JWKS_SERVER.createContext(
          "/.well-known/openid-configuration",
          exchange ->
              respond(
                  exchange,
                  "{\"issuer\":\""
                      + ISSUER
                      + "\",\"jwks_uri\":\""
                      + ISSUER
                      + "/jwks\"}"));
      JWKS_SERVER.createContext(
          "/jwks", exchange -> respond(exchange, "{\"keys\":[" + TRUSTED.publicJwk + "]}"));
      JWKS_SERVER.start();
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    properties.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "rwms-services");
  }

  @Autowired MockMvc mockMvc;

  @AfterAll
  static void stopIssuer() {
    JWKS_SERVER.stop(0);
  }

  @Test
  void validatesSignatureIssuerAudienceExpiryAndRejectsPanelIdTokenAudience() throws Exception {
    mockMvc
        .perform(get("/api/worker-classes").header(HttpHeaders.AUTHORIZATION, bearer(token(TRUSTED, ISSUER, "rwms-services", 60))))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/worker-classes")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(UNTRUSTED, ISSUER, "rwms-services", 60)))
                .header(CorrelationIdFilter.HEADER_NAME, "11111111-1111-1111-1111-111111111111"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("TASK_BOARD_UNAUTHORIZED"))
        .andExpect(
            jsonPath("$.correlation.correlationId")
                .value("11111111-1111-1111-1111-111111111111"))
        .andExpect(
            header()
                .string(
                    CorrelationIdFilter.HEADER_NAME,
                    "11111111-1111-1111-1111-111111111111"));
    mockMvc
        .perform(get("/api/worker-classes").header(HttpHeaders.AUTHORIZATION, bearer(token(TRUSTED, ISSUER + "/wrong", "rwms-services", 60))))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/worker-classes").header(HttpHeaders.AUTHORIZATION, bearer(token(TRUSTED, ISSUER, "other-service", 60))))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/worker-classes").header(HttpHeaders.AUTHORIZATION, bearer(token(TRUSTED, ISSUER, "rwms-panel", 60))))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/worker-classes").header(HttpHeaders.AUTHORIZATION, bearer(token(TRUSTED, ISSUER, "rwms-services", -60))))
        .andExpect(status().isUnauthorized());
  }

  private String token(KeyMaterial key, String issuer, String audience, long expirySeconds)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .subject(UUID.randomUUID().toString())
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(now.minusSeconds(1)))
            .expirationTime(Date.from(now.plusSeconds(expirySeconds)))
            .claim("principal_type", "USER")
            .claim("scope", "rwms.read")
            .build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("trusted").build(), claims);
    jwt.sign(new RSASSASigner(key.privateKey));
    return jwt.serialize();
  }

  private String bearer(String token) {
    return BEARER.getValue() + " " + token;
  }

  private static KeyMaterial key(String keyId) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      var publicKey = (RSAPublicKey) pair.getPublic();
      var privateKey = (RSAPrivateKey) pair.getPrivate();
      String jwk = new RSAKey.Builder(publicKey).keyID(keyId).algorithm(JWSAlgorithm.RS256).build().toJSONString();
      return new KeyMaterial(privateKey, jwk);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private record KeyMaterial(RSAPrivateKey privateKey, String publicJwk) {}
}
