package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  private static final String DEV_AUTH_PROPERTY = "rwms.security.dev-auth-bypass";
  private static final String DEV_AUTH_ENVIRONMENT_VARIABLE = "TASK_BOARD_DEV_AUTH_BYPASS";
  private static final String CLIENT_SECRET_ENVIRONMENT_VARIABLE = "TASK_BOARD_CLIENT_SECRET";
  private static final String[] CLIENT_REGISTRATIONS = {
    "auth-service",
    "warehouse-lifecycle-read",
    "warehouse-lifecycle-confirm",
    "warehouse-timezone-read",
    "warehouse-identity-read",
    "worker-profile-media"
  };

  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void developmentAuthBypassIsDisabledByDefaultAndRequiresExplicitOptIn() throws IOException {
    PropertySource<?> development = loadDevelopmentConfiguration();

    assertThat(development.getProperty(DEV_AUTH_PROPERTY))
        .isEqualTo("${TASK_BOARD_DEV_AUTH_BYPASS:false}");
    assertThat(resolve(development, Map.of(), DEV_AUTH_PROPERTY)).isEqualTo("false");
    assertThat(resolve(
            development,
            Map.of(DEV_AUTH_ENVIRONMENT_VARIABLE, "true"),
            DEV_AUTH_PROPERTY))
        .isEqualTo("true");
  }

  @Test
  void everyDevelopmentServiceClientRequiresTheExternalTaskBoardCredential()
      throws IOException {
    PropertySource<?> development = loadDevelopmentConfiguration();

    PropertySource<?> base = loader.load("task-board-base", new ClassPathResource("application.yaml")).getFirst();

    for (String registration : CLIENT_REGISTRATIONS) {
      String property =
          "spring.security.oauth2.client.registration." + registration + ".client-secret";
      assertThat(development.getProperty(property)).isNull();
      assertThat(base.getProperty(property)).isEqualTo("${TASK_BOARD_CLIENT_SECRET}");
      assertThatThrownBy(() -> resolve(development, Map.of(), property))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(CLIENT_SECRET_ENVIRONMENT_VARIABLE);
      assertThat(resolve(
              development,
              Map.of(CLIENT_SECRET_ENVIRONMENT_VARIABLE, "runtime-secret"),
              property))
          .isEqualTo("runtime-secret");
    }
  }

  private PropertySource<?> loadDevelopmentConfiguration() throws IOException {
    return loader
        .load("task-board-development", new ClassPathResource("application-dev.yaml"))
        .getFirst();
  }

  private String resolve(
      PropertySource<?> development, Map<String, Object> environment, String property) throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("task-board-development-environment", environment));
    sources.addLast(development);
    sources.addLast(loader.load("task-board-base", new ClassPathResource("application.yaml")).getFirst());
    return new PropertySourcesPropertyResolver(sources).getProperty(property);
  }
}
