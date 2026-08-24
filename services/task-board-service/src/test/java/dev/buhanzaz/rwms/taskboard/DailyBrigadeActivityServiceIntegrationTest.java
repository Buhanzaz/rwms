package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.DailyBrigadeActivityDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.DailyBrigadeActivityIntervalDto;
import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.service.DailyBrigadeActivityService;
import dev.buhanzaz.rwms.taskboard.service.WarehouseTimeZoneGateway.TimeZoneDecision;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Verifies current-day selection and legacy assignment coalescing against PostgreSQL. */
@SpringBootTest
@ActiveProfiles("test")
@Import(DailyBrigadeActivityServiceIntegrationTest.FixedClockConfiguration.class)
class DailyBrigadeActivityServiceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final Instant NOW = Instant.parse("2026-08-24T15:00:00Z");
  private static final Instant DAY_START = Instant.parse("2026-08-23T21:00:00Z");
  private static final Instant DAY_END = Instant.parse("2026-08-24T21:00:00Z");
  private static final UUID WAREHOUSE =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_WAREHOUSE =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID WORKER_CLASS =
      UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID QUEUE_DEFINITION =
      UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID QUEUE =
      UUID.fromString("40000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_QUEUE =
      UUID.fromString("40000000-0000-0000-0000-000000000002");
  private static final UUID GROUP =
      UUID.fromString("50000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_GROUP =
      UUID.fromString("50000000-0000-0000-0000-000000000002");
  private static final UUID WORKER_ONE =
      UUID.fromString("60000000-0000-0000-0000-000000000001");
  private static final UUID WORKER_TWO =
      UUID.fromString("60000000-0000-0000-0000-000000000002");
  private static final UUID OTHER_WORKER =
      UUID.fromString("60000000-0000-0000-0000-000000000003");

  @Autowired DailyBrigadeActivityService service;
  @Autowired TestWarehouseTimeZoneGateway timeZones;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    timeZones.reset();
    timeZones.setTimeline(
        WAREHOUSE,
        List.of(new TimeZoneDecision(ZoneId.of("Europe/Moscow"), Instant.EPOCH)));
    timeZones.setTimeline(
        OTHER_WAREHOUSE,
        List.of(new TimeZoneDecision(ZoneId.of("Europe/Moscow"), Instant.EPOCH)));
    seedCatalog();
  }

  @Test
  void usesExactWarehouseLocalDayAndActualAssignmentTimestamps() {
    UUID boundaryTask = task(WAREHOUSE, "Пересечение границы", "ACTIVE", 2, null);
    UUID boundaryEntry = entry(boundaryTask, QUEUE, 0, "Граничная работа", null);
    assignment(
        boundaryEntry,
        GROUP,
        WORKER_ONE,
        AssignmentStatus.DONE,
        DAY_START.minusSeconds(900),
        DAY_START.plusSeconds(600));

    UUID beforeTask = task(WAREHOUSE, "До дня", "DONE", 3, null);
    assignment(
        entry(beforeTask, QUEUE, 0, "До дня", null),
        GROUP,
        WORKER_ONE,
        AssignmentStatus.DONE,
        DAY_START.minusSeconds(3600),
        DAY_START);

    UUID afterTask = task(WAREHOUSE, "После дня", "ACTIVE", 3, null);
    assignment(
        entry(afterTask, QUEUE, 0, "После дня", null),
        GROUP,
        WORKER_ONE,
        AssignmentStatus.ACTIVE,
        DAY_END,
        null);

    Instant activeStart = Instant.parse("2026-08-24T12:34:56Z");
    UUID activeTask = task(WAREHOUSE, "Текущая работа", "ACTIVE", 1, null);
    assignment(
        entry(activeTask, QUEUE, 0, "Текущая работа", null),
        GROUP,
        WORKER_ONE,
        AssignmentStatus.ACTIVE,
        activeStart,
        null);

    Instant pausedStart = Instant.parse("2026-08-24T13:45:12Z");
    UUID pausedTask = task(WAREHOUSE, "Пауза", "ACTIVE", 4, null);
    assignment(
        entry(pausedTask, QUEUE, 0, "Пауза", null),
        GROUP,
        WORKER_ONE,
        AssignmentStatus.PAUSED,
        pausedStart,
        null);

    UUID foreignTask = task(OTHER_WAREHOUSE, "Чужой склад", "ACTIVE", 3, null);
    assignment(
        entry(foreignTask, OTHER_QUEUE, 0, "Чужой склад", null),
        OTHER_GROUP,
        OTHER_WORKER,
        AssignmentStatus.ACTIVE,
        NOW.minusSeconds(300),
        null);

    UUID cancelledAssignmentTask =
        task(WAREHOUSE, "Отмененное назначение", "ACTIVE", 3, null);
    assignment(
        entry(cancelledAssignmentTask, QUEUE, 0, "Отмененное назначение", null),
        GROUP,
        WORKER_ONE,
        AssignmentStatus.CANCELLED,
        NOW.minusSeconds(1200),
        NOW.minusSeconds(600));

    UUID cancelledTask = task(WAREHOUSE, "Отмененная задача", "CANCELLED", 3, null);
    assignment(
        entry(cancelledTask, QUEUE, 0, "Отмененная задача", null),
        GROUP,
        WORKER_ONE,
        AssignmentStatus.DONE,
        NOW.minusSeconds(1200),
        NOW.minusSeconds(600));

    DailyBrigadeActivityDto activity = service.currentDay(WAREHOUSE);

    assertThat(activity.warehouseId()).isEqualTo(WAREHOUSE);
    assertThat(activity.localDate()).hasToString("2026-08-24");
    assertThat(activity.serverTime())
        .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    assertThat(activity.intervals())
        .extracting(DailyBrigadeActivityIntervalDto::title)
        .containsExactly("Пересечение границы", "Текущая работа", "Пауза");

    DailyBrigadeActivityIntervalDto boundary = interval(activity, "Пересечение границы");
    assertThat(boundary.startedAt().toInstant()).isEqualTo(DAY_START.minusSeconds(900));
    assertThat(boundary.finishedAt().toInstant()).isEqualTo(DAY_START.plusSeconds(600));
    assertThat(boundary.status()).isEqualTo(AssignmentStatus.DONE);

    DailyBrigadeActivityIntervalDto active = interval(activity, "Текущая работа");
    assertThat(active.startedAt().toInstant()).isEqualTo(activeStart);
    assertThat(active.finishedAt()).isNull();
    assertThat(active.status()).isEqualTo(AssignmentStatus.ACTIVE);

    DailyBrigadeActivityIntervalDto paused = interval(activity, "Пауза");
    assertThat(paused.startedAt().toInstant()).isEqualTo(pausedStart);
    assertThat(paused.finishedAt()).isNull();
    assertThat(paused.status()).isEqualTo(AssignmentStatus.PAUSED);
  }

  @Test
  void coalescesOverlappingDuplicateQueueRowsButKeepsNonOverlappingRetakes() {
    UUID repairId = UUID.fromString("70000000-0000-0000-0000-000000000001");
    UUID task = task(WAREHOUSE, "Ремонт 231245", "DONE", 2, repairId);
    UUID representativeEntry =
        UUID.fromString("80000000-0000-0000-0000-000000000100");
    UUID duplicateEntry =
        UUID.fromString("80000000-0000-0000-0000-000000000001");
    UUID retakeEntry =
        UUID.fromString("80000000-0000-0000-0000-000000000002");
    entry(task, QUEUE, 0, "Первая работа", representativeEntry);
    entry(task, QUEUE, 1, "Дублированная работа", duplicateEntry);
    entry(task, QUEUE, 2, "Повторный заход", retakeEntry);

    assignment(
        representativeEntry,
        GROUP,
        WORKER_ONE,
        AssignmentStatus.DONE,
        Instant.parse("2026-08-24T07:12:00Z"),
        Instant.parse("2026-08-24T09:00:00Z"));
    assignment(
        representativeEntry,
        GROUP,
        WORKER_TWO,
        AssignmentStatus.DONE,
        Instant.parse("2026-08-24T07:13:00Z"),
        Instant.parse("2026-08-24T09:02:00Z"));
    assignment(
        duplicateEntry,
        GROUP,
        WORKER_ONE,
        AssignmentStatus.DONE,
        Instant.parse("2026-08-24T08:59:00Z"),
        Instant.parse("2026-08-24T09:03:00Z"));
    assignment(
        retakeEntry,
        GROUP,
        WORKER_ONE,
        AssignmentStatus.DONE,
        Instant.parse("2026-08-24T09:03:00Z"),
        Instant.parse("2026-08-24T10:00:00Z"));

    DailyBrigadeActivityDto activity = service.currentDay(WAREHOUSE);

    assertThat(activity.intervals()).hasSize(2);
    DailyBrigadeActivityIntervalDto first = activity.intervals().getFirst();
    assertThat(first.entryId()).isEqualTo(representativeEntry);
    assertThat(first.taskText()).isEqualTo("Первая работа");
    assertThat(first.startedAt().toInstant())
        .isEqualTo(Instant.parse("2026-08-24T07:12:00Z"));
    assertThat(first.finishedAt().toInstant())
        .isEqualTo(Instant.parse("2026-08-24T09:03:00Z"));
    assertThat(first.status()).isEqualTo(AssignmentStatus.DONE);
    assertThat(first.source().type()).isEqualTo(TaskSourceType.MAINTENANCE_REPAIR);
    assertThat(first.source().sourceId()).isEqualTo(repairId);

    DailyBrigadeActivityIntervalDto retake = activity.intervals().get(1);
    assertThat(retake.entryId()).isEqualTo(retakeEntry);
    assertThat(retake.startedAt().toInstant())
        .isEqualTo(Instant.parse("2026-08-24T09:03:00Z"));
    assertThat(retake.finishedAt().toInstant())
        .isEqualTo(Instant.parse("2026-08-24T10:00:00Z"));
  }

  private DailyBrigadeActivityIntervalDto interval(
      DailyBrigadeActivityDto activity, String title) {
    return activity.intervals().stream()
        .filter(candidate -> candidate.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private void seedCatalog() {
    jdbc.update(
        """
        insert into worker_class(id, version, revision_marker, name, sort_order, active)
        values (?, 0, ?, 'Электрики', 0, true)
        """,
        WORKER_CLASS,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into queue_definition(
          id, version, revision_marker, name, normalized_name, queue_type, queue_purpose)
        values (?, 0, ?, 'Электрика', 'электрика', 'REPAIR', 'GENERAL')
        """,
        QUEUE_DEFINITION,
        UUID.randomUUID());
    seedQueue(WAREHOUSE, QUEUE);
    seedQueue(OTHER_WAREHOUSE, OTHER_QUEUE);
    seedGroup(WAREHOUSE, GROUP, "Электрики");
    seedGroup(OTHER_WAREHOUSE, OTHER_GROUP, "Электрики другого склада");
    seedWorker(WAREHOUSE, WORKER_ONE, "Рабочий один");
    seedWorker(WAREHOUSE, WORKER_TWO, "Рабочий два");
    seedWorker(OTHER_WAREHOUSE, OTHER_WORKER, "Чужой рабочий");
  }

  private void seedQueue(UUID warehouseId, UUID queueId) {
    jdbc.update(
        """
        insert into work_queue(
          id, version, revision_marker, warehouse_id, definition_id, sort_order,
          active, hidden, collapsed, notify_when_threshold_reached,
          result_photo_min_count, available_task_limit, worker_feed_enabled)
        values (?, 0, ?, ?, ?, 0, true, false, false, false, 1, 6, true)
        """,
        queueId,
        UUID.randomUUID(),
        warehouseId,
        QUEUE_DEFINITION);
  }

  private void seedGroup(UUID warehouseId, UUID groupId, String name) {
    jdbc.update(
        """
        insert into worker_group(
          id, version, revision_marker, warehouse_id, worker_class_id, name, active)
        values (?, 0, ?, ?, ?, ?, true)
        """,
        groupId,
        UUID.randomUUID(),
        warehouseId,
        WORKER_CLASS,
        name);
  }

  private void seedWorker(UUID warehouseId, UUID workerId, String name) {
    jdbc.update(
        """
        insert into worker(
          id, version, revision_marker, warehouse_id, display_name, active, credential_status)
        values (?, 0, ?, ?, ?, true, 'NOT_CONFIGURED')
        """,
        workerId,
        UUID.randomUUID(),
        warehouseId,
        name);
  }

  private UUID task(
      UUID warehouseId, String title, String status, int priority, UUID sourceId) {
    UUID taskId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    jdbc.update(
        """
        insert into board_task(
          id, version, warehouse_id, external_task_id, title, unit_number, status,
          scheduled_date, task_lane, priority, pinned, completion_deadline_enforced)
        values (?, 0, ?, ?, ?, '231245', ?, date '2026-08-24', 'SCHEDULED', ?, false, false)
        """,
        taskId,
        warehouseId,
        externalTaskId,
        title,
        status,
        priority);
    if (sourceId != null) {
      jdbc.update(
          """
          insert into task_sync_source(
            board_task_id, external_task_id, source_client_id, source_type, source_id)
          values (?, ?, 'maintenance-service', 'MAINTENANCE_REPAIR', ?)
          """,
          taskId,
          externalTaskId,
          sourceId);
    }
    return taskId;
  }

  private UUID entry(
      UUID taskId, UUID queueId, int routeIndex, String taskText, UUID requestedId) {
    UUID entryId = requestedId == null ? UUID.randomUUID() : requestedId;
    jdbc.update(
        """
        insert into queue_entry(
          id, version, revision_marker, task_id, queue_id, route_index, queue_position,
          entry_type, status, task_text, active_work_seconds)
        values (?, 0, ?, ?, ?, ?, ?, 'REAL', 'DONE', ?, 0)
        """,
        entryId,
        UUID.randomUUID(),
        taskId,
        queueId,
        routeIndex,
        routeIndex,
        taskText);
    return entryId;
  }

  private void assignment(
      UUID entryId,
      UUID groupId,
      UUID workerId,
      AssignmentStatus status,
      Instant startedAt,
      Instant finishedAt) {
    String groupName = groupId.equals(GROUP) ? "Электрики" : "Электрики другого склада";
    jdbc.update(
        """
        insert into task_assignment(
          id, version, queue_entry_id, worker_group_id, worker_id, worker_name_snapshot,
          group_name_snapshot, status, assigned_at, started_at, finished_at)
        values (?, 0, ?, ?, ?, 'Рабочий', ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        entryId,
        groupId,
        workerId,
        groupName,
        status.name(),
        OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
        finishedAt == null ? null : OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC));
  }

  /** Supplies one fixed UTC server instant to the current-day projection. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    /** Returns the deterministic instant used by day-boundary assertions. */
    @Bean
    Clock fixedDailyActivityClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
