package dev.buhanzaz.rwms.gateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
  void rejectsUnsafeRouteShapesAndWildcardCors() {
    GatewayProperties path = validProperties();
    path.getRoutes().setAuthUri(URI.create("https://auth.internal/base"));
    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(path, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);

    GatewayProperties wildcard = validProperties();
    wildcard.getCors().setAllowedOrigins(List.of("https://*.example"));
    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(wildcard, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void productionRequiresHttpsPublicBoundary() {
    MockEnvironment production = new MockEnvironment().withProperty("spring.profiles.active", "prod");
    production.setActiveProfiles("prod");
    GatewayProperties insecurePublic = validProperties();
    insecurePublic.setPublicBaseUri(URI.create("http://panel.example"));
    insecurePublic.getSecurity().setIssuer("http://panel.example/auth");
    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(insecurePublic, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTPS");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("downstreamRouteSetters")
  void rejectsPublicGatewayHostForEveryDownstream(DownstreamRouteCase routeCase) {
    GatewayProperties properties = validProperties();
    routeCase.publicHostSetter().accept(properties.getRoutes());

    assertThatThrownBy(
            () -> new GatewayProductionSafetyValidator(properties, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(routeCase.label())
        .hasMessageContaining("public gateway host");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("downstreamRouteSetters")
  void productionRejectsLoopbackForEveryDownstream(DownstreamRouteCase routeCase) {
    MockEnvironment production = new MockEnvironment();
    production.setActiveProfiles("prod");
    GatewayProperties properties = validProperties();
    routeCase.loopbackSetter().accept(properties.getRoutes());

    assertThatThrownBy(() -> new GatewayProductionSafetyValidator(properties, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(routeCase.label())
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
    properties.getRoutes().setLogisticsPlannerUri(URI.create("http://logistics-planner:8000"));
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

  private static Stream<DownstreamRouteCase> downstreamRouteSetters() {
    URI publicHost = URI.create("https://panel.example:9443");
    URI loopback = URI.create("http://127.0.0.1:8999");
    return Stream.of(
        routeCase(
            "auth target",
            routes -> routes.setAuthUri(publicHost),
            routes -> routes.setAuthUri(loopback)),
        routeCase(
            "task-board target",
            routes -> routes.setTaskBoardUri(publicHost),
            routes -> routes.setTaskBoardUri(loopback)),
        routeCase(
            "warehouse target",
            routes -> routes.setWarehouseUri(publicHost),
            routes -> routes.setWarehouseUri(loopback)),
        routeCase(
            "asset target",
            routes -> routes.setAssetUri(publicHost),
            routes -> routes.setAssetUri(loopback)),
        routeCase(
            "maintenance target",
            routes -> routes.setMaintenanceUri(publicHost),
            routes -> routes.setMaintenanceUri(loopback)),
        routeCase(
            "media target",
            routes -> routes.setMediaUri(publicHost),
            routes -> routes.setMediaUri(loopback)),
        routeCase(
            "inventory target",
            routes -> routes.setInventoryUri(publicHost),
            routes -> routes.setInventoryUri(loopback)),
        routeCase(
            "logistics target",
            routes -> routes.setLogisticsUri(publicHost),
            routes -> routes.setLogisticsUri(loopback)),
        routeCase(
            "logistics planner target",
            routes -> routes.setLogisticsPlannerUri(publicHost),
            routes -> routes.setLogisticsPlannerUri(loopback)),
        routeCase(
            "dossier target",
            routes -> routes.setDossierUri(publicHost),
            routes -> routes.setDossierUri(loopback)),
        routeCase(
            "analytics target",
            routes -> routes.setAnalyticsUri(publicHost),
            routes -> routes.setAnalyticsUri(loopback)),
        routeCase(
            "assistant target",
            routes -> routes.setAssistantUri(publicHost),
            routes -> routes.setAssistantUri(loopback)));
  }

  private static DownstreamRouteCase routeCase(
      String label,
      Consumer<GatewayProperties.Routes> publicHostSetter,
      Consumer<GatewayProperties.Routes> loopbackSetter) {
    return new DownstreamRouteCase(label, publicHostSetter, loopbackSetter);
  }

  /** Route mutations used to apply the same public-host and loopback policy to every target. */
  private record DownstreamRouteCase(
      String label,
      Consumer<GatewayProperties.Routes> publicHostSetter,
      Consumer<GatewayProperties.Routes> loopbackSetter) {

    @Override
    public String toString() {
      return label;
    }
  }
}
