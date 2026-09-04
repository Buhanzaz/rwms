package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.KpiSettings;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiDayState;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiOpenState;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiSegmentOutcome;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkBreakInterval;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.TaskAssignment;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.KpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.repository.GroupKpiDayStateRepository;
import dev.buhanzaz.rwms.taskboard.repository.GroupKpiResponsibilitySegmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.KpiWorkScheduleRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueDefinitionRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import dev.buhanzaz.rwms.taskboard.service.GroupKpiEvidenceService;
import dev.buhanzaz.rwms.taskboard.service.WarehouseTimeZoneGateway.TimeZoneDecision;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GroupKpiEvidenceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000501");
  private static final LocalDate WORK_DATE = LocalDate.of(2035, 6, 4);

  @Autowired GroupKpiEvidenceService evidence;
  @Autowired GroupKpiDayStateRepository days;
  @Autowired GroupKpiResponsibilitySegmentRepository segments;
  @Autowired KpiSettingsRepository settings;
  @Autowired KpiWorkScheduleRepository schedules;
  @Autowired WorkerClassRepository workerClasses;
  @Autowired WorkerGroupRepository groups;
  @Autowired WorkerRepository workers;
  @Autowired WorkQueueRepository queues;
  @Autowired QueueDefinitionRepository queueDefinitions;
  @Autowired WorkQueueClassBindingRepository bindings;
  @Autowired BoardTaskRepository tasks;
  @Autowired QueueEntryRepository entries;
  @Autowired TaskAssignmentRepository assignments;
  @Autowired TestWarehouseTimeZoneGateway timeZones;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    timeZones.reset();
    timeZones.setTimeline(
        WAREHOUSE_ID, List.of(new TimeZoneDecision(ZoneId.of("UTC"), Instant.EPOCH)));
  }

  @Test
  void workingAccumulatesActiveSecondsAndCompletionAddsSpeedEvidence() {
    Fixture fixture = fixture(EntryStatus.IN_PROGRESS, List.of());
    OffsetDateTime startedAt = at(9, 0);

    evidence.beginSegment(WAREHOUSE_ID, fixture.group().getId(), fixture.entry(), startedAt);
    evidence.refreshGroup(WAREHOUSE_ID, fixture.group().getId(), startedAt.plusMinutes(10));

    fixture.entry().setStatus(EntryStatus.DONE);
    entries.saveAndFlush(fixture.entry());
    evidence.completeSegment(
        WAREHOUSE_ID, fixture.entry(), startedAt.plusMinutes(10));

    GroupKpiDayState day = day(fixture.group());
    assertThat(day.getOpenState()).isEqualTo(GroupKpiOpenState.EXCLUDED);
    assertThat(day.getActiveSeconds()).isEqualTo(600);
    assertThat(day.getCompletedBudgetSeconds()).isEqualTo(3_600);
    assertThat(day.getEarnedRemainingSeconds()).isEqualTo(3_000);
    assertThat(day.getCompletedTaskCount()).isOne();
    assertThat(day.getPenalizedIdleSeconds()).isZero();
    assertThat(
            segments.findAllByQueueEntryIdAndOutcome(
                fixture.entry().getId(), GroupKpiSegmentOutcome.COMPLETED))
        .singleElement()
        .satisfies(
            segment -> {
              assertThat(segment.getBudgetSeconds()).isEqualTo(3_600);
              assertThat(segment.getActiveSeconds()).isEqualTo(600);
            });
  }

  @Test
  void idleGraceDoesNotPenalizeFirstFiveMinutesAndIdleRequiresAvailableWork() {
    Fixture fixture = fixture(EntryStatus.WAITING, List.of());
    OffsetDateTime idleStartedAt = at(9, 0);

    evidence.refreshGroup(WAREHOUSE_ID, fixture.group().getId(), idleStartedAt);
    evidence.refreshDueGroup(
        WAREHOUSE_ID, fixture.group().getId(), idleStartedAt.plusMinutes(4).plusSeconds(59));

    GroupKpiDayState insideGrace = day(fixture.group());
    assertThat(insideGrace.getOpenState()).isEqualTo(GroupKpiOpenState.IDLE_GRACE);
    assertThat(insideGrace.getPenaltyStartsAt()).isEqualTo(idleStartedAt.plusMinutes(5));
    assertThat(insideGrace.getPenalizedIdleSeconds()).isZero();

    evidence.refreshDueGroup(
        WAREHOUSE_ID, fixture.group().getId(), idleStartedAt.plusMinutes(6));

    GroupKpiDayState penalized = day(fixture.group());
    assertThat(penalized.getOpenState()).isEqualTo(GroupKpiOpenState.IDLE_PENALIZED);
    assertThat(penalized.getPenalizedIdleSeconds()).isEqualTo(60);

    fixture.task().setScheduledDate(WORK_DATE.plusDays(1));
    tasks.saveAndFlush(fixture.task());
    evidence.refreshGroup(
        WAREHOUSE_ID, fixture.group().getId(), idleStartedAt.plusMinutes(6));
    evidence.refreshDueGroup(
        WAREHOUSE_ID, fixture.group().getId(), idleStartedAt.plusMinutes(16));

    GroupKpiDayState withoutAvailableWork = day(fixture.group());
    assertThat(withoutAvailableWork.getOpenState()).isEqualTo(GroupKpiOpenState.EXCLUDED);
    assertThat(withoutAvailableWork.getPenalizedIdleSeconds()).isEqualTo(60);
  }

  @Test
  void breakAndOffShiftDoNotAccumulateActiveOrIdleSeconds() {
    Fixture fixture =
        fixture(
            EntryStatus.IN_PROGRESS,
            List.of(
                new KpiWorkBreakInterval(
                    LocalTime.of(10, 0), LocalTime.of(10, 15))));
    OffsetDateTime startedAt = at(9, 55);

    evidence.beginSegment(WAREHOUSE_ID, fixture.group().getId(), fixture.entry(), startedAt);
    evidence.refreshDueGroup(WAREHOUSE_ID, fixture.group().getId(), at(10, 10));

    GroupKpiDayState duringBreak = day(fixture.group());
    assertThat(duringBreak.getOpenState()).isEqualTo(GroupKpiOpenState.EXCLUDED);
    assertThat(duringBreak.getActiveSeconds()).isEqualTo(300);
    assertThat(duringBreak.getPenalizedIdleSeconds()).isZero();

    evidence.refreshDueGroup(WAREHOUSE_ID, fixture.group().getId(), at(10, 20));
    assertThat(day(fixture.group()).getActiveSeconds()).isEqualTo(600);

    evidence.refreshDueGroup(WAREHOUSE_ID, fixture.group().getId(), at(17, 10));

    GroupKpiDayState afterShift = day(fixture.group());
    assertThat(afterShift.getOpenState()).isEqualTo(GroupKpiOpenState.EXCLUDED);
    assertThat(afterShift.getActiveSeconds()).isEqualTo(24_600);
    assertThat(afterShift.getPenalizedIdleSeconds()).isZero();

    evidence.refreshDueGroup(WAREHOUSE_ID, fixture.group().getId(), at(18, 0));
    assertThat(day(fixture.group()).getActiveSeconds()).isEqualTo(24_600);
    assertThat(day(fixture.group()).getPenalizedIdleSeconds()).isZero();
  }

  @Test
  void returningSegmentRemovesProvisionalActiveTimeAndAddsNoSpeedCredit() {
    Fixture fixture = fixture(EntryStatus.IN_PROGRESS, List.of());
    OffsetDateTime startedAt = at(11, 0);

    evidence.beginSegment(WAREHOUSE_ID, fixture.group().getId(), fixture.entry(), startedAt);
    evidence.refreshGroup(WAREHOUSE_ID, fixture.group().getId(), startedAt.plusMinutes(10));
    assertThat(day(fixture.group()).getActiveSeconds()).isEqualTo(600);

    fixture.entry().setStatus(EntryStatus.WAITING);
    entries.saveAndFlush(fixture.entry());
    evidence.returnSegment(
        WAREHOUSE_ID, fixture.entry(), startedAt.plusMinutes(10));

    GroupKpiDayState day = day(fixture.group());
    assertThat(day.getActiveSeconds()).isZero();
    assertThat(day.getCompletedBudgetSeconds()).isZero();
    assertThat(day.getEarnedRemainingSeconds()).isZero();
    assertThat(day.getCompletedTaskCount()).isZero();
    assertThat(
            segments.findAllByQueueEntryIdAndOutcome(
                fixture.entry().getId(), GroupKpiSegmentOutcome.RETURNED))
        .singleElement()
        .satisfies(segment -> assertThat(segment.getActiveSeconds()).isEqualTo(600));
  }

  @Test
  void firstRefreshReconcilesWorkThatWasAlreadyActiveWhenKpiEvidenceStarted() {
    Fixture fixture = fixture(EntryStatus.IN_PROGRESS, List.of());
    OffsetDateTime taskStartedAt = at(8, 30);
    fixture.entry().setActiveStartedAt(taskStartedAt);
    entries.saveAndFlush(fixture.entry());

    TaskAssignment assignment = new TaskAssignment();
    assignment.setQueueEntry(fixture.entry());
    assignment.setWorkerGroup(fixture.group());
    assignment.setWorker(fixture.worker());
    assignment.setWorkerNameSnapshot(fixture.worker().getDisplayName());
    assignment.setGroupNameSnapshot(fixture.group().getName());
    assignment.setStatus(AssignmentStatus.ACTIVE);
    assignment.setAssignedAt(taskStartedAt);
    assignment.setStartedAt(taskStartedAt);
    assignments.saveAndFlush(assignment);

    evidence.refreshGroup(WAREHOUSE_ID, fixture.group().getId(), at(9, 0));
    evidence.refreshGroup(WAREHOUSE_ID, fixture.group().getId(), at(9, 10));

    assertThat(day(fixture.group()).getActiveSeconds()).isEqualTo(600);
    assertThat(
            segments.findAllByQueueEntryIdAndOutcome(
                fixture.entry().getId(), GroupKpiSegmentOutcome.OPEN))
        .singleElement()
        .satisfies(
            segment -> {
              assertThat(segment.getBudgetSeconds()).isEqualTo(1_800);
              assertThat(segment.getActiveSeconds()).isEqualTo(600);
            });
  }

  @Test
  void responsibilityDoesNotStartBeforeFirstKpiEffectiveDate() {
    Fixture fixture = fixture(EntryStatus.IN_PROGRESS, List.of());

    evidence.beginSegment(
        WAREHOUSE_ID,
        fixture.group().getId(),
        fixture.entry(),
        WORK_DATE.minusDays(1).atTime(12, 0).atOffset(ZoneOffset.UTC));

    assertThat(
            segments.findAllByQueueEntryIdAndOutcome(
                fixture.entry().getId(), GroupKpiSegmentOutcome.OPEN))
        .isEmpty();
  }

  private Fixture fixture(
      EntryStatus entryStatus, List<KpiWorkBreakInterval> workBreaks) {
    configureSchedule(workBreaks);

    WorkerClass workerClass = new WorkerClass();
    workerClass.setName("Слесарь");
    workerClass = workerClasses.saveAndFlush(workerClass);

    WorkerGroup group = new WorkerGroup();
    group.setWarehouseId(WAREHOUSE_ID);
    group.setWorkerClass(workerClass);
    group.setName("Бригада KPI");
    group = groups.saveAndFlush(group);

    Worker worker = new Worker();
    worker.setWarehouseId(WAREHOUSE_ID);
    worker.setDisplayName("Сотрудник KPI");
    worker.setCurrentGroup(group);
    workers.saveAndFlush(worker);

    QueueDefinition definition = new QueueDefinition();
    definition.setName("Основная очередь");
    definition = queueDefinitions.saveAndFlush(definition);

    WorkQueue queue = new WorkQueue();
    queue.setWarehouseId(WAREHOUSE_ID);
    queue.setDefinition(definition);
    queue = queues.saveAndFlush(queue);

    WorkQueueClassBinding binding = new WorkQueueClassBinding();
    binding.setQueue(queue);
    binding.setWorkerClass(workerClass);
    binding.setBindingOrder(0);
    bindings.saveAndFlush(binding);

    BoardTask task = new BoardTask();
    task.setWarehouseId(WAREHOUSE_ID);
    task.setTitle("Задание для KPI");
    task.setScheduledDate(WORK_DATE);
    task = tasks.saveAndFlush(task);

    QueueEntry entry = new QueueEntry();
    entry.setTask(task);
    entry.setQueue(queue);
    entry.setRouteIndex(0);
    entry.setQueuePosition(1);
    entry.setEntryType(EntryType.REAL);
    entry.setStatus(entryStatus);
    entry.setPlannedDurationMinutes(60);
    entry = entries.saveAndFlush(entry);

    return new Fixture(group, worker, task, entry);
  }

  private void configureSchedule(List<KpiWorkBreakInterval> workBreaks) {
    KpiWorkScheduleRevision schedule =
        new KpiWorkScheduleRevision(
            WORK_DATE,
            LocalTime.of(8, 0),
            LocalTime.of(17, 0),
            List.of(),
            workBreaks);
    schedule.schedule();
    schedule = schedules.saveAndFlush(schedule);

    KpiSettings globalSettings = KpiSettings.create();
    globalSettings.setPendingSchedule(schedule);
    globalSettings.activatePendingSchedule();
    assertThat(globalSettings.promoteSchedule(WORK_DATE)).isTrue();
    settings.saveAndFlush(globalSettings);
  }

  private GroupKpiDayState day(WorkerGroup group) {
    return days
        .findByWarehouseIdAndWorkerGroupIdAndLocalDate(
            WAREHOUSE_ID, group.getId(), WORK_DATE)
        .orElseThrow();
  }

  private static OffsetDateTime at(int hour, int minute) {
    return WORK_DATE.atTime(hour, minute).atOffset(ZoneOffset.UTC);
  }

  private record Fixture(
      WorkerGroup group, Worker worker, BoardTask task, QueueEntry entry) {}
}
