package dev.buhanzaz.rwms.gateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GatewayProductionSafetyValidatorTest {

  @Test
  void acceptsExplicitNonProductionConfiguration() {
    GatewayProperties properties = validProperties();

    assertThatCode(() -> new GatewayProductionSafetyValidator(properties, new MockEnvironment()).validate())
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsIssuerThatDoesNotMatchPublicAuthPrefix() {
    GatewayProperties properties = validProperties();
    properties.getSecurity().setIssuer("https://other.example/auth");

    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(properties, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer");
  }

  @Test
  void rejectsUnsafeRouteShapesCredentialsAndWildcardCors() {
    GatewayProperties path = validProperties();
    path.getRoutes().setAuthUri(URI.create("https://auth.internal/base"));
    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(path, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);

    GatewayProperties credentials = validProperties();
    credentials.getSecurity().setJwkSetUri(URI.create("https://user:secret@auth.internal/oauth2/jwks"));
    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(credentials, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);

    GatewayProperties jwkQuery = validProperties();
    jwkQuery
        .getSecurity()
        .setJwkSetUri(URI.create("https://auth.internal/oauth2/jwks?tenant=unsafe"));
    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(jwkQuery, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);

    GatewayProperties wildcard = validProperties();
    wildcard.getCors().setAllowedOrigins(List.of("https://*.example"));
    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(wildcard, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void productionRequiresHttpsPublicBoundaryAndRejectsLoopbackTargets() {
    MockEnvironment production = new MockEnvironment().withProperty("spring.profiles.active", "prod");
    production.setActiveProfiles("prod");
    GatewayProperties insecurePublic = validProperties();
    insecurePublic.setPublicBaseUri(URI.create("http://panel.example"));
    insecurePublic.getSecurity().setIssuer("http://panel.example/auth");
    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(insecurePublic, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTPS");

    GatewayProperties loopback = validProperties();
    loopback.getRoutes().setTaskBoardUri(URI.create("http://127.0.0.1:8081"));
    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(loopback, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("loopback");
  }

  private static GatewayProperties validProperties() {
    GatewayProperties properties = new GatewayProperties();
    properties.setPublicBaseUri(URI.create("https://panel.example"));
    properties.getRoutes().setAuthUri(URI.create("http://auth-service:9000"));
    properties.getRoutes().setTaskBoardUri(URI.create("http://task-board-service:8081"));
    properties.getRoutes().setWarehouseUri(URI.create("http://warehouse-service:8083"));
    properties.getRoutes().setAssetUri(URI.create("http://asset-service:8086"));
    properties.getSecurity().setIssuer("https://panel.example/auth");
    properties.getSecurity().setAudience("rwms-services");
    properties.getSecurity().setJwkSetUri(URI.create("http://auth-service:9000/oauth2/jwks"));
    properties.getCors().setAllowedOrigins(List.of("https://panel.example"));
    return properties;
  }
}
