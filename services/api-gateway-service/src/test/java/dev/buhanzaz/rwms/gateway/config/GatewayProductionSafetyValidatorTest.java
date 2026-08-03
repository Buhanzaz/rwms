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
  void rejectsUnsafeRouteShapesPublicAuthTargetAndWildcardCors() {
    GatewayProperties path = validProperties();
    path.getRoutes().setAuthUri(URI.create("https://auth.internal/base"));
    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(path, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);

    GatewayProperties publicAuthTarget = validProperties();
    publicAuthTarget.getRoutes().setAuthUri(URI.create("https://panel.example"));
    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(publicAuthTarget, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("public gateway host");

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
    loopback.getRoutes().setDossierUri(URI.create("http://127.0.0.1:8091"));
    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(loopback, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("loopback");

    GatewayProperties analyticsLoopback = validProperties();
    analyticsLoopback.getRoutes().setAnalyticsUri(URI.create("http://127.0.0.1:8080"));
    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(analyticsLoopback, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("loopback");
  }

  @Test
  void productionRequiresWorkerAndroidReleaseFingerprint() {
    MockEnvironment production = new MockEnvironment();
    production.setActiveProfiles("production");
    GatewayProperties properties = validProperties();
    properties.getAppLinks().setSha256CertFingerprints(List.of());

    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(properties, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Asset Links");
  }

  private static GatewayProperties validProperties() {
    GatewayProperties properties = new GatewayProperties();
    properties.setPublicBaseUri(URI.create("https://panel.example"));
    properties.getRoutes().setAuthUri(URI.create("http://auth-service:9000"));
    properties.getRoutes().setTaskBoardUri(URI.create("http://task-board-service:8081"));
    properties.getRoutes().setWarehouseUri(URI.create("http://warehouse-service:8083"));
    properties.getRoutes().setAssetUri(URI.create("http://asset-service:8086"));
    properties.getRoutes().setMaintenanceUri(URI.create("http://maintenance-service:8087"));
    properties.getRoutes().setMediaUri(URI.create("http://media-service:8085"));
    properties.getRoutes().setInventoryUri(URI.create("http://inventory-service:8089"));
    properties.getRoutes().setLogisticsUri(URI.create("http://logistics-service:8090"));
    properties.getRoutes().setDossierUri(URI.create("http://dossier-service:8091"));
    properties.getRoutes().setAnalyticsUri(URI.create("http://analytics-service:8080"));
    properties.getRoutes().setAssistantUri(URI.create("http://assistant-service:8092"));
    properties.getSecurity().setIssuer("https://panel.example/auth");
    properties.getSecurity().setAudience("rwms-services");
    properties.getCors().setAllowedOrigins(List.of("https://panel.example"));
    properties
        .getAppLinks()
        .setSha256CertFingerprints(
            List.of(
                "AA:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:"
                    + "10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F"));
    return properties;
  }
}
