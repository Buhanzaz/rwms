package dev.buhanzaz.rwms.maintenance.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "rwms.maintenance.replay.include-state-in-errors=true",
    "AUTH_ISSUER=http://auth.test",
    "PANEL_ORIGIN=http://panel.test"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceEventStoreReplayIntegrationTest {
  private static final String ACTOR = """
      {"subjectId":"00000000-0000-0000-0000-0000000000d6","principalType":"USER"}
      """;

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MaintenanceEventStore events;
  @Autowired MaintenanceEventFactFactory eventFacts;
  @Autowired MaintenanceReplayVerifier replay;
  @Autowired MaintenanceProjectionSnapshotFactory projectionSnapshots;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired CatalogVersionRepository catalogs;
  @Autowired CatalogNodeRepository catalogNodes;
  @Autowired CatalogLinkRepository catalogLinks;
  @Autowired MaintenanceEstimateRepository estimates;
  @Autowired EstimateRevisionRepository revisions;
  @Autowired EstimateLineRepository lines;
  @Autowired EstimatePlanStageRepository plans;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired RepairStageRepository stages;
  @Autowired MaintenanceMediaReferenceRepository media;

  @BeforeEach
  void cleanBusinessState() {
    jdbc.execute("""
        truncate table catalog_version,maintenance_estimate,maintenance_repair,
          maintenance_media_reference,event_stream_head,projection_checkpoint
        cascade
        """);
  }

  @Test
  void completeCatalogEstimateRepairAndReworkGraphReplaysDeterministically() {
    Fixture fixture = createCompleteFixture();

    MaintenanceReplayVerifier.ReplayResult first = replay.rebuildAndVerify();
    MaintenanceReplayVerifier.ReplayResult second = replay.rebuildAndVerify();

    assertThat(second).isEqualTo(first);
    assertThat(first.streamCount()).isEqualTo(5);
    assertThat(first.checksum()).matches("^[0-9a-f]{64}$");
    assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isEqualTo(11);
    assertThat(jdbc.queryForObject("select count(*) from aggregate_snapshot", Integer.class)).isEqualTo(11);
    assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isEqualTo(11);
    assertThat(jdbc.queryForObject("select count(*) from domain_event where baseline", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select payload #>> '{event,privateBusinessText}' from domain_event
        where aggregate_type='ESTIMATE' and aggregate_id=? and aggregate_version=0
        """, String.class, fixture.estimateId().toString())).isEqualTo("local-estimate-only");
    assertThat(jdbc.queryForObject("""
        select payload #>> '{state,revisions,1,lines,0,title}' from domain_event
        where aggregate_type='ESTIMATE' and aggregate_id=? and aggregate_version=2
        """, String.class, fixture.estimateId().toString())).isEqualTo("Amended material");
    assertThat(jdbc.queryForObject("""
        select payload #>> '{state,nodes,0,media,0,safeMetadata,contentType}'
        from domain_event where aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """, String.class, fixture.catalogId().toString())).isEqualTo("image/jpeg");
    assertThat(jdbc.queryForObject("""
        select payload #>> '{state,media,0,safeMetadata,sha256}' from domain_event
        where aggregate_type='REPAIR' and aggregate_id=? and aggregate_version=0
        """, String.class, fixture.primaryRepairId().toString())).isEqualTo("c".repeat(64));
  }

  @Test
  void replayDetectsRevisionLinePlanStageMediaAndReworkChainDrift() {
    Fixture fixture = createCompleteFixture();
    MaintenanceReplayVerifier.ReplayResult stable = replay.rebuildAndVerify();

    assertDrift(
        "update catalog_node set name='drift' where catalog_version_id=? and node_id=?",
        new Object[] {fixture.catalogId(), fixture.catalogNodeId()},
        "update catalog_node set name='Structural work' where catalog_version_id=? and node_id=?",
        new Object[] {fixture.catalogId(), fixture.catalogNodeId()});
    assertDrift(
        "update estimate_revision set total_minor=total_minor+1 where estimate_id=? and revision=2",
        new Object[] {fixture.estimateId()},
        "update estimate_revision set total_minor=900 where estimate_id=? and revision=2",
        new Object[] {fixture.estimateId()});
    assertDrift(
        "update estimate_line set title='drift' where estimate_id=? and estimate_revision=2",
        new Object[] {fixture.estimateId()},
        "update estimate_line set title='Amended material' where estimate_id=? and estimate_revision=2",
        new Object[] {fixture.estimateId()});
    assertDrift(
        "update estimate_plan_stage set routing_queue_code='DRIFT' where estimate_id=? and estimate_revision=2",
        new Object[] {fixture.estimateId()},
        "update estimate_plan_stage set routing_queue_code='REPAIR-2' where estimate_id=? and estimate_revision=2",
        new Object[] {fixture.estimateId()});
    assertDrift(
        "update repair_stage set routing_queue_code='DRIFT' where repair_id=? and stage_no=0",
        new Object[] {fixture.primaryRepairId()},
        "update repair_stage set routing_queue_code='MOVE-IN' where repair_id=? and stage_no=0",
        new Object[] {fixture.primaryRepairId()});
    assertDrift(
        "update maintenance_media_reference set safe_metadata='{}'::jsonb where aggregate_type='REPAIR' and aggregate_id=?",
        new Object[] {fixture.primaryRepairId()},
        "update maintenance_media_reference set safe_metadata=jsonb_build_object('contentType','image/png','sha256',?) where aggregate_type='REPAIR' and aggregate_id=?",
        new Object[] {"c".repeat(64), fixture.primaryRepairId()});
    assertDrift(
        "update maintenance_repair set source_repair_id=? where id=?",
        new Object[] {fixture.primaryRepairId(), fixture.grandchildRepairId()},
        "update maintenance_repair set source_repair_id=? where id=?",
        new Object[] {fixture.childRepairId(), fixture.grandchildRepairId()});

    assertThat(replay.rebuildAndVerify()).isEqualTo(stable);
  }

  @Test
  void exactLiveAggregateSetRejectsBothLiveWithoutStreamAndStreamWithoutLive() {
    createCompleteFixture();
    UUID warehouseId = UUID.randomUUID();
    UUID liveOnly = inTransaction(() -> catalogs.saveAndFlush(
        CatalogVersion.draft(warehouseId, "d".repeat(64), 0, 0, "{}")).getId());

    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("liveWithoutStream")
        .hasMessageContaining(liveOnly.toString());
    inTransaction(() -> {
      catalogs.deleteById(liveOnly);
      catalogs.flush();
      return null;
    });

    UUID streamOnly = UUID.randomUUID();
    inTransaction(() -> {
      events.initialize(
          MaintenanceAggregateType.CATALOG_VERSION,
          streamOnly,
          0,
          MaintenanceEventType.CATALOG_IMPORTED,
          Map.of("catalogVersionId", streamOnly.toString()),
          catalogIntegration(streamOnly, warehouseId),
          minimalState(streamOnly, warehouseId, 0));
      return null;
    });
    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("streamWithoutLive")
        .hasMessageContaining(streamOnly.toString());
  }

  @Test
  void snapshotAndOutboxTamperingAreDetectedEvenWhenTamperedHashesAreRecalculated() {
    Fixture fixture = createCompleteFixture();
    replay.rebuildAndVerify();
    UUID eventId = jdbc.queryForObject("""
        select event_id from domain_event
        where aggregate_type='ESTIMATE' and aggregate_id=? and aggregate_version=2
        """, UUID.class, fixture.estimateId().toString());
    String snapshotHash = jdbc.queryForObject("""
        select state_sha256 from aggregate_snapshot
        where aggregate_type='ESTIMATE' and aggregate_id=? and aggregate_version=2
        """, String.class, fixture.estimateId().toString());
    jdbc.update("""
        update aggregate_snapshot set state_sha256=?
        where aggregate_type='ESTIMATE' and aggregate_id=? and aggregate_version=2
        """, "0".repeat(64), fixture.estimateId().toString());
    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("snapshot parity mismatch");
    jdbc.update("""
        update aggregate_snapshot set state_sha256=?
        where aggregate_type='ESTIMATE' and aggregate_id=? and aggregate_version=2
        """, snapshotHash, fixture.estimateId().toString());

    String originalEnvelope = jdbc.queryForObject(
        "select envelope_body::text from outbox_event where event_id=?", String.class, eventId);
    String originalHash = jdbc.queryForObject(
        "select envelope_sha256 from outbox_event where event_id=?", String.class, eventId);
    jdbc.update("""
        update outbox_event set envelope_body=jsonb_set(envelope_body,'{aggregateVersion}','99'::jsonb)
        where event_id=?
        """, eventId);
    String tamperedEnvelope = jdbc.queryForObject(
        "select envelope_body::text from outbox_event where event_id=?", String.class, eventId);
    jdbc.update(
        "update outbox_event set envelope_sha256=? where event_id=?",
        MaintenanceChecksum.sha256(tamperedEnvelope.getBytes(StandardCharsets.UTF_8)),
        eventId);
    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbox envelope identity mismatch");
    jdbc.update(
        "update outbox_event set envelope_body=?::jsonb,envelope_sha256=? where event_id=?",
        originalEnvelope,
        originalHash,
        eventId);
    assertThat(replay.rebuildAndVerify().streamCount()).isEqualTo(5);
  }

  @Test
  void failedReplayLeavesPreviouslyVerifiedShadowProjectionUntouched() {
    Fixture fixture = createCompleteFixture();
    replay.rebuildAndVerify();
    Map<String, String> before = shadowCheckpoints();

    jdbc.update(
        "update estimate_line set comment='shadow rollback canary' where estimate_id=? and estimate_revision=2",
        fixture.estimateId());
    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JPA projection parity mismatch");
    assertThat(shadowCheckpoints()).isEqualTo(before);
    jdbc.update(
        "update estimate_line set comment='revision two' where estimate_id=? and estimate_revision=2",
        fixture.estimateId());
  }

  @Test
  void concurrentCompareAndSetAllowsExactlyOneWriter() throws Exception {
    UUID aggregateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    inTransaction(() -> {
      events.initialize(
          MaintenanceAggregateType.CATALOG_VERSION,
          aggregateId,
          0,
          MaintenanceEventType.CATALOG_IMPORTED,
          Map.of("writer", "initializer"),
          catalogIntegration(aggregateId, warehouseId),
          minimalState(aggregateId, warehouseId, 0));
      return null;
    });
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> writes = List.of(
          executor.submit(() -> concurrentAppend(aggregateId, warehouseId, "first", ready, start)),
          executor.submit(() -> concurrentAppend(aggregateId, warehouseId, "second", ready, start)));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(writes.get(0).get(20, TimeUnit.SECONDS), writes.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      deleteStream(aggregateId);
    }
  }

  @Test
  void failedMultiStreamTransactionRollsBackHeadsFactsSnapshotsOutboxAndCheckpoints() {
    UUID estimateId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();

    assertThatThrownBy(() -> inTransaction(() -> {
      events.initialize(
          MaintenanceAggregateType.ESTIMATE,
          estimateId,
          0,
          MaintenanceEventType.ESTIMATE_CREATED,
          Map.of("estimateId", estimateId.toString()),
          estimateIntegration(estimateId, warehouseId, null),
          minimalState(estimateId, warehouseId, 0));
      events.initialize(
          MaintenanceAggregateType.REPAIR,
          repairId,
          0,
          MaintenanceEventType.REPAIR_CREATED,
          Map.of("repairId", repairId.toString()),
          repairIntegration(repairId, warehouseId),
          minimalState(repairId, warehouseId, 0));
      throw new IllegalStateException("rollback canary");
    })).isInstanceOf(IllegalStateException.class).hasMessageContaining("rollback canary");

    for (String table : List.of(
        "event_stream_head",
        "domain_event",
        "outbox_event",
        "aggregate_snapshot",
        "projection_checkpoint")) {
      assertThat(jdbc.queryForObject(
          "select count(*) from " + table + " where aggregate_id in (?, ?)",
          Integer.class,
          estimateId.toString(),
          repairId.toString())).isZero();
    }
  }

  private Fixture createCompleteFixture() {
    return inTransaction(() -> {
      UUID warehouseId = UUID.randomUUID();
      UUID rentalItemId = UUID.randomUUID();
      UUID parentNodeId = UUID.randomUUID();
      UUID childNodeId = UUID.randomUUID();
      UUID queueId = UUID.randomUUID();
      CatalogVersion catalog = catalogs.saveAndFlush(CatalogVersion.draft(
          warehouseId,
          "a".repeat(64),
          2,
          1,
          "{\"valid\":true,\"mapping\":\"reviewed\"}"));
      CatalogNode parent = new CatalogNode(
          parentNodeId,
          catalog.getId(),
          "CATEGORY-A",
          "CATEGORY",
          "Structural work",
          true,
          null,
          "piece",
          1200L,
          45,
          true,
          true,
          true,
          true,
          queueId,
          "REPAIR",
          "GENERAL",
          "[{\"kind\":\"equipment\",\"id\":\"EQ-1\"}]",
          "catalog-local comment",
          "[{\"mediaId\":\"" + UUID.randomUUID() + "\",\"generation\":3}]");
      CatalogNode childNode = new CatalogNode(
          childNodeId,
          catalog.getId(),
          "WORK-A",
          "WORK",
          "Panel repair",
          true,
          parentNodeId,
          "hour",
          700L,
          30,
          true,
          false,
          false,
          false,
          null,
          null,
          null,
          "[]",
          null,
          "[]");
      catalogNodes.saveAllAndFlush(List.of(parent, childNode));
      catalogLinks.saveAndFlush(new CatalogLink(
          UUID.randomUUID(),
          catalog.getId(),
          parentNodeId,
          childNodeId,
          "DEPENDENCY",
          0));
      media.saveAndFlush(new MaintenanceMediaReference(
          "CATALOG_NODE",
          parent.getRowId(),
          UUID.randomUUID(),
          3,
          "MAINTENANCE_CATALOG_NODE",
          warehouseId,
          "{\"contentType\":\"image/jpeg\",\"sha256\":\"" + "b".repeat(64) + "\"}"));
      events.initialize(
          MaintenanceAggregateType.CATALOG_VERSION,
          catalog.getId(),
          catalog.getVersion(),
          MaintenanceEventType.CATALOG_IMPORTED,
          Map.of("catalogVersionId", catalog.getId().toString(), "validationReport", Map.of("valid", true)),
          eventFacts.catalogPayload(MaintenanceEventType.CATALOG_IMPORTED, catalog),
          projectionSnapshots.catalog(catalog));

      MaintenanceEstimate estimate = estimates.saveAndFlush(MaintenanceEstimate.create(
          warehouseId,
          rentalItemId,
          7,
          catalog.getId(),
          LocalDate.of(2026, 7, 18),
          "Customer A",
          "estimate-local comment",
          ACTOR));
      revisions.saveAndFlush(new EstimateRevision(
          estimate.getId(),
          1,
          estimate.getDispatchDate(),
          estimate.getSourceParty(),
          null,
          500,
          ACTOR));
      lines.saveAndFlush(new EstimateLine(
          UUID.randomUUID(),
          estimate.getId(),
          1,
          0,
          childNodeId,
          "WORK",
          "Initial work",
          new BigDecimal("2.500000"),
          200,
          30,
          "REPAIR",
          "{\"catalogVersionId\":\"" + catalog.getId() + "\",\"nodeCode\":\"WORK-A\"}",
          "revision one",
          "[{\"mediaId\":\"" + UUID.randomUUID() + "\",\"generation\":1}]") );
      plans.saveAndFlush(new EstimatePlanStage(
          UUID.randomUUID(),
          estimate.getId(),
          1,
          0,
          RepairStageKind.REPAIR_WORK,
          queueId,
          "REPAIR-1",
          "GENERAL",
          OffsetDateTime.of(2026, 7, 19, 10, 0, 0, 0, ZoneOffset.UTC)));
      media.saveAndFlush(new MaintenanceMediaReference(
          "ESTIMATE",
          estimate.getId(),
          UUID.randomUUID(),
          2,
          "MAINTENANCE_ESTIMATE",
          warehouseId,
          "{\"contentType\":\"image/webp\",\"width\":1600}"));
      events.initialize(
          MaintenanceAggregateType.ESTIMATE,
          estimate.getId(),
          estimate.getVersion(),
          MaintenanceEventType.ESTIMATE_CREATED,
          Map.of("estimateId", estimate.getId().toString(), "privateBusinessText", "local-estimate-only"),
          eventFacts.estimatePayload(MaintenanceEventType.ESTIMATE_CREATED, estimate, 1),
          projectionSnapshots.estimate(estimate));

      MaintenanceRepair primary = repairs.saveAndFlush(MaintenanceRepair.primary(
          warehouseId,
          rentalItemId,
          7,
          estimate.getId(),
          RepairOrigin.ESTIMATE,
          estimate.getDispatchDate(),
          estimate.getSourceParty(),
          ACTOR));
      stages.saveAllAndFlush(List.of(
          new RepairStage(
              UUID.randomUUID(),
              primary.getId(),
              0,
              RepairStageKind.MOVE_TO_REPAIR,
              UUID.randomUUID(),
              "MOVE-IN",
              "MOVEMENT",
              null),
          new RepairStage(
              UUID.randomUUID(),
              primary.getId(),
              1,
              RepairStageKind.REPAIR_WORK,
              queueId,
              "REPAIR",
              "GENERAL",
              OffsetDateTime.of(2026, 7, 20, 12, 0, 0, 0, ZoneOffset.UTC))));
      media.saveAndFlush(new MaintenanceMediaReference(
          "REPAIR",
          primary.getId(),
          UUID.randomUUID(),
          5,
          "MAINTENANCE_REPAIR",
          warehouseId,
          "{\"contentType\":\"image/png\",\"sha256\":\"" + "c".repeat(64) + "\"}"));
      events.initialize(
          MaintenanceAggregateType.REPAIR,
          primary.getId(),
          primary.getVersion(),
          MaintenanceEventType.REPAIR_CREATED,
          Map.of("repairId", primary.getId().toString(), "privateBusinessText", "primary-local-only"),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_CREATED,
              primary,
              stages.findAllByRepairIdOrderByStageNo(primary.getId())),
          projectionSnapshots.repair(primary));

      estimate.complete(primary.getId());
      estimate = estimates.saveAndFlush(estimate);
      events.append(
          MaintenanceAggregateType.ESTIMATE,
          estimate.getId(),
          0,
          MaintenanceEventType.ESTIMATE_COMPLETED,
          Map.of("estimateId", estimate.getId().toString(), "outcome", "NON_EMPTY"),
          eventFacts.estimatePayload(MaintenanceEventType.ESTIMATE_COMPLETED, estimate, 1),
          projectionSnapshots.estimate(estimate));

      primary.queue(UUID.randomUUID(), 0, 101, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
      primary.completeForAcceptance();
      primary = repairs.saveAndFlush(primary);
      events.append(
          MaintenanceAggregateType.REPAIR,
          primary.getId(),
          0,
          MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE,
          Map.of("repairId", primary.getId().toString(), "outcome", "PENDING_ACCEPTANCE"),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE,
              primary,
              stages.findAllByRepairIdOrderByStageNo(primary.getId())),
          projectionSnapshots.repair(primary));

      MaintenanceRepair child = repairs.saveAndFlush(MaintenanceRepair.rework(
          primary, "first rework", ACTOR));
      stages.saveAndFlush(new RepairStage(
          UUID.randomUUID(),
          child.getId(),
          0,
          RepairStageKind.REPAIR_WORK,
          queueId,
          "REWORK-1",
          "GENERAL",
          null));
      events.initialize(
          MaintenanceAggregateType.REPAIR,
          child.getId(),
          child.getVersion(),
          MaintenanceEventType.REPAIR_REWORK_CREATED,
          Map.of("repairId", child.getId().toString(), "reason", "first rework"),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_REWORK_CREATED,
              child,
              stages.findAllByRepairIdOrderByStageNo(child.getId())),
          projectionSnapshots.repair(child));
      primary.enterRework();
      primary = repairs.saveAndFlush(primary);
      events.append(
          MaintenanceAggregateType.REPAIR,
          primary.getId(),
          1,
          MaintenanceEventType.REPAIR_REWORK_CREATED,
          Map.of("repairId", primary.getId().toString(), "childRepairId", child.getId().toString()),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_REWORK_CREATED,
              primary,
              stages.findAllByRepairIdOrderByStageNo(primary.getId())),
          projectionSnapshots.repair(primary));

      child.queue(UUID.randomUUID(), 0, 102, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
      child.completeForAcceptance();
      child = repairs.saveAndFlush(child);
      events.append(
          MaintenanceAggregateType.REPAIR,
          child.getId(),
          0,
          MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE,
          Map.of("repairId", child.getId().toString(), "outcome", "PENDING_ACCEPTANCE"),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE,
              child,
              stages.findAllByRepairIdOrderByStageNo(child.getId())),
          projectionSnapshots.repair(child));

      MaintenanceRepair grandchild = repairs.saveAndFlush(MaintenanceRepair.rework(
          child, "nested rework", ACTOR));
      stages.saveAndFlush(new RepairStage(
          UUID.randomUUID(),
          grandchild.getId(),
          0,
          RepairStageKind.REPAIR_WORK,
          queueId,
          "REWORK-2",
          "GENERAL",
          null));
      events.initialize(
          MaintenanceAggregateType.REPAIR,
          grandchild.getId(),
          grandchild.getVersion(),
          MaintenanceEventType.REPAIR_REWORK_CREATED,
          Map.of("repairId", grandchild.getId().toString(), "reason", "nested rework"),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_REWORK_CREATED,
              grandchild,
              stages.findAllByRepairIdOrderByStageNo(grandchild.getId())),
          projectionSnapshots.repair(grandchild));
      child.enterRework();
      child = repairs.saveAndFlush(child);
      events.append(
          MaintenanceAggregateType.REPAIR,
          child.getId(),
          1,
          MaintenanceEventType.REPAIR_REWORK_CREATED,
          Map.of("repairId", child.getId().toString(), "childRepairId", grandchild.getId().toString()),
          eventFacts.repairPayload(
              MaintenanceEventType.REPAIR_REWORK_CREATED,
              child,
              stages.findAllByRepairIdOrderByStageNo(child.getId())),
          projectionSnapshots.repair(child));

      estimate.replaceCompletedMetadata(
          LocalDate.of(2026, 7, 21), "Customer B", estimate.getComment(), primary.getId());
      estimate = estimates.saveAndFlush(estimate);
      revisions.saveAndFlush(new EstimateRevision(
          estimate.getId(),
          2,
          estimate.getDispatchDate(),
          estimate.getSourceParty(),
          "scope corrected",
          900,
          ACTOR));
      lines.saveAndFlush(new EstimateLine(
          UUID.randomUUID(),
          estimate.getId(),
          2,
          0,
          childNodeId,
          "MATERIAL",
          "Amended material",
          new BigDecimal("3.000000"),
          300,
          20,
          "REPAIR-2",
          "{\"catalogVersionId\":\"" + catalog.getId() + "\",\"nodeCode\":\"WORK-A\"}",
          "revision two",
          "[{\"mediaId\":\"" + UUID.randomUUID() + "\",\"generation\":2}]") );
      plans.saveAndFlush(new EstimatePlanStage(
          UUID.randomUUID(),
          estimate.getId(),
          2,
          0,
          RepairStageKind.REPAIR_WORK,
          queueId,
          "REPAIR-2",
          "GENERAL",
          OffsetDateTime.of(2026, 7, 22, 9, 0, 0, 0, ZoneOffset.UTC)));
      events.append(
          MaintenanceAggregateType.ESTIMATE,
          estimate.getId(),
          1,
          MaintenanceEventType.ESTIMATE_AMENDED,
          Map.of("estimateId", estimate.getId().toString(), "reason", "scope corrected"),
          eventFacts.estimatePayload(MaintenanceEventType.ESTIMATE_AMENDED, estimate, 1),
          projectionSnapshots.estimate(estimate));

      return new Fixture(
          catalog.getId(),
          parentNodeId,
          estimate.getId(),
          primary.getId(),
          child.getId(),
          grandchild.getId());
    });
  }

  private boolean concurrentAppend(
      UUID aggregateId,
      UUID warehouseId,
      String writer,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent CAS test did not start");
    }
    try {
      inTransaction(() -> {
        events.append(
            MaintenanceAggregateType.CATALOG_VERSION,
            aggregateId,
            0,
            MaintenanceEventType.CATALOG_CHANGED,
            Map.of("writer", writer),
            catalogIntegration(aggregateId, warehouseId),
            minimalState(aggregateId, warehouseId, 1));
        return null;
      });
      return true;
    } catch (MaintenanceConflictException expected) {
      return false;
    }
  }

  private void assertDrift(
      String corruptionSql,
      Object[] corruptionArguments,
      String restoreSql,
      Object[] restoreArguments) {
    assertThat(jdbc.update(corruptionSql, corruptionArguments)).isOne();
    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JPA projection parity mismatch");
    assertThat(jdbc.update(restoreSql, restoreArguments)).isOne();
    assertThat(replay.rebuildAndVerify().streamCount()).isEqualTo(5);
  }

  private Map<String, String> shadowCheckpoints() {
    Map<String, String> result = new LinkedHashMap<>();
    jdbc.query("""
        select aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at
        from projection_checkpoint where projection_name=? order by aggregate_type,aggregate_id
        """, (resultSet, row) -> Map.entry(
        resultSet.getString("aggregate_type") + ":" + resultSet.getString("aggregate_id"),
        resultSet.getLong("aggregate_version") + ":"
            + resultSet.getString("projection_sha256").trim() + ":"
            + resultSet.getObject("updated_at", OffsetDateTime.class)),
        MaintenanceReplayVerifier.SHADOW_PROJECTION).forEach(entry ->
        result.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(result);
  }

  private void deleteStream(UUID aggregateId) {
    inTransaction(() -> {
      for (String table : List.of(
          "outbox_event", "aggregate_snapshot", "domain_event", "projection_checkpoint")) {
        jdbc.update("delete from " + table + " where aggregate_id=?", aggregateId.toString());
      }
      jdbc.update("delete from event_stream_head where aggregate_id=?", aggregateId.toString());
      return null;
    });
  }

  private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
    return new TransactionTemplate(transactionManager).execute(status -> {
      try {
        return action.call();
      } catch (RuntimeException exception) {
        throw exception;
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    });
  }

  private static Map<String, Object> minimalState(UUID id, UUID warehouseId, long version) {
    return Map.of(
        "id", id.toString(),
        "version", version,
        "warehouseId", warehouseId.toString(),
        "lifecycle", "DRAFT",
        "nodes", List.of(),
        "links", List.of(),
        "media", List.of());
  }

  private static Map<String, Object> catalogIntegration(UUID id, UUID warehouseId) {
    return Map.of(
        "catalogVersionId", id.toString(),
        "warehouseId", warehouseId.toString(),
        "lifecycle", CatalogVersionState.DRAFT.name(),
        "sourceSha256", "e".repeat(64),
        "nodeCount", 0,
        "linkCount", 0,
        "validationReportSha256", "f".repeat(64));
  }

  private static Map<String, Object> estimateIntegration(
      UUID id, UUID warehouseId, UUID repairId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("estimateId", id.toString());
    result.put("warehouseId", warehouseId.toString());
    result.put("rentalItemId", UUID.randomUUID().toString());
    result.put("lifecycle", "DRAFT");
    result.put("revision", 1);
    result.put("dispatchDate", "2026-07-18");
    result.put("lineCount", 0);
    result.put("completionKind", "NOT_COMPLETED");
    result.put("repairId", repairId == null ? null : repairId.toString());
    return result;
  }

  private static Map<String, Object> repairIntegration(UUID id, UUID warehouseId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("repairId", id.toString());
    result.put("rootRepairId", id.toString());
    result.put("sourceRepairId", null);
    result.put("estimateId", null);
    result.put("warehouseId", warehouseId.toString());
    result.put("rentalItemId", UUID.randomUUID().toString());
    result.put("origin", "DIRECT_REPAIR");
    result.put("kind", "PRIMARY");
    result.put("executionState", "DRAFT");
    result.put("acceptanceState", "NOT_READY");
    result.put("dispatchDate", "2026-07-18");
    result.put("stages", List.of());
    return result;
  }

  private record Fixture(
      UUID catalogId,
      UUID catalogNodeId,
      UUID estimateId,
      UUID primaryRepairId,
      UUID childRepairId,
      UUID grandchildRepairId) {}
}
