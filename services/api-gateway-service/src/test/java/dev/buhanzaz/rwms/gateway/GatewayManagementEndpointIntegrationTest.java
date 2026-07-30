package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "management.otlp.tracing.export.enabled=false")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GatewayManagementEndpointIntegrationTest {
  private static final String PUBLIC_HOST = "panel.example";

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.public-base-uri", () -> "https://" + PUBLIC_HOST);
    registry.add("rwms.gateway.routes.auth-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.task-board-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.warehouse-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.asset-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.maintenance-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.media-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.inventory-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.logistics-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.dossier-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.analytics-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.routes.assistant-uri", () -> "http://127.0.0.1:1");
    registry.add("rwms.gateway.security.issuer", () -> "https://" + PUBLIC_HOST + "/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add(
        "rwms.gateway.security.jwk-set-uri", () -> "http://127.0.0.1:1/oauth2/jwks");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://" + PUBLIC_HOST);
  }

  @Test
  void healthAndKubernetesProbesAreAvailableWithoutPublicHostOrBearer() throws Exception {
    for (String path :
        List.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness")) {
      mvc.perform(get(path))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"));
    }
  }

  @Test
  void prometheusIsExposedButRequiresAnAuthenticatedServiceRequest() throws Exception {
    mvc.perform(get("/actuator/prometheus").header(HttpHeaders.HOST, PUBLIC_HOST))
        .andExpect(status().isUnauthorized());

    String body =
        mvc.perform(
                get("/actuator/prometheus")
                    .header(HttpHeaders.HOST, PUBLIC_HOST)
                    .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .contains("jvm_info")
        .contains("application=\"api-gateway-service\"");
  }

  @Test
  void telemetryNeverExposesSecretsPiiOrEntityIdentifiers(CapturedOutput logs) throws Exception {
    String entityId = UUID.randomUUID().toString();
    String tokenCanary = "gateway-metrics-secret-token";
    String piiCanary = "operator-personal-name";
    mvc.perform(
            get("/api/inventory/v1/sessions/" + entityId)
                .header(HttpHeaders.HOST, PUBLIC_HOST)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenCanary)
                .header("X-Operator-Name", piiCanary)
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isBadGateway());

    String scrape =
        mvc.perform(
                get("/actuator/prometheus")
                    .header(HttpHeaders.HOST, PUBLIC_HOST)
                    .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(scrape)
        .contains("http_server_")
        .contains("_seconds")
        .doesNotContain(entityId, tokenCanary, piiCanary);
    assertThat(logs.getAll()).doesNotContain(entityId, tokenCanary, piiCanary);
  }

  @TestConfiguration
  static class DecoderConfiguration {
    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
      return token ->
          Jwt.withTokenValue(token)
              .header("alg", "none")
              .subject("prometheus-service")
              .audience(List.of("rwms-services"))
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(60))
              .build();
    }
  }
}
