package dev.buhanzaz.rwms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
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

/** Validates clean assistant schema creation and supported Flyway upgrade paths through V6. */
@Testcontainers
class AssistantFlywayMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

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

    assertThat(flyway().load().migrate().migrationsExecuted).isEqualTo(5);

    String result =
        jdbc.queryForObject(
            "select result_payload::text from assistant_tool_call where provider_call_id='call-1'",
            String.class);
    assertThat(result)
        .doesNotContain(
            "legacy", "old-panel-rental-items-v1", "locationNodeId", "hasPhotos", "photoCount")
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

    assertThat(flyway().load().migrate().migrationsExecuted).isEqualTo(4);

    String facets =
        jdbc.queryForObject(
            "select result_payload::text from assistant_tool_call where"
                + " provider_call_id='call-facets'",
            String.class);
    assertThat(facets).doesNotContain("\"code\"", "MSK-1").contains("Moscow", "6m");
    String failure =
        jdbc.queryForObject(
            "select result_payload::text from assistant_tool_call where"
                + " provider_call_id='call-failure'",
            String.class);
    assertThat(failure).contains("\"code\"", "LOGISTICS_UNAVAILABLE");
  }

  @Test
  void upgradePreservesLegacyInboxAsUnknownHashAndCreatesSanitizedRecoverySchema() {
    flyway().target(MigrationVersion.fromVersion("3")).load().migrate();
    UUID eventId = UUID.randomUUID();
    jdbc.update(
        """
        insert into assistant_event_inbox(
          event_id,event_type,occurred_at,rental_inquiry_id,conversation_id,
          order_id,received_at,payload)
        values (?,'logistics.rental-inquiry.booked.v1',clock_timestamp(),?,?,?,
          clock_timestamp(),'{}'::jsonb)
        """,
        eventId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());

    assertThat(flyway().load().migrate().migrationsExecuted).isEqualTo(3);

    Map<String, Object> legacy =
        jdbc.queryForMap(
            """
            select processing_state,canonical_envelope_sha256,source_topic,
                   attempt_count,processed_at
            from assistant_event_inbox where event_id=?
            """,
            eventId);
    assertThat(legacy)
        .containsEntry("processing_state", "LEGACY_PROCESSED")
        .containsEntry("attempt_count", 0);
    assertThat(legacy.get("canonical_envelope_sha256")).isNull();
    assertThat(legacy.get("source_topic")).isNull();
    assertThat(legacy.get("processed_at")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from information_schema.tables
                where table_schema='public' and table_name='assistant_event_dead_letter'
                """,
                Integer.class))
        .isOne();
  }

  @Test
  void upgradeFromV5SerializesQuestionsAndAddsOneActiveConversationPerOrder() {
    flyway().target(MigrationVersion.fromVersion("5")).load().migrate();
    UUID conversationId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID toolCallId = UUID.randomUUID();
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
        values (?,?,'USER','Нужны ОСБ и ЛДСП',clock_timestamp())
        """,
        messageId,
        conversationId);
    jdbc.update(
        """
        insert into assistant_tool_call(
          id,conversation_id,turn_message_id,provider_call_id,tool_name,
          arguments_payload,status,created_at)
        values (?,?,?,'questions','request_cabin_clarifications','{}'::jsonb,
          'STARTED',clock_timestamp())
        """,
        toolCallId,
        conversationId,
        messageId);

    UUID firstQuestionId = UUID.randomUUID();
    UUID secondQuestionId = UUID.randomUUID();
    String options =
        """
        [{"id":"%s","label":"Модуль","value":"Модуль"},
         {"id":"%s","label":"Пост охраны","value":"Пост охраны"}]
        """
            .formatted(UUID.randomUUID(), UUID.randomUUID());
    jdbc.update(
        """
        insert into assistant_clarification_question(
          id,version,conversation_id,turn_message_id,tool_call_id,branch_key,kind,
          prompt,warehouse_id,options_payload,status,created_at)
        values (?,0,?,?,?,'finish:osb','CABIN_TYPE','Выберите тип',?,?::jsonb,
          'PENDING','2026-08-10T10:00:00Z')
        """,
        firstQuestionId,
        conversationId,
        messageId,
        toolCallId,
        UUID.randomUUID(),
        options);
    jdbc.update(
        """
        insert into assistant_clarification_question(
          id,version,conversation_id,turn_message_id,tool_call_id,branch_key,kind,
          prompt,warehouse_id,options_payload,status,created_at)
        values (?,0,?,?,?,'finish:ldsp','CABIN_TYPE','Выберите второй тип',?,?::jsonb,
          'PENDING','2026-08-10T10:01:00Z')
        """,
        secondQuestionId,
        conversationId,
        messageId,
        toolCallId,
        UUID.randomUUID(),
        options);

    assertThat(flyway().load().migrate().migrationsExecuted).isOne();
    assertThat(
            jdbc.queryForList(
                """
                select id,status,sequence_number
                from assistant_clarification_question
                where conversation_id=?
                order by sequence_number
                """,
                conversationId))
        .containsExactly(
            Map.of("id", firstQuestionId, "status", "PENDING", "sequence_number", 1),
            Map.of("id", secondQuestionId, "status", "QUEUED", "sequence_number", 2));

    UUID rentalOrderId = UUID.randomUUID();
    jdbc.update(
        "update assistant_conversation set rental_order_id=? where id=?",
        rentalOrderId,
        conversationId);
    UUID secondConversationId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into assistant_conversation(
                      id,version,owner_subject_id,client_id,rental_inquiry_id,rental_order_id,
                      archived,created_at,updated_at)
                    values (?,0,?,?,?,?,false,clock_timestamp(),clock_timestamp())
                    """,
                    secondConversationId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    rentalOrderId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "update assistant_conversation set archived=true,archived_at=clock_timestamp() where id=?",
        conversationId);
    assertThat(
            jdbc.update(
                """
                insert into assistant_conversation(
                  id,version,owner_subject_id,client_id,rental_inquiry_id,rental_order_id,
                  archived,created_at,updated_at)
                values (?,0,?,?,?,?,false,clock_timestamp(),clock_timestamp())
                """,
                secondConversationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                rentalOrderId))
        .isOne();
  }

  @Test
  void cumulativeCurrentMigrationsRetainManagerOwnershipWithoutLegacyBoundaryColumn() {
    Flyway current = flyway().load();
    assertThat(current.migrate().migrationsExecuted).isEqualTo(6);
    current.validate();
    assertThat(current.migrate().migrationsExecuted).isZero();
    UUID conversationId = UUID.randomUUID();
    UUID rentalOrderId = UUID.randomUUID();
    UUID managerSubjectId = UUID.randomUUID();
    jdbc.update(
        """
        insert into assistant_conversation(
          id,version,owner_subject_id,client_id,rental_inquiry_id,rental_order_id,
          archived,created_at,updated_at)
        values (?,0,?,?,?,?,false,clock_timestamp(),clock_timestamp())
        """,
        conversationId,
        managerSubjectId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        rentalOrderId);

    assertThat(
            jdbc.queryForObject(
                "select owner_subject_id from assistant_conversation where id=?",
                UUID.class,
                conversationId))
        .isEqualTo(managerSubjectId);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='assistant_conversation'
                  and column_name='company_id'
                """,
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success",
                Integer.class))
        .isEqualTo(6);
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
