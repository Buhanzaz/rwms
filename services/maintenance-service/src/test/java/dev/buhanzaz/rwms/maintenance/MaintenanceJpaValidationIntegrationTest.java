package dev.buhanzaz.rwms.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "AUTH_ISSUER=http://auth.test",
    "PANEL_ORIGIN=http://panel.test"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceJpaValidationIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayV9PassesHibernateValidationForEveryBusinessProjection() {
    assertThat(entityManagerFactory.isOpen()).isTrue();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(9);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from information_schema.columns
        where table_schema='public' and table_name='catalog_node' and column_name='photo_required'
        """,
        Integer.class)).isZero();
    assertThat(entityManagerFactory.getMetamodel().getEntities())
        .extracting(value -> value.getJavaType().getSimpleName())
        .contains(
            "CatalogVersion", "CatalogNode", "CatalogLink", "MaintenanceEstimate",
            "EstimateRevision", "EstimateLine", "EstimatePlanStage", "MaintenanceRepair",
            "RepairStage", "MaintenanceMediaReference", "MediaFactProjection",
            "RentalItemFactProjection", "OperationLeaseFactProjection");
    assertThat(entityManagerFactory.getMetamodel().getEntities())
        .extracting(value -> value.getJavaType().getSimpleName())
        .contains("InventoryRepairSource", "LogisticsReturnShortage");
  }
}
