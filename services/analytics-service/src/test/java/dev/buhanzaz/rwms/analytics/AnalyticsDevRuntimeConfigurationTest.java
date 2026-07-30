package dev.buhanzaz.rwms.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class AnalyticsDevRuntimeConfigurationTest {
  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void developmentProfileSuppliesIssuerForThePermittedReadOnlyBypass() throws IOException {
    PropertySource<?> development = load("application-dev.yaml");

    assertThat(development.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
        .isEqualTo("${AUTH_ISSUER:http://localhost:9000}");
    assertThat(development.getProperty("rwms.analytics.security.dev-auth-bypass"))
        .isEqualTo("${ANALYTICS_DEV_AUTH_BYPASS:true}");
  }

  private PropertySource<?> load(String resource) throws IOException {
    return loader.load(resource, new ClassPathResource(resource)).getFirst();
  }
}
