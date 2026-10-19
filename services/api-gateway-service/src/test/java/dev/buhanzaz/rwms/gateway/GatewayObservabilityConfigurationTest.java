package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class GatewayObservabilityConfigurationTest {
  @Test
  void applicationConfigurationEnablesProbesPrometheusTracingAndStructuredLogs()
      throws IOException {
    var source =
        new YamlPropertySourceLoader()
            .load("gateway-observability", new ClassPathResource("application.yaml"))
            .getFirst();

    assertThat(source.getProperty("management.endpoint.health.probes.enabled")).isEqualTo(true);
    assertThat(source.getProperty("management.health.livenessstate.enabled")).isEqualTo(true);
    assertThat(source.getProperty("management.health.readinessstate.enabled")).isEqualTo(true);
    assertThat(String.valueOf(source.getProperty("management.endpoints.web.exposure.include")))
        .contains("health", "prometheus");
    assertThat(source.getProperty("management.metrics.tags.application"))
        .isEqualTo("api-gateway-service");
    assertThat(String.valueOf(source.getProperty("management.tracing.sampling.probability")))
        .contains("API_GATEWAY_TRACING_SAMPLING_PROBABILITY", "0.1");
    assertThat(source.getProperty("logging.structured.format.console")).isEqualTo("ecs");
  }

  @Test
  void runtimeContainsOnlyTheApprovedObservabilityStack() {
    String classpath = System.getProperty("rwms.test.runtime-classpath", "").toLowerCase();

    assertThat(classpath)
        .contains(
            "micrometer-registry-prometheus",
            "micrometer-tracing-bridge-otel",
            "opentelemetry-exporter-otlp");
  }

}
