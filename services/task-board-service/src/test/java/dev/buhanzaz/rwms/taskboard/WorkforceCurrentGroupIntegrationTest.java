package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.WorkerGroupAvailabilityService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
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
class WorkforceCurrentGroupIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID W1 =
      UUID.fromString("00000000-0000-0000-0000-000000000401");
  private static final UUID W2 =
      UUID.fromString("00000000-0000-0000-0000-000000000402");

  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;
  @Autowired WorkerGroupAvailabilityService availability;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void managerSelectsOneCurrentGroupAndHistoryIsWarehouseScoped() {
    WorkerClassDto workerClass =
        registry.createClass(new WorkerClassRequest(0L, "Слесарь", null, null, 0, true));
    WorkerDto worker =
        workforce.createWorker(
            W1,
            new WorkerRequest(
                0L,
                "Иван",
                "Иван",
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    WorkerGroupDto group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Бригада 1",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), true))));

    WorkerDto assigned =
        workforce.setCurrentGroup(
            W1, worker.id(), new SetCurrentGroupRequest(worker.version(), group.id()));

    assertThat(assigned.currentGroupId()).isEqualTo(group.id());
    assertThat(assigned.currentGroupName()).isEqualTo("Бригада 1");
    assertThat(assigned.operationalAvailability())
        .isEqualTo(GroupOperationalStatus.AVAILABLE);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from worker_current_group_interval
                 where worker_id=? and worker_group_id=? and ended_at is null
                """,
                Integer.class,
                worker.id(),
                group.id()))
        .isOne();

    assertThatThrownBy(
            () ->
                workforce.setCurrentGroup(
                    W2,
                    worker.id(),
                    new SetCurrentGroupRequest(assigned.version(), group.id())))
        .isInstanceOf(dev.buhanzaz.rwms.taskboard.service.NotFoundException.class);
  }

  @Test
  void groupCommandAssignsAndRemovesCurrentWorkersAtomically() {
    WorkerClassDto workerClass =
        registry.createClass(new WorkerClassRequest(0L, "Монтажник", null, null, 0, true));
    WorkerDto first = createQualifiedWorker(workerClass, "Иван");
    WorkerDto second = createQualifiedWorker(workerClass, "Анна");

    WorkerGroupDto group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Монтажная бригада",
                null,
                true,
                List.of(
                    new GroupMemberRequest(first.id(), true),
                    new GroupMemberRequest(second.id(), true)),
                List.of(
                    new CurrentGroupChangeRequest(first.id(), first.version(), true),
                    new CurrentGroupChangeRequest(second.id(), second.version(), true))));

    List<WorkerDto> assigned = workforce.listWorkers(W1);
    assertThat(assigned)
        .filteredOn(worker -> worker.id().equals(first.id()) || worker.id().equals(second.id()))
        .allSatisfy(worker -> assertThat(worker.currentGroupId()).isEqualTo(group.id()));

    WorkerDto refreshedFirst =
        assigned.stream().filter(worker -> worker.id().equals(first.id())).findFirst().orElseThrow();
    workforce.updateGroup(
        W1,
        group.id(),
        new WorkerGroupRequest(
            group.version(),
            workerClass.id(),
            group.name(),
            group.description(),
            true,
            List.of(new GroupMemberRequest(second.id(), true)),
            List.of(
                new CurrentGroupChangeRequest(
                    first.id(), refreshedFirst.version(), false))));

    WorkerDto cleared =
        workforce.listWorkers(W1).stream()
            .filter(worker -> worker.id().equals(first.id()))
            .findFirst()
            .orElseThrow();
    assertThat(cleared.currentGroupId()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_group_member where worker_group_id=? and worker_id=?",
                Integer.class,
                group.id(),
                first.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_current_group_interval where worker_id=? and ended_at is null",
                Integer.class,
                first.id()))
        .isZero();
  }

  @Test
  void staleWorkerVersionRollsBackGroupCreation() {
    WorkerClassDto workerClass =
        registry.createClass(new WorkerClassRequest(0L, "Сборщик", null, null, 0, true));
    WorkerDto worker = createQualifiedWorker(workerClass, "Пётр");

    assertThatThrownBy(
            () ->
                workforce.createGroup(
                    W1,
                    new WorkerGroupRequest(
                        0L,
                        workerClass.id(),
                        "Несохранённая бригада",
                        null,
                        true,
                        List.of(new GroupMemberRequest(worker.id(), true)),
                        List.of(
                            new CurrentGroupChangeRequest(
                                worker.id(), worker.version() + 1, true)))))
        .isInstanceOf(ConflictException.class);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker_group where warehouse_id=? and name=?",
                Integer.class,
                W1,
                "Несохранённая бригада"))
        .isZero();
  }

  @Test
  void workerCannotSelectAGroupWithoutActiveMembership() {
    WorkerClassDto workerClass =
        registry.createClass(new WorkerClassRequest(0L, "Водитель", null, null, 0, true));
    WorkerDto worker =
        workforce.createWorker(
            W1,
            new WorkerRequest(
                0L,
                "Пётр",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    WorkerGroupDto group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L, workerClass.id(), "Чужая бригада", null, true, List.of()));

    assertThatThrownBy(
            () ->
                workforce.setCurrentGroup(
                    W1,
                    worker.id(),
                    new SetCurrentGroupRequest(worker.version(), group.id())))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("не состоит");
  }

  @Test
  void managerCanMoveWorkerAwayFromDisabledCurrentGroup() {
    WorkerClassDto workerClass =
        registry.createClass(new WorkerClassRequest(0L, "Комплектовщик", null, null, 0, true));
    WorkerDto worker =
        workforce.createWorker(
            W1,
            new WorkerRequest(
                0L,
                "Анна",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    WorkerGroupDto first =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Первая смена",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), true))));
    WorkerGroupDto second =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Вторая смена",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), true))));
    WorkerDto assigned =
        workforce.setCurrentGroup(
            W1, worker.id(), new SetCurrentGroupRequest(worker.version(), first.id()));

    availability.disable(
        W1, first.id(), new GroupAvailabilityRequest(first.version(), "Пересменка"));
    WorkerDto moved =
        workforce.setCurrentGroup(
            W1, worker.id(), new SetCurrentGroupRequest(assigned.version(), second.id()));

    assertThat(moved.currentGroupId()).isEqualTo(second.id());
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from worker_current_group_interval
                 where worker_id=? and worker_group_id=? and ended_at is null
                """,
                Integer.class,
                worker.id(),
                second.id()))
        .isOne();
  }

  @Test
  void disablingGroupRequiresReason() {
    WorkerClassDto workerClass =
        registry.createClass(new WorkerClassRequest(0L, "Такелажник", null, null, 0, true));
    WorkerGroupDto group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L, workerClass.id(), "Бригада", null, true, List.of()));

    assertThatThrownBy(
            () ->
                availability.disable(
                    W1, group.id(), new GroupAvailabilityRequest(group.version(), "  ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("причину");
  }

  private WorkerDto createQualifiedWorker(WorkerClassDto workerClass, String name) {
    return workforce.createWorker(
        W1,
        new WorkerRequest(
            0L,
            name,
            null,
            null,
            null,
            true,
            null,
            null,
            null,
            List.of(new QualificationRequest(workerClass.id(), true, null))));
  }
}
