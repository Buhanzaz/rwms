package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayArchitectureTest {

  @Test
  void runtimeHasNoPersistenceMessagingCacheWorkflowOrOAuthClientDependencies() {
    String classpath = System.getProperty("rwms.test.runtime-classpath", "").toLowerCase();
    assertThat(classpath).isNotBlank();
    assertThat(classpath)
        .doesNotContain("spring-data-jpa")
        .doesNotContain("spring-data-jdbc")
        .doesNotContain("spring-data-r2dbc")
        .doesNotContain("spring-data-redis")
        .doesNotContain("spring-jdbc")
        .doesNotContain("hibernate-core")
        .doesNotContain("spring-rabbit")
        .doesNotContain("spring-amqp")
        .doesNotContain("liquibase")
        .doesNotContain("flyway")
        .doesNotContain("postgresql")
        .doesNotContain("oauth2-client")
        .doesNotContain("spring-cloud-stream")
        .doesNotContain("spring-integration-kafka")
        .doesNotContain("spring-kafka")
        .doesNotContain("kafka-clients")
        .doesNotContain("lettuce-core")
        .doesNotContain("jedis")
        .doesNotContain("redisson")
        .doesNotContain("camunda")
        .doesNotContain("zeebe")
        .doesNotContain("temporal")
        .doesNotContain("spring-batch")
        .doesNotContain("mapstruct")
        .doesNotContain("lombok");
    assertThat(classExists("org.springframework.jdbc.datasource.DriverManagerDataSource")).isFalse();
    assertThat(classExists("com.zaxxer.hikari.HikariDataSource")).isFalse();
    assertThat(classExists("jakarta.persistence.EntityManager")).isFalse();
    assertThat(classExists("org.springframework.data.redis.core.RedisTemplate")).isFalse();
    assertThat(classExists("org.springframework.amqp.rabbit.core.RabbitTemplate")).isFalse();
    assertThat(classExists("org.springframework.cloud.stream.function.StreamBridge")).isFalse();
    assertThat(classExists("org.apache.kafka.clients.producer.KafkaProducer")).isFalse();
    assertThat(classExists("io.camunda.zeebe.client.ZeebeClient")).isFalse();
    assertThat(classExists("io.temporal.client.WorkflowClient")).isFalse();
    assertThat(classExists("org.mapstruct.Mapper")).isFalse();
    assertThat(classExists("lombok.Getter")).isFalse();
    assertThat(classExists("liquibase.Liquibase")).isFalse();
    assertThat(classExists("org.flywaydb.core.Flyway")).isFalse();
  }

  @Test
  void moduleContainsNoDatabaseOrMigrationTree() {
    Path projectDir = Path.of(System.getProperty("rwms.test.project-dir"));
    assertThat(projectDir).isDirectory();
    List<Path> forbidden =
        List.of(
            projectDir.resolve("database"),
            projectDir.resolve("src/main/resources/db"),
            projectDir.resolve("src/main/resources/database"),
            projectDir.resolve("src/main/java/dev/buhanzaz/rwms/gateway/domain"),
            projectDir.resolve("src/main/java/dev/buhanzaz/rwms/gateway/persistence"),
            projectDir.resolve("src/main/java/dev/buhanzaz/rwms/gateway/eventing"));
    assertThat(forbidden).allSatisfy(path -> assertThat(Files.exists(path)).isFalse());
  }

  private static boolean classExists(String name) {
    try {
      Class.forName(name, false, GatewayArchitectureTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException exception) {
      return false;
    }
  }
}
