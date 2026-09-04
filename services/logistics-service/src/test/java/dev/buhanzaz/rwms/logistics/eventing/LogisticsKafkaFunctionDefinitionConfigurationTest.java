package dev.buhanzaz.rwms.logistics.eventing;

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

/** Verifies that a disabled Kafka runtime declares no phantom inbound function. */
class LogisticsKafkaFunctionDefinitionConfigurationTest {
  private static final String PROPERTY = "spring.cloud.function.definition";
  private static final String ENVIRONMENT_VARIABLE = "LOGISTICS_KAFKA_FUNCTION_DEFINITION";

  @Test
  void functionDefinitionIsEmptyByDefaultAndExplicitWhenKafkaIsEnabled() throws IOException {
    PropertySource<?> base =
        new YamlPropertySourceLoader()
            .load("logistics-application", new ClassPathResource("application.yaml"))
            .getFirst();

    assertThat(base.getProperty(PROPERTY))
        .isEqualTo("${LOGISTICS_KAFKA_FUNCTION_DEFINITION:}");
    assertThat(resolve(base, Map.of())).isEmpty();
    assertThat(resolve(base, Map.of(ENVIRONMENT_VARIABLE, "logisticsInbound")))
        .isEqualTo("logisticsInbound");
  }

  private static String resolve(PropertySource<?> base, Map<String, Object> environment) {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("logistics-test-environment", environment));
    sources.addLast(base);
    return new PropertySourcesPropertyResolver(sources).getProperty(PROPERTY, "");
  }
}
