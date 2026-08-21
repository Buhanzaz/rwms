package dev.buhanzaz.rwms.logistics.maintenance.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.LogisticsServiceApplication;
import dev.buhanzaz.rwms.logistics.maintenance.service.MaintenanceReturnArrivalService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves the bounded JPA return-arrival projection against clean Flyway-owned PostgreSQL. */
@SpringBootTest(
    classes = LogisticsServiceApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://issuer.invalid",
      "PANEL_ORIGIN=http://localhost:5173"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceReturnArrivalRepositoryIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("logistics_return_arrival_projection");

  @Autowired JdbcTemplate jdbc;
  @Autowired MaintenanceReturnArrivalService service;

  /** Supplies the isolated PostgreSQL connection used by Flyway and JPA validation. */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:65535/jwks");
  }

  @BeforeEach
  void clearDocuments() {
    jdbc.execute("truncate table logistics_document cascade");
  }

  @Test
  void selectsNewestPhysicalArrivalForAssetAndWarehouse() {
    UUID warehouseId = UUID.randomUUID();
    UUID otherWarehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    OffsetDateTime firstArrival = OffsetDateTime.parse("2026-08-01T08:00:00Z");
    OffsetDateTime latestArrival = OffsetDateTime.parse("2026-08-03T09:30:00Z");
    UUID firstReturn = returnDocument(warehouseId, rentalItemId, firstArrival);
    UUID latestReturn = returnDocument(warehouseId, rentalItemId, latestArrival);
    returnDocument(
        otherWarehouseId, rentalItemId, OffsetDateTime.parse("2026-08-05T10:00:00Z"));

    assertThat(service.latest(warehouseId, rentalItemId))
        .satisfies(
            arrival -> {
              assertThat(arrival.warehouseId()).isEqualTo(warehouseId);
              assertThat(arrival.rentalItemId()).isEqualTo(rentalItemId);
              assertThat(arrival.returnDocumentId()).isEqualTo(latestReturn);
              assertThat(arrival.arrivedAt()).isEqualTo(latestArrival);
            });
    assertThat(service.forReturn(firstReturn, warehouseId, rentalItemId))
        .hasValueSatisfying(
            arrival -> {
              assertThat(arrival.returnDocumentId()).isEqualTo(firstReturn);
              assertThat(arrival.arrivedAt()).isEqualTo(firstArrival);
            });
    assertThat(service.forReturn(firstReturn, otherWarehouseId, rentalItemId)).isEmpty();
  }

  private UUID returnDocument(
      UUID warehouseId, UUID rentalItemId, OffsetDateTime arrivedAt) {
    UUID documentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,driver_snapshot,scheduled_date,
          return_arrived_at,requested_by_subject_id,correlation_id,created_at,updated_at)
        values (?,0,'RETURN','INSPECTION_REQUIRED',?,'Водитель',date '2026-08-01',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        documentId,
        warehouseId,
        arrivedAt,
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_document_line(
          id,version,document_id,line_number,asset_id,asset_version,state,created_at,updated_at)
        values (?,0,?,1,?,0,'ARRIVED',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        documentId,
        rentalItemId);
    return documentId;
  }
}
