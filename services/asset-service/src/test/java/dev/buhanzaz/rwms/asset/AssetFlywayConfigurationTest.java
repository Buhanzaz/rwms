package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class AssetFlywayConfigurationTest {
  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void everyProfileKeepsFlywayAuthoritativeAndHibernateInValidationMode() throws IOException {
    PropertySource<?> base = load("application.yaml");
    PropertySource<?> development = load("application-dev.yaml");
    PropertySource<?> test = load("application-test.yaml");

    assertThat(base.getProperty("spring.flyway.enabled")).isEqualTo(true);
    assertThat(base.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
    assertThat(base.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
    assertThat(base.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
    assertThat(base.getProperty("spring.flyway.clean-disabled")).isEqualTo(true);
    assertThat(base.getProperty("spring.flyway.out-of-order")).isEqualTo(false);
    assertThat(base.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(test.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(test.getProperty("spring.sql.init.mode")).isEqualTo("never");
    assertThat(development.getProperty("spring.flyway.baseline-on-migrate")).isNull();
  }

  private PropertySource<?> load(String resource) throws IOException {
    return loader.load(resource, new ClassPathResource(resource)).getFirst();
  }
}
