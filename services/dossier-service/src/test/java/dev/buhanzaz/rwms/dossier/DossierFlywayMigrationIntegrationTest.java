package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierActivity;
import dev.buhanzaz.rwms.dossier.domain.DossierActivityCode;
import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierProjectionGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import dev.buhanzaz.rwms.dossier.domain.DossierSourceFact;
import dev.buhanzaz.rwms.dossier.eventing.DossierRelayTransactions;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierProjectionGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DossierFlywayMigrationIntegrationTest {
  private static final String MIGRATIONS = "classpath:db/migration";

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
  void cleanInstallIsRepeatSafeAndPassesJpaValidation() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(tableNames())
        .containsExactlyInAnyOrder(
            "dossier_active_generation",
            "dossier_activity",
            "dossier_aggregate_checkpoint",
            "dossier_cabin_publication_head",
            "dossier_inbox",
            "dossier_media_projection",
            "dossier_outbox_event",
            "dossier_partition_checkpoint",
            "dossier_projection_generation",
            "dossier_replay_run",
            "dossier_replay_partition_high_water",
            "dossier_sanitized_dead_letter",
            "dossier_source_fact",
            "dossier_subject_association",
            "dossier_unlinked_fact",
            "flyway_schema_history");
    assertThat(toRegclass("databasechangelog")).isNull();
    assertThat(toRegclass("rental_item")).isNull();
    assertThat(
            jdbc.queryForObject(
                """
                select is_nullable from information_schema.columns
                where table_schema='public' and table_name='dossier_media_projection'
                  and column_name='folder_id'
                """,
                String.class))
        .isEqualTo("NO");
    assertThat(
            jdbc.queryForObject(
                "select generation_id from dossier_active_generation where pointer_name='DOSSIER'",
                UUID.class))
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000901"));
    assertThat(
            jdbc.queryForObject(
                "select state from dossier_projection_generation where id='00000000-0000-0000-0000-000000000901'",
                String.class))
        .isEqualTo("ACTIVE");
    assertJpaValidationStarts();
  }

  @Test
  void v2BackfillsExistingMediaAsDistinctOnePhotoFolders() {
    Flyway beforeFolderGrouping =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("1")
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .outOfOrder(false)
            .load();
    assertThat(beforeFolderGrouping.migrate().migrationsExecuted).isOne();

    UUID cabinId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstMediaId = UUID.randomUUID();
    UUID secondMediaId = UUID.randomUUID();
    insertLegacyMediaProjection(cabinId, warehouseId, firstMediaId, 0);
    insertLegacyMediaProjection(cabinId, warehouseId, secondMediaId, 1);

    assertThat(flyway(MIGRATIONS).migrate().migrationsExecuted).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select folder_id from dossier_media_projection where media_id=?",
                UUID.class,
                firstMediaId))
        .isEqualTo(firstMediaId);
    assertThat(
            jdbc.queryForObject(
                "select folder_id from dossier_media_projection where media_id=?",
                UUID.class,
                secondMediaId))
        .isEqualTo(secondMediaId);
    assertThat(
            jdbc.queryForObject(
                "select count(distinct folder_id) from dossier_media_projection where cabin_id=?",
                Long.class,
                cabinId))
        .isEqualTo(2L);
  }

  @Test
  void v3BackfillsOnlyExactActiveCabinEvidenceAndAddsLookupIndex() {
    Flyway throughV2 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("2")
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .outOfOrder(false)
            .load();
    assertThat(throughV2.migrate().migrationsExecuted).isEqualTo(2);

    UUID activeGeneration =
        UUID.fromString("00000000-0000-0000-0000-000000000901");
    UUID inactiveGeneration = UUID.randomUUID();
    jdbc.update(
        "insert into dossier_projection_generation(id,state,created_at) values (?,'BUILDING',clock_timestamp())",
        inactiveGeneration);
    UUID unresolvedEvent = UUID.randomUUID();
    UUID resolvedEvent = UUID.randomUUID();
    UUID globalEvent = UUID.randomUUID();
    UUID inactiveEvent = UUID.randomUUID();
    UUID unresolvedCabin = UUID.randomUUID();
    UUID resolvedCabin = UUID.randomUUID();
    UUID inactiveCabin = UUID.randomUUID();
    OffsetDateTime resolvedAt = OffsetDateTime.parse("2026-08-09T10:15:30Z");
    insertV2VisibilityEvidence(
        unresolvedEvent, activeGeneration, unresolvedCabin, null, 700L);
    insertV2VisibilityEvidence(
        resolvedEvent, activeGeneration, resolvedCabin, resolvedAt, 701L);
    insertV2VisibilityEvidence(globalEvent, activeGeneration, null, null, 702L);
    insertV2VisibilityEvidence(
        inactiveEvent, inactiveGeneration, inactiveCabin, null, 703L);
    UUID unresolvedFailure = insertV2DeadLetter(unresolvedEvent, 700L);
    UUID resolvedFailure = insertV2DeadLetter(resolvedEvent, 701L);
    UUID globalFailure = insertV2DeadLetter(globalEvent, 702L);
    UUID inactiveFailure = insertV2DeadLetter(inactiveEvent, 703L);
    UUID validationFailure = insertV2DeadLetter(null, 704L);
    UUID validationFailureWithEventId = insertV2DeadLetter(unresolvedEvent, 705L);
    jdbc.update(
        "update dossier_sanitized_dead_letter set failure_code='INVALID_ENVELOPE' where id=?",
        validationFailureWithEventId);

    assertThat(flyway(MIGRATIONS).migrate().migrationsExecuted).isEqualTo(2);

    assertThat(
            jdbc.queryForObject(
                "select coverage_generation_id from dossier_sanitized_dead_letter where id=?",
                UUID.class,
                unresolvedFailure))
        .isEqualTo(activeGeneration);
    assertThat(
            jdbc.queryForObject(
                "select coverage_subject_cabin_id from dossier_sanitized_dead_letter where id=?",
                UUID.class,
                unresolvedFailure))
        .isEqualTo(unresolvedCabin);
    assertThat(
            jdbc.queryForObject(
                "select coverage_resolved_at from dossier_sanitized_dead_letter where id=?",
                OffsetDateTime.class,
                resolvedFailure))
        .isEqualTo(resolvedAt);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from dossier_sanitized_dead_letter
                where id in (?,?,?,?)
                  and coverage_generation_id is null
                  and coverage_subject_cabin_id is null
                  and coverage_resolved_at is null
                """,
                Long.class,
                globalFailure,
                inactiveFailure,
                validationFailure,
                validationFailureWithEventId))
        .isEqualTo(4L);
    assertThat(
            jdbc.queryForObject(
                    "select indexdef from pg_indexes where schemaname='public' and indexname='idx_dossier_dead_letter_unresolved_coverage'",
                    String.class)
                .toLowerCase(java.util.Locale.ROOT))
        .contains("(coverage_generation_id, coverage_subject_cabin_id)")
        .contains("where (coverage_resolved_at is null)");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update dossier_sanitized_dead_letter set coverage_generation_id=? where id=?",
                    activeGeneration,
                    validationFailure))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void v4AddsOnlyTheCanonicalRepairTransferActivityCodes() {
    Flyway throughV3 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("3")
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .outOfOrder(false)
            .load();
    assertThat(throughV3.migrate().migrationsExecuted).isEqualTo(3);

    assertThat(flyway(MIGRATIONS).migrate().migrationsExecuted).isOne();

    String definition =
        jdbc.queryForObject(
            """
            select pg_get_constraintdef(oid)
            from pg_constraint
            where conrelid='public.dossier_activity'::regclass
              and conname='ck_dossier_activity_code'
            """,
            String.class);
    assertThat(definition)
        .contains("REPAIR_TRANSFER_PREPARED", "REPAIR_TRANSFERRED", "CABIN_CREATED")
        .doesNotContain("REPAIR_TRANSFER_FAILED");
  }

  @Test
  void checksumDriftIsRejected(@TempDir Path directory) throws IOException {
    Path migration = directory.resolve("V1__dossier_schema.sql");
    try (var source = requireResource("db/migration/V1__dossier_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(
        migration,
        Files.readString(migration)
            .replace("source_topic varchar(200) NOT NULL", "source_topic varchar(199) NOT NULL"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() {
    jdbc.execute("create table legacy_dossier_evidence (id uuid primary key)");

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(toRegclass("flyway_schema_history")).isNull();
  }

  @Test
  void associationsAreGenerationIsolatedAndActorProfileIsStrict() {
    flyway(MIGRATIONS).migrate();
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID firstGeneration = UUID.randomUUID();
    UUID secondGeneration = UUID.randomUUID();
    jdbc.update(
        "insert into dossier_projection_generation(id,state,created_at) values (?,'BUILDING',clock_timestamp()),(?,'BUILDING',clock_timestamp())",
        firstGeneration,
        secondGeneration);
    jdbc.update(
        """
        insert into dossier_source_fact(
          id,event_id,producer,source_topic,source_partition,source_offset,kafka_key,
          aggregate_type,aggregate_id,aggregate_version,event_type,event_version,payload_sha256,
          canonical_envelope,recorded_at,actor_subject_id,actor_principal_type,
          actor_profile_revision,correlation_id,ingested_at)
        values (?,?, 'ASSET','rwms.asset.rental-item.v1',0,0,?,'RENTAL_ITEM',?,0,
          'asset.rental-item.created.v1',1,?,cast(? as jsonb),clock_timestamp(),?,'USER',?, ?,clock_timestamp())
        """,
        UUID.randomUUID(),
        eventId,
        aggregateId,
        aggregateId,
        "a".repeat(64),
        "{}",
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        UUID.randomUUID());
    UUID sourceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into dossier_subject_association(
          id,generation_id,producer,source_type,source_id,cabin_id,warehouse_id,
          source_event_id,proven_at)
        values (?,?,'ASSET','RENTAL_ITEM',?,?,?,?,clock_timestamp()),
               (?,?,'ASSET','RENTAL_ITEM',?,?,?,?,clock_timestamp())
        """,
        UUID.randomUUID(), firstGeneration, sourceId, UUID.randomUUID(), UUID.randomUUID(), eventId,
        UUID.randomUUID(), secondGeneration, sourceId, UUID.randomUUID(), UUID.randomUUID(), eventId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from dossier_subject_association where source_id=?",
                Long.class,
                sourceId))
        .isEqualTo(2L);

    assertThat(
            jdbc.update(
                """
                insert into dossier_source_fact(
                  id,event_id,producer,source_topic,source_partition,source_offset,kafka_key,
                  aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
                  payload_sha256,canonical_envelope,recorded_at,correlation_id,
                  subject_warehouse_id,subject_secondary_id,ingested_at)
                values (?,?,'MEDIA','rwms.media.media.v1',0,1,?,'MEDIA',?,1,
                  'media.media.uploaded.v1',1,?,cast(? as jsonb),clock_timestamp(),?,?,?,clock_timestamp())
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                aggregateId,
                aggregateId,
                "c".repeat(64),
                "{}",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()))
        .isOne();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update dossier_source_fact
                    set actor_profile_revision='00000000-0000-0000-0000-00000000000Z'
                    where event_id=?
                    """,
                    eventId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void actorSubjectRequiresPrincipalTypeInSourceFactsAndActivities() {
    flyway(MIGRATIONS).migrate();
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into dossier_source_fact(
          id,event_id,producer,source_topic,source_partition,source_offset,kafka_key,
          aggregate_type,aggregate_id,aggregate_version,event_type,event_version,payload_sha256,
          canonical_envelope,recorded_at,correlation_id,ingested_at)
        values (?,?,'ASSET','rwms.asset.rental-item.v1',0,0,?,'RENTAL_ITEM',?,0,
          'asset.rental-item.created.v1',1,?,cast(? as jsonb),clock_timestamp(),?,clock_timestamp())
        """,
        UUID.randomUUID(),
        eventId,
        aggregateId,
        aggregateId,
        "a".repeat(64),
        "{}",
        correlationId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update dossier_source_fact set actor_subject_id=? where event_id=?",
                    UUID.randomUUID(),
                    eventId))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into dossier_activity(
                      id,activity_id,generation_id,source_event_id,cabin_id,warehouse_id,
                      activity_code,source_producer,source_aggregate_type,source_aggregate_id,
                      recorded_at,actor_subject_id,correlation_id,created_at)
                    values (?,?, '00000000-0000-0000-0000-000000000901', ?, ?, ?,
                      'CABIN_CREATED','ASSET','RENTAL_ITEM',?,clock_timestamp(),?, ?,clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    eventId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    aggregateId,
                    UUID.randomUUID(),
                    correlationId))
        .isInstanceOf(DataIntegrityViolationException.class);
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

  private void insertV2VisibilityEvidence(
      UUID eventId,
      UUID generationId,
      UUID subjectCabinId,
      OffsetDateTime resolvedAt,
      long sourceOffset) {
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into dossier_source_fact(
          id,event_id,producer,source_topic,source_partition,source_offset,kafka_key,
          aggregate_type,aggregate_id,aggregate_version,event_type,event_version,payload_sha256,
          canonical_envelope,recorded_at,correlation_id,subject_cabin_id,
          subject_warehouse_id,ingested_at)
        values (?,?,'ASSET','rwms.asset.rental-item.v1',7,?,?,'RENTAL_ITEM',?,0,
          'asset.rental-item.created.v1',1,?,cast(? as jsonb),clock_timestamp(),?,?,?,clock_timestamp())
        """,
        UUID.randomUUID(),
        eventId,
        sourceOffset,
        aggregateId,
        aggregateId,
        "d".repeat(64),
        "{}",
        UUID.randomUUID(),
        subjectCabinId,
        subjectCabinId == null ? null : UUID.randomUUID());
    jdbc.update(
        """
        insert into dossier_unlinked_fact(
          id,generation_id,source_event_id,subject_cabin_id,reason,source_producer,
          source_aggregate_type,source_aggregate_id,payload_sha256,recorded_at,resolved_at)
        values (?,?,?,?,'AGGREGATE_QUARANTINED','ASSET','RENTAL_ITEM',?,?,clock_timestamp(),?)
        """,
        UUID.randomUUID(),
        generationId,
        eventId,
        subjectCabinId,
        aggregateId,
        "d".repeat(64),
        resolvedAt);
  }

  private UUID insertV2DeadLetter(UUID sourceEventId, long sourceOffset) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into dossier_sanitized_dead_letter(
          id,source_event_id,source_aggregate_id,source_topic,source_partition,source_offset,
          destination,record_key_sha256,message_sha256,failure_code,status,attempt_count,
          next_attempt_at,failed_at)
        values (?,?,?,'rwms.asset.rental-item.v1',7,?,
          'rwms.asset.rental-item.v1.dossier-projection-v1.dlt',?,?,?,'PENDING',0,
          clock_timestamp(),clock_timestamp())
        """,
        id,
        sourceEventId,
        sourceEventId,
        sourceOffset,
        "e".repeat(64),
        "f".repeat(64),
        sourceEventId == null ? "INVALID_ENVELOPE" : "PROCESSING_FAILED");
    return id;
  }

  private void insertLegacyMediaProjection(
      UUID cabinId, UUID warehouseId, UUID mediaId, int sourceOffset) {
    UUID eventId = UUID.randomUUID();
    jdbc.update(
        """
        insert into dossier_source_fact(
          id,event_id,producer,source_topic,source_partition,source_offset,kafka_key,
          aggregate_type,aggregate_id,aggregate_version,event_type,event_version,payload_sha256,
          canonical_envelope,recorded_at,correlation_id,subject_cabin_id,
          subject_warehouse_id,subject_secondary_id,ingested_at)
        values (?,?,'MEDIA','rwms.media.media.v1',0,?,?,'MEDIA',?,1,
          'media.media.ready.v1',1,?,cast(? as jsonb),clock_timestamp(),?,?,?, ?,clock_timestamp())
        """,
        UUID.randomUUID(),
        eventId,
        sourceOffset,
        mediaId,
        mediaId,
        "a".repeat(64),
        "{}",
        UUID.randomUUID(),
        cabinId,
        warehouseId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into dossier_media_projection(
          id,generation_id,cabin_id,warehouse_id,media_id,inventory_finding_id,
          media_generation,source_aggregate_version,state,source_event_id,updated_at)
        values (?,'00000000-0000-0000-0000-000000000901',?,?,?,?,0,1,'READY',?,clock_timestamp())
        """,
        UUID.randomUUID(),
        cabinId,
        warehouseId,
        mediaId,
        UUID.randomUUID(),
        eventId);
  }

  private List<String> tableNames() {
    return jdbc.queryForList(
        "select table_name from information_schema.tables where table_schema='public' order by table_name",
        String.class);
  }

  private String toRegclass(String table) {
    return jdbc.queryForObject("select to_regclass(?)", String.class, "public." + table);
  }

  private java.net.URL requireResource(String path) {
    java.net.URL resource = getClass().getClassLoader().getResource(path);
    if (resource == null) throw new IllegalStateException("Missing dossier migration resource: " + path);
    return resource;
  }

  private void assertJpaValidationStarts() {
    try (var context =
        new SpringApplicationBuilder(DossierServiceApplication.class)
            .profiles("test")
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0",
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "DOSSIER_DB_URL=" + POSTGRES.getJdbcUrl(),
                "DOSSIER_DB_USERNAME=" + POSTGRES.getUsername(),
                "DOSSIER_DB_PASSWORD=" + POSTGRES.getPassword(),
                "AUTH_ISSUER=http://issuer.invalid",
                "AUTH_AUDIENCE=rwms-services",
                "PANEL_ORIGIN=http://localhost:5173",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:65535/jwks",
                "rwms.platform.kafka.enabled=false",
                "rwms.dossier.security.dev-auth-bypass=false")
            .run()) {
      assertThat(context.getBean(EntityManagerFactory.class).isOpen()).isTrue();
      assertRepositoryOrdering(context);
      assertDltRepeatSave(context);
    }
  }

  private void assertRepositoryOrdering(org.springframework.context.ApplicationContext context) {
    DossierProjectionGeneration generation =
        context
            .getBean(DossierProjectionGenerationRepository.class)
            .saveAndFlush(DossierProjectionGeneration.building(java.time.OffsetDateTime.now()));
    DossierSourceFactRepository facts = context.getBean(DossierSourceFactRepository.class);
    DossierActivityRepository activities = context.getBean(DossierActivityRepository.class);
    UUID cabinId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID firstEventId = new UUID(0, 1);
    UUID secondEventId = new UUID(0, 2);
    UUID undatedEventId = new UUID(0, 3);
    java.time.OffsetDateTime businessTime = java.time.OffsetDateTime.now().minusDays(1);
    java.time.OffsetDateTime recordedAt = java.time.OffsetDateTime.now();

    for (int index = 0; index < 3; index++) {
      UUID eventId = List.of(firstEventId, secondEventId, undatedEventId).get(index);
      facts.saveAndFlush(
          DossierSourceFact.record(
              eventId,
              DossierProducer.ASSET,
              "rwms.asset.rental-item.v1",
              0,
              index,
              eventId,
              "RENTAL_ITEM",
              eventId,
              index,
              "asset.rental-item.changed.v1",
              1,
              "b".repeat(64),
              "{}",
              index == 2 ? null : businessTime,
              recordedAt,
              null,
              null,
              null,
              correlationId,
              null,
              cabinId,
              warehouseId,
              null,
              DossierActivityCode.CABIN_PASSPORT_CHANGED,
              recordedAt));
      activities.saveAndFlush(
          DossierActivity.project(
              UUID.randomUUID(),
              generation.getId(),
              eventId,
              cabinId,
              warehouseId,
              DossierActivityCode.CABIN_PASSPORT_CHANGED,
              DossierProducer.ASSET,
              "RENTAL_ITEM",
              eventId,
              null,
              index == 2 ? null : businessTime,
              recordedAt,
              null,
              null,
              null,
              correlationId,
              null,
              recordedAt));
    }

    Sort sort =
        Sort.by(
            Sort.Order.desc("occurredAt").nullsLast(),
            Sort.Order.desc("recordedAt"),
            Sort.Order.desc("sourceEventId"),
            Sort.Order.desc("cabinId"));
    List<DossierActivity> ordered =
        activities
            .findAll(
                (root, query, criteria) ->
                    criteria.and(
                        criteria.equal(root.get("cabinId"), cabinId),
                        criteria.equal(root.get("generationId"), generation.getId())),
                PageRequest.of(0, 10, sort))
            .getContent();

    assertThat(ordered)
        .extracting(DossierActivity::getSourceEventId)
        .containsExactly(secondEventId, firstEventId, undatedEventId);

    DossierOutboxEventRepository outbox = context.getBean(DossierOutboxEventRepository.class);
    DossierOutboxEvent first =
        DossierOutboxEvent.pending(
            UUID.randomUUID(),
            cabinId,
            0,
            firstEventId,
            "1".repeat(64),
            "{}",
            recordedAt);
    DossierOutboxEvent second =
        DossierOutboxEvent.pending(
            UUID.randomUUID(),
            cabinId,
            1,
            secondEventId,
            "2".repeat(64),
            "{}",
            recordedAt);
    outbox.saveAndFlush(first);
    outbox.saveAndFlush(second);
    assertThat(
            outbox.existsByCabinIdAndAggregateVersionLessThanAndStatusNot(
                cabinId, 1, DossierOutboxState.PUBLISHED))
        .isTrue();
    first.published(recordedAt.plusSeconds(1));
    outbox.saveAndFlush(first);
    assertThat(
            outbox.existsByCabinIdAndAggregateVersionLessThanAndStatusNot(
                cabinId, 1, DossierOutboxState.PUBLISHED))
        .isFalse();
    second.published(recordedAt.plusSeconds(2));
    outbox.saveAndFlush(second);

    UUID blockedCabin = UUID.randomUUID();
    UUID readyCabin = UUID.randomUUID();
    List<DossierSourceFact> relayFacts = new ArrayList<>();
    List<DossierOutboxEvent> relayEvents = new ArrayList<>();
    OffsetDateTime older = recordedAt.minusMinutes(10);
    for (int version = 0; version <= 100; version++) {
      UUID sourceEventId = UUID.randomUUID();
      relayFacts.add(relaySourceFact(sourceEventId, version + 100L, older));
      DossierOutboxEvent event =
          DossierOutboxEvent.pending(
              UUID.randomUUID(),
              blockedCabin,
              version,
              sourceEventId,
              "3".repeat(64),
              "{}",
              older.plusSeconds(version));
      if (version == 0) event.deadLetter();
      relayEvents.add(event);
    }
    UUID readySourceEventId = UUID.randomUUID();
    UUID readyOutboxEventId = UUID.randomUUID();
    relayFacts.add(relaySourceFact(readySourceEventId, 500L, recordedAt));
    relayEvents.add(
        DossierOutboxEvent.pending(
            readyOutboxEventId,
            readyCabin,
            0,
            readySourceEventId,
            "4".repeat(64),
            "{}",
            recordedAt));
    facts.saveAllAndFlush(relayFacts);
    outbox.saveAllAndFlush(relayEvents);

    assertThat(context.getBean(DossierRelayTransactions.class).nextOutbox())
        .get()
        .extracting(DossierOutboxEvent::getEventId)
        .isEqualTo(readyOutboxEventId);
  }

  private static DossierSourceFact relaySourceFact(
      UUID eventId, long sourceOffset, OffsetDateTime recordedAt) {
    return DossierSourceFact.record(
        eventId,
        DossierProducer.ASSET,
        "rwms.asset.rental-item.v1",
        9,
        sourceOffset,
        eventId,
        "RENTAL_ITEM",
        eventId,
        0,
        "asset.rental-item.created.v1",
        1,
        "5".repeat(64),
        "{}",
        null,
        recordedAt,
        null,
        null,
        null,
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        DossierActivityCode.CABIN_CREATED,
        recordedAt);
  }

  private void assertDltRepeatSave(org.springframework.context.ApplicationContext context) {
    DossierSanitizedDeadLetterRepository repository =
        context.getBean(DossierSanitizedDeadLetterRepository.class);
    UUID eventId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    DossierSanitizedDeadLetter first =
        DossierSanitizedDeadLetter.pending(
            eventId,
            eventId,
            "rwms.media.media.v1",
            1,
            9,
            "e".repeat(64),
            "f".repeat(64),
            DossierDltFailureCode.INVALID_PAYLOAD,
            null,
            null,
            now);
    DossierSanitizedDeadLetter repeated =
        DossierSanitizedDeadLetter.pending(
            eventId,
            eventId,
            "rwms.media.media.v1",
            1,
            9,
            "e".repeat(64),
            "f".repeat(64),
            DossierDltFailureCode.INVALID_PAYLOAD,
            null,
            null,
            now.plusSeconds(1));

    repository.saveAndFlush(first);
    repository.saveAndFlush(repeated);

    assertThat(repository.count()).isOne();
    assertThat(repository.findById(first.getId())).isPresent();
  }
}
