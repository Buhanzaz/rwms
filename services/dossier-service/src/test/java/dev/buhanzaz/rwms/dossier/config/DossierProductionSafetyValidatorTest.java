package dev.buhanzaz.rwms.dossier.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class DossierProductionSafetyValidatorTest {
  @Test
  void rejectsDefaultAndLoopbackProductionConfiguration() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    assertThatThrownBy(
            () -> new DossierProductionSafetyValidator(environment).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("password");
  }

  @Test
  void acceptsOnlyExplicitFailClosedProductionConfiguration() {
    MockEnvironment environment = productionEnvironment();
    environment.withProperty("spring.cloud.stream.kafka.default.producer.sync", "true");

    assertThatCode(
            () -> new DossierProductionSafetyValidator(environment).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMisplacedOrDuplicateProducerSyncConfiguration() {
    MockEnvironment misplaced = productionEnvironment();
    misplaced.withProperty("spring.cloud.stream.default.producer.sync", "true");
    assertThatThrownBy(
            () -> new DossierProductionSafetyValidator(misplaced).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class);

    MockEnvironment duplicate = productionEnvironment();
    duplicate
        .withProperty("spring.cloud.stream.kafka.default.producer.sync", "true")
        .withProperty("spring.cloud.stream.default.producer.sync", "true");
    assertThatThrownBy(
            () -> new DossierProductionSafetyValidator(duplicate).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void applicationYamlUsesOnlyTheKafkaBinderProducerSyncPath() throws IOException {
    var source =
        new YamlPropertySourceLoader()
            .load("dossier-application", new ClassPathResource("application.yaml"))
            .getFirst();

    assertThat(source.getProperty("spring.cloud.stream.kafka.default.producer.sync"))
        .isEqualTo(true);
    assertThat(source.getProperty("spring.cloud.stream.default.producer.sync")).isNull();
  }

  private static MockEnvironment productionEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("production");
    return environment
        .withProperty("spring.datasource.url", "jdbc:postgresql://dossier-db.internal/rwms")
        .withProperty("spring.datasource.username", "dossier")
        .withProperty("spring.datasource.password", "opaque")
        .withProperty("rwms.platform.kafka.enabled", "true")
        .withProperty("spring.cloud.stream.kafka.binder.brokers", "kafka.internal:9092")
        .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://auth.internal")
        .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "rwms-services")
        .withProperty("rwms.cors.allowed-origins", "https://panel.internal")
        .withProperty(
            "rwms.dossier.cursor.secret",
            "a-production-cursor-secret-longer-than-thirty-two-bytes")
        .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
        .withProperty("spring.flyway.enabled", "true")
        .withProperty("spring.flyway.baseline-on-migrate", "false")
        .withProperty("rwms.dossier.security.dev-auth-bypass", "false")
        .withProperty("spring.cloud.stream.kafka.binder.auto-create-topics", "false");
  }
}
