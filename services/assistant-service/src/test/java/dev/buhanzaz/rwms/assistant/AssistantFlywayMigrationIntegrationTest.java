package dev.buhanzaz.rwms.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AssistantFlywayMigrationIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void resetDatabase() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }

  @Test
  void upgradeRemovesObsoleteCabinImportMetadataFromStoredToolResults() {
    flyway().target(MigrationVersion.fromVersion("1")).load().migrate();

    UUID conversationId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    jdbc.update(
        """
        insert into assistant_conversation(
          id,version,owner_subject_id,client_id,rental_inquiry_id,archived,
          created_at,updated_at)
        values (?,0,?,?,?,false,clock_timestamp(),clock_timestamp())
        """,
        conversationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into assistant_message(id,conversation_id,role,content,created_at)
        values (?,?,'USER','Покажи бытовки',clock_timestamp())
        """,
        messageId,
        conversationId);
    jdbc.update(
        """
        insert into assistant_tool_call(
          id,conversation_id,turn_message_id,provider_call_id,tool_name,
          arguments_payload,result_payload,status,created_at,completed_at)
        values (?,?,?,'call-1','search_available_cabins','{}'::jsonb,?::jsonb,
          'COMPLETED',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        conversationId,
        messageId,
        """
        {
          "tool": "search_available_cabins",
          "data": {
            "groups": [{
              "cabins": [{
                "number": "БЫТ-042",
                "passport": {
                  "source": "old-panel-rental-items-v1",
                  "legacyId": "spb-42",
                  "legacyWarehouseId": "spb",
                  "legacyNumber": "БЫТ-042",
                  "locationNodeId": null,
                  "hasPhotos": false,
                  "photoCount": 0,
                  "price": 45000,
                  "shipmentDate": "2026-05-12",
                  "tenant": "ООО Строй"
                }
              }]
            }]
          }
        }
        """);
    jdbc.update(
        """
        insert into assistant_tool_call(
          id,conversation_id,turn_message_id,provider_call_id,tool_name,
          arguments_payload,result_payload,status,created_at,completed_at)
        values (?,?,?,'call-2','search_available_cabins','{}'::jsonb,?::jsonb,
          'COMPLETED',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        conversationId,
        messageId,
        """
        {
          "cabins": [{
            "number": "БЫТ-043",
            "passport": {
              "locationNodeId": "node-43",
              "photoCount": 2,
              "price": 46000
            }
          }]
        }
        """);

    assertThat(flyway().load().migrate().migrationsExecuted).isEqualTo(2);

    String result =
        jdbc.queryForObject(
            "select result_payload::text from assistant_tool_call where provider_call_id='call-1'",
            String.class);
    assertThat(result)
        .doesNotContain(
            "legacy",
            "old-panel-rental-items-v1",
            "locationNodeId",
            "hasPhotos",
            "photoCount")
        .contains("БЫТ-042", "45000", "2026-05-12", "ООО Строй");
    String technicalOnlyResult =
        jdbc.queryForObject(
            "select result_payload::text from assistant_tool_call where provider_call_id='call-2'",
            String.class);
    assertThat(technicalOnlyResult)
        .doesNotContain("locationNodeId", "photoCount")
        .contains("БЫТ-043", "46000");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from pg_proc
                where proname='strip_obsolete_cabin_import_metadata_v2'
                """,
                Integer.class))
        .isZero();
  }

  @Test
  void upgradeRemovesWarehouseBusinessCodeFromStoredFacetResults() {
    flyway().target(MigrationVersion.fromVersion("2")).load().migrate();

    UUID conversationId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    jdbc.update(
        """
        insert into assistant_conversation(
          id,version,owner_subject_id,client_id,rental_inquiry_id,archived,
          created_at,updated_at)
        values (?,0,?,?,?,false,clock_timestamp(),clock_timestamp())
        """,
        conversationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into assistant_message(id,conversation_id,role,content,created_at)
        values (?,?,'USER','Покажи бытовки',clock_timestamp())
        """,
        messageId,
        conversationId);
    jdbc.update(
        """
        insert into assistant_tool_call(
          id,conversation_id,turn_message_id,provider_call_id,tool_name,
          arguments_payload,result_payload,status,created_at,completed_at)
        values (?,?,?,'call-facets','list_available_cabin_facets','{}'::jsonb,?::jsonb,
          'COMPLETED',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        conversationId,
        messageId,
        """
        {
          "tool": "list_available_cabin_facets",
          "data": {
            "warehouses": [{
              "warehouseId": "6e25414b-cabc-4617-8664-2df42baf871b",
              "code": "MSK-1",
              "name": "Moscow",
              "city": "Moscow",
              "cabinTypes": ["6m"],
              "finishes": ["basic"],
              "dimensions": ["6x2.4"],
              "categories": ["Новая"]
            }]
          }
        }
        """);
    jdbc.update(
        """
        insert into assistant_tool_call(
          id,conversation_id,turn_message_id,provider_call_id,tool_name,
          arguments_payload,result_payload,status,failure_code,created_at,completed_at)
        values (?,?,?,'call-failure','search_available_cabins','{}'::jsonb,?::jsonb,
          'FAILED','LOGISTICS_UNAVAILABLE',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        conversationId,
        messageId,
        """
        {"code":"LOGISTICS_UNAVAILABLE"}
        """);

    assertThat(flyway().load().migrate().migrationsExecuted).isEqualTo(1);

    String facets =
        jdbc.queryForObject(
            "select result_payload::text from assistant_tool_call where provider_call_id='call-facets'",
            String.class);
    assertThat(facets).doesNotContain("\"code\"", "MSK-1").contains("Moscow", "6m");
    String failure =
        jdbc.queryForObject(
            "select result_payload::text from assistant_tool_call where provider_call_id='call-failure'",
            String.class);
    assertThat(failure).contains("\"code\"", "LOGISTICS_UNAVAILABLE");
  }

  private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false);
  }
}
