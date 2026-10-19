package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TASK_BOARD_F0_RESTORED_JDBC_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TASK_BOARD_F0_RESTORED_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TASK_BOARD_F0_RESTORED_PASSWORD", matches = ".+")
class TaskBoardF0RestoredDatabaseIntegrationTest {
  @DynamicPropertySource
  static void restoredDatabase(DynamicPropertyRegistry properties) {
    adoptRestoredDatabase();
    properties.add(
        "spring.datasource.url", () -> System.getenv("TASK_BOARD_F0_RESTORED_JDBC_URL"));
    properties.add(
        "spring.datasource.username", () -> System.getenv("TASK_BOARD_F0_RESTORED_USERNAME"));
    properties.add(
        "spring.datasource.password", () -> System.getenv("TASK_BOARD_F0_RESTORED_PASSWORD"));
  }

  private static void adoptRestoredDatabase() {
    String url = System.getenv("TASK_BOARD_F0_RESTORED_JDBC_URL");
    String username = System.getenv("TASK_BOARD_F0_RESTORED_USERNAME");
    String password = System.getenv("TASK_BOARD_F0_RESTORED_PASSWORD");
    try (Connection connection = DriverManager.getConnection(url, username, password);
        Statement statement = connection.createStatement()) {
      statement.execute(resource("/flyway/verify-version-4.sql"));
    } catch (SQLException exception) {
      throw new IllegalStateException("Restored task-board database failed version-4 preflight", exception);
    }

    Flyway flyway =
        Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .baselineVersion("4")
            .baselineDescription("Task-board post-F2 schema")
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .outOfOrder(false)
            .load();
    flyway.baseline();
    flyway.migrate();
    flyway.validate();
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void restoredF0DatabaseValidatesWithoutChangingHistoricalRows() {
    assertThat(jdbc.queryForObject("select count(*) from databasechangelog", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from databasechangeloglock", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from board_task", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from worker", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from work_queue", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from rwms_schema_history", Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version='4' and type='BASELINE'",
                Integer.class))
        .isEqualTo(1);
  }

  private static String resource(String path) {
    try (InputStream input =
        TaskBoardF0RestoredDatabaseIntegrationTest.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalStateException("Missing test resource: " + path);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read test resource: " + path, exception);
    }
  }
}
