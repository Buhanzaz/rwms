package dev.buhanzaz.rwms.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MaintenanceFlywayMigrationIntegrationTest {
  private static final String MIGRATIONS = "classpath:db/migration";

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void resetDatabase() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }

  @Test
  void cleanInstallIsRepeatSafeAndContainsTheAuthoritativeStageSixSchema() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isOne();
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(tableNames()).contains(
        "catalog_version", "catalog_node", "catalog_link", "maintenance_estimate",
        "estimate_revision", "estimate_line", "estimate_plan_stage", "maintenance_repair",
        "repair_stage", "maintenance_media_reference", "domain_event", "event_stream_head",
        "aggregate_snapshot", "projection_checkpoint", "outbox_event", "inbox_message",
        "consumer_aggregate_checkpoint", "version_gap_quarantine", "sanitized_dead_letter",
        "maintenance_inbound_replay_message", "maintenance_inbound_correlation",
        "rental_item_fact_projection", "operation_lease_fact_projection",
        "maintenance_idempotency_record", "integration_reconciliation");
    assertThat(columnCount("maintenance_estimate", "rental_item_version_snapshot")).isOne();
    assertThat(columnCount("maintenance_repair", "rental_item_version_snapshot")).isOne();
    assertThat(columnCount("maintenance_repair", "dispatch_date")).isOne();
    assertThat(columnCount("repair_stage", "external_queue_entry_id")).isOne();
    assertThat(columns("catalog_node")).contains(
        "active", "parent_node_id", "unit", "include_in_estimate", "common_item",
        "show_in_main_menu", "photo_required", "routing_queue_id", "routing_queue_code",
        "routing_queue_kind", "opaque_references", "comment", "media_references");
    assertThat(columns("estimate_line")).contains(
        "catalog_snapshot", "comment", "media_references");
    assertThat(columns("estimate_plan_stage")).contains(
        "routing_queue_id", "routing_queue_code", "routing_queue_kind", "task_deadline");
    assertThat(columns("repair_stage")).contains(
        "routing_queue_id", "routing_queue_code", "routing_queue_kind", "task_deadline");
    assertThat(jdbc.queryForObject("select count(*) from catalog_version", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from maintenance_repair", Integer.class)).isZero();
  }

  @Test
  void appliedMigrationChecksumDriftIsRejected(@TempDir Path directory) throws IOException {
    Path migration = directory.resolve("V1__maintenance_schema.sql");
    try (var source = requireResource("db/migration/V1__maintenance_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(migration, Files.readString(migration)
        .replace("source_party varchar(512)", "source_party varchar(511)"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() {
    jdbc.execute("create table historical_maintenance_evidence (id uuid primary key)");

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(jdbc.queryForObject(
        "select to_regclass('public.flyway_schema_history')", String.class)).isNull();
  }

  @Test
  void catalogNodeMediaStorageIsIsolatedAcrossVersionsWithTheSameLogicalNodeId() {
    flyway(MIGRATIONS).migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID firstVersion = UUID.randomUUID();
    UUID secondVersion = UUID.randomUUID();
    UUID logicalNodeId = UUID.randomUUID();
    UUID firstRowId = UUID.randomUUID();
    UUID secondRowId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    insertCatalogVersion(firstVersion, warehouseId, "1".repeat(64));
    insertCatalogVersion(secondVersion, warehouseId, "2".repeat(64));
    insertCatalogNode(firstRowId, logicalNodeId, firstVersion, "NODE_A");
    insertCatalogNode(secondRowId, logicalNodeId, secondVersion, "NODE_B");

    for (UUID storageId : List.of(firstRowId, secondRowId)) {
      jdbc.update("""
          insert into maintenance_media_reference(
            aggregate_type,aggregate_id,media_id,generation,owner_type,warehouse_id,
            safe_metadata,attached_at)
          values ('CATALOG_NODE',?,?,0,'MAINTENANCE_CATALOG_NODE',?,'{}',clock_timestamp())
          """, storageId, mediaId, warehouseId);
    }

    assertThat(jdbc.queryForObject("""
        select count(*) from maintenance_media_reference
        where aggregate_type='CATALOG_NODE' and media_id=?
        """, Integer.class, mediaId)).isEqualTo(2);
    jdbc.update("""
        delete from maintenance_media_reference
        where aggregate_type='CATALOG_NODE' and aggregate_id=?
        """, secondRowId);
    assertThat(jdbc.queryForObject("""
        select aggregate_id from maintenance_media_reference
        where aggregate_type='CATALOG_NODE' and media_id=?
        """, UUID.class, mediaId)).isEqualTo(firstRowId);
  }

  @Test
  void technicalConstraintsAndDueIndexesMatchTheReviewedStageSixDefinitions() {
    flyway(MIGRATIONS).migrate();

    assertThat(constraintDefinition("estimate_line", "fk_estimate_line_revision"))
        .contains(
            "FOREIGN KEY (estimate_id, estimate_revision) REFERENCES estimate_revision(estimate_id, revision)")
        .contains("DEFERRABLE INITIALLY DEFERRED");
    assertThat(constraintDefinition("estimate_plan_stage", "fk_estimate_plan_revision"))
        .contains(
            "FOREIGN KEY (estimate_id, estimate_revision) REFERENCES estimate_revision(estimate_id, revision)")
        .contains("DEFERRABLE INITIALLY DEFERRED");
    assertThat(constraintDefinition("maintenance_repair", "ck_repair_hierarchy"))
        .contains("root_repair_id IS NULL", "source_repair_id <> id", "root_repair_id <> id");
    assertThat(constraintDefinition("integration_reconciliation", "ck_reconciliation_review"))
        .contains("review_version = 0", "review_subject_id IS NOT NULL", "btrim");
    assertThat(constraintDefinition("outbox_event", "ck_maintenance_outbox_review"))
        .contains("review_version = 0", "review_reason IS NOT NULL", "reviewed_at IS NOT NULL");
    assertThat(
            constraintDefinition(
                "maintenance_inbound_correlation",
                "fk_maintenance_inbound_correlation_counterpart"))
        .contains("FOREIGN KEY (counterpart_event_id)")
        .contains("REFERENCES maintenance_inbound_replay_message(event_id)");
    assertThat(
            constraintDefinition(
                "maintenance_inbound_correlation",
                "ck_maintenance_inbound_correlation_identity"))
        .contains("rwms.task-board.board-task.v1", "external_task_id IS NOT NULL")
        .contains("rwms.task-board.queue-entry.v1", "queue_entry_id IS NOT NULL");

    assertThat(indexDefinition("uk_repair_primary_estimate"))
        .contains("UNIQUE INDEX", "estimate_id IS NOT NULL");
    assertThat(indexDefinition("idx_repair_lease_renewal_due"))
        .contains("lease_expires_at", "lease_reconciliation_state", "ACTIVE");
    assertThat(indexDefinition("idx_integration_reconciliation_due"))
        .contains("next_attempt_at", "attempt_count < 4");
    assertThat(indexDefinition("idx_maintenance_inbound_correlation_pending_queue"))
        .contains("state", "PENDING", "queue_entry_id IS NOT NULL");
    assertThat(columnDefault("integration_reconciliation", "review_version")).contains("0");
    assertThat(columnDefault("outbox_event", "review_version")).contains("0");
    assertThat(columnNullable("integration_reconciliation", "review_version")).isEqualTo("NO");
    assertThat(columnNullable("outbox_event", "review_version")).isEqualTo("NO");
  }

  @Test
  void invalidRevisionRepairReviewAndCorrelationRowsAreRejectedByPostgres() {
    flyway(MIGRATIONS).migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "3".repeat(64));
    insertEstimateWithRevision(estimateId, catalogId, warehouseId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into estimate_line(
                      row_id,line_id,estimate_id,estimate_revision,line_no,line_type,title,
                      quantity,unit_price_minor,media_references)
                    values (?,?,?,2,0,'WORK','orphan revision',1,0,'[]')
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    estimateId))
        .hasMessageContaining("fk_estimate_line_revision");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into estimate_plan_stage(
                      row_id,stage_id,estimate_id,estimate_revision,stage_no,stage_kind,
                      routing_queue_id,routing_queue_code,routing_queue_kind)
                    values (?,?,?,2,0,'REPAIR_WORK',?,'REPAIR','REPAIR')
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    estimateId,
                    UUID.randomUUID()))
        .hasMessageContaining("fk_estimate_plan_revision");

    UUID firstRepairId = UUID.randomUUID();
    insertRepair(
        firstRepairId,
        warehouseId,
        estimateId,
        "ESTIMATE",
        "PRIMARY",
        null,
        null);
    assertThatThrownBy(
            () ->
                insertRepair(
                    UUID.randomUUID(),
                    warehouseId,
                    estimateId,
                    "ESTIMATE",
                    "PRIMARY",
                    null,
                    null))
        .hasMessageContaining("uk_repair_primary_estimate");
    UUID selfRepairId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                insertRepair(
                    selfRepairId,
                    warehouseId,
                    null,
                    "DIRECT_REPAIR",
                    "REWORK",
                    selfRepairId,
                    selfRepairId))
        .hasMessageContaining("ck_repair_hierarchy");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into integration_reconciliation(
                      id,dependency_type,operation_type,idempotency_key,state,attempt_count,
                      next_attempt_at,response_snapshot,review_version,review_subject_id,reviewed_at,
                      created_at,updated_at)
                    values (?,'ASSET','TEST',?,'PENDING',0,clock_timestamp(),'{}',1,?,
                      clock_timestamp(),clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_reconciliation_review");

    UUID outboxEventId = insertDomainEvent(UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into outbox_event(
                      event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
                      envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,
                      review_version,review_subject_id,reviewed_at,created_at)
                    select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,
                      'rwms.maintenance.catalog-version.v1','{}',payload_sha256,'PENDING',0,
                      clock_timestamp(),1,?,clock_timestamp(),clock_timestamp()
                    from domain_event where event_id=?
                    """,
                    UUID.randomUUID(),
                    outboxEventId))
        .hasMessageContaining("ck_maintenance_outbox_review");

    UUID replayEventId = insertReplayMessage("rwms.task-board.board-task.v1", "BOARD_TASK");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into maintenance_inbound_correlation(
                      source_event_id,source_topic,board_task_id,queue_entry_id,state,
                      recorded_at,updated_at)
                    values (?,'rwms.task-board.board-task.v1',?,?,'PENDING',
                      clock_timestamp(),clock_timestamp())
                    """,
                    replayEventId,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_maintenance_inbound_correlation_identity");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into maintenance_inbound_correlation(
                      source_event_id,source_topic,board_task_id,external_task_id,
                      counterpart_event_id,state,recorded_at,updated_at)
                    values (?,'rwms.task-board.board-task.v1',?,?,?,'CORRELATED',
                      clock_timestamp(),clock_timestamp())
                    """,
                    replayEventId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("fk_maintenance_inbound_correlation_counterpart");
  }

  private Flyway flyway(String location) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(location)
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false)
        .load();
  }

  private List<String> tableNames() {
    return jdbc.queryForList(
        "select table_name from information_schema.tables where table_schema='public'",
        String.class);
  }

  private int columnCount(String table, String column) {
    Integer count = jdbc.queryForObject("""
        select count(*) from information_schema.columns
        where table_schema='public' and table_name=? and column_name=?
        """, Integer.class, table, column);
    return count == null ? 0 : count;
  }

  private List<String> columns(String table) {
    return jdbc.queryForList("""
        select column_name from information_schema.columns
        where table_schema='public' and table_name=?
        """, String.class, table);
  }

  private String constraintDefinition(String table, String constraint) {
    return jdbc.queryForObject(
        """
        select pg_get_constraintdef(con.oid)
        from pg_constraint con
        join pg_class relation on relation.oid=con.conrelid
        join pg_namespace namespace on namespace.oid=relation.relnamespace
        where namespace.nspname='public' and relation.relname=? and con.conname=?
        """,
        String.class,
        table,
        constraint);
  }

  private String indexDefinition(String index) {
    return jdbc.queryForObject(
        "select indexdef from pg_indexes where schemaname='public' and indexname=?",
        String.class,
        index);
  }

  private String columnDefault(String table, String column) {
    return jdbc.queryForObject(
        """
        select column_default from information_schema.columns
        where table_schema='public' and table_name=? and column_name=?
        """,
        String.class,
        table,
        column);
  }

  private String columnNullable(String table, String column) {
    return jdbc.queryForObject(
        """
        select is_nullable from information_schema.columns
        where table_schema='public' and table_name=? and column_name=?
        """,
        String.class,
        table,
        column);
  }

  private void insertCatalogVersion(UUID id, UUID warehouseId, String sourceSha256) {
    jdbc.update("""
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,created_at,updated_at)
        values (?,0,?,'DRAFT',?,1,0,'{}',clock_timestamp(),clock_timestamp())
        """, id, warehouseId, sourceSha256);
  }

  private void insertCatalogNode(
      UUID rowId, UUID logicalNodeId, UUID versionId, String code) {
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,duration_minutes,
          include_in_estimate,common_item,show_in_main_menu,photo_required,
          opaque_references,media_references)
        values (?,?,?,?,'WORK','Node',true,0,true,false,false,false,'[]','[]')
        """, rowId, logicalNodeId, versionId, code);
  }

  private void insertEstimateWithRevision(
      UUID estimateId, UUID catalogId, UUID warehouseId) {
    jdbc.update(
        """
        insert into maintenance_estimate(
          id,version,warehouse_id,rental_item_id,rental_item_version_snapshot,
          catalog_version_id,state,revision,dispatch_date,actor_ref,created_at,updated_at)
        values (?,0,?,?,0,?,'DRAFT',1,current_date,'{}',clock_timestamp(),clock_timestamp())
        """,
        estimateId,
        warehouseId,
        UUID.randomUUID(),
        catalogId);
    jdbc.update(
        """
        insert into estimate_revision(
          id,estimate_id,revision,dispatch_date,total_minor,actor_ref,recorded_at)
        values (?,?,1,current_date,0,'{}',clock_timestamp())
        """,
        UUID.randomUUID(),
        estimateId);
  }

  private void insertRepair(
      UUID id,
      UUID warehouseId,
      UUID estimateId,
      String origin,
      String kind,
      UUID rootRepairId,
      UUID sourceRepairId) {
    jdbc.update(
        """
        insert into maintenance_repair(
          id,version,warehouse_id,rental_item_id,rental_item_version_snapshot,
          root_repair_id,source_repair_id,estimate_id,origin,kind,execution_state,
          acceptance_state,dispatch_date,actor_ref,external_task_id,task_generation_state,
          delivery_state,delivery_attempts,delivery_updated_at,lease_reconciliation_state,
          reconciliation_state,created_at,updated_at)
        values (?,0,?,?,0,?,?,?, ?,?,'DRAFT','NOT_READY',current_date,'{}',?,
          'PENDING_GENERATION','PENDING',0,clock_timestamp(),'NOT_REQUIRED','NOT_REQUIRED',
          clock_timestamp(),clock_timestamp())
        """,
        id,
        warehouseId,
        UUID.randomUUID(),
        rootRepairId,
        sourceRepairId,
        estimateId,
        origin,
        kind,
        UUID.randomUUID());
  }

  private UUID insertDomainEvent(UUID aggregateId) {
    UUID eventId = UUID.randomUUID();
    String hash = "4".repeat(64);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,0,?,clock_timestamp())
        """,
        aggregateId.toString(),
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'CATALOG_VERSION',?,0,'maintenance.catalog-version.changed.v1',1,
          clock_timestamp(),clock_timestamp(),?,'{}',?,false)
        """,
        eventId,
        aggregateId.toString(),
        UUID.randomUUID(),
        hash);
    return eventId;
  }

  private UUID insertReplayMessage(String topic, String aggregateType) {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into maintenance_inbound_replay_message(
          event_id,source_topic,kafka_key,aggregate_type,aggregate_id,aggregate_version,
          event_type,envelope_body,message_sha256,state,staged_at,updated_at)
        values (?,?,?,?,?,0,'task-board.board-task.created.v1','{}',?,'STAGED',
          clock_timestamp(),clock_timestamp())
        """,
        eventId,
        topic,
        aggregateId.toString(),
        aggregateType,
        aggregateId.toString(),
        "5".repeat(64));
    return eventId;
  }

  private java.net.URL requireResource(String path) {
    java.net.URL resource = getClass().getClassLoader().getResource(path);
    if (resource == null) throw new IllegalStateException("Missing maintenance migration " + path);
    return resource;
  }
}
