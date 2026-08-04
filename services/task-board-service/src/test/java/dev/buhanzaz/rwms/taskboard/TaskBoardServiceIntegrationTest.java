package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.config.TaskBoardClientProperties;
import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueUsageReferenceRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskTimeEventRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerDeletionIntentRepository;
import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TaskBoardServiceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID W1 = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID W2 = UUID.fromString("00000000-0000-0000-0000-000000000102");
  @org.springframework.beans.factory.annotation.Autowired RegistryService registry;
  @org.springframework.beans.factory.annotation.Autowired
  GlobalQueueProjectionService globalQueueProjections;
  @org.springframework.beans.factory.annotation.Autowired WorkforceService workforce;
  @org.springframework.beans.factory.annotation.Autowired TaskBoardService board;
  @org.springframework.beans.factory.annotation.Autowired WorkerTaskBoardService workerBoard;
  @org.springframework.beans.factory.annotation.Autowired FakeCredentials credentials;
  @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;

  @org.springframework.beans.factory.annotation.Autowired
  OAuth2ResourceServerProperties resourceServer;

  @org.springframework.beans.factory.annotation.Autowired WarehouseAccessAuthorizer authorizer;
  @org.springframework.beans.factory.annotation.Autowired TaskTimeEventRepository timeEvents;
  @org.springframework.beans.factory.annotation.Autowired BoardTaskRepository tasks;
  @org.springframework.beans.factory.annotation.Autowired QueueEntryRepository entries;
  @org.springframework.beans.factory.annotation.Autowired QueueUsageReferenceRepository queueReferences;
  @org.springframework.beans.factory.annotation.Autowired TaskAssignmentRepository assignments;
  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;

  @org.springframework.beans.factory.annotation.Autowired
  WorkerDeletionIntentRepository deletionIntents;

  @org.springframework.beans.factory.annotation.Autowired
  TaskBoardClientProperties clientProperties;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    credentials.resetState();
  }

  @Test
  void holdingIsAlwaysLastAndStaleVersionConflicts() {
    var holding =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("HOLD", QueueType.HOLDING, List.of()));
    var repair =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("REPAIR", QueueType.REPAIR, List.of()));
    var secondRepair =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            W1,
            queue("REPAIR_SECOND", QueueType.REPAIR, List.of()));
    assertThat(registry.listQueues(W1))
        .extracting(WorkQueueDto::id)
        .containsExactly(repair.id(), secondRepair.id(), holding.id());
    assertThat(registry.listQueues(W1))
        .extracting(WorkQueueDto::sortOrder)
        .containsExactly(1, 2, 3);
    assertThatThrownBy(
            () ->
                registry.updateQueueDefinition(
                    repair.definitionId(),
                    globalDefinitionRequest(
                        registry.dto(registry.requireQueueDefinition(repair.definitionId())),
                        "REPAIR",
                        null,
                        repair.definitionVersion() + 99)))
        .isInstanceOf(StaleVersionException.class);
  }

  @Test
  void globalDefinitionsShareOrderAndStatusAcrossEveryWarehouse() {
    var firstW1 =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("GLOBAL_EXTERNAL", QueueType.REPAIR, List.of()));
    var secondW1 =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("GLOBAL_INTERNAL", QueueType.REPAIR, List.of()));
    var firstW2 =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W2, queue("GLOBAL_EXTERNAL", QueueType.REPAIR, List.of()));

    assertThat(firstW1.definitionId()).isEqualTo(firstW2.definitionId());
    assertThat(firstW1.id()).isNotEqualTo(firstW2.id());
    assertThat(registry.listQueues(W2))
        .extracting(WorkQueueDto::definitionId)
        .containsExactly(firstW2.definitionId(), secondW1.definitionId());
    assertThat(registry.listQueues(UUID.randomUUID())).isEmpty();
    assertThat(registry.listQueueDefinitions())
        .extracting(QueueDefinitionDto::id)
        .containsExactlyInAnyOrder(firstW1.definitionId(), secondW1.definitionId());
    assertThat(registry.listQueueDefinitions())
        .extracting(QueueDefinitionDto::sortOrder)
        .containsExactly(1, 2);
    assertThat(registry.listQueues(W1))
        .filteredOn(queue -> queue.definitionId().equals(firstW1.definitionId()))
        .hasSize(1);

    var beforeReorder = registry.listQueueDefinitions();
    QueueDefinitionDto firstDefinition =
        beforeReorder.stream()
            .filter(candidate -> candidate.id().equals(firstW1.definitionId()))
            .findFirst()
            .orElseThrow();
    QueueDefinitionDto secondDefinition =
        beforeReorder.stream()
            .filter(candidate -> candidate.id().equals(secondW1.definitionId()))
            .findFirst()
            .orElseThrow();
    registry.reorderQueueDefinitions(
        new QueueDefinitionOrderRequest(
            List.of(
                new QueueDefinitionOrderItem(secondDefinition.id(), secondDefinition.version()),
                new QueueDefinitionOrderItem(firstDefinition.id(), firstDefinition.version()))));

    assertThat(registry.listQueues(W1))
        .extracting(WorkQueueDto::definitionId)
        .containsExactly(secondW1.definitionId(), firstW1.definitionId());
    assertThat(registry.listQueues(W2))
        .extracting(WorkQueueDto::definitionId)
        .containsExactly(secondW1.definitionId(), firstW2.definitionId());
    assertThat(registry.listQueues(W1))
        .extracting(WorkQueueDto::sortOrder)
        .containsExactly(1, 2);
    assertThat(registry.listQueues(W2))
        .extracting(WorkQueueDto::sortOrder)
        .containsExactly(1, 2);

    QueueDefinitionDto definition =
        registry.listQueueDefinitions().stream()
            .filter(candidate -> candidate.id().equals(firstW1.definitionId()))
            .findFirst()
            .orElseThrow();
    registry.updateQueueDefinition(
        definition.id(),
        globalDefinitionRequest(
            definition, "Общие внешние работы", "Изменение общего каталога"));

    assertThat(registry.listQueues(W1))
        .filteredOn(queue -> queue.definitionId().equals(definition.id()))
        .extracting(WorkQueueDto::name)
        .containsExactly("Общие внешние работы");
    assertThat(registry.listQueues(W2))
        .filteredOn(queue -> queue.definitionId().equals(definition.id()))
        .extracting(WorkQueueDto::name)
        .containsExactly("Общие внешние работы");
  }

  @Test
  void globalDefinitionConfigAndClassesMaterializeForEveryActiveWarehouse() {
    var definition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "GLOBAL_SETTINGS", null, QueueType.REPAIR));
    var workerClass = registry.createClass(workerClass("GLOBAL_SETTINGS_PRIMARY"));
    var first =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            W1,
            new QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                true,
                true,
                null,
                null,
                false,
                2,
                List.of(
                    new QueueBindingRequest(
                        workerClass.id(), 0, true, ParticipationPolicy.PRIMARY, false))));
    var second =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            W2,
            new QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                true,
                true,
                null,
                null,
                false,
                2,
                List.of(
                    new QueueBindingRequest(
                        workerClass.id(), 0, true, ParticipationPolicy.PRIMARY, false))));

    QueueDefinitionDto configured = registry.dto(registry.requireQueueDefinition(definition.id()));
    registry.updateQueueDefinition(
        configured.id(),
        globalDefinitionRequest(
            configured, "GLOBAL_SETTINGS_RENAMED_AGAIN", "Каталог 2"));

    WorkQueueDto firstProjection = registry.listQueues(W1).getFirst();
    WorkQueueDto secondProjection = registry.listQueues(W2).getFirst();
    assertThat(firstProjection.id()).isEqualTo(first.id());
    assertThat(secondProjection.id()).isEqualTo(second.id()).isNotEqualTo(first.id());
    assertThat(List.of(firstProjection, secondProjection)).allSatisfy(connection -> {
      assertThat(connection.name()).isEqualTo("GLOBAL_SETTINGS_RENAMED_AGAIN");
      assertThat(connection.description()).isEqualTo("Каталог 2");
      assertThat(connection.active()).isTrue();
      assertThat(connection.hidden()).isTrue();
      assertThat(connection.collapsed()).isTrue();
      assertThat(connection.resultPhotoMinCount()).isEqualTo(2);
      assertThat(connection.bindings()).extracting(binding -> binding.workerClass().id())
          .containsExactly(workerClass.id());
    });
  }

  @Test
  void globalTemplateRepairsAnExistingWarehouseBindingWithAnotherPrimaryClass() {
    var primaryClass = registry.createClass(workerClass("GLOBAL_TEMPLATE_PRIMARY"));
    var secondaryClass = registry.createClass(workerClass("GLOBAL_TEMPLATE_SECONDARY"));
    List<QueueBindingRequest> template =
        List.of(
            new QueueBindingRequest(
                primaryClass.id(), 0, false, ParticipationPolicy.PRIMARY, false),
            new QueueBindingRequest(
                secondaryClass.id(), 1, true, ParticipationPolicy.REQUIRED, true));
    var first =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("GLOBAL_TEMPLATE_BINDINGS", QueueType.REPAIR, template));
    var second =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W2, queue("GLOBAL_TEMPLATE_BINDINGS", QueueType.REPAIR, template));

    jdbc.update("delete from work_queue_class_binding where queue_id = ?", second.id());
    jdbc.update(
        """
        insert into work_queue_class_binding(
          id, version, queue_id, worker_class_id, stop_task_on_take,
          binding_order, participation_policy, notify_on_primary_take)
        values (?, 0, ?, ?, false, 0, 'PRIMARY', false)
        """,
        UUID.randomUUID(),
        second.id(),
        secondaryClass.id());

    globalQueueProjections.synchronizeDefinition(first.definitionId());

    WorkQueueDto repaired = registry.dto(registry.requireQueue(W2, second.id()));
    assertThat(repaired.id()).isEqualTo(second.id());
    assertThat(repaired.bindings())
        .extracting(binding -> binding.workerClass().id())
        .containsExactly(primaryClass.id(), secondaryClass.id());
    assertThat(repaired.bindings())
        .extracting(QueueBindingDto::participationPolicy)
        .containsExactly(ParticipationPolicy.PRIMARY, ParticipationPolicy.REQUIRED);
  }

  @Test
  void driverQueueUpdateRemainsWarehouseSpecificInQueueCapabilities() {
    var movementW1 =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "GLOBAL_DRIVERS",
                QueueType.MOVEMENT,
                QueuePurpose.LOGISTICS_DRIVER,
                List.of()));

    assertThat(registry.listQueueDefinitions())
        .filteredOn(definition -> definition.purpose() == QueuePurpose.LOGISTICS_DRIVER)
        .extracting(QueueDefinitionDto::id)
        .containsExactly(movementW1.definitionId());
    assertThat(registry.queueCapabilities(W1).movementQueueDefinitions())
        .containsExactly(
            new MovementQueueCapability(movementW1.definitionId(), movementW1.id()));
    assertThat(registry.queueCapabilities(W2).movementQueueDefinitions()).isEmpty();

    var movementW2 =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W2,
            queue(
                "GLOBAL_DRIVERS",
                QueueType.MOVEMENT,
                QueuePurpose.LOGISTICS_DRIVER,
                List.of()));
    assertThat(movementW2.definitionId()).isEqualTo(movementW1.definitionId());
    assertThat(registry.queueCapabilities(W2).movementQueueDefinitions()).hasSize(1);

    QueueRegistryTestFixtures.update(registry, jdbc,
        W2,
        movementW2.id(),
        new QueueFixtureRequest(
            movementW2.version(),
            movementW2.definitionId(),
            false,
            false,
            movementW2.collapsed(),
            movementW2.holdingPeriodMinutes(),
            movementW2.notificationThreshold(),
            movementW2.notifyWhenThresholdReached(),
            movementW2.resultPhotoMinCount(),
            List.of()));

    assertThat(registry.queueCapabilities(W2).movementQueueDefinitions()).isEmpty();
    assertThat(registry.queueCapabilities(W1).movementQueueDefinitions()).hasSize(1);
  }

  @Test
  void logisticsPrimaryClassMarkerIsGlobalWhenAnotherWarehouseHasNoDriverQueue() {
    var driverClass = registry.createClass(workerClass("LOGISTICS_PRIMARY"));
    var repairClass = registry.createClass(workerClass("ORDINARY_REPAIR"));
    QueueRegistryTestFixtures.create(
        registry,
        jdbc,
        W1,
        queue(
            "DRIVER_MARKER",
            QueueType.MOVEMENT,
            QueuePurpose.LOGISTICS_DRIVER,
            List.of(
                new QueueBindingRequest(
                    driverClass.id(), 0, false, ParticipationPolicy.PRIMARY, false))));
    QueueRegistryTestFixtures.create(
        registry,
        jdbc,
        W2,
        queue(
            "ORDINARY_REPAIR_QUEUE",
            QueueType.REPAIR,
            List.of(
                new QueueBindingRequest(
                    repairClass.id(), 0, false, ParticipationPolicy.PRIMARY, false))));

    var disabledPlaceholder =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            W2,
            queue(
                "DRIVER_MARKER",
                QueueType.MOVEMENT,
                QueuePurpose.LOGISTICS_DRIVER,
                List.of(
                    new QueueBindingRequest(
                        repairClass.id(), 0, false, ParticipationPolicy.PRIMARY, false))));
    QueueRegistryTestFixtures.update(
        registry,
        jdbc,
        W2,
        disabledPlaceholder.id(),
        new QueueFixtureRequest(
            disabledPlaceholder.version(),
            disabledPlaceholder.definitionId(),
            false,
            true,
            false,
            null,
            null,
            false,
            disabledPlaceholder.resultPhotoMinCount(),
            List.of(
                new QueueBindingRequest(
                    repairClass.id(), 0, false, ParticipationPolicy.PRIMARY, false))));

    assertThat(registry.listQueues(UUID.randomUUID())).isEmpty();
    assertThat(registry.listClasses())
        .filteredOn(workerClass -> workerClass.id().equals(driverClass.id()))
        .extracting(WorkerClassDto::logisticsPrimary)
        .containsExactly(true);
    assertThat(registry.listClasses())
        .filteredOn(workerClass -> workerClass.id().equals(repairClass.id()))
        .extracting(WorkerClassDto::logisticsPrimary)
        .containsExactly(false);
    assertThat(
            registry.listQueues(W1).stream()
                .filter(queue -> queue.purpose() == QueuePurpose.LOGISTICS_DRIVER)
                .findFirst()
                .orElseThrow()
                .bindings()
                .getFirst()
                .workerClass()
                .logisticsPrimary())
        .isTrue();
  }

  @Test
  void driverCurrentLaneIsOrderedQueueAndCurrentCardCanReturnToCalendarDate() {
    var movement =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            W1,
            queue(
                "DRIVER_CURRENT_QUEUE",
                QueueType.MOVEMENT,
                QueuePurpose.LOGISTICS_DRIVER,
                List.of()));
    LocalDate today = LocalDate.of(2026, 8, 1);
    UUID firstExternalId = UUID.randomUUID();
    UUID secondExternalId = UUID.randomUUID();
    BoardTaskRegistrationDto first =
        board.registerExternalTask(
            "logistics-service",
            driverRegistration(movement.definitionId(), firstExternalId, today));
    BoardTaskRegistrationDto second =
        board.registerExternalTask(
            "logistics-service",
            driverRegistration(movement.definitionId(), secondExternalId, today));

    first =
        board.setExternalTaskLane(
            "logistics-service",
            firstExternalId,
            new SetTaskLaneRequest(first.taskVersion(), TaskLane.CURRENT));
    second =
        board.setExternalTaskLane(
            "logistics-service",
            secondExternalId,
            new SetTaskLaneRequest(second.taskVersion(), TaskLane.CURRENT));

    assertThat(board.logisticsSnapshot(W1).current())
        .extracting(BoardEntryDto::externalTaskId)
        .containsExactly(firstExternalId, secondExternalId);

    LocalDate targetDate = today.plusDays(2);
    BoardTaskRegistrationDto moved =
        board.moveExternalLogisticsTask(
            firstExternalId,
            new MoveExternalLogisticsTaskRequest(
                first.taskVersion(),
                first.route().getFirst().entryVersion(),
                TaskLane.SCHEDULED,
                targetDate,
                0));

    assertThat(moved.lane()).isEqualTo(TaskLane.SCHEDULED);
    LogisticsBoardSnapshot snapshot = board.logisticsSnapshot(W1);
    assertThat(snapshot.current())
        .extracting(BoardEntryDto::externalTaskId)
        .containsExactly(secondExternalId);
    assertThat(snapshot.dates())
        .filteredOn(column -> column.date().equals(targetDate))
        .flatExtracting(LogisticsDateColumnDto::entries)
        .extracting(BoardEntryDto::externalTaskId)
        .containsExactly(firstExternalId);

    BoardTaskRegistrationDto insertedFirst =
        board.moveExternalLogisticsTask(
            firstExternalId,
            new MoveExternalLogisticsTaskRequest(
                moved.taskVersion(),
                moved.route().getFirst().entryVersion(),
                TaskLane.CURRENT,
                today,
                0));
    assertThat(board.logisticsSnapshot(W1).current())
        .extracting(BoardEntryDto::externalTaskId)
        .containsExactly(firstExternalId, secondExternalId);

    board.moveExternalLogisticsTask(
        firstExternalId,
        new MoveExternalLogisticsTaskRequest(
            insertedFirst.taskVersion(),
            insertedFirst.route().getFirst().entryVersion(),
            TaskLane.CURRENT,
            today,
            1));
    assertThat(board.logisticsSnapshot(W1).current())
        .extracting(BoardEntryDto::externalTaskId)
        .containsExactly(secondExternalId, firstExternalId);
  }

  @Test
  void movingCurrentDriverTaskAcrossPinnedCardKeepsPinnedCurrentOrdinal() {
    var movement =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            W1,
            queue(
                "DRIVER_PINNED_CURRENT_QUEUE",
                QueueType.MOVEMENT,
                QueuePurpose.LOGISTICS_DRIVER,
                List.of()));
    LocalDate today = LocalDate.of(2026, 8, 1);
    UUID firstExternalId = UUID.randomUUID();
    UUID pinnedExternalId = UUID.randomUUID();
    UUID afterExternalId = UUID.randomUUID();
    BoardTaskRegistrationDto first =
        board.registerExternalTask(
            "logistics-service",
            driverRegistration(movement.definitionId(), firstExternalId, today));
    BoardTaskRegistrationDto pinned =
        board.registerExternalTask(
            "logistics-service",
            driverRegistration(movement.definitionId(), pinnedExternalId, today));
    BoardTaskRegistrationDto after =
        board.registerExternalTask(
            "logistics-service",
            driverRegistration(movement.definitionId(), afterExternalId, today));

    first =
        board.setExternalTaskLane(
            "logistics-service",
            firstExternalId,
            new SetTaskLaneRequest(first.taskVersion(), TaskLane.CURRENT));
    pinned =
        board.setExternalTaskLane(
            "logistics-service",
            pinnedExternalId,
            new SetTaskLaneRequest(pinned.taskVersion(), TaskLane.CURRENT));
    after =
        board.setExternalTaskLane(
            "logistics-service",
            afterExternalId,
            new SetTaskLaneRequest(after.taskVersion(), TaskLane.CURRENT));
    board.pin(W1, pinned.taskId(), new PinTaskRequest(pinned.taskVersion(), true));

    board.moveExternalLogisticsTask(
        afterExternalId,
        new MoveExternalLogisticsTaskRequest(
            after.taskVersion(),
            after.route().getFirst().entryVersion(),
            TaskLane.CURRENT,
            today,
            0));

    assertThat(board.logisticsSnapshot(W1).current())
        .extracting(BoardEntryDto::externalTaskId)
        .containsExactly(afterExternalId, pinnedExternalId, firstExternalId);
    assertThat(board.logisticsSnapshot(W1).current())
        .extracting(BoardEntryDto::queuePosition)
        .containsExactly(0, 1, 2);
  }

  @Test
  void queueUpdateCanReplaceOrderedBindingsWithoutNaturalKeyConflict() {
    var driverClass = registry.createClass(workerClass("DRIVER_ORDERED"));
    var slingerClass = registry.createClass(workerClass("SLINGER_ORDERED"));
    List<QueueBindingRequest> bindings =
        List.of(
            new QueueBindingRequest(
                driverClass.id(), 0, false, ParticipationPolicy.PRIMARY, false),
            new QueueBindingRequest(
                slingerClass.id(), 1, true, ParticipationPolicy.REQUIRED, true));
    var created =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ORDERED_MOVEMENT", QueueType.MOVEMENT, bindings));

    var updated =
        QueueRegistryTestFixtures.update(registry, jdbc,
            W1,
            created.id(),
            new QueueFixtureRequest(
                created.version(),
                created.definitionId(),
                created.active(),
                created.hidden(),
                created.collapsed(),
                created.holdingPeriodMinutes(),
                created.notificationThreshold(),
                created.notifyWhenThresholdReached(),
                created.resultPhotoMinCount(),
                bindings));

    assertThat(updated.bindings())
        .extracting(QueueBindingDto::workerClass)
        .extracting(WorkerClassDto::id)
        .containsExactly(driverClass.id(), slingerClass.id());
    assertThat(updated.bindings()).extracting(QueueBindingDto::order).containsExactly(0, 1);
    assertThat(updated.bindings()).extracting(QueueBindingDto::primary).containsExactly(true, false);
    assertThat(updated.bindings())
        .extracting(QueueBindingDto::notifyOnPrimaryTake)
        .containsExactly(false, true);
  }

  @Test
  void updateClassAndReusedReferenceReturnReusableVersions() {
    var created = registry.createClass(workerClass("VERSIONED"));
    var updated =
        registry.updateClass(
            created.id(),
            new WorkerClassRequest(
                created.version(), "Versioned", null, null, 10, true));
    assertThat(updated.version()).isGreaterThan(created.version());
    var updatedAgain =
        registry.updateClass(
            updated.id(),
            new WorkerClassRequest(
                updated.version(), "Versioned", null, null, 10, true));
    assertThat(updatedAgain.version()).isGreaterThan(updated.version());

    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("REF", QueueType.REPAIR, List.of()));
    var first =
        registry.registerReference(
            queue.definitionId(),
            new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-version"));
    var reused =
        registry.registerReference(
            queue.definitionId(),
            new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-version"));
    assertThat(reused.id()).isEqualTo(first.id());
    assertThat(reused.version()).isEqualTo(first.version());
    registry.deleteReference(first.type(), first.externalReferenceId(), first.version());
    assertThat(queueReferences.count()).isZero();
  }

  @Test
  void reusedReferenceRejectsAnotherQueueAndConcurrentRetryCreatesOneRow() throws Exception {
    var firstQueue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("REF_FIRST", QueueType.REPAIR, List.of()));
    var secondQueue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("REF_SECOND", QueueType.REPAIR, List.of()));
    var request = new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-concurrent");

    List<Object> outcomes =
        race(
            () -> registry.registerReference(firstQueue.definitionId(), request),
            () -> registry.registerReference(firstQueue.definitionId(), request));

    assertThat(outcomes).allMatch(QueueReferenceDto.class::isInstance);
    var registered = outcomes.stream().map(QueueReferenceDto.class::cast).toList();
    assertThat(registered).extracting(QueueReferenceDto::id).containsOnly(registered.getFirst().id());
    assertThat(registered)
        .extracting(QueueReferenceDto::version)
        .containsOnly(registered.getFirst().version());
    assertThat(queueReferences.count()).isEqualTo(1);
    assertThatThrownBy(
            () -> registry.registerReference(secondQueue.definitionId(), request))
        .isInstanceOf(ConflictException.class);
    assertThat(queueReferences.count()).isEqualTo(1);
  }

  @Test
  void unfinishedHoldingHidesAllLaterShadowStagesEvenWhenRequested() {
    var holding = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("HOLD", QueueType.HOLDING, List.of()));
    var after = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("AFTER", QueueType.REPAIR, List.of()));
    var snapshot =
        board.createTask(
            W1,
            new CreateBoardTaskRequest(
                null,
                "holding-route",
                null,
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(holding.definitionId(), "hold", null),
                    new RouteStepRequest(after.definitionId(), "after", null))));
    assertThat(snapshot.columns().stream().flatMap(column -> column.entries().stream()))
        .extracting(BoardEntryDto::taskText)
        .containsExactly("hold");
    assertThat(board.snapshot(W1, true).columns().stream().flatMap(c -> c.entries().stream()))
        .extracting(BoardEntryDto::taskText)
        .containsExactly("hold");
  }

  @Test
  void createRejectsDuplicateEffectiveRouteAndExternalTaskId() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("DUP", QueueType.REPAIR, List.of()));
    var duplicateRoute =
        new CreateBoardTaskRequest(
            null,
            "duplicate-route",
            null,
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(queue.definitionId(), null, null),
                new RouteStepRequest(queue.definitionId(), null, null)));
    assertThatThrownBy(() -> board.createTask(W1, duplicateRoute))
        .isInstanceOf(ConflictException.class);
    var missingQueueRoute =
        new CreateBoardTaskRequest(
            null,
            "missing-queue-route",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(null, null, null)));
    assertThatThrownBy(() -> board.createTask(W1, missingQueueRoute))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("общей очереди");
    assertThat(board.snapshot(W1, true).columns())
        .flatExtracting(BoardColumnDto::entries)
        .isEmpty();

    UUID externalTaskId = UUID.randomUUID();
    board.createTask(
        W1,
        new CreateBoardTaskRequest(
            externalTaskId,
            "external",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.definitionId(), null, null))));
    assertThat(entry("external").externalTaskId()).isEqualTo(externalTaskId);
    assertThatThrownBy(
            () ->
                board.createTask(
                    W1,
                    new CreateBoardTaskRequest(
                        externalTaskId,
                        "external-duplicate",
                        null,
                        null,
                        null,
                        null,
                        List.of(new RouteStepRequest(queue.definitionId(), null, null)))))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void identicalExternalTaskRetryReturnsCurrentStateWithoutSecondEvent() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("IDEMPOTENT", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.parse("2026-07-13T12:00:00+03:00");
    var first =
        new CreateBoardTaskRequest(
            externalTaskId,
            "  Идемпотентная задача  ",
            "  БЫТ-001 ",
            " описание ",
            30,
            deadline,
            List.of(new RouteStepRequest(queue.definitionId(), " работа ", 15)));
    var equivalent =
        new CreateBoardTaskRequest(
            externalTaskId,
            "Идемпотентная задача",
            "БЫТ-001",
            "описание",
            30,
            deadline.withOffsetSameInstant(ZoneOffset.UTC),
            List.of(new RouteStepRequest(queue.definitionId(), "работа", 15)));

    var created = board.createTask(W1, first);
    QueueRegistryTestFixtures.update(registry, jdbc,
        W1,
        queue.id(),
        new QueueFixtureRequest(
            queue.version(),
            queue.definitionId(),
            true,
            queue.hidden(),
            queue.collapsed(),
            queue.holdingPeriodMinutes(),
            queue.notificationThreshold(),
            queue.notifyWhenThresholdReached(),
            queue.resultPhotoMinCount(),
            List.of()));
    registry.updateQueueDefinition(
        queue.definitionId(),
        globalDefinitionRequest(
            registry.dto(registry.requireQueueDefinition(queue.definitionId())),
            "Переименованная очередь",
            queue.description()));
    var replayed = board.createTask(W1, equivalent);

    assertThat(replayed.columns())
        .flatExtracting(BoardColumnDto::entries)
        .extracting(BoardEntryDto::taskId)
        .containsExactly(
            created.columns().stream()
                .flatMap(column -> column.entries().stream())
                .findFirst()
                .orElseThrow()
                .taskId());
    assertThat(tasks.findByExternalTaskId(externalTaskId)).isPresent();
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CREATED)).isEqualTo(1);
    assertThat(board.registration(W1, externalTaskId).route()).hasSize(1);
  }

  @Test
  void externalTaskRetryRequiresCanonicalFingerprintAndWarehouse() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("IDEMPOTENT_CONFLICT", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    var request = externalTask(externalTaskId, queue.id(), "original");
    board.createTask(W1, request);

    assertThatThrownBy(() -> board.createTask(W1, externalTask(externalTaskId, queue.id(), "changed")))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> board.createTask(W2, request))
        .isInstanceOf(ConflictException.class);

    jdbc.update(
        "update board_task set request_fingerprint = null where external_task_id = ?",
        externalTaskId);
    assertThatThrownBy(() -> board.createTask(W1, request)).isInstanceOf(ConflictException.class);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CREATED)).isEqualTo(1);
  }

  @Test
  void externalTaskRetryUsesEffectiveSchedulingInOneCanonicalFingerprint() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("IDEMPOTENT_SCHEDULE", QueueType.REPAIR, List.of()));
    UUID implicitExternalTaskId = UUID.randomUUID();
    var implicitRequest = externalTask(implicitExternalTaskId, queue.id(), "implicit-schedule");
    board.createTask(W1, implicitRequest);
    BoardTask implicitTask = tasks.findByExternalTaskId(implicitExternalTaskId).orElseThrow();
    var explicitEffectiveRequest =
        new CreateBoardTaskRequest(
            implicitRequest.externalTaskId(),
            implicitRequest.title(),
            implicitRequest.unitNumber(),
            implicitRequest.description(),
            implicitRequest.plannedDurationMinutes(),
            implicitRequest.deadlineAt(),
            implicitRequest.route(),
            implicitTask.getScheduledDate(),
            implicitTask.getPriority());

    board.createTask(W1, explicitEffectiveRequest);
    assertThatThrownBy(
            () ->
                board.createTask(
                    W1,
                    new CreateBoardTaskRequest(
                        explicitEffectiveRequest.externalTaskId(),
                        explicitEffectiveRequest.title(),
                        explicitEffectiveRequest.unitNumber(),
                        explicitEffectiveRequest.description(),
                        explicitEffectiveRequest.plannedDurationMinutes(),
                        explicitEffectiveRequest.deadlineAt(),
                        explicitEffectiveRequest.route(),
                        explicitEffectiveRequest.scheduledDate(),
                        1)))
        .isInstanceOf(ConflictException.class);

    UUID scheduledExternalTaskId = UUID.randomUUID();
    var scheduled =
        new CreateBoardTaskRequest(
            scheduledExternalTaskId,
            "scheduled",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.definitionId(), "scheduled", null)),
            LocalDate.of(2026, 7, 25),
            2);
    board.createTask(W1, scheduled);
    board.createTask(W1, scheduled);
    assertThatThrownBy(
            () ->
                board.createTask(
                    W1,
                    new CreateBoardTaskRequest(
                        scheduled.externalTaskId(),
                        scheduled.title(),
                        scheduled.unitNumber(),
                        scheduled.description(),
                        scheduled.plannedDurationMinutes(),
                        scheduled.deadlineAt(),
                        scheduled.route(),
                        scheduled.scheduledDate().plusDays(1),
                        scheduled.priority())))
        .isInstanceOf(ConflictException.class);
    assertThat(tasks.count()).isEqualTo(2);
  }

  @Test
  void externalTaskRetryFingerprintsCompleteWorkerContent() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("IDEMPOTENT_CONTENT", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    var initial =
        new CreateBoardTaskRequest(
            externalTaskId,
            "worker content",
            null,
            null,
            30,
            null,
            List.of(
                new RouteStepRequest(
                    queue.definitionId(),
                    "repair",
                    30,
                    List.of(
                        new TaskWorkSnapshotRequest(
                            workId, "Замена двери", 1, "шт.", 30, null)),
                    List.of(
                        new TaskMaterialSnapshotRequest(
                            materialId, "Железная дверь", 1, "шт.")),
                    List.of(),
                    List.of())));

    board.createTask(W1, initial);
    board.createTask(W1, initial);
    var changedMaterialQuantity =
        new CreateBoardTaskRequest(
            initial.externalTaskId(),
            initial.title(),
            initial.unitNumber(),
            initial.description(),
            initial.plannedDurationMinutes(),
            initial.deadlineAt(),
            List.of(
                new RouteStepRequest(
                    queue.definitionId(),
                    "repair",
                    30,
                    initial.route().getFirst().works(),
                    List.of(
                        new TaskMaterialSnapshotRequest(
                            materialId, "Железная дверь", 2, "шт.")),
                    List.of(),
                    List.of())),
            null,
            null);

    assertThatThrownBy(() -> board.createTask(W1, changedMaterialQuantity))
        .isInstanceOf(ConflictException.class);
    assertThat(tasks.count()).isOne();
  }

  @Test
  void workSourceMediaMustBelongToTheStepAndToOneWorkOnly() {
    var queue = QueueRegistryTestFixtures.create(
        registry, jdbc, W1, queue("WORK_MEDIA", QueueType.REPAIR, List.of()));
    UUID mediaId = UUID.randomUUID();
    UUID firstWorkId = UUID.randomUUID();
    UUID secondWorkId = UUID.randomUUID();
    var source = new TaskSourceMediaSnapshotRequest(
        mediaId, 1, "image/jpeg", null, OffsetDateTime.now(ZoneOffset.UTC));

    var missingSource = new CreateBoardTaskRequest(
        null,
        "missing source",
        null,
        null,
        null,
        null,
        List.of(new RouteStepRequest(
            queue.definitionId(),
            null,
            null,
            List.of(new TaskWorkSnapshotRequest(
                firstWorkId, "Работа", 1, "шт.", 30, null, List.of(mediaId))),
            List.of(),
            List.of(),
            List.of())));
    assertThatThrownBy(() -> board.createTask(W1, missingSource))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("отсутствует в исходных материалах");

    var duplicatedSource = new CreateBoardTaskRequest(
        null,
        "duplicate source",
        null,
        null,
        null,
        null,
        List.of(new RouteStepRequest(
            queue.definitionId(),
            null,
            null,
            List.of(
                new TaskWorkSnapshotRequest(
                    firstWorkId, "Первая", 1, "шт.", 30, null, List.of(mediaId)),
                new TaskWorkSnapshotRequest(
                    secondWorkId, "Вторая", 1, "шт.", 30, null, List.of(mediaId))),
            List.of(),
            List.of(),
            List.of(source))));
    assertThatThrownBy(() -> board.createTask(W1, duplicatedSource))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("не может принадлежать двум работам");
  }

  @Test
  void concurrentExternalTaskRetryCreatesOneTaskAndOneEvent() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("IDEMPOTENT_RACE", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    var request = externalTask(externalTaskId, queue.id(), "race");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return board.createTask(W1, request);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return board.createTask(W1, request);
              });
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }

    assertThat(tasks.count()).isEqualTo(1);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CREATED)).isEqualTo(1);
  }

  @Test
  void classesUseUuidIdentityWhileGlobalQueueDefinitionsRejectDuplicateNames() throws Exception {
    List<Object> classOutcomes =
        race(
            () -> registry.createClass(workerClass("MixedClass")),
            () -> registry.createClass(workerClass("mixedclass")));
    assertThat(classOutcomes).allMatch(WorkerClassDto.class::isInstance);
    assertThat(registry.listClasses()).extracting(WorkerClassDto::id).hasSize(2);

    List<Object> queueOutcomes =
        race(
            () -> QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("MixedQueue", QueueType.REPAIR, List.of())),
            () -> QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("mixedqueue", QueueType.REPAIR, List.of())));
    assertSingleSuccess(queueOutcomes);
    assertThat(queueOutcomes)
        .filteredOn(RuntimeException.class::isInstance)
        .allMatch(ConflictException.class::isInstance);
    assertThat(registry.listQueues(W1)).extracting(WorkQueueDto::id).hasSize(1);
    assertThat(registry.listQueueDefinitions())
        .extracting(QueueDefinitionDto::name)
        .singleElement()
        .asString()
        .isEqualToIgnoringCase("MixedQueue");

    List<Object> workerOutcomes =
        race(
            () ->
                workforce.createWorker(
                    W1, worker("Login One", "Mixed.Login", "password-123", List.of())),
            () ->
                workforce.createWorker(
                    W1, worker("Login Two", "mixed.login", "password-123", List.of())));
    assertSingleSuccess(workerOutcomes);
    assertThat(workforce.listWorkers(W1)).extracting(WorkerDto::appLogin).containsExactly("mixed.login");
    assertThat(credentials.lastConfiguredLogin).hasValue("mixed.login");
  }

  @Test
  void concurrentQueueCreationKeepsUniqueOrderAndHoldingLast() throws Exception {
    List<Object> outcomes =
        race(
            () -> QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("REGULAR_RACE", QueueType.REPAIR, List.of())),
            () -> QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("HOLDING_RACE", QueueType.HOLDING, List.of())));

    assertThat(outcomes).allMatch(WorkQueueDto.class::isInstance);
    var ordered = registry.listQueues(W1);
    assertThat(ordered).hasSize(2);
    assertThat(ordered).extracting(WorkQueueDto::sortOrder).doesNotHaveDuplicates();
    assertThat(ordered.getLast().type()).isEqualTo(QueueType.HOLDING);
  }

  @Test
  void reorderIsFullSetCasAndConcurrentCreateCannotCorruptOrder() throws Exception {
    var first = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ORDER_FIRST", QueueType.REPAIR, List.of()));
    var second = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ORDER_SECOND", QueueType.MOVEMENT, List.of()));
    var holding = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ORDER_HOLDING", QueueType.HOLDING, List.of()));

    assertThatThrownBy(
            () ->
                QueueRegistryTestFixtures.reorder(registry, jdbc,
                    W1,
                    new QueueFixtureOrderRequest(
                        List.of(
                            new QueueFixtureOrderItem(first.id(), first.version()),
                            new QueueFixtureOrderItem(second.id(), second.version())))))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                QueueRegistryTestFixtures.reorder(registry, jdbc,
                    W1,
                    new QueueFixtureOrderRequest(
                        List.of(
                            new QueueFixtureOrderItem(first.id(), first.version()),
                            new QueueFixtureOrderItem(first.id(), first.version()),
                            new QueueFixtureOrderItem(holding.id(), holding.version())))))
        .isInstanceOf(ConflictException.class);
    var foreign = QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("ORDER_FOREIGN", QueueType.REPAIR, List.of()));
    assertThatThrownBy(
            () ->
                QueueRegistryTestFixtures.reorder(registry, jdbc,
                    W1,
                    new QueueFixtureOrderRequest(
                        List.of(
                            new QueueFixtureOrderItem(first.id(), first.version()),
                            new QueueFixtureOrderItem(second.id(), second.version()),
                            new QueueFixtureOrderItem(foreign.id(), foreign.version())))))
        .isInstanceOf(ConflictException.class);
    var foreignW1 =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("ORDER_FOREIGN", QueueType.REPAIR, List.of()));
    assertThat(foreignW1.definitionId()).isEqualTo(foreign.definitionId());
    assertThatThrownBy(
            () ->
                QueueRegistryTestFixtures.reorder(registry, jdbc,
                    W1,
                    new QueueFixtureOrderRequest(
                        List.of(
                            new QueueFixtureOrderItem(first.id(), first.version() + 1),
                            new QueueFixtureOrderItem(second.id(), second.version()),
                            new QueueFixtureOrderItem(holding.id(), holding.version()),
                            new QueueFixtureOrderItem(foreignW1.id(), foreignW1.version())))))
        .isInstanceOf(StaleVersionException.class);

    var reorder =
        new QueueFixtureOrderRequest(
            List.of(
                new QueueFixtureOrderItem(second.id(), second.version()),
                new QueueFixtureOrderItem(holding.id(), holding.version()),
                new QueueFixtureOrderItem(first.id(), first.version()),
                new QueueFixtureOrderItem(foreignW1.id(), foreignW1.version())));
    List<Object> outcomes =
        race(
            () -> QueueRegistryTestFixtures.reorder(registry, jdbc, W1, reorder),
            () -> QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ORDER_CONCURRENT", QueueType.REPAIR, List.of())));
    assertThat(outcomes).anyMatch(WorkQueueDto.class::isInstance);
    assertThat(outcomes)
        .allMatch(value -> value instanceof List<?> || value instanceof WorkQueueDto || value instanceof ConflictException);

    var ordered = registry.listQueues(W1);
    assertThat(ordered).hasSize(5);
    assertThat(ordered).extracting(WorkQueueDto::id).doesNotHaveDuplicates();
    assertThat(ordered).extracting(WorkQueueDto::sortOrder).doesNotHaveDuplicates();
    assertThat(ordered.getLast().type()).isEqualTo(QueueType.HOLDING);
  }

  @Test
  void kafkaOutboxInsertFailureRollsBackTaskAndRouteAtomically() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("OUTBOX_ROLLBACK", QueueType.REPAIR, List.of()));
    long initialOutboxCount = kafkaOutboxCount(null);
    jdbc.execute(
        "create function reject_task_board_kafka_outbox() returns trigger language plpgsql as $$ begin raise exception 'outbox rejected'; end $$");
    jdbc.execute(
        "create trigger reject_task_board_kafka_outbox before insert on outbox_event for each row execute function reject_task_board_kafka_outbox()");
    try {
      assertThatThrownBy(
              () ->
                  board.createTask(
                      W1,
                      externalTask(UUID.randomUUID(), queue.id(), "atomic-rollback")))
          .isInstanceOf(RuntimeException.class);
      assertThat(tasks.count()).isZero();
      assertThat(entries.count()).isZero();
      assertThat(kafkaOutboxCount(null)).isEqualTo(initialOutboxCount);
    } finally {
      jdbc.execute("drop trigger if exists reject_task_board_kafka_outbox on outbox_event");
      jdbc.execute("drop function if exists reject_task_board_kafka_outbox()");
    }
  }

  @Test
  void routeWithoutARealQueueIsRejected() {
    assertThatThrownBy(
            () ->
                board.createTask(
                    W1, externalTask(UUID.randomUUID(), null, "missing-real-queue")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("общей очереди");
    assertThat(tasks.count()).isZero();
    assertThat(entries.count()).isZero();
  }

  @Test
  void concurrentCompleteMoveAndCancelPreserveQueuePositionInvariants() throws Exception {
    var workerClass = registry.createClass(workerClass("QUEUE_RACE_WORKER"));
    var source =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "QUEUE_RACE_SOURCE",
                QueueType.MOVEMENT,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var target =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "QUEUE_RACE_TARGET",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Queue race worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    UUID completeExternal = UUID.randomUUID();
    UUID moveExternal = UUID.randomUUID();
    UUID cancelExternal = UUID.randomUUID();
    board.createTask(W1, externalTask(completeExternal, source.id(), "complete-race"));
    board.createTask(W1, externalTask(moveExternal, source.id(), "move-race"));
    board.createTask(W1, externalTask(cancelExternal, source.id(), "cancel-race"));
    var completeEntry = entry("complete-race");
    completeEntry =
        board.take(
            W1,
            completeEntry.id(),
            new TakeEntryRequest(completeEntry.version(), null, worker.id()),
            null);
    var moveEntry = entry("move-race");
    long cancelVersion = board.registration(W1, cancelExternal).taskVersion();

    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(3)) {
      BoardEntryDto finalCompleteEntry = completeEntry;
      var completeFuture =
          executor.submit(
              () -> {
                start.await();
                return board.complete(
                    W1,
                    finalCompleteEntry.id(),
                    new VersionCommand(finalCompleteEntry.version()),
                    null);
              });
      var moveFuture =
          executor.submit(
              () -> {
                start.await();
                try {
                  return board.move(
                      W1,
                      moveEntry.id(),
                      new MoveEntryRequest(
                          moveEntry.version(),
                          moveEntry.taskVersion(),
                          target.id(),
                          0,
                          moveEntry.scheduledDate()));
                } catch (RuntimeException conflict) {
                  return conflict;
                }
              });
      var cancelFuture =
          executor.submit(
              () -> {
                start.await();
                return board.cancelTask(
                    W1,
                    cancelExternal,
                    new CancelTaskRequest(cancelVersion, "concurrent cancellation"));
              });
      start.countDown();
      assertThat(completeFuture.get(10, TimeUnit.SECONDS).status()).isEqualTo(EntryStatus.DONE);
      assertThat(cancelFuture.get(10, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.CANCELLED);
      Object moveResult = moveFuture.get(10, TimeUnit.SECONDS);
      assertThat(moveResult)
          .matches(
              value -> value instanceof TaskBoardSnapshot || value instanceof StaleVersionException);
    }

    assertThat(tasks.findByWarehouseIdAndExternalTaskId(W1, completeExternal).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.DONE);
    assertThat(tasks.findByWarehouseIdAndExternalTaskId(W1, cancelExternal).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.CANCELLED);
    var unfinished =
        jdbc.queryForList(
            """
            select t.external_task_id, e.queue_position
              from queue_entry e
              join board_task t on t.id = e.task_id
             where t.warehouse_id = ?
               and e.status in ('WAITING', 'IN_PROGRESS', 'PAUSED')
            """,
            W1);
    assertThat(unfinished).hasSize(1);
    assertThat(unfinished.getFirst().get("external_task_id")).isEqualTo(moveExternal);
    assertThat(unfinished.getFirst().get("queue_position")).isEqualTo(0);
  }

  @Test
  void cancellationStopsActiveWorkAndIsIdempotentAfterCancellation() {
    var workerClass = registry.createClass(workerClass("CANCEL_WORKER"));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "CANCEL_QUEUE",
                QueueType.MOVEMENT,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Cancel worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Cancel group",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), null, true))));
    UUID externalTaskId = UUID.randomUUID();
    var created =
        board.createTask(W1, externalTask(externalTaskId, queue.id(), "cancel-active"));
    var entry =
        created.columns().stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    entry =
        board.take(
            W1, entry.id(), new TakeEntryRequest(entry.version(), group.id(), worker.id()), null);

    var cancelled =
        board.cancelTask(
            W1,
            externalTaskId,
            new CancelTaskRequest(entry.taskVersion(), "Отмена логистической операции"));

    assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(cancelled.cancelledAt()).isNotNull();
    assertThat(entries.findAllByTaskIdOrderByRouteIndexAsc(cancelled.taskId()))
        .allSatisfy(
            cancelledEntry -> {
              assertThat(cancelledEntry.getStatus()).isEqualTo(EntryStatus.CANCELLED);
              assertThat(cancelledEntry.getActiveStartedAt()).isNull();
              assertThat(cancelledEntry.getDoneAt()).isNotNull();
            });
    assertThat(assignments.findAllByQueueEntryId(entry.id()))
        .extracting(TaskAssignment::getStatus)
        .containsExactly(AssignmentStatus.CANCELLED);
    assertThat(board.history(W1, entry.id()))
        .filteredOn(event -> event.eventType() == TimeEventType.CANCELLED)
        .extracting(TimeEventDto::reason)
        .containsExactly("Отмена логистической операции");

    var repeated =
        board.cancelTask(
            W1,
            externalTaskId,
            new CancelTaskRequest(entry.taskVersion(), "Повторная отмена"));
    assertThat(repeated.taskId()).isEqualTo(cancelled.taskId());
    assertThat(repeated.taskVersion()).isEqualTo(cancelled.taskVersion());
    assertThat(repeated.status()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(timeEvents.countByQueueEntryIdAndEventType(entry.id(), TimeEventType.CANCELLED))
        .isEqualTo(1);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CANCELLED)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select aggregate_version from outbox_event where event_type=?",
                Long.class,
                TaskBoardEventTypes.BOARD_TASK_CANCELLED))
        .isEqualTo(cancelled.taskVersion());
    assertThat(
            jdbc.queryForObject(
                "select envelope_body::text from outbox_event where event_type=?",
                String.class,
                TaskBoardEventTypes.BOARD_TASK_CANCELLED))
        .doesNotContain("Отмена логистической операции", "Повторная отмена");
    var cancelledEntry = entries.findById(entry.id()).orElseThrow();
    LocalDate cancelledDate =
        tasks.findById(cancelled.taskId()).orElseThrow().getScheduledDate();
    assertThatThrownBy(
            () ->
                board.move(
                    W1,
                    cancelledEntry.getId(),
                    new MoveEntryRequest(
                        cancelledEntry.getVersion(),
                        cancelled.taskVersion(),
                        null,
                        0,
                        cancelledDate)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("отмененный");
  }

  @RepeatedTest(10)
  void atomicPreStartCancellationNeverCancelsWorkThatWinsTheStartRace() throws Exception {
    var queue =
        QueueRegistryTestFixtures.create(
            registry,
            jdbc,
            W1,
            queue("PRE_START_CANCEL_RACE", QueueType.REPAIR, List.of()));
    var worker =
        workforce.createWorker(
            W1, worker("Pre-start race worker", null, null, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    var registration =
        board.registerExternalTask(
            "logistics-service",
            new RegisterExternalTaskRequest(
                W1,
                externalTaskId,
                "Pre-start race",
                "CABIN-RACE",
                null,
                10,
                null,
                List.of(new RouteStepRequest(queue.definitionId(), "Repair", 10))));
    var route = registration.route().getFirst();

    CountDownLatch start = new CountDownLatch(1);
    Object takeResult;
    PreStartCancellationResult cancellation;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var takeFuture =
          executor.submit(
              () -> {
                start.await();
                try {
                  return board.take(
                      W1,
                      route.entryId(),
                      new TakeEntryRequest(route.entryVersion(), null, worker.id()),
                      null);
                } catch (RuntimeException conflict) {
                  return conflict;
                }
              });
      var cancelFuture =
          executor.submit(
              () -> {
                start.await();
                return board.cancelExternalTaskIfPreStart(
                    "logistics-service",
                    externalTaskId,
                    new CancelTaskRequest(
                        registration.taskVersion(), "inventory replacement"));
              });
      start.countDown();
      takeResult = takeFuture.get(10, TimeUnit.SECONDS);
      cancellation = cancelFuture.get(10, TimeUnit.SECONDS);
    }

    var storedTask =
        tasks.findByWarehouseIdAndExternalTaskId(W1, externalTaskId).orElseThrow();
    var storedEntry = entries.findById(route.entryId()).orElseThrow();
    if (cancellation.outcome() == PreStartCancellationOutcome.CANCELLED) {
      assertThat(takeResult).isInstanceOf(RuntimeException.class);
      assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.CANCELLED);
      assertThat(storedEntry.getStatus()).isEqualTo(EntryStatus.CANCELLED);
      assertThat(assignments.findAllByQueueEntryId(route.entryId())).isEmpty();
    } else {
      assertThat(cancellation.outcome()).isEqualTo(PreStartCancellationOutcome.STARTED);
      assertThat(takeResult).isInstanceOf(BoardEntryDto.class);
      assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.ACTIVE);
      assertThat(storedEntry.getStatus()).isEqualTo(EntryStatus.IN_PROGRESS);
      assertThat(assignments.findAllByQueueEntryId(route.entryId()))
          .extracting(TaskAssignment::getStatus)
          .containsExactly(AssignmentStatus.ACTIVE);
    }
  }

  @Test
  void cancellationRejectsStaleActiveAndCompletedTasks() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("CANCEL_CONFLICT", QueueType.MOVEMENT, List.of()));
    UUID staleExternalId = UUID.randomUUID();
    board.createTask(W1, externalTask(staleExternalId, queue.id(), "stale-cancel"));
    assertThatThrownBy(
            () ->
                board.cancelTask(
                    W1, staleExternalId, new CancelTaskRequest(99L, "Устаревшая команда")))
        .isInstanceOf(StaleVersionException.class);

    var workerClass = registry.createClass(workerClass("DONE_WORKER"));
    var boundQueue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "DONE_QUEUE",
                QueueType.MOVEMENT,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Done worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    UUID doneExternalId = UUID.randomUUID();
    var entry =
        board.createTask(W1, externalTask(doneExternalId, boundQueue.id(), "done-task"))
            .columns()
            .stream()
            .flatMap(column -> column.entries().stream())
            .filter(candidate -> candidate.externalTaskId().equals(doneExternalId))
            .findFirst()
            .orElseThrow();
    entry =
        board.take(
            W1, entry.id(), new TakeEntryRequest(entry.version(), null, worker.id()), null);
    board.complete(W1, entry.id(), new VersionCommand(entry.version()), null);
    var doneTask = tasks.findByWarehouseIdAndExternalTaskId(W1, doneExternalId).orElseThrow();
    assertThatThrownBy(
            () ->
                board.cancelTask(
                    W1,
                    doneExternalId,
                    new CancelTaskRequest(doneTask.getVersion(), "Поздняя отмена")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Завершенную задачу");
    var completedEntry = entries.findById(entry.id()).orElseThrow();
    assertThatThrownBy(
            () ->
                board.move(
                    W1,
                    completedEntry.getId(),
                    new MoveEntryRequest(
                        completedEntry.getVersion(),
                        doneTask.getVersion(),
                        null,
                        0,
                        doneTask.getScheduledDate())))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Завершенный");
  }

  @Test
  void queueWithTaskCannotBeDeletedButItsPresentationNameCanChange() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("REPAIR", QueueType.REPAIR, List.of()));
    board.createTask(W1, task(queue.id(), "task"));
    var current = registry.listQueues(W1).getFirst();
    assertThatThrownBy(() -> QueueRegistryTestFixtures.delete(registry, jdbc, W1, current.id(), current.version()))
        .isInstanceOf(ConflictException.class);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CANCELLED)).isZero();
    registry.updateQueueDefinition(
        current.definitionId(),
        globalDefinitionRequest(
            registry.dto(registry.requireQueueDefinition(current.definitionId())),
            "Renamed repair",
            null));
    var renamed = registry.listQueues(W1).getFirst();
    assertThat(renamed.id()).isEqualTo(current.id());
    assertThat(renamed.name()).isEqualTo("Renamed repair");
    assertThat(board.snapshot(W1, true).columns())
        .extracting(BoardColumnDto::queueName)
        .containsExactly("Renamed repair");
  }

  @Test
  void externalPlanReferenceBlocksGlobalDefinitionDelete() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("PLAN", QueueType.REPAIR, List.of()));
    var reference =
        registry.registerReference(
            queue.definitionId(),
            new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-1"));
    assertThatThrownBy(
            () ->
                registry.deleteQueueDefinition(
                    queue.definitionId(), queue.definitionVersion()))
        .isInstanceOf(ConflictException.class);
    assertThat(registry.listQueues(W1)).extracting(WorkQueueDto::id).containsExactly(queue.id());
    registry.deleteReference(
        reference.type(), reference.externalReferenceId(), reference.version());
    QueueDefinitionDto currentDefinition =
        registry.dto(registry.requireQueueDefinition(queue.definitionId()));
    registry.deleteQueueDefinition(queue.definitionId(), currentDefinition.version());
    assertThat(registry.listQueues(W1)).isEmpty();
  }

  @Test
  void groupRejectsCrossWarehouseMemberAndWorkerHistoryBlocksDelete() {
    var workerClass = registry.createClass(workerClass("REPAIR"));
    var worker =
        workforce.createWorker(
            W2,
            worker(
                "Worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var group =
        new WorkerGroupRequest(
            0L,
            workerClass.id(),
            "Group",
            null,
            true,
            List.of(new GroupMemberRequest(worker.id(), null, true)));
    assertThatThrownBy(() -> workforce.createGroup(W1, group))
        .isInstanceOf(NotFoundException.class);
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("WORKER_HISTORY", QueueType.REPAIR, List.of()));
    var entry =
        board.createTask(W2, task(queue.id(), "worker-history"))
            .columns()
            .stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    board.take(
        W2, entry.id(), new TakeEntryRequest(entry.version(), null, worker.id()), null);
    assertThatThrownBy(() -> workforce.deleteWorker(W2, worker.id(), worker.version()))
        .isInstanceOf(ConflictException.class);
    assertThat(credentials.deletes).hasValue(0);
  }

  @Test
  void groupUpdateCanReplaceOneWorkerWhileKeepingAnotherMember() {
    var workerClass = registry.createClass(workerClass("DRIVER"));
    var driverQualification =
        new QualificationRequest(workerClass.id(), true, "Водитель");
    var retained =
        workforce.createWorker(
            W1, worker("Retained worker", null, null, List.of(driverQualification)));
    var replaced =
        workforce.createWorker(
            W1, worker("Old driver", null, null, List.of(driverQualification)));
    var replacement =
        workforce.createWorker(
            W1, worker("New driver", null, null, List.of(driverQualification)));
    var created =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Drivers",
                null,
                true,
                List.of(
                    new GroupMemberRequest(retained.id(), "Рабочий", true),
                    new GroupMemberRequest(replaced.id(), "Водитель", true))));

    var updated =
        workforce.updateGroup(
            W1,
            created.id(),
            new WorkerGroupRequest(
                created.version(),
                workerClass.id(),
                created.name(),
                created.description(),
                created.active(),
                List.of(
                    new GroupMemberRequest(retained.id(), "Рабочий", true),
                    new GroupMemberRequest(replacement.id(), "Водитель", true))));

    assertThat(updated.members())
        .extracting(GroupMemberDto::workerId)
        .containsExactlyInAnyOrder(retained.id(), replacement.id());
  }

  @Test
  void groupRejectsWorkerWithoutMatchingActiveQualification() {
    var generalClass = registry.createClass(workerClass("GENERAL_GROUP"));
    var driverClass = registry.createClass(workerClass("DRIVER_GROUP"));
    var driver =
        workforce.createWorker(
            W1,
            worker(
                "Driver",
                null,
                null,
                List.of(new QualificationRequest(driverClass.id(), true, null))));

    assertThatThrownBy(
            () ->
                workforce.createGroup(
                    W1,
                    new WorkerGroupRequest(
                        0L,
                        generalClass.id(),
                        "General workers",
                        null,
                        true,
                        List.of(new GroupMemberRequest(driver.id(), true)))))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("не имеет квалификации");
  }

  @Test
  void workerUpdateCanRetainQualificationWithoutNaturalKeyConflict() {
    var workerClass = registry.createClass(workerClass("MOVEMENT"));
    var qualification = new QualificationRequest(workerClass.id(), true, "Основная");
    var created =
        workforce.createWorker(
            W1, worker("Movement worker", null, null, List.of(qualification)));

    var updated =
        workforce.updateWorker(
            W1,
            created.id(),
            new WorkerRequest(
                created.version(),
                "Movement worker updated",
                null,
                null,
                null,
                true,
                "Смена профиля",
                null,
                null,
                List.of(qualification)));

    assertThat(updated.displayName()).isEqualTo("Movement worker updated");
    assertThat(updated.qualifications())
        .singleElement()
        .satisfies(
            retained -> {
              assertThat(retained.workerClass().id()).isEqualTo(workerClass.id());
              assertThat(retained.comment()).isEqualTo("Основная");
            });
  }

  @Test
  void disablingAndEnablingWorkerLoginPreservesLoginAndPasswordState() {
    var created =
        workforce.createWorker(
            W1, worker("Worker", "worker.toggle", "password-123", List.of()));

    var disabled = workforce.disableCredentials(W1, created.id(), created.version());

    assertThat(disabled.credentialStatus()).isEqualTo(CredentialStatus.DISABLED);
    assertThat(disabled.appLogin()).isEqualTo("worker.toggle");
    assertThat(credentials.externalCredentialState).hasValue("DISABLED");

    var passwordReset =
        workforce.resetPassword(
            W1, disabled.id(), disabled.version(), "password-rotated");

    assertThat(passwordReset.credentialStatus()).isEqualTo(CredentialStatus.DISABLED);
    assertThat(passwordReset.appLogin()).isEqualTo("worker.toggle");
    assertThat(credentials.externalCredentialState).hasValue("DISABLED");

    var enabled =
        workforce.enableCredentials(W1, passwordReset.id(), passwordReset.version());

    assertThat(enabled.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
    assertThat(enabled.appLogin()).isEqualTo("worker.toggle");
    assertThat(credentials.externalCredentialState).hasValue("ACTIVE");
  }

  @Test
  void credentialFailureDoesNotRollbackWorker() {
    credentials.failConfigure.set(true);
    var created =
        workforce.createWorker(W1, worker("Worker", "worker.login", "password-123", List.of()));
    assertThat(created.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(workforce.listWorkers(W1)).hasSize(1);
    assertThat(created).extracting(WorkerDto::appLogin).isEqualTo("worker.login");
  }

  @Test
  void failedCredentialConfigureWithOperationMetadataCanBeDeletedWhenAuthIsAbsent() {
    var workerClass = registry.createClass(workerClass("REJECTED_DELETE"));
    credentials.failConfigure.set(true);
    var created =
        workforce.createWorker(
            W1,
            worker(
                "Rejected worker",
                "worker.rejected",
                "password-123",
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Rejected worker group",
                null,
                true,
                List.of(new GroupMemberRequest(created.id(), null, true))));

    assertThat(created.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(credentials.externalCredentialState).hasValue(null);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from worker
                 where id = ?
                   and credential_operation_id is not null
                   and credential_operation_type = 'CONFIGURE'
                   and credential_operation_started_at is not null
                """,
                Integer.class,
                created.id()))
        .isEqualTo(1);

    workforce.deleteWorker(W1, created.id(), created.version());

    assertThat(workforce.listWorkers(W1)).isEmpty();
    assertThat(credentials.deletes).hasValue(1);
    assertThat(deletionIntents.findByWorkerId(created.id())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_class_assignment where worker_id = ?",
                Integer.class,
                created.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_group_member where worker_id = ?",
                Integer.class,
                created.id()))
        .isZero();
    assertThat(workforce.listGroups(W1))
        .singleElement()
        .satisfies(
            remainingGroup -> {
              assertThat(remainingGroup.id()).isEqualTo(group.id());
              assertThat(remainingGroup.version()).isGreaterThan(group.version());
              assertThat(remainingGroup.members()).isEmpty();
            });
  }

  @Test
  void failedCredentialConfigureCanBeRetriedWithDifferentLogin() {
    credentials.failConfigure.set(true);
    var failed =
        workforce.createWorker(
            W1, worker("Retry worker", "occupied.login", "password-123", List.of()));
    assertThat(failed.credentialStatus()).isEqualTo(CredentialStatus.ERROR);

    credentials.failConfigure.set(false);
    var recovered =
        workforce.updateWorker(
            W1,
            failed.id(),
            workerWithVersion(
                failed.version(), "Retry worker updated", "available.login", "password-456"));

    assertThat(recovered.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
    assertThat(recovered.appLogin()).isEqualTo("available.login");
    assertThat(recovered.displayName()).isEqualTo("Retry worker updated");
    assertThat(credentials.lastConfiguredLogin).hasValue("available.login");
    assertThat(credentials.externalCredentialState).hasValue("ACTIVE");
  }

  @Test
  void pendingCredentialOperationStillBlocksWorkerDeletion() {
    var created =
        workforce.createWorker(W1, worker("Pending worker", null, null, List.of()));
    jdbc.update(
        """
        update worker
           set credential_status = 'PENDING',
               credential_operation_id = ?,
               credential_operation_type = 'CONFIGURE',
               credential_operation_started_at = clock_timestamp(),
               version = version + 1
         where id = ?
        """,
        UUID.randomUUID(),
        created.id());
    var pending = workforce.listWorkers(W1).getFirst();

    assertThatThrownBy(
            () -> workforce.deleteWorker(W1, pending.id(), pending.version()))
        .isInstanceOf(ConflictException.class);
    assertThat(workforce.listWorkers(W1)).hasSize(1);
    assertThat(credentials.deletes).hasValue(0);
    assertThat(deletionIntents.findByWorkerId(created.id())).isEmpty();
  }

  @Test
  void loginUpdateIsSagaSafeAndClearPublishesOnlyAfterDisable() throws Exception {
    var created =
        workforce.createWorker(W1, worker("Worker", "old.login", "password-123", List.of()));
    assertThatThrownBy(
            () ->
                workforce.updateWorker(
                    W1,
                    created.id(),
                    workerWithVersion(created.version(), "Worker", "new.login", null)))
        .isInstanceOf(ConflictException.class);
    var unchanged = workforce.listWorkers(W1).getFirst();
    assertThat(unchanged.appLogin()).isEqualTo("old.login");
    assertThat(unchanged.version()).isEqualTo(created.version());

    credentials.failConfigure.set(true);
    var failed =
        workforce.updateWorker(
            W1,
            created.id(),
            workerWithVersion(created.version(), "Worker updated", "new.login", "password-456"));
    assertThat(failed.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(failed.appLogin()).isEqualTo("old.login");
    assertThat(credentials.lastConfiguredLogin).hasValue("new.login");

    var profileUpdatedAfterFailure =
        workforce.updateWorker(
            W1,
            failed.id(),
            workerWithVersion(
                failed.version(), "Worker profile updated", "old.login", null));
    assertThat(profileUpdatedAfterFailure.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(profileUpdatedAfterFailure.appLogin()).isEqualTo("old.login");
    assertThatThrownBy(
            () ->
                workforce.reconcileDisableCredentials(
                    W1,
                    profileUpdatedAfterFailure.id(),
                    profileUpdatedAfterFailure.version()))
        .isInstanceOf(ConflictException.class);
    Thread.sleep(1_250);
    var converged =
        workforce.reconcileDisableCredentials(
            W1,
            profileUpdatedAfterFailure.id(),
            profileUpdatedAfterFailure.version());
    assertThat(converged.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
    assertThat(converged.appLogin()).isEqualTo("old.login");

    credentials.failConfigure.set(false);
    credentials.failDisable.set(true);
    assertThatThrownBy(
            () ->
                workforce.updateWorker(
                    W1,
                    converged.id(),
                    workerWithVersion(converged.version(), "Worker updated", null, null)))
        .isInstanceOf(ExternalServiceException.class);
    var clearFailed = workforce.listWorkers(W1).getFirst();
    assertThat(clearFailed.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(clearFailed.appLogin()).isEqualTo("old.login");

    credentials.failDisable.set(false);
    var cleared =
        workforce.updateWorker(
            W1,
            clearFailed.id(),
            workerWithVersion(clearFailed.version(), "Worker updated", null, null));
    assertThat(cleared.credentialStatus()).isEqualTo(CredentialStatus.NOT_CONFIGURED);
    assertThat(cleared.appLogin()).isNull();
  }

  @Test
  void pendingCredentialOperationBlocksMutationsAndReconcileRunsOnlyAfterFailure()
      throws Exception {
    var created =
        workforce.createWorker(W1, worker("Worker", "worker.fenced", "password-123", List.of()));
    credentials.delayReset.set(true);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reset =
          executor.submit(
              () ->
                  workforce.resetPassword(
                      W1, created.id(), created.version(), "password-reset"));
      assertThat(credentials.resetEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var pending = workforce.listWorkers(W1).getFirst();
      assertThat(pending.credentialStatus()).isEqualTo(CredentialStatus.PENDING);

      assertThatThrownBy(() -> workforce.disableCredentials(W1, created.id(), pending.version()))
          .isInstanceOf(ConflictException.class);
      assertThatThrownBy(
              () ->
                  workforce.updateWorker(
                      W1,
                      created.id(),
                      workerWithVersion(
                          pending.version(), "Cannot mutate", "worker.fenced", null)))
          .isInstanceOf(ConflictException.class);
      Thread.sleep(1_250);
      assertThatThrownBy(
              () ->
                  workforce.reconcileDisableCredentials(W1, created.id(), pending.version()))
          .isInstanceOf(ConflictException.class);
      assertThat(credentials.disables).hasValue(0);

      credentials.releaseReset.countDown();
      var resetResult = reset.get(5, TimeUnit.SECONDS);
      assertThat(resetResult.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
      assertThat(resetResult.appLogin()).isEqualTo("worker.fenced");
      assertThat(credentials.externalCredentialState).hasValue("ACTIVE");

      credentials.failDisable.set(true);
      assertThatThrownBy(
              () ->
                  workforce.disableCredentials(W1, created.id(), resetResult.version()))
          .isInstanceOf(ExternalServiceException.class);
      var failed = workforce.listWorkers(W1).getFirst();
      assertThat(failed.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
      assertThat(credentials.externalCredentialState).hasValue("ACTIVE");

      credentials.failDisable.set(false);
      assertThatThrownBy(
              () ->
                  workforce.reconcileDisableCredentials(
                      W1, created.id(), failed.version()))
          .isInstanceOf(ConflictException.class);
      Thread.sleep(1_250);
      var reconciled =
          workforce.reconcileDisableCredentials(W1, created.id(), failed.version());
      assertThat(reconciled.credentialStatus()).isEqualTo(CredentialStatus.DISABLED);
      assertThat(reconciled.appLogin()).isEqualTo("worker.fenced");
      assertThat(credentials.externalCredentialState).hasValue("DISABLED");
    }
  }

  @Test
  void expiredOrphanCredentialOperationCanBeRecoveredAfterSessionLockIsGone() {
    var created =
        workforce.createWorker(W1, worker("Orphan", "worker.orphan", "password-123", List.of()));
    UUID orphanOperationId = UUID.randomUUID();
    jdbc.update(
        """
        update worker
           set credential_status = 'PENDING',
               credential_error = null,
               credential_operation_id = ?,
               credential_operation_type = 'RESET',
               credential_operation_started_at = clock_timestamp() + interval '1 hour',
               version = version + 1
         where id = ?
        """,
        orphanOperationId,
        created.id());
    var nonExpired = workforce.listWorkers(W1).getFirst();

    assertThatThrownBy(
            () ->
                workforce.reconcileDisableCredentials(
                    W1, created.id(), nonExpired.version()))
        .isInstanceOf(ConflictException.class);
    assertThat(credentials.disables).hasValue(0);
    jdbc.update(
        "update worker set credential_operation_started_at = clock_timestamp() - interval '1 hour', version = version + 1 where id = ?",
        created.id());
    var orphan = workforce.listWorkers(W1).getFirst();

    var recovered =
        workforce.reconcileDisableCredentials(W1, created.id(), orphan.version());

    assertThat(recovered.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
    assertThat(recovered.appLogin()).isEqualTo("worker.orphan");
    assertThat(credentials.externalCredentialState).hasValue("ACTIVE");
    assertThat(credentials.disables).hasValue(0);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker where id = ? and credential_operation_id is null and credential_operation_type is null and credential_operation_started_at is null",
                Integer.class,
                created.id()))
        .isEqualTo(1);

    credentials.externalCredentialState.set("DISABLED");
    jdbc.update(
        """
        update worker
           set credential_status = 'PENDING',
               credential_operation_id = ?,
               credential_operation_type = 'RESET',
               credential_operation_started_at = clock_timestamp() - interval '1 hour',
               version = version + 1
         where id = ?
        """,
        UUID.randomUUID(),
        created.id());
    var externallyDisabled = workforce.listWorkers(W1).getFirst();

    var disabledRecovery =
        workforce.reconcileDisableCredentials(
            W1, created.id(), externallyDisabled.version());

    assertThat(disabledRecovery.credentialStatus()).isEqualTo(CredentialStatus.DISABLED);
    assertThat(disabledRecovery.appLogin()).isEqualTo("worker.orphan");
    assertThat(credentials.disables).hasValue(0);
  }

  @Test
  void legacyPendingCredentialWithoutMetadataIsRecoveredByForcedDisable() {
    var created =
        workforce.createWorker(W1, worker("Legacy", "worker.legacy", "password-123", List.of()));
    jdbc.update(
        """
        update worker
           set credential_status = 'PENDING',
               credential_operation_id = null,
               credential_operation_type = null,
               credential_operation_started_at = null,
               version = version + 1
         where id = ?
        """,
        created.id());
    var legacy = workforce.listWorkers(W1).getFirst();

    var recovered =
        workforce.reconcileDisableCredentials(W1, created.id(), legacy.version());

    assertThat(recovered.credentialStatus()).isEqualTo(CredentialStatus.DISABLED);
    assertThat(recovered.appLogin()).isEqualTo("worker.legacy");
    assertThat(credentials.externalCredentialState).hasValue("DISABLED");
    assertThat(credentials.disables).hasValue(1);
  }

  @Test
  void legacyNotConfiguredProjectionRestoresDisabledLoginFromAuth() {
    var created =
        workforce.createWorker(
            W1, worker("Legacy disabled", "worker.disabled", "password-123", List.of()));
    credentials.disable(created.id());
    jdbc.update(
        """
        update worker
           set app_login = null,
               credential_status = 'NOT_CONFIGURED',
               credential_operation_id = null,
               credential_operation_type = null,
               credential_operation_started_at = null,
               version = version + 1
         where id = ?
        """,
        created.id());
    var legacy = workforce.listWorkers(W1).getFirst();

    var recovered =
        workforce.reconcileDisableCredentials(W1, legacy.id(), legacy.version());

    assertThat(recovered.credentialStatus()).isEqualTo(CredentialStatus.DISABLED);
    assertThat(recovered.appLogin()).isEqualTo("worker.disabled");
    assertThat(credentials.externalCredentialState).hasValue("DISABLED");
  }

  @Test
  void completionIsFencedByOperationIdAndCannotClearReplacementOperation() throws Exception {
    var created =
        workforce.createWorker(W1, worker("Fenced", "worker.operation", "password-123", List.of()));
    credentials.delayReset.set(true);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reset =
          executor.submit(
              () ->
                  workforce.resetPassword(
                      W1, created.id(), created.version(), "password-reset"));
      assertThat(credentials.resetEntered.await(5, TimeUnit.SECONDS)).isTrue();
      UUID replacementOperationId = UUID.randomUUID();
      jdbc.update(
          "update worker set credential_operation_id = ?, version = version + 1 where id = ?",
          replacementOperationId,
          created.id());

      credentials.releaseReset.countDown();
      var result = reset.get(5, TimeUnit.SECONDS);

      assertThat(result.credentialStatus()).isEqualTo(CredentialStatus.PENDING);
      assertThat(
              jdbc.queryForObject(
                  "select credential_operation_id from worker where id = ?",
                  UUID.class,
                  created.id()))
          .isEqualTo(replacementOperationId);
      assertThat(credentials.externalCredentialState).hasValue("ACTIVE");
    }
  }

  @Test
  void failedDeleteKeepsPublicWorkerUnchangedAndCanBeReconciled() {
    var created = workforce.createWorker(W1, worker("Delete me", null, null, List.of()));
    credentials.failDelete.set(true);
    assertThatThrownBy(() -> workforce.deleteWorker(W1, created.id(), created.version()))
        .isInstanceOf(ExternalServiceException.class);
    var unchanged = workforce.listWorkers(W1).getFirst();
    assertThat(unchanged.version()).isEqualTo(created.version());
    assertThat(unchanged.displayName()).isEqualTo(created.displayName());
    assertThat(unchanged.active()).isEqualTo(created.active());
    assertThat(unchanged.credentialStatus()).isEqualTo(created.credentialStatus());
    assertThat(deletionIntents.findByWorkerId(created.id()).orElseThrow().getStatus())
        .isEqualTo(WorkerDeletionStatus.ERROR);

    credentials.failDelete.set(false);
    workforce.deleteWorker(W1, created.id(), created.version());
    assertThat(workforce.listWorkers(W1)).isEmpty();
    assertThat(deletionIntents.findByWorkerId(created.id())).isEmpty();
  }

  @Test
  void stopOnTakeResumesOnlyItsAutoInterruptedTask() {
    var workerClass = registry.createClass(workerClass("REPAIR"));
    var q1 =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "NORMAL",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var q2 =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "URGENT",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), true))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Group",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), null, true))));
    var first =
        board.createTask(W1, task(q1.id(), "first")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .filter(e -> e.title().equals("first"))
            .findFirst()
            .orElseThrow();
    first =
        board.take(
            W1, first.id(), new TakeEntryRequest(first.version(), group.id(), worker.id()), null);
    var second =
        board.createTask(W1, task(q2.id(), "second")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .filter(e -> e.title().equals("second"))
            .findFirst()
            .orElseThrow();
    second =
        board.take(
            W1, second.id(), new TakeEntryRequest(second.version(), group.id(), worker.id()), null);
    assertThat(entry("first").status()).isEqualTo(EntryStatus.PAUSED);
    assertThat(
            timeEvents.countByQueueEntryIdAndEventType(first.id(), TimeEventType.AUTO_INTERRUPTED))
        .isEqualTo(1);
    assertThat(board.history(W1, first.id()))
        .filteredOn(event -> event.eventType() == TimeEventType.AUTO_INTERRUPTED)
        .extracting(TimeEventDto::relatedEntryId)
        .containsExactly(second.id());
    board.complete(W1, second.id(), new VersionCommand(second.version()), null);
    assertThat(entry("first").status()).isEqualTo(EntryStatus.IN_PROGRESS);
    assertThat(board.history(W1, first.id()))
        .filteredOn(event -> event.eventType() == TimeEventType.AUTO_RESUMED)
        .extracting(TimeEventDto::relatedEntryId)
        .containsExactly(second.id());
  }

  @Test
  void primaryTakeRequiresUrgentSecondaryAndSecondaryPausesAndResumesWholeBrigade() {
    var driverClass = registry.createClass(workerClass("DRIVER_URGENT"));
    var slingerClass = registry.createClass(workerClass("SLINGER_URGENT"));
    var generalClass = registry.createClass(workerClass("GENERAL_URGENT"));
    var repairQueue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "BRIGADE_REPAIR",
                QueueType.REPAIR,
                List.of(
                    new QueueBindingRequest(
                        generalClass.id(), 0, false, ParticipationPolicy.PRIMARY, false))));
    var movementQueue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "DRIVER_WITH_SLINGER",
                QueueType.MOVEMENT,
                List.of(
                    new QueueBindingRequest(
                        driverClass.id(), 0, false, ParticipationPolicy.PRIMARY, false),
                    new QueueBindingRequest(
                        slingerClass.id(), 1, true, ParticipationPolicy.REQUIRED, true))));
    var driver =
        workforce.createWorker(
            W1,
            worker(
                "Driver",
                null,
                null,
                List.of(new QualificationRequest(driverClass.id(), true, null))));
    var slinger =
        workforce.createWorker(
            W1,
            worker(
                "Slinger",
                null,
                null,
                List.of(
                    new QualificationRequest(generalClass.id(), true, null),
                    new QualificationRequest(slingerClass.id(), true, null))));
    var brigadeMate =
        workforce.createWorker(
            W1,
            worker(
                "Brigade mate",
                null,
                null,
                List.of(new QualificationRequest(generalClass.id(), true, null))));
    var repairBrigade =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                generalClass.id(),
                "Repair brigade",
                null,
                true,
                List.of(
                    new GroupMemberRequest(slinger.id(), true),
                    new GroupMemberRequest(brigadeMate.id(), true))));
    var driverGroup =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                driverClass.id(),
                "Driver group",
                null,
                true,
                List.of(new GroupMemberRequest(driver.id(), true))));
    workforce.setCurrentGroup(
        W1,
        driver.id(),
        new SetCurrentGroupRequest(driver.version(), driverGroup.id()));
    workforce.setCurrentGroup(
        W1,
        slinger.id(),
        new SetCurrentGroupRequest(slinger.version(), repairBrigade.id()));

    var repair =
        board.createTask(W1, task(repairQueue.id(), "repair")).columns().stream()
            .flatMap(column -> column.entries().stream())
            .filter(entry -> entry.title().equals("repair"))
            .findFirst()
            .orElseThrow();
    repair =
        board.take(
            W1,
            repair.id(),
            new TakeEntryRequest(repair.version(), repairBrigade.id(), null),
            null);
    var movement =
        board.createTask(W1, task(movementQueue.id(), "movement")).columns().stream()
            .flatMap(column -> column.entries().stream())
            .filter(entry -> entry.title().equals("movement"))
            .findFirst()
            .orElseThrow();
    movement =
        board.take(
            W1,
            movement.id(),
            new TakeEntryRequest(movement.version(), null, driver.id()),
            driver.id());
    BoardEntryDto awaitingSecondary = movement;

    assertThatThrownBy(
            () ->
                board.complete(
                    W1,
                    awaitingSecondary.id(),
                    new VersionCommand(awaitingSecondary.version()),
                    driver.id()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("вторичного исполнителя");
    movement =
        board.take(
            W1,
            movement.id(),
            new TakeEntryRequest(
                movement.version(), repairBrigade.id(), slinger.id()),
            slinger.id());

    BoardEntryDto pausedRepair = entry("repair");
    assertThat(pausedRepair.status()).isEqualTo(EntryStatus.PAUSED);
    assertThat(pausedRepair.assignments())
        .extracting(AssignmentDto::status)
        .containsOnly(AssignmentStatus.PAUSED);
    assertThat(movement.assignments())
        .extracting(AssignmentDto::workerId)
        .containsExactlyInAnyOrder(driver.id(), slinger.id());

    board.complete(
        W1, movement.id(), new VersionCommand(movement.version()), slinger.id());

    BoardEntryDto resumedRepair = entry("repair");
    assertThat(resumedRepair.status()).isEqualTo(EntryStatus.IN_PROGRESS);
    assertThat(resumedRepair.assignments())
        .extracting(AssignmentDto::status)
        .containsOnly(AssignmentStatus.ACTIVE);
  }

  @Test
  void secondaryWorkerWaitsForPrimaryThenSeesMandatoryUrgentTask() {
    var driverClass = registry.createClass(workerClass("DRIVER_VISIBILITY"));
    var slingerClass = registry.createClass(workerClass("SLINGER_VISIBILITY"));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "MOVEMENT_VISIBILITY",
                QueueType.MOVEMENT,
                List.of(
                    new QueueBindingRequest(
                        driverClass.id(), 0, false, ParticipationPolicy.PRIMARY, false),
                    new QueueBindingRequest(
                        slingerClass.id(), 1, true, ParticipationPolicy.REQUIRED, true))));
    var driver =
        workforce.createWorker(
            W1,
            worker(
                "Driver visibility",
                null,
                null,
                List.of(new QualificationRequest(driverClass.id(), true, null))));
    var slinger =
        workforce.createWorker(
            W1,
            worker(
                "Slinger visibility",
                null,
                null,
                List.of(new QualificationRequest(slingerClass.id(), true, null))));
    var driverGroup =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                driverClass.id(),
                "Driver visibility group",
                null,
                true,
                List.of(new GroupMemberRequest(driver.id(), true))));
    var slingerGroup =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                slingerClass.id(),
                "Slinger visibility group",
                null,
                true,
                List.of(new GroupMemberRequest(slinger.id(), true))));
    workforce.setCurrentGroup(
        W1,
        driver.id(),
        new SetCurrentGroupRequest(driver.version(), driverGroup.id()));
    workforce.setCurrentGroup(
        W1,
        slinger.id(),
        new SetCurrentGroupRequest(slinger.version(), slingerGroup.id()));
    var waiting =
        board.createTask(W1, task(queue.id(), "visible movement")).columns().stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();

    assertThat(workerBoard.detail(slinger.id(), W1, waiting.id()).availabilityMode())
        .isEqualTo("SECONDARY_PENDING");
    var started =
        board.take(
            W1,
            waiting.id(),
            new TakeEntryRequest(waiting.version(), null, driver.id()),
            driver.id());

    assertThat(workerBoard.detail(slinger.id(), W1, started.id()).availabilityMode())
        .isEqualTo("REQUIRED_JOIN");
  }

  @Test
  void directWorkerNeedsMatchingActiveQualification() {
    var required = registry.createClass(workerClass("REQUIRED"));
    var other = registry.createClass(workerClass("OTHER"));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "BOUND", QueueType.REPAIR, List.of(new QueueBindingRequest(required.id(), true))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Worker", null, null, List.of(new QualificationRequest(other.id(), true, null))));
    var entry =
        board.createTask(W1, task(queue.id(), "task")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                board.take(
                    W1, entry.id(), new TakeEntryRequest(entry.version(), null, worker.id()), null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void moveInsertsAtTargetAndNormalizesBothQueues() {
    var q1 = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ONE", QueueType.REPAIR, List.of()));
    var q2 = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("TWO", QueueType.REPAIR, List.of()));
    var moving =
        board.createTask(W1, task(q1.id(), "moving")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .filter(e -> e.title().equals("moving"))
            .findFirst()
            .orElseThrow();
    board.createTask(W1, task(q1.id(), "left"));
    board.createTask(W1, task(q2.id(), "target"));
    var snapshot =
        board.move(
            W1,
            moving.id(),
            new MoveEntryRequest(
                moving.version(), moving.taskVersion(), q2.id(), 0, moving.scheduledDate()));
    var source =
        snapshot.columns().stream()
            .filter(c -> q1.id().equals(c.queueId()))
            .findFirst()
            .orElseThrow();
    var target =
        snapshot.columns().stream()
            .filter(c -> q2.id().equals(c.queueId()))
            .findFirst()
            .orElseThrow();
    assertThat(source.entries()).extracting(BoardEntryDto::queuePosition).containsExactly(0);
    assertThat(target.entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("moving", "target");
    assertThat(target.entries()).extracting(BoardEntryDto::queuePosition).containsExactly(0, 1);
  }

  @Test
  void sameDayMovePropagatesToCompanionRouteQueuesBehindActiveAndPinnedBarriers() {
    var workerClass = registry.createClass(workerClass("ROUTE_ORDER_WORKER"));
    var firstQueue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "ROUTE_ORDER_FIRST",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var secondQueue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1, queue("ROUTE_ORDER_SECOND", QueueType.REPAIR, List.of()));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Route order worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    LocalDate date = LocalDate.of(2026, 7, 24);
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(firstQueue.id(), secondQueue.id()), "active", date, 3));
    board.createTask(W1, scheduledTask(firstQueue.id(), "pinned", date, 3));
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(firstQueue.id(), secondQueue.id()), "middle", date, 3));
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(firstQueue.id(), secondQueue.id()), "moving", date, 3));
    BoardEntryDto pinned = entry(date, firstQueue.id(), "pinned");
    board.pin(W1, pinned.taskId(), new PinTaskRequest(pinned.taskVersion(), true));
    BoardEntryDto active = entry(date, firstQueue.id(), "active");
    board.take(
        W1,
        active.id(),
        new TakeEntryRequest(active.version(), null, worker.id()),
        null);

    BoardEntryDto moving = entry(date, firstQueue.id(), "moving");
    TaskBoardSnapshot moved =
        board.move(
            W1,
            moving.id(),
            new MoveEntryRequest(
                moving.version(),
                moving.taskVersion(),
                firstQueue.id(),
                0,
                date));

    assertThat(queueEntries(moved, firstQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("active", "pinned", "moving", "middle");
    assertThat(queueEntries(moved, secondQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("active", "moving", "middle");
    assertThat(queueEntries(moved, firstQueue.id()))
        .extracting(BoardEntryDto::queuePosition)
        .containsExactly(0, 1, 2, 3);
    assertThat(queueEntries(moved, secondQueue.id()))
        .extracting(BoardEntryDto::queuePosition)
        .containsExactly(0, 1, 2);
  }

  @Test
  void dateMoveMovesEveryRouteEntryAndKeepsTheRequestedOrdinalInEachQueue() {
    var sourceQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ROUTE_DATE_SOURCE", QueueType.REPAIR, List.of()));
    var companionQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ROUTE_DATE_COMPANION", QueueType.REPAIR, List.of()));
    var targetQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ROUTE_DATE_TARGET", QueueType.REPAIR, List.of()));
    LocalDate firstDate = LocalDate.of(2026, 7, 24);
    LocalDate secondDate = firstDate.plusDays(1);
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(sourceQueue.id(), companionQueue.id()), "moving", firstDate, 3));
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(sourceQueue.id(), companionQueue.id()), "remaining", firstDate, 3));
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(companionQueue.id(), targetQueue.id()), "planned-first", secondDate, 3));
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(companionQueue.id(), targetQueue.id()), "planned-last", secondDate, 3));
    BoardEntryDto moving = entry(firstDate, sourceQueue.id(), "moving");

    TaskBoardSnapshot moved =
        board.move(
            W1,
            moving.id(),
            new MoveEntryRequest(
                moving.version(),
                moving.taskVersion(),
                targetQueue.id(),
                1,
                secondDate));

    assertThat(moved.selectedDate()).isEqualTo(secondDate);
    assertThat(queueEntries(moved, targetQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("planned-first", "moving", "planned-last");
    assertThat(queueEntries(moved, companionQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("planned-first", "moving", "planned-last");
    assertThat(queueEntries(board.snapshot(W1, firstDate, true), sourceQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("remaining");
    assertThat(queueEntries(board.snapshot(W1, firstDate, true), companionQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("remaining");
  }

  @Test
  void swappingDateColumnsKeepsTaskMembershipAndPersistsTheCommonRouteDate() {
    var repairQueue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("DATE_SWAP_REPAIR", QueueType.REPAIR, List.of()));
    var verificationQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("DATE_SWAP_VERIFY", QueueType.REPAIR, List.of()));
    LocalDate firstDate = LocalDate.of(2026, 7, 24);
    LocalDate secondDate = firstDate.plusDays(1);
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(repairQueue.id(), verificationQueue.id()), "first-route", firstDate, 3));
    board.createTask(W1, scheduledTask(repairQueue.id(), "first-single", firstDate, 3));
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(repairQueue.id(), verificationQueue.id()), "second-route", secondDate, 3));
    board.createTask(W1, scheduledTask(verificationQueue.id(), "second-single", secondDate, 3));

    TaskBoardSnapshot firstBefore = board.snapshot(W1, firstDate, true);
    TaskBoardSnapshot secondBefore = board.snapshot(W1, secondDate, true);
    List<BoardEntryDto> firstBeforeEntries = boardEntries(firstBefore);
    List<BoardEntryDto> secondBeforeEntries = boardEntries(secondBefore);
    Map<UUID, String> physicalPlacementBefore = new java.util.LinkedHashMap<>();
    for (BoardEntryDto entry : firstBeforeEntries) {
      physicalPlacementBefore.put(
          entry.id(), entry.queueId() + ":" + entry.queuePosition() + ":" + entry.version());
    }
    for (BoardEntryDto entry : secondBeforeEntries) {
      physicalPlacementBefore.put(
          entry.id(), entry.queueId() + ":" + entry.queuePosition() + ":" + entry.version());
    }
    UUID firstRouteTaskId =
        firstBeforeEntries.stream()
            .filter(entry -> entry.title().equals("first-route"))
            .findFirst()
            .orElseThrow()
            .taskId();

    TaskBoardSnapshot firstAfter =
        board.swapDates(
            W1, swapDateColumnsRequest(firstDate, firstBefore, secondDate, secondBefore));
    TaskBoardSnapshot secondAfter = board.snapshot(W1, secondDate, true);

    assertThat(firstAfter.selectedDate()).isEqualTo(firstDate);
    assertThat(boardEntries(firstAfter))
        .extracting(BoardEntryDto::id)
        .containsExactlyInAnyOrderElementsOf(
            secondBeforeEntries.stream().map(BoardEntryDto::id).toList());
    assertThat(boardEntries(secondAfter))
        .extracting(BoardEntryDto::id)
        .containsExactlyInAnyOrderElementsOf(
            firstBeforeEntries.stream().map(BoardEntryDto::id).toList());
    assertThat(boardEntries(firstAfter))
        .extracting(BoardEntryDto::scheduledDate)
        .containsOnly(firstDate);
    assertThat(boardEntries(secondAfter))
        .extracting(BoardEntryDto::scheduledDate)
        .containsOnly(secondDate);

    Map<UUID, String> physicalPlacementAfter = new java.util.LinkedHashMap<>();
    for (BoardEntryDto entry : boardEntries(firstAfter)) {
      physicalPlacementAfter.put(
          entry.id(), entry.queueId() + ":" + entry.queuePosition() + ":" + entry.version());
    }
    for (BoardEntryDto entry : boardEntries(secondAfter)) {
      physicalPlacementAfter.put(
          entry.id(), entry.queueId() + ":" + entry.queuePosition() + ":" + entry.version());
    }
    assertThat(physicalPlacementAfter).containsExactlyInAnyOrderEntriesOf(physicalPlacementBefore);

    assertThat(entries.findAllByTaskIdOrderByRouteIndexAsc(firstRouteTaskId)).hasSize(2);
    assertThat(
            jdbc.queryForList(
                """
                select task.scheduled_date
                  from queue_entry entry
                  join board_task task on task.id = entry.task_id
                 where entry.task_id = ?
                 order by entry.route_index
                """,
                LocalDate.class,
                firstRouteTaskId))
        .containsExactly(secondDate, secondDate);
    assertThat(
            jdbc.queryForObject(
                "select scheduled_date from board_task where id = ?",
                LocalDate.class,
                firstRouteTaskId))
        .isEqualTo(secondDate);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CHANGED)).isEqualTo(4);
  }

  @Test
  void routePropagationPreservesRealShadowQueueSwap() {
    var firstQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ROUTE_SWAP_FIRST", QueueType.REPAIR, List.of()));
    var secondQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ROUTE_SWAP_SECOND", QueueType.REPAIR, List.of()));
    LocalDate date = LocalDate.of(2026, 7, 24);
    board.createTask(
        W1,
        scheduledRouteTask(
            List.of(firstQueue.id(), secondQueue.id()), "moving", date, 3));
    BoardEntryDto moving = entry(date, firstQueue.id(), "moving");

    TaskBoardSnapshot moved =
        board.move(
            W1,
            moving.id(),
            new MoveEntryRequest(
                moving.version(),
                moving.taskVersion(),
                secondQueue.id(),
                0,
                date));

    List<QueueEntry> route =
        entries.findAllByTaskIdOrderByRouteIndexAsc(moving.taskId());
    assertThat(route)
        .extracting(routeEntry -> routeEntry.getQueue().getId())
        .containsExactly(secondQueue.id(), firstQueue.id());
    assertThat(route)
        .extracting(QueueEntry::getEntryType)
        .containsExactly(EntryType.REAL, EntryType.SHADOW);
    assertThat(queueEntries(moved, firstQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("moving");
    assertThat(queueEntries(moved, secondQueue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("moving");
  }

  @Test
  void repeatedWaitingStagesInOneQueueMoveTogetherWithoutAFalseConflict() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("REPEATED_ROUTE", QueueType.REPAIR, List.of()));
    LocalDate date = LocalDate.of(2026, 7, 24);
    board.createTask(W1, scheduledTask(queue.id(), "first", date, 3));
    board.registerExternalTask(
        "maintenance-service",
        new RegisterExternalTaskRequest(
            W1,
            UUID.randomUUID(),
            "moving",
            "БТ-3",
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(queue.definitionId(), "first stage", null),
                new RouteStepRequest(queue.definitionId(), "second stage", null)),
            date,
            3));
    board.createTask(W1, scheduledTask(queue.id(), "last", date, 3));
    BoardEntryDto realEntry =
        queueEntries(board.snapshot(W1, date, true), queue.id()).stream()
            .filter(
                candidate ->
                    candidate.title().equals("moving")
                        && candidate.entryType() == EntryType.REAL)
            .findFirst()
            .orElseThrow();

    TaskBoardSnapshot moved =
        board.move(
            W1,
            realEntry.id(),
            new MoveEntryRequest(
                realEntry.version(),
                realEntry.taskVersion(),
                queue.id(),
                0,
                date));

    assertThat(queueEntries(moved, queue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("moving", "moving", "first", "last");
    assertThat(queueEntries(moved, queue.id()).subList(0, 2))
        .extracting(BoardEntryDto::routeIndex)
        .containsExactly(0, 1);
  }

  @Test
  void newTasksAreAppendedToTheirPriorityGroup() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("STABLE_PRIORITY", QueueType.REPAIR, List.of()));
    LocalDate date = LocalDate.of(2026, 7, 24);
    board.createTask(W1, scheduledTask(queue.id(), "priority 4 first", date, 4));
    board.createTask(W1, scheduledTask(queue.id(), "priority 1 first", date, 1));
    board.createTask(W1, scheduledTask(queue.id(), "priority 5", date, 5));
    board.createTask(W1, scheduledTask(queue.id(), "priority 3", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "priority 2", date, 2));
    board.createTask(W1, scheduledTask(queue.id(), "priority 4 second", date, 4));
    board.createTask(W1, scheduledTask(queue.id(), "priority 1 second", date, 1));

    assertThat(queueEntries(board.snapshot(W1, date, true), queue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly(
            "priority 1 first",
            "priority 1 second",
            "priority 2",
            "priority 3",
            "priority 4 first",
            "priority 4 second",
            "priority 5");
  }

  @Test
  void maintenanceRegistrationUsesDailyCapacityButManualDateMovesRemainUnrestricted() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("DAILY_CAPACITY", QueueType.REPAIR, List.of()));
    LocalDate requestedDate = LocalDate.of(2026, 7, 25);
    List<BoardTaskRegistrationDto> registrations = new java.util.ArrayList<>();
    for (int number = 1; number <= 7; number++) {
      List<RouteStepRequest> route =
          number == 1
              ? List.of(
                  new RouteStepRequest(queue.definitionId(), "repair", null),
                  new RouteStepRequest(queue.definitionId(), "verification", null))
              : List.of(new RouteStepRequest(queue.definitionId(), "repair", null));
      registrations.add(
          board.registerExternalTask(
              "maintenance-service",
              new RegisterExternalTaskRequest(
                  W1,
                  UUID.randomUUID(),
                  "maintenance-" + number,
                  "BT-" + number,
                  null,
                  null,
                  null,
                  route,
                  requestedDate,
                  3,
                  6)));
    }

    assertThat(registrations.subList(0, 6))
        .extracting(BoardTaskRegistrationDto::scheduledDate)
        .containsOnly(requestedDate);
    BoardTaskRegistrationDto seventhRegistration = registrations.get(6);
    LocalDate overflowDate = requestedDate.plusDays(1);
    assertThat(seventhRegistration.scheduledDate()).isEqualTo(overflowDate);
    assertThat(tasks.findById(seventhRegistration.taskId()).orElseThrow().getScheduledDate())
        .isEqualTo(overflowDate);
    assertThat(
            board.registerExternalTask(
                "maintenance-service",
                new RegisterExternalTaskRequest(
                    W1,
                    seventhRegistration.externalTaskId(),
                    "maintenance-7",
                    "BT-7",
                    null,
                    null,
                    null,
                    List.of(new RouteStepRequest(queue.definitionId(), "repair", null)),
                    requestedDate,
                    3,
                    7)))
        .isEqualTo(seventhRegistration);

    BoardEntryDto seventhEntry = entry(overflowDate, "maintenance-7");
    TaskBoardSnapshot moved =
        board.move(
            W1,
            seventhEntry.id(),
            new MoveEntryRequest(
                seventhEntry.version(),
                seventhEntry.taskVersion(),
                queue.id(),
                6,
                requestedDate));

    assertThat(moved.selectedDate()).isEqualTo(requestedDate);
    assertThat(queueEntries(board.snapshot(W1, requestedDate, false), queue.id()))
        .extracting(BoardEntryDto::taskId)
        .containsExactlyInAnyOrderElementsOf(
            registrations.stream().map(BoardTaskRegistrationDto::taskId).toList());
  }

  @Test
  void dailyCapacityDoesNotChangeNonMaintenanceOrPublicRegistration() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("CAPACITY_SCOPE", QueueType.REPAIR, List.of()));
    LocalDate requestedDate = LocalDate.of(2026, 7, 25);
    board.registerExternalTask(
        "maintenance-service",
        new RegisterExternalTaskRequest(
            W1,
            UUID.randomUUID(),
            "maintenance",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.definitionId(), "repair", null)),
            requestedDate,
            3,
            1));

    BoardTaskRegistrationDto otherSource =
        board.registerExternalTask(
            "other-service",
            new RegisterExternalTaskRequest(
                W1,
                UUID.randomUUID(),
                "other source",
                null,
                null,
                null,
                null,
                List.of(new RouteStepRequest(queue.definitionId(), "repair", null)),
                requestedDate,
                3,
                1));
    TaskBoardSnapshot publicRegistration =
        board.createTask(W1, scheduledTask(queue.id(), "public", requestedDate, 3));

    assertThat(otherSource.scheduledDate()).isEqualTo(requestedDate);
    assertThat(publicRegistration.selectedDate()).isEqualTo(requestedDate);
  }

  @Test
  void maintenanceRouteUsesTargetWarehouseQueueUuid() {
    var targetQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("Target repair route", QueueType.REPAIR, List.of()));
    var replacementQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("Replacement repair route", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();

    BoardTaskRegistrationDto registered =
        board.registerExternalTask(
            "maintenance-service",
            new RegisterExternalTaskRequest(
                W2,
                externalTaskId,
                "global catalog task",
                "BT-101",
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(targetQueue.definitionId(), "repair", null))));

    assertThat(registered.route())
        .extracting(RegisteredRouteStepDto::workQueueId)
        .containsExactly(targetQueue.id());
    assertThat(registered.route())
        .extracting(RegisteredRouteStepDto::queueName)
        .containsExactly(targetQueue.name());

    BoardTaskRegistrationDto updated =
        board.updateExternalTaskBeforeStart(
            "maintenance-service",
            externalTaskId,
            new PreStartUpdateTaskRequest(
                registered.taskVersion(),
                "updated global catalog task",
                "BT-101",
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(
                        replacementQueue.definitionId(), "external repair", null))));

    assertThat(updated.route())
        .extracting(RegisteredRouteStepDto::workQueueId)
        .containsExactly(replacementQueue.id());
    assertThat(updated.route())
        .extracting(RegisteredRouteStepDto::queueName)
        .containsExactly(replacementQueue.name());
  }

  @Test
  void relocationKeepsTaskRouteStatusAndHistoryAndRemapsBindingsIdempotently() {
    var sourceRepair =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("RELOCATE_REPAIR", QueueType.REPAIR, List.of()));
    var sourceAcceptance =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("RELOCATE_ACCEPTANCE", QueueType.REPAIR, List.of()));
    var targetRepair =
        QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("RELOCATE_REPAIR", QueueType.REPAIR, List.of()));
    var targetAcceptance =
        QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("RELOCATE_ACCEPTANCE", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();

    RegisterExternalTaskRequest registrationRequest =
        new RegisterExternalTaskRequest(
            W1,
            externalTaskId,
            "Активный ремонт при перемещении",
            "BT-RELOCATE",
            "Смета и маршрут остаются у той же задачи",
            120,
            null,
            List.of(
                new RouteStepRequest(
                    sourceRepair.definitionId(), "Незавершённая работа", 90),
                new RouteStepRequest(
                    sourceAcceptance.definitionId(), "Приёмка результата", 30)));
    BoardTaskRegistrationDto registered =
        board.registerExternalTask(
            "maintenance-service",
            registrationRequest);
    QueueEntry originalEntry =
        entries.findById(registered.route().getFirst().entryId()).orElseThrow();
    var history = new TaskTimeEvent();
    history.setQueueEntry(originalEntry);
    history.setEventType(TimeEventType.PAUSED);
    history.setReason("Историческое событие до перемещения");
    history.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    history = timeEvents.saveAndFlush(history);

    BoardTaskRegistrationDto relocated =
        board.relocateExternalTask(
            "maintenance-service",
            externalTaskId,
            new RelocateExternalTaskRequest(registered.taskVersion(), W2));

    assertThat(relocated.taskId()).isEqualTo(registered.taskId());
    assertThat(relocated.warehouseId()).isEqualTo(W2);
    assertThat(relocated.route())
        .extracting(RegisteredRouteStepDto::entryId)
        .containsExactlyElementsOf(
            registered.route().stream().map(RegisteredRouteStepDto::entryId).toList());
    assertThat(relocated.route())
        .extracting(RegisteredRouteStepDto::queueDefinitionId)
        .containsExactly(sourceRepair.definitionId(), sourceAcceptance.definitionId());
    assertThat(relocated.route())
        .extracting(RegisteredRouteStepDto::workQueueId)
        .containsExactly(targetRepair.id(), targetAcceptance.id());
    assertThat(relocated.route())
        .extracting(RegisteredRouteStepDto::status)
        .containsExactlyElementsOf(
            registered.route().stream().map(RegisteredRouteStepDto::status).toList());
    assertThat(timeEvents.findAllByQueueEntryIdOrderByCreatedAtAsc(originalEntry.getId()))
        .extracting(TaskTimeEvent::getId)
        .containsExactly(history.getId());
    BoardTaskRegistrationDto targetRegistrationReplay =
        board.registerExternalTask(
            "maintenance-service",
            new RegisterExternalTaskRequest(
                W2,
                registrationRequest.externalTaskId(),
                registrationRequest.title(),
                registrationRequest.unitNumber(),
                registrationRequest.description(),
                registrationRequest.plannedDurationMinutes(),
                registrationRequest.deadlineAt(),
                registrationRequest.route()));
    assertThat(targetRegistrationReplay.taskId()).isEqualTo(relocated.taskId());
    assertThat(targetRegistrationReplay.taskVersion()).isEqualTo(relocated.taskVersion());
    assertThat(
            jdbc.queryForObject(
                """
                select payload->>'warehouseId'
                  from domain_event
                 where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
                   and aggregate_id=?
                 order by aggregate_version desc
                 limit 1
                """,
                String.class,
                originalEntry.getId().toString()))
        .isEqualTo(W2.toString());

    BoardTaskRegistrationDto replay =
        board.relocateExternalTask(
            "maintenance-service",
            externalTaskId,
            new RelocateExternalTaskRequest(registered.taskVersion(), W2));
    assertThat(replay.taskVersion()).isEqualTo(relocated.taskVersion());
    assertThat(replay.route())
        .extracting(RegisteredRouteStepDto::entryVersion)
        .containsExactlyElementsOf(
            relocated.route().stream().map(RegisteredRouteStepDto::entryVersion).toList());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from task_relocation_receipt where external_task_id=?",
                Integer.class,
                externalTaskId))
        .isOne();
  }

  @Test
  void relocationKeepsCompletedStagesOnTheirSourceQueueAndRequiresOnlyUnfinishedBindings() {
    var workerClass = registry.createClass(workerClass("RELOCATE_COMPLETED_WORKER"));
    var sourceCompleted =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "RELOCATE_COMPLETED_STAGE",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var sourcePending =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1, queue("RELOCATE_PENDING_STAGE", QueueType.REPAIR, List.of()));
    var targetPending =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W2, queue("RELOCATE_PENDING_STAGE", QueueType.REPAIR, List.of()));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Relocation worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    UUID externalTaskId = UUID.randomUUID();
    BoardTaskRegistrationDto registered =
        board.registerExternalTask(
            "maintenance-service",
            new RegisterExternalTaskRequest(
                W1,
                externalTaskId,
                "Ремонт с завершённым этапом",
                "BT-RELOCATE-DONE",
                null,
                60,
                null,
                List.of(
                    new RouteStepRequest(
                        sourceCompleted.definitionId(), "Завершённая работа", 30),
                    new RouteStepRequest(
                        sourcePending.definitionId(), "Оставшаяся работа", 30))));
    RegisteredRouteStepDto completedStage = registered.route().getFirst();
    BoardEntryDto taken =
        board.take(
            W1,
            completedStage.entryId(),
            new TakeEntryRequest(completedStage.entryVersion(), null, worker.id()),
            null);
    board.complete(W1, taken.id(), new VersionCommand(taken.version()), null);
    QueueEntry completedBeforeRelocation =
        entries.findById(completedStage.entryId()).orElseThrow();
    long completedVersion = completedBeforeRelocation.getVersion();
    List<UUID> historyIds =
        timeEvents.findAllByQueueEntryIdOrderByCreatedAtAsc(completedStage.entryId()).stream()
            .map(TaskTimeEvent::getId)
            .toList();
    BoardTask task = tasks.findByExternalTaskId(externalTaskId).orElseThrow();

    BoardTaskRegistrationDto relocated =
        board.relocateExternalTask(
            "maintenance-service",
            externalTaskId,
            new RelocateExternalTaskRequest(task.getVersion(), W2));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from work_queue where warehouse_id=? and definition_id=?",
                Integer.class,
                W2,
                sourceCompleted.definitionId()))
        .isOne();
    assertThat(relocated.route())
        .extracting(
            RegisteredRouteStepDto::status,
            RegisteredRouteStepDto::workQueueId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(EntryStatus.DONE, sourceCompleted.id()),
            org.assertj.core.groups.Tuple.tuple(EntryStatus.WAITING, targetPending.id()));
    QueueEntry completedAfterRelocation =
        entries.findById(completedStage.entryId()).orElseThrow();
    assertThat(completedAfterRelocation.getVersion()).isEqualTo(completedVersion);
    assertThat(timeEvents.findAllByQueueEntryIdOrderByCreatedAtAsc(completedStage.entryId()))
        .extracting(TaskTimeEvent::getId)
        .containsExactlyElementsOf(historyIds);
    assertThat(
            jdbc.queryForObject(
                """
                select payload->>'warehouseId'
                  from domain_event
                 where aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
                   and aggregate_id=?
                 order by aggregate_version desc
                 limit 1
                """,
                String.class,
                completedStage.entryId().toString()))
        .isEqualTo(W1.toString());
  }

  @Test
  void relocationWithoutRequiredTargetBindingFailsBeforeAnyMutation() {
    var sourceQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("RELOCATE_MISSING_TARGET", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    BoardTaskRegistrationDto registered =
        board.registerExternalTask(
            "maintenance-service",
            new RegisterExternalTaskRequest(
                W1,
                externalTaskId,
                "Ремонт без очереди на целевом складе",
                null,
                null,
                30,
                null,
                List.of(
                    new RouteStepRequest(
                        sourceQueue.definitionId(), "Работа без fallback", 30))));

    assertThatThrownBy(
            () ->
                board.relocateExternalTask(
                    "maintenance-service",
                    externalTaskId,
                    new RelocateExternalTaskRequest(registered.taskVersion(), W2)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("не подключил общую очередь");

    BoardTaskRegistrationDto unchanged =
        board.externalTask("maintenance-service", externalTaskId);
    assertThat(unchanged.warehouseId()).isEqualTo(W1);
    assertThat(unchanged.taskVersion()).isEqualTo(registered.taskVersion());
    assertThat(unchanged.route())
        .extracting(
            RegisteredRouteStepDto::entryId,
            RegisteredRouteStepDto::entryVersion,
            RegisteredRouteStepDto::workQueueId,
            RegisteredRouteStepDto::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                registered.route().getFirst().entryId(),
                registered.route().getFirst().entryVersion(),
                sourceQueue.id(),
                registered.route().getFirst().status()));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from task_relocation_receipt where external_task_id=?",
                Integer.class,
                externalTaskId))
        .isZero();
  }

  @Test
  void nonMaintenanceRouteCannotUseAnotherWarehousesQueueUuid() {
    var sourceQueue =
        QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("Source repair route", QueueType.REPAIR, List.of()));

    assertThatThrownBy(
            () ->
                board.registerExternalTask(
                    "other-service",
                    new RegisterExternalTaskRequest(
                        W2,
                        UUID.randomUUID(),
                        "strict task",
                        null,
                        null,
                        null,
                        null,
                        List.of(
                            new RouteStepRequest(
                                sourceQueue.definitionId(), null, null)))))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("не подключена");
  }

  @Test
  void maintenanceRouteRejectsMissingHiddenAndInactiveTargetQueues() {
    var hiddenTarget =
        QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("Hidden target", QueueType.REPAIR, List.of()));
    QueueRegistryTestFixtures.update(registry, jdbc,
        W2,
        hiddenTarget.id(),
        new QueueFixtureRequest(
            hiddenTarget.version(),
            hiddenTarget.definitionId(),
            true,
            true,
            hiddenTarget.collapsed(),
            hiddenTarget.holdingPeriodMinutes(),
            hiddenTarget.notificationThreshold(),
            hiddenTarget.notifyWhenThresholdReached(),
            hiddenTarget.resultPhotoMinCount(),
            List.of()));
    var inactiveTarget =
        QueueRegistryTestFixtures.create(registry, jdbc, W2, queue("Inactive target", QueueType.REPAIR, List.of()));
    QueueRegistryTestFixtures.update(registry, jdbc,
        W2,
        inactiveTarget.id(),
        new QueueFixtureRequest(
            inactiveTarget.version(),
            inactiveTarget.definitionId(),
            false,
            false,
            inactiveTarget.collapsed(),
            inactiveTarget.holdingPeriodMinutes(),
            inactiveTarget.notificationThreshold(),
            inactiveTarget.notifyWhenThresholdReached(),
            inactiveTarget.resultPhotoMinCount(),
            List.of()));

    assertThatThrownBy(
            () ->
                registerMaintenanceRoute(
                    UUID.randomUUID(), "missing target"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("не подключена");
    assertThatThrownBy(
            () ->
                registerMaintenanceRoute(
                    hiddenTarget.id(), "hidden target"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("активна и видима");
    assertThatThrownBy(
            () ->
                registerMaintenanceRoute(
                    inactiveTarget.id(), "inactive target"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("активна и видима");
  }

  @Test
  void concurrentMaintenanceRegistrationsDoNotOverfillDailyCapacity() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("CAPACITY_RACE", QueueType.REPAIR, List.of()));
    LocalDate requestedDate = LocalDate.of(2026, 7, 25);

    List<Object> outcomes =
        race(
            () ->
                board.registerExternalTask(
                    "maintenance-service",
                    new RegisterExternalTaskRequest(
                        W1,
                        UUID.randomUUID(),
                        "first",
                        null,
                        null,
                        null,
                        null,
                        List.of(new RouteStepRequest(queue.definitionId(), "repair", null)),
                        requestedDate,
                        3,
                        1)),
            () ->
                board.registerExternalTask(
                    "maintenance-service",
                    new RegisterExternalTaskRequest(
                        W1,
                        UUID.randomUUID(),
                        "second",
                        null,
                        null,
                        null,
                        null,
                        List.of(new RouteStepRequest(queue.definitionId(), "repair", null)),
                        requestedDate,
                        3,
                        1)));

    assertThat(outcomes).allMatch(BoardTaskRegistrationDto.class::isInstance);
    assertThat(outcomes.stream().map(BoardTaskRegistrationDto.class::cast).toList())
        .extracting(BoardTaskRegistrationDto::scheduledDate)
        .containsExactlyInAnyOrder(requestedDate, requestedDate.plusDays(1));
  }

  @Test
  void priorityInsertionKeepsPinnedOrdinalAndDateMoveUpdatesBothViews() {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("PLANNED", QueueType.REPAIR, List.of()));
    LocalDate firstDate = LocalDate.of(2026, 7, 24);
    LocalDate secondDate = firstDate.plusDays(1);
    board.createTask(W1, scheduledTask(queue.id(), "first", firstDate, 3));
    board.createTask(W1, scheduledTask(queue.id(), "pinned", firstDate, 3));
    BoardEntryDto pinned = entry(firstDate, "pinned");
    board.pin(
        W1, pinned.taskId(), new PinTaskRequest(pinned.taskVersion(), true));

    board.createTask(W1, scheduledTask(queue.id(), "urgent", firstDate, 1));

    assertThat(board.snapshot(W1, firstDate, true).columns().getFirst().entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("urgent", "pinned", "first");
    assertThat(entry(firstDate, "pinned").queuePosition()).isEqualTo(1);
    BoardEntryDto urgent = entry(firstDate, "urgent");
    TaskBoardSnapshot moved =
        board.move(
            W1,
            urgent.id(),
            new MoveEntryRequest(
                urgent.version(),
                urgent.taskVersion(),
                queue.id(),
                0,
                secondDate));

    assertThat(moved.selectedDate()).isEqualTo(secondDate);
    assertThat(moved.availableDates()).containsExactly(firstDate, secondDate);
    assertThat(moved.columns().getFirst().entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("urgent");
    assertThat(board.snapshot(W1, firstDate, true).columns().getFirst().entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("first", "pinned");
  }

  @Test
  void movingTaskFromBeforePinnedCardToAfterKeepsPinnedAbsoluteOrdinal() {
    var queue =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("PINNED_ORDINAL_AFTER", QueueType.REPAIR, List.of()));
    LocalDate date = LocalDate.of(2026, 8, 3);
    board.createTask(W1, scheduledTask(queue.id(), "before", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "pinned", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "after", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "tail", date, 3));

    BoardEntryDto pinned = entry(date, queue.id(), "pinned");
    board.pin(W1, pinned.taskId(), new PinTaskRequest(pinned.taskVersion(), true));
    BoardEntryDto before = entry(date, queue.id(), "before");

    TaskBoardSnapshot snapshot =
        board.move(
            W1,
            before.id(),
            new MoveEntryRequest(
                before.version(), before.taskVersion(), queue.id(), 3, date));

    assertThat(queueEntries(snapshot, queue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("after", "pinned", "tail", "before");
    assertThat(queueEntries(snapshot, queue.id()))
        .extracting(BoardEntryDto::queuePosition)
        .containsExactly(0, 1, 2, 3);
  }

  @Test
  void movingTaskFromAfterPinnedCardToBeforeKeepsPinnedAbsoluteOrdinal() {
    var queue =
        QueueRegistryTestFixtures.create(
            registry, jdbc, W1, queue("PINNED_ORDINAL_BEFORE", QueueType.REPAIR, List.of()));
    LocalDate date = LocalDate.of(2026, 8, 3);
    board.createTask(W1, scheduledTask(queue.id(), "before", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "pinned", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "after", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "tail", date, 3));

    BoardEntryDto pinned = entry(date, queue.id(), "pinned");
    board.pin(W1, pinned.taskId(), new PinTaskRequest(pinned.taskVersion(), true));
    BoardEntryDto tail = entry(date, queue.id(), "tail");

    TaskBoardSnapshot snapshot =
        board.move(
            W1,
            tail.id(),
            new MoveEntryRequest(tail.version(), tail.taskVersion(), queue.id(), 0, date));

    assertThat(queueEntries(snapshot, queue.id()))
        .extracting(BoardEntryDto::title)
        .containsExactly("tail", "pinned", "before", "after");
    assertThat(queueEntries(snapshot, queue.id()))
        .extracting(BoardEntryDto::queuePosition)
        .containsExactly(0, 1, 2, 3);
  }

  @Test
  void urgentTaskIsInsertedImmediatelyAfterTheCurrentInProgressTask() {
    var workerClass = registry.createClass(workerClass("PRIORITY_WORKER"));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "PRIORITY",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Priority worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    LocalDate date = LocalDate.of(2026, 7, 24);
    board.createTask(W1, scheduledTask(queue.id(), "current", date, 3));
    board.createTask(W1, scheduledTask(queue.id(), "waiting", date, 3));
    BoardEntryDto current = entry(date, "current");
    board.take(
        W1,
        current.id(),
        new TakeEntryRequest(current.version(), null, worker.id()),
        null);

    board.createTask(W1, scheduledTask(queue.id(), "urgent", date, 1));

    assertThat(board.snapshot(W1, date, true).columns().getFirst().entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("current", "urgent", "waiting");

    BoardEntryDto paused =
        board.pause(
            W1,
            current.id(),
            new PauseEntryRequest(entry(date, "current").version(), "Перерыв"),
            null);
    board.createTask(W1, scheduledTask(queue.id(), "another urgent", date, 1));

    assertThat(board.snapshot(W1, date, true).columns().getFirst().entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("current", "urgent", "another urgent", "waiting");
    assertThatThrownBy(
            () ->
                board.move(
                    W1,
                    paused.id(),
                    new MoveEntryRequest(
                        paused.version(),
                        paused.taskVersion(),
                        queue.id(),
                        2,
                        paused.scheduledDate())))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("в работе");
  }

  @Test
  void overdueMaintenanceTasksRollOverBeforeOriginallyPlannedTasks() {
    var workerClass = registry.createClass(workerClass("ROLLOVER_WORKER"));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            W1,
            queue(
                "ROLLOVER",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Rollover worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    LocalDate today = LocalDate.of(2026, 7, 24);
    board.createTask(W1, scheduledTask(queue.id(), "current", today, 3));
    board.createTask(W1, scheduledTask(queue.id(), "planned", today, 3));
    BoardEntryDto current = entry(today, "current");
    board.take(
        W1,
        current.id(),
        new TakeEntryRequest(current.version(), null, worker.id()),
        null);
    board.registerExternalTask(
        "maintenance-service",
        new RegisterExternalTaskRequest(
            W1,
            UUID.randomUUID(),
            "overdue",
            "БТ-1",
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.definitionId(), "repair", null)),
            today.minusDays(1),
            4));

    assertThat(board.rolloverOverdueMaintenanceTasks(today)).isEqualTo(1);

    TaskBoardSnapshot snapshot = board.snapshot(W1, today, true);
    assertThat(snapshot.availableDates()).containsExactly(today);
    assertThat(snapshot.columns().getFirst().entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("current", "overdue", "planned");
    assertThat(board.rolloverOverdueMaintenanceTasks(today)).isZero();
  }

  @Test
  void concurrentMaintenanceRolloverMovesEachTaskExactlyOnce() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("ROLLOVER-RACE", QueueType.REPAIR, List.of()));
    LocalDate today = LocalDate.of(2026, 7, 24);
    board.registerExternalTask(
        "maintenance-service",
        new RegisterExternalTaskRequest(
            W1,
            UUID.randomUUID(),
            "overdue",
            "БТ-2",
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.definitionId(), "repair", null)),
            today.minusDays(1),
            3));

    List<Object> outcomes =
        race(
            () -> board.rolloverOverdueMaintenanceTasks(today),
            () -> board.rolloverOverdueMaintenanceTasks(today));

    assertThat(outcomes).containsExactlyInAnyOrder(0, 1);
    assertThat(board.snapshot(W1, today, true).columns().getFirst().entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("overdue");
  }

  @Test
  void audiencePropertyAndWorkerScopesAreEnforced() {
    assertThat(resourceServer.getJwt().getAudiences()).containsExactly("rwms-services");
    assertThat(clientProperties.connectTimeout()).isEqualTo(java.time.Duration.ofMillis(250));
    assertThat(clientProperties.readTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
    assertThat(clientProperties.credentialOperationTimeout())
        .isEqualTo(java.time.Duration.ofSeconds(1));
    Jwt workerWithoutScope = token("WORKER", W1, UUID.randomUUID(), "rwms.read");
    assertThatThrownBy(() -> authorizer.requireTaskScope(workerWithoutScope, true))
        .isInstanceOf(AccessDeniedException.class);
    Jwt worker = token("WORKER", W1, UUID.randomUUID(), "worker.tasks");
    authorizer.requireTaskScope(worker, true);
    authorizer.requireWarehouse(worker, W1, AccessLevel.EDIT, true);
    assertThatThrownBy(() -> authorizer.requireWarehouse(worker, W2, AccessLevel.VIEW, true))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void corsAllowsPanelAndWorkerOriginsButRejectsUnknownOrigin() throws Exception {
    for (String origin : List.of("http://localhost:8080", "http://localhost:8082")) {
      mockMvc
          .perform(
              options("/api/warehouses/{warehouseId}/task-board", W1)
                  .header("Origin", origin)
                  .header("Access-Control-Request-Method", "GET")
                  .header(
                      "Access-Control-Request-Headers",
                      "Authorization, Content-Type, X-Correlation-Id, "
                          + "Idempotency-Key, If-None-Match, Last-Event-ID"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", origin))
          .andExpect(
              header().string(
                  "Access-Control-Allow-Headers",
                  org.hamcrest.Matchers.containsString("X-Correlation-Id")));
    }
    mockMvc
        .perform(
            options("/api/warehouses/{warehouseId}/task-board", W1)
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

    mockMvc
        .perform(
            get("/api/worker-classes")
                .header("Origin", "http://localhost:8080")
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("scope", "rwms.read"))))
        .andExpect(status().isOk())
        .andExpect(
            header().string(
                "Access-Control-Expose-Headers",
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("X-Correlation-Id"),
                    org.hamcrest.Matchers.containsString("ETag"),
                    org.hamcrest.Matchers.containsString("Retry-After"))))
        .andExpect(header().exists("X-Correlation-Id"));
  }

  @Test
  void taskBoardApiRequiresAuthenticationOutsideDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/worker-classes")).andExpect(status().isUnauthorized());
  }

  @Test
  void staleControllerMutationReturns409ProblemDetail() throws Exception {
    var workerClass = registry.createClass(workerClass("HTTP"));
    String body =
        """
        {"version":99,"name":"HTTP","description":null,"comment":null,"sortOrder":10,"active":true}
        """;
    mockMvc
        .perform(
            put("/api/worker-classes/{id}", workerClass.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/worker-classes/{id}", workerClass.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"))
        .andExpect(jsonPath("$.correlation.correlationId").isNotEmpty());
  }

  @Test
  void dateColumnSwapApiRejectsStaleTaskVersionWithoutMovingEitherColumn() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("HTTP_DATE_SWAP", QueueType.REPAIR, List.of()));
    LocalDate firstDate = LocalDate.of(2026, 7, 24);
    LocalDate secondDate = firstDate.plusDays(1);
    board.createTask(W1, scheduledTask(queue.id(), "first", firstDate, 3));
    board.createTask(W1, scheduledTask(queue.id(), "second", secondDate, 3));
    TaskBoardSnapshot firstBefore = board.snapshot(W1, firstDate, true);
    TaskBoardSnapshot secondBefore = board.snapshot(W1, secondDate, true);
    BoardEntryDto firstEntry = boardEntries(firstBefore).getFirst();
    BoardEntryDto secondEntry = boardEntries(secondBefore).getFirst();
    board.pin(W1, firstEntry.taskId(), new PinTaskRequest(firstEntry.taskVersion(), true));

    String body =
        """
        {
          "firstDate":"%s",
          "secondDate":"%s",
          "entries":[
            {"entryId":"%s","expectedVersion":%d,"expectedTaskVersion":%d},
            {"entryId":"%s","expectedVersion":%d,"expectedTaskVersion":%d}
          ]
        }
        """
            .formatted(
                firstDate,
                secondDate,
                firstEntry.id(),
                firstEntry.version(),
                firstEntry.taskVersion(),
                secondEntry.id(),
                secondEntry.version(),
                secondEntry.taskVersion());

    mockMvc
        .perform(
            post("/api/warehouses/{warehouseId}/task-board/dates/swap", W1)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));

    assertThat(boardEntries(board.snapshot(W1, firstDate, true)))
        .extracting(BoardEntryDto::id)
        .containsExactly(firstEntry.id());
    assertThat(boardEntries(board.snapshot(W1, secondDate, true)))
        .extracting(BoardEntryDto::id)
        .containsExactly(secondEntry.id());
  }

  @Test
  void duplicateRouteControllerMutationReturns409() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("HTTP_ROUTE", QueueType.REPAIR, List.of()));
    String body =
        """
        {"externalTaskId":null,"title":"duplicate","route":[
          {"queueDefinitionId":"%s","taskText":null,"plannedDurationMinutes":null},
          {"queueDefinitionId":"%s","taskText":null,"plannedDurationMinutes":null}
        ]}
        """
            .formatted(queue.definitionId(), queue.definitionId());
    mockMvc
        .perform(
            post("/api/warehouses/{warehouseId}/task-board/tasks", W1)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void cancellationApiRequiresUserWriteAndEditWarehouseAccess() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("HTTP_CANCEL", QueueType.MOVEMENT, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    var entry =
        board.createTask(W1, externalTask(externalTaskId, queue.id(), "http-cancel"))
            .columns()
            .stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    String body =
        """
        {"expectedTaskVersion":%d,"reason":"Отмена оператором"}
        """
            .formatted(entry.taskVersion());
    String path =
        "/api/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel";

    mockMvc
        .perform(post(path, W1, externalTaskId).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "WAREHOUSE_MANAGER")
                                    .claim("scope", "rwms.read")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId", W1.toString(), "level", "EDIT")))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "WAREHOUSE_MANAGER")
                                    .claim("scope", "rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId", W1.toString(), "level", "VIEW")))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "WAREHOUSE_MANAGER")
                                    .claim("scope", "rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId", W1.toString(), "level", "EDIT")))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    var cancelledEntry = entries.findById(entry.id()).orElseThrow();
    var cancelledTask = tasks.findByWarehouseIdAndExternalTaskId(W1, externalTaskId).orElseThrow();
    mockMvc
        .perform(
            post(
                    "/api/warehouses/{warehouseId}/task-board/entries/{entryId}/move",
                    W1,
                    entry.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":"
                        + cancelledEntry.getVersion()
                        + ",\"expectedTaskVersion\":"
                        + cancelledTask.getVersion()
                        + ",\"targetQueueId\":null,\"targetIndex\":0,\"targetDate\":\""
                        + cancelledTask.getScheduledDate()
                        + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mutableVersionTokensMustBePresentNonNullAndNonNegative() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("HTTP_VERSION_REQUIRED", QueueType.MOVEMENT, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    board.createTask(W1, externalTask(externalTaskId, queue.id(), "version-required"));
    String path =
        "/api/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel";
    var admin =
        jwt()
            .jwt(
                token ->
                    token
                        .claim("principal_type", "USER")
                        .claim("global_role", "SYSTEM_ADMIN")
                        .claim("scope", "rwms.write"));

    for (String invalidBody :
        List.of(
            "{\"reason\":\"missing\"}",
            "{\"expectedTaskVersion\":null,\"reason\":\"null\"}",
            "{\"expectedTaskVersion\":-1,\"reason\":\"negative\"}")) {
      mockMvc
          .perform(
              post(path, W1, externalTaskId)
                  .with(admin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalidBody))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"missing\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("TASK_BOARD_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.violations[0].field").value("expectedTaskVersion"))
        .andExpect(jsonPath("$.correlation.correlationId").isNotEmpty());
    assertThat(tasks.findByExternalTaskId(externalTaskId).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.ACTIVE);

    long currentVersion = board.registration(W1, externalTaskId).taskVersion();
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedTaskVersion\":"
                        + currentVersion
                        + ",\"reason\":\"valid\"}"))
        .andExpect(status().isOk());

    var workerClass = registry.createClass(workerClass("NEGATIVE_QUERY_VERSION"));
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/worker-classes/{id}", workerClass.id())
                .queryParam("expectedVersion", "-1")
                .with(admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void exactExternalTaskRegistrationApiReturnsCurrentRoute() throws Exception {
    var queue = QueueRegistryTestFixtures.create(registry, jdbc, W1, queue("HTTP_LOOKUP", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    board.createTask(W1, externalTask(externalTaskId, queue.id(), "http-lookup"));

    mockMvc
        .perform(
            get(
                    "/api/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}",
                    W1,
                    externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.read"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.externalTaskId").value(externalTaskId.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.route.length()").value(1))
        .andExpect(
            jsonPath("$.route[0].queueDefinitionId")
                .value(queue.definitionId().toString()))
        .andExpect(jsonPath("$.route[0].workQueueId").value(queue.id().toString()));
  }

  private long kafkaOutboxCount(String eventType) {
    Long count =
        eventType == null
            ? jdbc.queryForObject("select count(*) from outbox_event", Long.class)
            : jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?", Long.class, eventType);
    return count == null ? 0 : count;
  }

  private BoardEntryDto entry(String title) {
    return board.snapshot(W1, true).columns().stream()
        .flatMap(c -> c.entries().stream())
        .filter(e -> e.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private WorkerClassRequest workerClass(String name) {
    return new WorkerClassRequest(0L, name, null, null, 10, true);
  }

  private QueueFixtureRequest queue(String name, QueueType type, List<QueueBindingRequest> bindings) {
    return queue(name, type, QueuePurpose.GENERAL, bindings);
  }

  private QueueFixtureRequest queue(
      String name,
      QueueType type,
      QueuePurpose purpose,
      List<QueueBindingRequest> bindings) {
    QueueDefinitionDto definition;
    if (purpose == QueuePurpose.LOGISTICS_DRIVER) {
      definition = QueueRegistryTestFixtures.ensureDriverDefinition(registry, jdbc, name, type);
    } else {
      definition =
          registry.listQueueDefinitions().stream()
              .filter(
                  candidate ->
                      candidate.name().equalsIgnoreCase(name)
                          && candidate.type() == type
                          && candidate.purpose() == purpose)
              .findFirst()
              .orElseGet(
                  () ->
                      registry.createQueueDefinition(
                          QueueRegistryTestFixtures.globalDefinition(0L, name, null, type)));
    }
    return new QueueFixtureRequest(
        0L,
        definition.id(),
        true,
        false,
        false,
        type == QueueType.HOLDING ? 10 : null,
        type == QueueType.HOLDING ? 2 : null,
        false,
        null,
        bindings);
  }

  private WorkerRequest worker(
      String name, String login, String password, List<QualificationRequest> q) {
    return new WorkerRequest(0L, name, null, null, null, true, null, login, password, q);
  }

  private WorkerRequest workerWithVersion(
      long version, String name, String login, String password) {
    return new WorkerRequest(
        version, name, null, null, null, true, null, login, password, List.of());
  }

  private CreateBoardTaskRequest task(UUID queue, String title) {
    return new CreateBoardTaskRequest(
        null,
        title,
        null,
        null,
        null,
        null,
        List.of(new RouteStepRequest(routeDefinition(queue), null, null)));
  }

  private CreateBoardTaskRequest externalTask(UUID externalTaskId, UUID queue, String title) {
    return new CreateBoardTaskRequest(
        externalTaskId,
        title,
        null,
        null,
        null,
        null,
        List.of(new RouteStepRequest(routeDefinition(queue), title, null)));
  }

  private BoardTaskRegistrationDto registerMaintenanceRoute(
      UUID queueId, String title) {
    return board.registerExternalTask(
        "maintenance-service",
        new RegisterExternalTaskRequest(
            W2,
            UUID.randomUUID(),
            title,
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(routeDefinition(queueId), null, null))));
  }

  private RegisterExternalTaskRequest driverRegistration(
      UUID queueDefinitionId, UUID externalTaskId, LocalDate scheduledDate) {
    return new RegisterExternalTaskRequest(
        W1,
        externalTaskId,
        "Переместить бытовку",
        "БЫТ-001",
        "Перемещение на ремонт",
        null,
        null,
        List.of(new RouteStepRequest(queueDefinitionId, "Перемещение на ремонт", null)),
        scheduledDate,
        3,
        null,
        new TaskSourceReferenceDto(TaskSourceType.LOGISTICS_DRIVER_TASK, UUID.randomUUID()),
        TaskLane.SCHEDULED);
  }

  private CreateBoardTaskRequest scheduledTask(
      UUID queue, String title, LocalDate scheduledDate, int priority) {
    return new CreateBoardTaskRequest(
        null,
        title,
        null,
        null,
        null,
        null,
        List.of(new RouteStepRequest(routeDefinition(queue), title, null)),
        scheduledDate,
        priority);
  }

  private CreateBoardTaskRequest scheduledRouteTask(
      List<UUID> route, String title, LocalDate scheduledDate, int priority) {
    return new CreateBoardTaskRequest(
        null,
        title,
        null,
        null,
        null,
        null,
        route.stream()
            .map(queue -> new RouteStepRequest(routeDefinition(queue), title, null))
            .toList(),
        scheduledDate,
        priority);
  }

  private UUID routeDefinition(UUID queueOrDefinitionId) {
    if (queueOrDefinitionId == null) {
      return null;
    }
    try {
      return registry.requireQueue(queueOrDefinitionId).getDefinition().getId();
    } catch (NotFoundException ignored) {
      return queueOrDefinitionId;
    }
  }

  private long versionOf(List<WorkQueueDto> queues, UUID queueId) {
    return queues.stream()
        .filter(queue -> queue.id().equals(queueId))
        .mapToLong(WorkQueueDto::version)
        .findFirst()
        .orElseThrow();
  }

  private QueueDefinitionRequest globalDefinitionRequest(
      QueueDefinitionDto definition, String name, String description) {
    return globalDefinitionRequest(definition, name, description, definition.version());
  }

  private QueueDefinitionRequest globalDefinitionRequest(
      QueueDefinitionDto definition, String name, String description, long version) {
    return new QueueDefinitionRequest(
        version,
        name,
        description,
        definition.type(),
        QueuePurpose.GENERAL,
        definition.sortOrder(),
        definition.active(),
        definition.hidden(),
        definition.collapsed(),
        definition.holdingPeriodMinutes(),
        definition.notificationThreshold(),
        definition.notifyWhenThresholdReached(),
        definition.resultPhotoMinCount(),
        definition.bindings().stream()
            .map(
                binding ->
                    new QueueBindingRequest(
                        binding.workerClass().id(),
                        binding.order(),
                        binding.stopTaskOnTake(),
                        binding.participationPolicy(),
                        binding.notifyOnPrimaryTake()))
            .toList());
  }

  private BoardEntryDto entry(LocalDate date, String title) {
    return board.snapshot(W1, date, true).columns().stream()
        .flatMap(column -> column.entries().stream())
        .filter(entry -> entry.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private BoardEntryDto entry(LocalDate date, UUID queueId, String title) {
    return queueEntries(board.snapshot(W1, date, true), queueId).stream()
        .filter(entry -> entry.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private List<BoardEntryDto> queueEntries(TaskBoardSnapshot snapshot, UUID queueId) {
    return snapshot.columns().stream()
        .filter(column -> Objects.equals(column.queueId(), queueId))
        .findFirst()
        .orElseThrow()
        .entries();
  }

  private List<BoardEntryDto> boardEntries(TaskBoardSnapshot snapshot) {
    return snapshot.columns().stream().flatMap(column -> column.entries().stream()).toList();
  }

  private SwapTaskBoardDatesRequest swapDateColumnsRequest(
      LocalDate firstDate,
      TaskBoardSnapshot firstColumn,
      LocalDate secondDate,
      TaskBoardSnapshot secondColumn) {
    List<TaskBoardDateEntryExpectation> expectations = new java.util.ArrayList<>();
    for (BoardEntryDto entry : boardEntries(firstColumn)) {
      expectations.add(
          new TaskBoardDateEntryExpectation(
              entry.id(), entry.version(), entry.taskVersion()));
    }
    for (BoardEntryDto entry : boardEntries(secondColumn)) {
      expectations.add(
          new TaskBoardDateEntryExpectation(
              entry.id(), entry.version(), entry.taskVersion()));
    }
    return new SwapTaskBoardDatesRequest(firstDate, secondDate, expectations);
  }

  private List<Object> race(Supplier<Object> firstCommand, Supplier<Object> secondCommand)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                try {
                  return firstCommand.get();
                } catch (RuntimeException exception) {
                  return exception;
                }
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                try {
                  return secondCommand.get();
                } catch (RuntimeException exception) {
                  return exception;
                }
              });
      start.countDown();
      return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }
  }

  private void assertSingleSuccess(List<Object> outcomes) {
    assertThat(outcomes).filteredOn(RuntimeException.class::isInstance).hasSize(1);
    assertThat(outcomes).filteredOn(value -> !(value instanceof RuntimeException)).hasSize(1);
  }

  private Jwt token(String type, UUID warehouse, UUID worker, String scope) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("subject")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", type)
        .claim("warehouse_id", warehouse.toString())
        .claim("worker_id", worker.toString())
        .claim("scope", scope)
        .build();
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    FakeCredentials fakeCredentials() {
      return new FakeCredentials();
    }
  }

  static class FakeCredentials implements WorkerCredentialGateway {
    final AtomicBoolean failConfigure = new AtomicBoolean();
    final AtomicBoolean failDisable = new AtomicBoolean();
    final AtomicBoolean failDelete = new AtomicBoolean();
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger disables = new AtomicInteger();
    final AtomicReference<String> lastConfiguredLogin = new AtomicReference<>();
    final AtomicReference<String> externalAppLogin = new AtomicReference<>();
    final AtomicReference<String> externalCredentialState = new AtomicReference<>();
    final AtomicBoolean delayReset = new AtomicBoolean();
    CountDownLatch resetEntered = new CountDownLatch(1);
    CountDownLatch releaseReset = new CountDownLatch(1);

    void resetState() {
      failConfigure.set(false);
      failDisable.set(false);
      failDelete.set(false);
      deletes.set(0);
      disables.set(0);
      lastConfiguredLogin.set(null);
      externalAppLogin.set(null);
      externalCredentialState.set(null);
      delayReset.set(false);
      resetEntered = new CountDownLatch(1);
      releaseReset = new CountDownLatch(1);
    }

    public void configure(UUID workerId, UUID warehouseId, String appLogin, String password) {
      lastConfiguredLogin.set(appLogin);
      if (failConfigure.get()) throw new IllegalStateException("auth down");
      externalAppLogin.set(appLogin);
      externalCredentialState.set("ACTIVE");
    }

    public void reset(UUID workerId, String password) {
      if (!delayReset.get()) return;
      resetEntered.countDown();
      try {
        if (!releaseReset.await(5, TimeUnit.SECONDS))
          throw new IllegalStateException("reset timeout");
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("reset interrupted", exception);
      }
      externalCredentialState.set("ACTIVE");
    }

    public void disable(UUID workerId) {
      if (failDisable.get()) throw new IllegalStateException("auth down");
      disables.incrementAndGet();
      externalCredentialState.set("DISABLED");
    }

    public void enable(UUID workerId) {
      externalCredentialState.set("ACTIVE");
    }

    public void delete(UUID workerId) {
      deletes.incrementAndGet();
      if (failDelete.get()) throw new IllegalStateException("auth down");
      externalCredentialState.set(null);
    }

    public WorkerCredentialSnapshot status(UUID workerId, UUID expectedWarehouseId) {
      return switch (externalCredentialState.get()) {
        case "ACTIVE" ->
            new WorkerCredentialSnapshot(
                workerId,
                expectedWarehouseId,
                externalAppLogin.get(),
                WorkerCredentialStatus.ACTIVE);
        case "DISABLED" ->
            new WorkerCredentialSnapshot(
                workerId,
                expectedWarehouseId,
                externalAppLogin.get(),
                WorkerCredentialStatus.DISABLED);
        case null ->
            new WorkerCredentialSnapshot(
                workerId, expectedWarehouseId, null, WorkerCredentialStatus.ABSENT);
        default -> throw new IllegalStateException("unknown fake credential state");
      };
    }
  }
}
