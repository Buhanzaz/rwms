package dev.buhanzaz.rwms.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

class InventoryConfigurationTest {
  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void inventoryUsesDedicatedDefaultPortAndKeepsEnvironmentOverride() throws IOException {
    PropertySource<?> application =
        loader.load("inventory-application", new ClassPathResource("application.yaml")).getFirst();

    assertThat(application.getProperty("server.port")).isEqualTo("${INVENTORY_SERVICE_PORT:8089}");

    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(
        new MapPropertySource("inventory-port-override", Map.of("INVENTORY_SERVICE_PORT", "18089")));
    sources.addLast(application);
    PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

    assertThat(resolver.getProperty("server.port")).isEqualTo("18089");
  }
}
