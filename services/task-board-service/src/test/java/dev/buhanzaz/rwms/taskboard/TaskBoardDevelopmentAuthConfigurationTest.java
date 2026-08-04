package dev.buhanzaz.rwms.taskboard;

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

class TaskBoardDevelopmentAuthConfigurationTest {
  private static final String PROPERTY = "rwms.security.dev-auth-bypass";
  private static final String ENVIRONMENT_VARIABLE = "TASK_BOARD_DEV_AUTH_BYPASS";

  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void developmentAuthBypassIsDisabledByDefaultAndRequiresExplicitOptIn() throws IOException {
    PropertySource<?> development = loadDevelopmentConfiguration();

    assertThat(development.getProperty(PROPERTY))
        .isEqualTo("${TASK_BOARD_DEV_AUTH_BYPASS:false}");
    assertThat(resolve(development, Map.of())).isEqualTo("false");
    assertThat(resolve(development, Map.of(ENVIRONMENT_VARIABLE, "true"))).isEqualTo("true");
  }

  private PropertySource<?> loadDevelopmentConfiguration() throws IOException {
    return loader
        .load("task-board-development", new ClassPathResource("application-dev.yaml"))
        .getFirst();
  }

  private String resolve(PropertySource<?> development, Map<String, Object> environment) {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("task-board-development-environment", environment));
    sources.addLast(development);
    return new PropertySourcesPropertyResolver(sources).getProperty(PROPERTY);
  }
}
