package dev.buhanzaz.rwms.taskboard;
import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.EvidenceReservationRequest;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerContext;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerDeviceRegistrationRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerMediaReference;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.WorkerMediaEventProcessor;
import dev.buhanzaz.rwms.taskboard.push.WorkerPushClient;
import dev.buhanzaz.rwms.taskboard.push.WorkerPushDispatcher;
import dev.buhanzaz.rwms.taskboard.push.WorkerPushOutbox;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.KpiSettingsService;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardEntryOwnerProofReconciler;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardEntryOwnerProofService;
import dev.buhanzaz.rwms.taskboard.service.WorkerOfflineLeaseCodec;
import dev.buhanzaz.rwms.taskboard.service.WorkerTaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WorkerEvidenceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000601");

  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;
  @Autowired TaskBoardService board;
  @Autowired TaskBoardEntryOwnerProofService ownerProofs;
  @Autowired WorkerTaskBoardService workerBoard;
  @Autowired KpiSettingsService kpiSettings;
  @Autowired WorkerOfflineLeaseCodec leases;
  @Autowired WorkerMediaEventProcessor mediaEvents;
  @Autowired WorkerPushOutbox pushOutbox;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void feedCountsTheCompleteRouteAndEvidenceDeclarationsKeepExactFormatLimits() {
    var workerClass =
        registry.createClass(
            new WorkerClassRequest(0L, "Пакетные фото", null, null, 10, true));
    var firstDefinition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "Первый этап", null, QueueType.REPAIR));
    var secondDefinition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "Второй этап", null, QueueType.REPAIR));
    var thirdDefinition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "Третий этап", null, QueueType.REPAIR));
    for (UUID definitionId :
        List.of(firstDefinition.id(), secondDefinition.id(), thirdDefinition.id())) {
      QueueRegistryTestFixtures.create(
          registry,
          jdbc,
          WAREHOUSE,
          new QueueFixtureRequest(
              0L,
              definitionId,
              true,
              false,
              false,
              null,
              null,
              false,
              null,
              List.of(new QueueBindingRequest(workerClass.id(), false))));
    }
    var worker =
        workforce.createWorker(
            WAREHOUSE,
            new WorkerRequest(
                0L,
                "Оператор пакетных фото",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    jdbc.update("update worker set app_login='worker-bundle-photo' where id=?", worker.id());
    var group =
        workforce.createGroup(
            WAREHOUSE,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Группа пакетных фото",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), true))));
    workforce.setCurrentGroup(
        WAREHOUSE,
        worker.id(),
        new SetCurrentGroupRequest(worker.version(), group.id()));

    var created =
        board.createTask(
            WAREHOUSE,
            new CreateBoardTaskRequest(
                null,
                "Маршрут с пакетным фото",
                "БТ-77",
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(firstDefinition.id(), "Первый", null),
                    new RouteStepRequest(secondDefinition.id(), "Второй", null),
                    new RouteStepRequest(thirdDefinition.id(), "Третий", null))));
    BoardEntryDto entry =
        created.columns().stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    var routeFeed =
        workerBoard.feed(worker.id(), WAREHOUSE, null, 50).feed().categories().stream()
            .flatMap(category -> category.entries().stream())
            .filter(candidate -> candidate.taskId().equals(entry.taskId()))
            .sorted(java.util.Comparator.comparingInt(candidate -> candidate.routeIndex()))
            .toList();
    assertThat(routeFeed)
        .extracting(candidate -> candidate.entryType())
        .containsExactly("REAL");
    assertThat(routeFeed)
        .allSatisfy(candidate -> assertThat(candidate.pinned()).isFalse());
    var feedEntry =
        routeFeed.stream()
            .filter(candidate -> candidate.entryId().equals(entry.id()))
            .findFirst()
            .orElseThrow();
    assertThat(feedEntry.routeIndex()).isZero();
    assertThat(feedEntry.routeStepCount()).isEqualTo(3);
    assertThat(feedEntry.entryType()).isEqualTo("REAL");
    assertThat(feedEntry.pinned()).isFalse();

    var maintenanceRegistration =
        board.registerExternalTask(
            "maintenance-service",
            new RegisterExternalTaskRequest(
                WAREHOUSE,
                UUID.randomUUID(),
                "Ремонт одним пакетом на очередь",
                "БТ-78",
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(firstDefinition.id(), "Первая работа", null),
                    new RouteStepRequest(firstDefinition.id(), "Вторая работа", null),
                    new RouteStepRequest(secondDefinition.id(), "Другая очередь", null)),
                null,
                null,
                new TaskSourceReferenceDto(
                    TaskSourceType.MAINTENANCE_REPAIR, UUID.randomUUID()),
                TaskLane.SCHEDULED));
    var maintenanceFeedEntry =
        workerBoard.feed(worker.id(), WAREHOUSE, null, 50).feed().categories().stream()
            .flatMap(category -> category.entries().stream())
            .filter(candidate -> candidate.taskId().equals(maintenanceRegistration.taskId()))
            .findFirst()
            .orElseThrow();
    assertThat(maintenanceFeedEntry.routeIndex()).isZero();
    assertThat(maintenanceFeedEntry.routeStepCount()).isEqualTo(2);

    BoardEntryDto active =
        board.take(
            WAREHOUSE,
            entry.id(),
            new TakeEntryRequest(entry.version(), null, worker.id()),
            worker.id());
    var context = workerBoard.context(worker.id(), WAREHOUSE);
    UUID expiredResultLeaseId =
        leases
            .issue(
                worker.id(),
                WAREHOUSE,
                workerBoard.revision(),
                context.serverTime().minusDays(2))
            .id();
    UUID jpegOperationId = UUID.randomUUID();
    UUID jpegEvidenceId = UUID.randomUUID();
    var jpegRequest =
        new EvidenceReservationRequest(
            jpegOperationId,
            jpegEvidenceId,
            active.routeIndex(),
            context.serverTime(),
            expiredResultLeaseId,
            "image/jpeg",
            15_728_640,
            "a".repeat(64));
    var jpeg =
        workerBoard.reserveEvidence(
            worker.id(), WAREHOUSE, active.id(), jpegOperationId.toString(), jpegRequest);
    var jpegReplay =
        workerBoard.reserveEvidence(
            worker.id(),
            WAREHOUSE,
            active.id(),
            jpegOperationId.toString(),
            new EvidenceReservationRequest(
                jpegOperationId,
                jpegEvidenceId,
                active.routeIndex(),
                context.serverTime(),
                expiredResultLeaseId,
                "IMAGE/JPEG",
                15_728_640,
                "a".repeat(64)));
    assertThat(jpegReplay).isEqualTo(jpeg);

    UUID bundleOperationId = UUID.randomUUID();
    UUID bundleEvidenceId = UUID.randomUUID();
    var bundle =
        workerBoard.reserveEvidence(
            worker.id(),
            WAREHOUSE,
            active.id(),
            bundleOperationId.toString(),
            new EvidenceReservationRequest(
                bundleOperationId,
                bundleEvidenceId,
                active.routeIndex(),
                context.serverTime(),
                context.offlineLease().id(),
                "image/webp",
                1_048_576,
                "b".repeat(64)));
    assertThat(bundle.contentType()).isEqualTo("image/webp");
    var bundleReplay =
        workerBoard.reserveEvidence(
            worker.id(),
            WAREHOUSE,
            active.id(),
            bundleOperationId.toString(),
            new EvidenceReservationRequest(
                bundleOperationId,
                bundleEvidenceId,
                active.routeIndex(),
                context.serverTime(),
                context.offlineLease().id(),
                "IMAGE/WEBP",
                1_048_576,
                "b".repeat(64)));
    assertThat(bundleReplay).isEqualTo(bundle);
    assertThatThrownBy(
            () ->
                workerBoard.reserveEvidence(
                    worker.id(),
                    WAREHOUSE,
                    active.id(),
                    bundleOperationId.toString(),
                    new EvidenceReservationRequest(
                        bundleOperationId,
                        bundleEvidenceId,
                        active.routeIndex(),
                        context.serverTime(),
                        context.offlineLease().id(),
                        "image/webp",
                        1_048_576,
                        "d".repeat(64))))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("уже использован другой фотографией");
    assertThat(
            jdbc.queryForMap(
                """
                select content_type,size_bytes,sha256
                  from worker_task_evidence
                 where evidence_id=?
                """,
                bundleEvidenceId))
        .containsEntry("content_type", "image/webp")
        .containsEntry("size_bytes", 1_048_576L)
        .containsEntry("sha256", "b".repeat(64));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_task_evidence where entry_id=?",
                Integer.class,
                active.id()))
        .isEqualTo(2);

    assertInvalidEvidenceDeclaration(
        worker,
        active,
        context,
        "image/webp",
        1_048_577,
        "1048576");
    assertInvalidEvidenceDeclaration(
        worker,
        active,
        context,
        "image/jpeg",
        15_728_641,
        "15728640");
    assertInvalidEvidenceDeclaration(worker, active, context, "image/png", 128, "image/webp");
  }

  @Test
  void pendingEvidenceSurvivesPanelCompletionAndReadyMediaClosesOwnerProof() {
    var workerClass =
        registry.createClass(
            new WorkerClassRequest(
                0L, "Фото", null, null, 10, true));
    var definition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(0L, "Ремонт", null, QueueType.REPAIR));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            WAREHOUSE,
            new QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                false,
                false,
                null,
                null,
                false,
                null,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            WAREHOUSE,
            new WorkerRequest(
                0L,
                "Фотограф",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    jdbc.update(
        "update worker set app_login='worker-photo' where id=?",
        worker.id());
    var group =
        workforce.createGroup(
            WAREHOUSE,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Фото-группа",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), true))));
    workforce.setCurrentGroup(
        WAREHOUSE,
        worker.id(),
        new SetCurrentGroupRequest(worker.version(), group.id()));
    kpiSettings.savePalette(
        WAREHOUSE,
        new SaveKpiPaletteRequest(
            0,
            List.of(
                new KpiPaletteRangeRequest(0, 60, "#DC2626"),
                new KpiPaletteRangeRequest(60, 85, "#EAB308"),
                new KpiPaletteRangeRequest(85, 100, "#16A34A")),
            "#7F1D1D"));
    var context = workerBoard.context(worker.id(), WAREHOUSE);
    assertThat(context.categories())
        .singleElement()
        .satisfies(category -> assertThat(category.groupIds()).containsExactly(group.id()));
    assertThat(context.kpiPalette().ranges())
        .extracting(
            dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerKpiPaletteRange::fromPercent,
            dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerKpiPaletteRange::toPercent,
            dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerKpiPaletteRange::color)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0, 60, "#DC2626"),
            org.assertj.core.groups.Tuple.tuple(60, 85, "#EAB308"),
            org.assertj.core.groups.Tuple.tuple(85, 100, "#16A34A"));
    assertThat(context.kpiPalette().overdueColor()).isEqualTo("#7F1D1D");
    UUID coverMediaId = UUID.randomUUID();
    UUID sourceMediaId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    OffsetDateTime sourceRecordedAt =
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
    var created =
        board.createTask(
            WAREHOUSE,
            new CreateBoardTaskRequest(
                null,
                "Результат ремонта",
                "БТ-42",
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(
                        queue.definitionId(),
                        "Сфотографировать",
                        null,
                        List.of(
                            new TaskWorkSnapshotRequest(
                                workId,
                                "Замена профлиста",
                                2,
                                "шт.",
                                60,
                                "Установить без повреждения покрытия",
                                List.of(sourceMediaId))),
                        List.of(
                            new TaskMaterialSnapshotRequest(
                                materialId, "Краска", 2.5, "л")),
                        List.of(
                            new TaskCommentSnapshotRequest(
                                UUID.randomUUID(),
                                "Проверить внешний угол",
                                "Диспетчер",
                                sourceRecordedAt)),
                        List.of(
                            new TaskSourceMediaSnapshotRequest(
                                coverMediaId,
                                1,
                                "image/jpeg",
                                sourceRecordedAt.minusMinutes(2),
                                sourceRecordedAt.minusMinutes(1)),
                            new TaskSourceMediaSnapshotRequest(
                                sourceMediaId,
                                2,
                                "image/jpeg",
                                sourceRecordedAt.minusMinutes(1),
                                sourceRecordedAt))))));
    BoardEntryDto entry =
        created.columns().stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    assertThat(latestProofContains(entry.id(), "allowedWorkerIds", worker.id())).isFalse();
    assertThat(latestProofContains(entry.id(), "readerWorkerIds", worker.id())).isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select aggregate_version
                  from domain_event
                 where aggregate_type='QUEUE_ENTRY' and aggregate_id=?
                 order by aggregate_version
                 limit 1
                """,
                Long.class,
                entry.id().toString()))
        .isZero();
    board.take(
        WAREHOUSE,
        entry.id(),
        new TakeEntryRequest(entry.version(), null, worker.id()),
        worker.id());

    var deviceRequest =
        new WorkerDeviceRegistrationRequest(
            "FCM", "test-provider-token", "0.1.4", 36, "ru-RU");
    var registered =
        workerBoard.registerDevice(
            worker.id(), WAREHOUSE, "test-installation", deviceRequest);
    var refreshed =
        workerBoard.registerDevice(
            worker.id(), WAREHOUSE, "test-installation", deviceRequest);
    assertThat(registered.created()).isTrue();
    assertThat(refreshed.created()).isFalse();
    assertThat(refreshed.registration().provider()).isEqualTo("FCM");
    workerBoard.unregisterDevice(worker.id(), WAREHOUSE, "test-installation");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_device_registration", Integer.class))
        .isZero();

    OffsetDateTime capturedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
    UUID leaseId =
        leases
            .issue(
                worker.id(),
                WAREHOUSE,
                workerBoard.revision(),
                capturedAt.minusSeconds(1))
            .id();
    UUID operationId = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    var request =
        new EvidenceReservationRequest(
            operationId,
            evidenceId,
            entry.routeIndex(),
            capturedAt,
            leaseId,
            "image/jpeg",
            128,
            "a".repeat(64));

    var reserved =
        workerBoard.reserveEvidence(
            worker.id(), WAREHOUSE, entry.id(), operationId.toString(), request);
    var storedReservation =
        jdbc.queryForMap(
            """
            select evidence_id,operation_id,entry_id,worker_id,warehouse_id,route_index,
                   captured_at,content_type,size_bytes,sha256
              from worker_task_evidence
             where evidence_id=?
            """,
            evidenceId);
    assertThat(storedReservation.get("evidence_id")).isEqualTo(evidenceId);
    assertThat(storedReservation.get("operation_id")).isEqualTo(operationId);
    assertThat(storedReservation.get("entry_id")).isEqualTo(entry.id());
    assertThat(storedReservation.get("worker_id")).isEqualTo(worker.id());
    assertThat(storedReservation.get("warehouse_id")).isEqualTo(WAREHOUSE);
    assertThat(storedReservation.get("route_index")).isEqualTo(entry.routeIndex());
    assertThat(
            jdbc.queryForObject(
                "select captured_at=? from worker_task_evidence where evidence_id=?",
                Boolean.class,
                capturedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                evidenceId))
        .isTrue();
    assertThat(latestProofContains(entry.id(), "readerWorkerIds", worker.id())).isTrue();
    assertThat(storedReservation.get("content_type")).isEqualTo("image/jpeg");
    assertThat(storedReservation.get("size_bytes")).isEqualTo(128L);
    assertThat(storedReservation.get("sha256")).isEqualTo("a".repeat(64));
    var replayed =
        workerBoard.reserveEvidence(
            worker.id(), WAREHOUSE, entry.id(), operationId.toString(), request);

    assertThat(reserved.state()).isEqualTo("RESERVED");
    assertThat(replayed).isEqualTo(reserved);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_task_evidence where evidence_id=?",
                Integer.class,
                evidenceId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.ENTRY_OWNER_PROOF_CHANGED))
        .isEqualTo(4);

    BoardEntryDto activeEntry = board.entry(WAREHOUSE, entry.id());
    BoardEntryDto completed =
        board.complete(
            WAREHOUSE,
            entry.id(),
            new VersionCommand(activeEntry.version()),
            worker.id());
    assertThat(completed.status().name()).isEqualTo("DONE");
    assertThat(completed.assignments())
        .allSatisfy(
            assignment -> assertThat(assignment.status().name()).isEqualTo("DONE"));
    assertThat(latestOwnerProofActive(entry.id())).isTrue();

    UUID mediaId = UUID.randomUUID();
    UUID mediaEventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    OffsetDateTime readyAt = OffsetDateTime.now(ZoneOffset.UTC);
    String readyEvent =
        """
        {
          "envelopeVersion":2,
          "eventId":"%s",
          "eventType":"media.media.ready.v1",
          "eventVersion":1,
          "occurredAt":null,
          "recordedAt":"%s",
          "producer":"media-service",
          "aggregateType":"MEDIA",
          "aggregateId":"%s",
          "aggregateVersion":2,
          "correlation":{"correlationId":"%s","causationId":null},
          "actorRef":{"subjectId":"%s","principalType":"WORKER","profileRevision":null},
          "payload":{
            "mediaId":"%s",
            "ownerType":"TASK_BOARD_ENTRY",
            "ownerId":"%s",
            "warehouseId":"%s",
            "clientReferenceId":"%s",
            "kind":"IMAGE",
            "status":"READY",
            "generation":1,
            "rotationDegrees":0
          }
        }
        """
            .formatted(
                mediaEventId,
                readyAt,
                mediaId,
                correlationId,
                worker.id(),
                mediaId,
                entry.id(),
                WAREHOUSE,
                evidenceId);
    mediaEvents.process(readyEvent.getBytes(StandardCharsets.UTF_8));

    var detail = workerBoard.detail(worker.id(), WAREHOUSE, entry.id());
    assertThat(detail.taskObject().label()).isEqualTo("БТ-42");
    assertThat(detail.works())
        .singleElement()
        .satisfies(
            work -> {
              assertThat(work.id()).isEqualTo(workId);
              assertThat(work.name()).isEqualTo("Замена профлиста");
              assertThat(work.quantity()).isEqualTo(2);
              assertThat(work.unit()).isEqualTo("шт.");
              assertThat(work.durationMinutes()).isEqualTo(60);
              assertThat(work.comment()).isEqualTo("Установить без повреждения покрытия");
              assertThat(work.sourceMediaIds()).containsExactly(sourceMediaId);
            });
    assertThat(detail.materials())
        .singleElement()
        .satisfies(
            material -> {
              assertThat(material.id()).isEqualTo(materialId);
              assertThat(material.name()).isEqualTo("Краска");
              assertThat(material.quantity()).isEqualTo(2.5);
              assertThat(material.unit()).isEqualTo("л");
            });
    assertThat(detail.comments())
        .singleElement()
        .satisfies(comment -> assertThat(comment.text()).isEqualTo("Проверить внешний угол"));
    assertThat(detail.sourceMedia())
        .extracting(WorkerMediaReference::mediaId)
        .containsExactly(coverMediaId, sourceMediaId);
    assertThat(detail.sourceMedia())
        .filteredOn(source -> source.mediaId().equals(sourceMediaId))
        .singleElement()
        .satisfies(
            source -> {
              assertThat(source.mediaId()).isEqualTo(sourceMediaId);
              assertThat(source.generation()).isEqualTo(2);
              assertThat(source.readPath())
                  .isEqualTo(
                      "/api/media/v1/assets/"
                          + sourceMediaId
                          + "/original?ownerType=TASK_BOARD_ENTRY&ownerId="
                          + entry.id()
                          + "&warehouseId="
                          + WAREHOUSE
                          + "&context=WORK_RESULT&generation=2");
            });
    assertThat(detail.evidence()).singleElement().satisfies(
        evidence -> {
          assertThat(evidence.state()).isEqualTo("READY");
          assertThat(evidence.mediaId()).isEqualTo(mediaId);
          assertThat(evidence.mediaGeneration()).isOne();
          assertThat(evidence.contentType()).isEqualTo("image/jpeg");
          assertThat(evidence.readPath())
              .isEqualTo(
                  "/api/media/v1/assets/"
                      + mediaId
                      + "/original?ownerType=TASK_BOARD_ENTRY&ownerId="
                      + entry.id()
                      + "&warehouseId="
                      + WAREHOUSE
                      + "&context=WORK_RESULT&generation=1");
          assertThat(evidence.thumbnailPath()).contains("/variants/SMALL/content");
        });
    assertThat(detail.status()).isEqualTo("DONE");
    assertThat(detail.completionAllowed()).isFalse();
    assertThat(latestOwnerProofActive(entry.id())).isFalse();
    assertThat(
            jdbc.queryForObject(
                """
                select payload->'allowedWorkerIds' @> ?::jsonb
                  from domain_event
                 where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
                   and aggregate_id=?
                 order by aggregate_version desc
                 limit 1
                """,
                Boolean.class,
                "[\"" + worker.id() + "\"]",
                entry.id().toString()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.TASK_EVIDENCE_READY))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select aggregate_version
                  from domain_event
                 where aggregate_type='TASK_EVIDENCE' and aggregate_id=?
                """,
                Long.class,
                evidenceId.toString()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from domain_event
                 where event_type=?
                   and payload @> ?::jsonb
                """,
                Integer.class,
                TaskBoardEventTypes.ENTRY_OWNER_PROOF_CHANGED,
                """
                {"sourceMediaReferences":[{"mediaId":"%s","generation":2}]}
                """
                    .formatted(sourceMediaId)))
        .isPositive();
  }

  @Test
  void waitingTaskReaderAudienceReconcilesWithoutGrantingResultUpload() {
    var workerClass =
        registry.createClass(
            new WorkerClassRequest(0L, "Читатели фото", null, null, 10, true));
    var definition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "Очередь фото", null, QueueType.REPAIR));
    var queue =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            WAREHOUSE,
            new QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                false,
                false,
                null,
                null,
                false,
                null,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var first =
        workforce.createWorker(
            WAREHOUSE,
            new WorkerRequest(
                0L,
                "Первый читатель",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var created =
        board.createTask(
            WAREHOUSE,
            new CreateBoardTaskRequest(
                null,
                "Ожидающее задание",
                "БТ-READ",
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(
                        queue.definitionId(),
                        "Открыть исходное фото",
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()))));
    BoardEntryDto entry =
        created.columns().stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    assertThat(workerBoard.detail(first.id(), WAREHOUSE, entry.id()).status())
        .isEqualTo("WAITING");
    assertThat(latestProofContains(entry.id(), "readerWorkerIds", first.id())).isTrue();
    assertThat(latestProofContains(entry.id(), "allowedWorkerIds", first.id())).isFalse();

    var second =
        workforce.createWorker(
            WAREHOUSE,
            new WorkerRequest(
                0L,
                "Второй читатель",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    assertThat(workerBoard.detail(second.id(), WAREHOUSE, entry.id()).status())
        .isEqualTo("WAITING");
    assertThat(latestProofContains(entry.id(), "readerWorkerIds", second.id())).isFalse();

    var reconciler = new TaskBoardEntryOwnerProofReconciler(jdbc, ownerProofs);
    reconciler.reconcile();
    long reconciledVersion = latestProofVersion(entry.id());
    assertThat(latestProofContains(entry.id(), "readerWorkerIds", second.id())).isTrue();
    assertThat(latestProofContains(entry.id(), "allowedWorkerIds", second.id())).isFalse();
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    try {
      reconciler.reconcile();
      assertThat(latestProofVersion(entry.id())).isEqualTo(reconciledVersion);
      assertThat(statistics.getEntityLoadCount())
          .as("an unchanged audience must not rehydrate every open task and worker")
          .isZero();
    } finally {
      statistics.clear();
      statistics.setStatisticsEnabled(statisticsWereEnabled);
    }
  }

  @Test
  void fidPushOutboxRetriesThenDeliversAndRevokesInvalidInstallation() {
    var worker =
        workforce.createWorker(
            WAREHOUSE,
            new WorkerRequest(
                0L,
                "Push worker",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of()));
    workerBoard.registerDevice(
        worker.id(),
        WAREHOUSE,
        "push-worker-installation",
        new WorkerDeviceRegistrationRequest(
            "FCM", "FID", "push-worker-fid", "0.1.16", 36, "ru-RU"));

    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean invalid = new AtomicBoolean();
    AtomicReference<WorkerPushClient.Target> observedTarget = new AtomicReference<>();
    WorkerPushClient client =
        (message, target) -> {
          observedTarget.set(target);
          if (invalid.get()) {
            throw new WorkerPushClient.DeliveryException(
                "invalid installation", true, false, null);
          }
          if (attempts.getAndIncrement() == 0) {
            throw new WorkerPushClient.DeliveryException("temporary outage", false, true, null);
          }
        };
    WorkerPushDispatcher dispatcher =
        new WorkerPushDispatcher(pushOutbox, client, Duration.ofSeconds(30), 10, 3);
    UUID retryEntryId = UUID.randomUUID();
    pushOutbox.enqueueJoinAvailable(Set.of(worker.id()), WAREHOUSE, retryEntryId, 42);

    dispatcher.dispatch();
    assertThat(
            jdbc.queryForMap(
                "select status,attempt_count,last_error from worker_push_outbox where entry_id=?",
                retryEntryId))
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 1)
        .containsEntry("last_error", "temporary outage");
    jdbc.update(
        "update worker_push_outbox set available_at=clock_timestamp() where entry_id=?",
        retryEntryId);

    dispatcher.dispatch();
    assertThat(
            jdbc.queryForMap(
                "select status,attempt_count from worker_push_outbox where entry_id=?",
                retryEntryId))
        .containsEntry("status", "SENT")
        .containsEntry("attempt_count", 2);
    assertThat(observedTarget.get().targetKind()).isEqualTo("FID");
    assertThat(observedTarget.get().value()).isEqualTo("push-worker-fid");

    invalid.set(true);
    UUID invalidEntryId = UUID.randomUUID();
    pushOutbox.enqueueJoinAvailable(Set.of(worker.id()), WAREHOUSE, invalidEntryId, 43);
    dispatcher.dispatch();

    assertThat(
            jdbc.queryForObject(
                "select status from worker_push_outbox where entry_id=?",
                String.class,
                invalidEntryId))
        .isEqualTo("SENT");
    assertThat(
            jdbc.queryForObject(
                "select status from worker_device_registration where installation_id='push-worker-installation'",
                String.class))
        .isEqualTo("REVOKED");
  }

  private void assertInvalidEvidenceDeclaration(
      WorkerDto worker,
      BoardEntryDto active,
      WorkerContext context,
      String contentType,
      long sizeBytes,
      String messageFragment) {
    UUID operationId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                workerBoard.reserveEvidence(
                    worker.id(),
                    WAREHOUSE,
                    active.id(),
                    operationId.toString(),
                    new EvidenceReservationRequest(
                        operationId,
                        UUID.randomUUID(),
                        active.routeIndex(),
                        context.serverTime(),
                        context.offlineLease().id(),
                        contentType,
                        sizeBytes,
                        "c".repeat(64))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(messageFragment);
  }

  private boolean latestOwnerProofActive(UUID entryId) {
    Boolean active =
        jdbc.queryForObject(
            """
            select (payload ->> 'active')::boolean
              from domain_event
             where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
               and aggregate_id=?
             order by aggregate_version desc
             limit 1
            """,
            Boolean.class,
            entryId.toString());
    return Boolean.TRUE.equals(active);
  }

  private boolean latestProofContains(UUID entryId, String field, UUID workerId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            select payload->? @> ?::jsonb
              from domain_event
             where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
               and aggregate_id=?
             order by aggregate_version desc
             limit 1
            """,
            Boolean.class,
            field,
            "[\"" + workerId + "\"]",
            entryId.toString()));
  }

  private long latestProofVersion(UUID entryId) {
    Long version =
        jdbc.queryForObject(
            """
            select aggregate_version
              from domain_event
             where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
               and aggregate_id=?
             order by aggregate_version desc
             limit 1
            """,
            Long.class,
            entryId.toString());
    return version == null ? -1 : version;
  }
}
