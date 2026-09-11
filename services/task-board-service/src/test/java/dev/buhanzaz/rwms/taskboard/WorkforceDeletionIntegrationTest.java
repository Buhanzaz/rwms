package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardReplayVerifier;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.StaleVersionException;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Real PostgreSQL coverage for directory deletion without erasing execution or KPI history. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TaskBoardServiceIntegrationTest.TestConfig.class)
class WorkforceDeletionIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000501");
  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;
  @Autowired TaskBoardService board;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkerRepository workers;
  @Autowired WorkerGroupRepository groups;
  @Autowired TaskAssignmentRepository assignments;
  @Autowired TaskBoardReplayVerifier verifier;
  @Autowired TaskBoardServiceIntegrationTest.FakeCredentials credentials;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    credentials.resetState();
  }

  @Test
  void deletingWorkerPreservesCompletedAssignmentsTimeAndGroupHistory() {
    Fixture fixture = fixture();
    board.complete(WAREHOUSE, fixture.entry().id(), new VersionCommand(fixture.entry().version()), null);
    int timeCount = count("select count(*) from task_time_event where worker_id=?", fixture.worker().id());
    assertThat(timeCount).isPositive();
    verifyWorkforce(fixture);
    String historyBefore = historyFingerprint(fixture.entry().id());

    workforce.deleteWorker(WAREHOUSE, fixture.worker().id(), fixture.worker().version());

    assertThat(workforce.listWorkers(WAREHOUSE)).isEmpty();
    assertThat(workers.findById(fixture.worker().id())).isEmpty();
    assertThat(credentials.deletes).hasValue(1);
    assertThat(count("select count(*) from worker where id=? and archived and not active "
        + "and app_login is null and current_group_id is null", fixture.worker().id())).isOne();
    assertThat(count("select count(*) from task_time_event where worker_id=?", fixture.worker().id()))
        .isEqualTo(timeCount);
    assertThat(count("select count(*) from worker_current_group_interval where worker_id=? "
        + "and ended_at is not null", fixture.worker().id())).isOne();
    var historical = assignments.findAllBoardAssignmentsByEntryIdIn(List.of(fixture.entry().id()));
    assertThat(historical).hasSize(1);
    assertThat(historical.getFirst().getWorker().getDisplayName()).isEqualTo("Архивный рабочий");
    assertThat(historical.getFirst().getWorker().isArchived()).isTrue();
    assertThat(workforce.listGroups(WAREHOUSE).getFirst().members()).isEmpty();
    assertThat(historyFingerprint(fixture.entry().id())).isEqualTo(historyBefore);
    verifyWorkforce(fixture);
  }

  @Test
  void deletingGroupDetachesCurrentMembersPreservesHistoryAndReleasesName() {
    Fixture fixture = fixture();
    board.complete(WAREHOUSE, fixture.entry().id(), new VersionCommand(fixture.entry().version()), null);
    verifyWorkforce(fixture);

    String historyBefore = historyFingerprint(fixture.entry().id());
    workforce.deleteGroup(WAREHOUSE, fixture.group().id(), fixture.group().version());

    assertThat(workforce.listGroups(WAREHOUSE)).isEmpty();
    assertThat(groups.findById(fixture.group().id())).isEmpty();
    assertThat(workforce.listWorkers(WAREHOUSE).getFirst().currentGroupId()).isNull();
    assertThat(credentials.deletes).hasValue(0);
    assertThat(count("select count(*) from worker_group_member where worker_group_id=?", fixture.group().id()))
        .isZero();
    assertThat(count("select count(*) from worker_current_group_interval where worker_group_id=? "
        + "and ended_at is not null", fixture.group().id())).isOne();
    var historical = assignments.findAllBoardAssignmentsByEntryIdIn(List.of(fixture.entry().id()));
    assertThat(historical.getFirst().getWorkerGroup().getName()).isEqualTo("Архивная группа");
    assertThat(historical.getFirst().getWorkerGroup().isArchived()).isTrue();
    WorkerGroupDto replacement = workforce.createGroup(WAREHOUSE,
        new WorkerGroupRequest(0L, fixture.group().workerClass().id(), fixture.group().name(), null, true, List.of()));
    assertThat(replacement.id()).isNotEqualTo(fixture.group().id());
    assertThat(historyFingerprint(fixture.entry().id())).isEqualTo(historyBefore);
    verifyWorkforce(fixture);
  }

  @Test
  void unfinishedAssignmentsAndStaleVersionsBlockDeletionBeforeAuthSideEffects() {
    Fixture fixture = fixture();
    assertThatThrownBy(() -> workforce.deleteWorker(WAREHOUSE, fixture.worker().id(), fixture.worker().version() + 1))
        .isInstanceOf(StaleVersionException.class);
    assertThatThrownBy(() -> workforce.deleteGroup(WAREHOUSE, fixture.group().id(), fixture.group().version() + 1))
        .isInstanceOf(StaleVersionException.class);
    assertThatThrownBy(() -> workforce.deleteWorker(WAREHOUSE, fixture.worker().id(), fixture.worker().version()))
        .isInstanceOf(ConflictException.class).hasMessageContaining("завершите или отмените");
    assertThatThrownBy(() -> workforce.deleteGroup(WAREHOUSE, fixture.group().id(), fixture.group().version()))
        .isInstanceOf(ConflictException.class).hasMessageContaining("завершите или отмените");
    assertThat(credentials.deletes).hasValue(0);
    assertThat(workforce.listWorkers(WAREHOUSE)).hasSize(1);
    assertThat(workforce.listGroups(WAREHOUSE)).hasSize(1);
  }

  private int count(String sql, UUID id) {
    return jdbc.queryForObject(sql, Integer.class, id);
  }

  private void verifyWorkforce(Fixture fixture) {
    assertThatCode(() -> verifier.verify(TaskBoardAggregateType.WORKER, fixture.worker().id()))
        .as("worker projection parity").doesNotThrowAnyException();
    assertThatCode(() -> verifier.verify(TaskBoardAggregateType.WORKER_GROUP, fixture.group().id()))
        .as("group projection parity").doesNotThrowAnyException();
  }

  private String historyFingerprint(UUID entryId) {
    return jdbc.queryForObject("""
        select md5(
          (select coalesce(jsonb_agg(to_jsonb(a) order by a.id)::text, '')
             from task_assignment a where a.queue_entry_id=?) ||
          (select coalesce(jsonb_agg(to_jsonb(e) order by e.id)::text, '')
             from task_time_event e where e.queue_entry_id=?))
        """, String.class, entryId, entryId);
  }

  private Fixture fixture() {
    WorkerClassDto qualification = registry.createClass(new WorkerClassRequest(0L, "Слесарь", null, null, 0, true));
    WorkerDto worker = workforce.createWorker(WAREHOUSE,
        new WorkerRequest(0L, "Архивный рабочий", null, null, null, true, null,
            "archive-test", "archive-password", List.of(new QualificationRequest(qualification.id(), true, null))));
    WorkerGroupDto group = workforce.createGroup(WAREHOUSE,
        new WorkerGroupRequest(0L, qualification.id(), "Архивная группа", null, true,
            List.of(new GroupMemberRequest(worker.id(), true))));
    worker = workforce.setCurrentGroup(WAREHOUSE, worker.id(), new SetCurrentGroupRequest(worker.version(), group.id()));
    var definition = registry.createQueueDefinition(
        QueueRegistryTestFixtures.globalDefinition(0L, "Архивная очередь", null, QueueType.MOVEMENT));
    WorkQueueDto queue = QueueRegistryTestFixtures.create(registry, jdbc, WAREHOUSE,
        new QueueFixtureModels.QueueFixtureRequest(0L, definition.id(), true,
            false, false, null, null, false, null,
            List.of(new QueueBindingRequest(qualification.id(), false))));
    BoardEntryDto entry = board.createTask(WAREHOUSE,
        new CreateBoardTaskRequest(null, "Историческая работа", null, null, null, null,
            List.of(new RouteStepRequest(queue.definitionId(), null, null))))
        .columns().stream().flatMap(column -> column.entries().stream()).findFirst().orElseThrow();
    entry = board.take(WAREHOUSE, entry.id(), new TakeEntryRequest(entry.version(), group.id(), worker.id()), null);
    return new Fixture(worker, group, entry);
  }

  private record Fixture(WorkerDto worker, WorkerGroupDto group, BoardEntryDto entry) {}
}
