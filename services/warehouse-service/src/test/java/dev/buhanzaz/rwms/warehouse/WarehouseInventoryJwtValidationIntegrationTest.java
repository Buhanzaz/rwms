package dev.buhanzaz.rwms.warehouse;

import static org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WarehouseInventoryJwtValidationIntegrationTest {
  private static final String INVENTORY_PATH =
      "/api/internal/warehouse/v1/warehouses/inventory/00000000-0000-0000-0000-000000000001/metadata";
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final KeyMaterial TRUSTED = key("warehouse-inventory");
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
                  "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/jwks\"}"));
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
    properties.add("rwms.platform.kafka.enabled", () -> "false");
    properties.add("rwms.cors.allowed-origins", () -> "http://localhost");
  }

  @Autowired MockMvc mockMvc;

  @AfterAll
  static void stopDependencies() {
    JWKS_SERVER.stop(0);
    POSTGRES.stop();
  }

  @Test
  void inventoryEndpointAcceptsOnlyTheServiceAudience() throws Exception {
    mockMvc
        .perform(
            get(INVENTORY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(token("rwms-services"))))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get(INVENTORY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(token("wrong-audience"))))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(INVENTORY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(token("rwms-panel"))))
        .andExpect(status().isUnauthorized());
  }


  private String token(String audience) throws Exception {
    return token(audience, "inventory-service", "warehouse.read");
  }

  private String token(String audience, String clientId, String scope) throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .subject(clientId)
            .issuer(ISSUER)
            .audience(audience)
            .issueTime(Date.from(now.minusSeconds(1)))
            .expirationTime(Date.from(now.plusSeconds(60)))
            .claim("principal_type", "SERVICE")
            .claim("client_id", clientId)
            .claim("scope", scope)
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("warehouse-inventory").build(), claims);
    jwt.sign(new RSASSASigner(TRUSTED.privateKey));
    return jwt.serialize();
  }

  private static String bearer(String token) {
    return BEARER.getValue() + " " + token;
  }

  private static KeyMaterial key(String keyId) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      var publicKey = (RSAPublicKey) pair.getPublic();
      var privateKey = (RSAPrivateKey) pair.getPrivate();
      String jwk =
          new RSAKey.Builder(publicKey)
              .keyID(keyId)
              .algorithm(JWSAlgorithm.RS256)
              .build()
              .toJSONString();
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
