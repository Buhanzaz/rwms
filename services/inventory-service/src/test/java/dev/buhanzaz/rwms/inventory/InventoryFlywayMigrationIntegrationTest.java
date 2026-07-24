package dev.buhanzaz.rwms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class InventoryFlywayMigrationIntegrationTest {
  private static final String MIGRATIONS = "classpath:db/migration";

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
  void cleanInstallIsRepeatSafeAndContainsNoSeedOrImporter() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(tableNames())
        .contains(
            "inventory_session",
            "inventory_start_operation",
            "inventory_start_capture_attempt",
            "inventory_start_capture_result",
            "inventory_capture_release",
            "inventory_expected_item",
            "inventory_finding",
            "finding_plan_snapshot",
            "finding_plan_line",
            "finding_plan_stage",
            "finding_media_reference",
            "inventory_source_attachment",
            "inventory_validation_snapshot",
            "inventory_validation_item",
            "inventory_completion_statistics",
            "inventory_statistics_line",
            "inventory_publication_intent",
            "inventory_publication_attempt",
            "inventory_publication_attempt_result",
            "inventory_media_fact_projection",
            "inventory_idempotency_record",
            "domain_event",
            "event_stream_head",
            "aggregate_snapshot",
            "projection_checkpoint",
            "outbox_event",
            "inbox_message",
            "consumer_aggregate_checkpoint",
            "version_gap_quarantine",
            "sanitized_dead_letter")
        .doesNotContain("legacy_inventory", "browser_inventory", "inventory_import");
    assertThat(count("inventory_session")).isZero();
    assertThat(count("inventory_finding")).isZero();
    assertThat(count("domain_event")).isZero();
  }

  @Test
  void findingOwnerProofLifecycleDefaultsActiveAtZeroAndRejectsNegativeRevision() {
    flyway(MIGRATIONS).migrate();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "AB12");

    assertThat(
            jdbc.queryForMap(
                "select owner_proof_revision,owner_proof_active from inventory_finding where id=?",
                findingId))
        .containsEntry("owner_proof_revision", 0L)
        .containsEntry("owner_proof_active", true);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_finding set owner_proof_revision=-1 where id=?", findingId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void appliedChecksumDriftIsRejected(@TempDir Path directory) throws IOException {
    Path migration = directory.resolve("V1__inventory_schema.sql");
    try (var source = requireResource("db/migration/V1__inventory_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(
        migration,
        Files.readString(migration)
            .replace("cancellation_reason varchar(2000)", "cancellation_reason varchar(1999)"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void nonEmptyUnversionedSchemaIsRejectedWithoutAutomaticBaseline() {
    jdbc.execute("create table historical_inventory_evidence (id uuid primary key)");

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(
            jdbc.queryForObject("select to_regclass('public.flyway_schema_history')", String.class))
        .isNull();
  }

  @Test
  void databaseRejectsActiveSessionFindingSourceAndUnsafeDltViolations() throws Exception {
    flyway(MIGRATIONS).migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID firstSession = UUID.randomUUID();
    insertActiveSession(firstSession, warehouseId, UUID.randomUUID());

    assertThatThrownBy(() -> insertActiveSession(UUID.randomUUID(), warehouseId, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID terminalSession = UUID.randomUUID();
    insertActiveSession(terminalSession, UUID.randomUUID(), UUID.randomUUID());
    jdbc.update(
        """
        update inventory_session
           set lifecycle='CANCELLED', cancelled_by_actor_ref=?::jsonb,
               cancellation_reason='reviewed', cancelled_at=clock_timestamp(),
               session_revision=1, updated_at=clock_timestamp()
         where id=?
        """,
        actor(),
        terminalSession);

    UUID finding = UUID.randomUUID();
    insertFinding(terminalSession, finding, UUID.randomUUID(), "AB12");
    assertThatThrownBy(
            () -> insertFinding(terminalSession, UUID.randomUUID(), UUID.randomUUID(), "AB12"))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID sourceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_source_attachment(
          id,inventory_id,finding_id,source_key,source_revision,technical_attempt_id,
          request_sha256,state,created_at,updated_at)
        values (?,?,?,?,1,?,?,'PENDING',?,?)
        """,
        sourceId,
        terminalSession,
        finding,
        terminalSession + ":" + finding,
        UUID.randomUUID(),
        "1".repeat(64),
        now(),
        now());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into inventory_source_attachment(
                      id,inventory_id,finding_id,source_key,source_revision,technical_attempt_id,
                      request_sha256,state,created_at,updated_at)
                    values (?,?,?,?,1,?,?,'PENDING',?,?)
                    """,
                    UUID.randomUUID(),
                    terminalSession,
                    finding,
                    terminalSession + ":" + finding,
                    UUID.randomUUID(),
                    "2".repeat(64),
                    now(),
                    now()))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into sanitized_dead_letter(
                      dlt_id,destination,message_sha256,failure_code,safe_body,body_sha256,
                      status,attempt_count,next_attempt_at,created_at)
                    values (?,'rwms.inventory.dlt.v1',?,'VALIDATION_REJECTED',?::jsonb,?,
                      'PENDING',0,clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    "3".repeat(64),
                    "{\"failureCode\":\"VALIDATION_REJECTED\",\"messageSha256\":\""
                        + "3".repeat(64)
                        + "\",\"recordedAt\":\"2026-07-17T12:00:00Z\",\"rawError\":\"secret\"}",
                    "4".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void domainEventsAndPublicationAttemptsAreDatabaseAppendOnly() {
    flyway(MIGRATIONS).migrate();
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          recorded_at,correlation_id,event_body,event_sha256)
        values (?,'SESSION',?,0,'inventory.session.started.v1',1,
          clock_timestamp(),?,'{}'::jsonb,?)
        """,
        eventId,
        aggregateId.toString(),
        UUID.randomUUID(),
        "5".repeat(64));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update domain_event set event_sha256=? where event_id=?",
                    "6".repeat(64),
                    eventId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void startCaptureAndReleaseRecoveryLedgerIsDurableMonotonicAndAppendOnly() {
    flyway(MIGRATIONS).migrate();
    UUID operationId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime createdAt = now();
    jdbc.update(
        """
        insert into inventory_start_operation(
          operation_id,subject_id,idempotency_key,request_sha256,warehouse_id,state,
          created_at,updated_at,expires_at)
        values (?,?,?,?,?,'REQUESTED',?,?,?)
        """,
        operationId,
        subjectId,
        idempotencyKey,
        "7".repeat(64),
        warehouseId,
        createdAt,
        createdAt,
        createdAt.plusDays(7));
    jdbc.update(
        """
        insert into inventory_start_capture_attempt(
          operation_id,technical_attempt,request_fingerprint,requested_at)
        values (?,1,?,clock_timestamp())
        """,
        operationId,
        "8".repeat(64));
    UUID captureId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_start_capture_result(
          operation_id,technical_attempt,outcome,capture_id,membership_digest,total_count,
          expires_at,recorded_at)
        values (?,1,'CAPTURED',?,?,0,clock_timestamp()+interval '30 minutes',clock_timestamp())
        """,
        operationId,
        captureId,
        "9".repeat(64));
    jdbc.update(
        """
        insert into inventory_capture_release(
          operation_id,technical_attempt,capture_id,state,attempt_count,next_attempt_at,created_at)
        values (?,1,?,'PENDING',0,clock_timestamp(),clock_timestamp())
        """,
        operationId,
        captureId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_start_capture_attempt set technical_attempt=2 where operation_id=?",
                    operationId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into inventory_start_capture_attempt(
                      operation_id,technical_attempt,request_fingerprint,requested_at)
                    values (?,0,?,clock_timestamp())
                    """,
                    operationId,
                    "a".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(count("inventory_capture_release")).isOne();
  }

  @Test
  void validationRowsDistinguishMissingAndEnforceOwningWarehouseForFoundAssets() {
    flyway(MIGRATIONS).migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    insertActiveSession(inventoryId, warehouseId, UUID.randomUUID());
    jdbc.update(
        """
        insert into inventory_validation_snapshot(
          inventory_id,session_revision,validation_sha256,acknowledgement_sha256,
          validated_at,snapshot_body)
        values (?,0,?,?,clock_timestamp(),'{}'::jsonb)
        """,
        inventoryId,
        "b".repeat(64),
        "c".repeat(64));
    UUID foundFinding = UUID.randomUUID();
    UUID missingFinding = UUID.randomUUID();
    UUID crossWarehouseFinding = UUID.randomUUID();
    insertFinding(inventoryId, foundFinding, UUID.randomUUID(), "FOUND");
    insertFinding(inventoryId, missingFinding, UUID.randomUUID(), "MISSING");
    insertFinding(inventoryId, crossWarehouseFinding, UUID.randomUUID(), "CROSS");
    jdbc.update(
        """
        insert into inventory_validation_item(
          inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
        values (?,?,true,3,'FREE',?,?)
        """,
        inventoryId,
        UUID.randomUUID(),
        warehouseId,
        foundFinding);
    jdbc.update(
        """
        insert into inventory_validation_item(
          inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
        values (?,?,false,null,null,null,?)
        """,
        inventoryId,
        UUID.randomUUID(),
        missingFinding);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into inventory_validation_item(
                      inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
                    values (?,?,true,3,'FREE',?,?)
                    """,
                    inventoryId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    crossWarehouseFinding))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void publicationAttemptRequestPrecedesAndCannotBeMutatedWithSeparateTerminalResult() {
    flyway(MIGRATIONS).migrate();
    UUID inventoryId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    UUID findingId = UUID.randomUUID();
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "PUB");
    UUID intentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,
          source_revision,attempt_count,created_at,updated_at)
        values (?,?,?,0,'READY',?,1,0,clock_timestamp(),clock_timestamp())
        """,
        intentId,
        inventoryId,
        findingId,
        inventoryId + ":" + findingId);
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_attempt(
          id,publication_intent_id,attempt_no,idempotency_key,transition_kind,
          request_sha256,started_at)
        values (?,?,1,?,'REQUEST',?,clock_timestamp())
        """,
        attemptId,
        intentId,
        UUID.randomUUID(),
        "d".repeat(64));

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from inventory_publication_attempt attempt
                left join inventory_publication_attempt_result result
                  on result.publication_attempt_id=attempt.id
                where attempt.id=? and result.publication_attempt_id is null
                """,
                Integer.class,
                attemptId))
        .isOne();
    jdbc.update(
        """
        insert into inventory_publication_attempt_result(
          publication_attempt_id,outcome,repair_id,finished_at)
        values (?,'SUCCEEDED',?,clock_timestamp())
        """,
        attemptId,
        UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_publication_attempt_result set outcome='BLOCKED' where publication_attempt_id=?",
                    attemptId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.update("delete from inventory_publication_attempt where id=?", attemptId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  private void insertActiveSession(UUID id, UUID warehouseId, UUID idempotencyKey) {
    OffsetDateTime current = now();
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'Inventory operator',?::jsonb,?,?,?)
        """,
        id,
        warehouseId,
        idempotencyKey,
        idempotencyKey,
        "0".repeat(64),
        "1".repeat(64),
        UUID.randomUUID(),
        actor(),
        current,
        current,
        current);
  }

  private void insertFinding(UUID inventoryId, UUID findingId, UUID assetId, String matchKey) {
    jdbc.update(
        """
        insert into inventory_finding(
          id,inventory_id,finding_revision,origin,inspection,reconciliation,asset_id,
          asset_version_snapshot,display_canonical_number,identity_match_key,
          passport_observation_state,equipment_observation_state,mutation_state,
          actor_ref,created_at,updated_at)
        values (?,?,0,'UNEXPECTED_EXISTING','NOT_INSPECTED','MATCHED',?,0,?,?,'ABSENT',
          'ABSENT','IDLE',?::jsonb,?,?)
        """,
        findingId,
        inventoryId,
        assetId,
        matchKey,
        matchKey,
        actor(),
        now(),
        now());
  }

  private Flyway flyway(String location) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(location)
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .load();
  }

  private Set<String> tableNames() {
    return jdbc
        .queryForList("select tablename from pg_tables where schemaname='public'", String.class)
        .stream()
        .collect(Collectors.toSet());
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private java.net.URL requireResource(String name) {
    java.net.URL resource = getClass().getClassLoader().getResource(name);
    if (resource == null) throw new IllegalStateException("Missing resource " + name);
    return resource;
  }

  private static String actor() {
    return "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\",\"principalType\":\"USER\",\"profileRevision\":null}";
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
