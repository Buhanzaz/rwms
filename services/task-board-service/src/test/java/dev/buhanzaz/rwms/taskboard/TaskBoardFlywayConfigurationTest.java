package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class TaskBoardFlywayConfigurationTest {

  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void baseConfigurationEnforcesFlywayAndJpaValidation() throws IOException {
    PropertySource<?> source = load("application.yaml");

    assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(true);
    assertThat(source.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
    assertThat(source.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
    assertThat(source.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
    assertThat(source.getProperty("spring.flyway.clean-disabled")).isEqualTo(true);
    assertThat(source.getProperty("spring.flyway.out-of-order")).isEqualTo(false);
    assertThat(source.getProperty("spring.flyway.validate-migration-naming")).isEqualTo(true);
    assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
  }

  @Test
  void queueRegistryAllowlistDefaultsToMaintenanceServiceOnly() throws IOException {
    PropertySource<?> source = load("application.yaml");

    assertThat(source.getProperty("rwms.security.queue-registry-client-ids"))
        .isEqualTo("${TASK_BOARD_QUEUE_REGISTRY_CLIENT_IDS:maintenance-service}");
  }

  @Test
  void developmentNeverLetsHibernateMutateTheSchema() throws IOException {
    PropertySource<?> source = load("application-dev.yaml");

    assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
  }

  @Test
  void testsUseFlywayBeforeJpaAndDisableLegacySqlInitialization() throws IOException {
    PropertySource<?> source = load("application-test.yaml");

    assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(true);
    assertThat(source.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
    assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(source.getProperty("spring.sql.init.mode")).isEqualTo("never");
    assertThat(source.getProperty("spring.jpa.defer-datasource-initialization")).isNull();
  }

  private PropertySource<?> load(String resource) throws IOException {
    return loader.load(resource, new ClassPathResource(resource)).getFirst();
  }
}
