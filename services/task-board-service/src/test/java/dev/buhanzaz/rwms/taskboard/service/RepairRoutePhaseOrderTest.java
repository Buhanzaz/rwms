package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Focused policy coverage for maintenance-owned repair routes and global queue presentation. */
class RepairRoutePhaseOrderTest {

  @Test
  void maintenanceRouteOrdersCanonicalPhasesAndKeepsUnknownStagesInSourceOrder() {
    WorkQueue plumbing = queue("Сантехника", QueuePurpose.GENERAL);
    WorkQueue unknownBefore = queue("legacy-before", QueuePurpose.GENERAL);
    WorkQueue exteriorFirst = queue("Внешние работы", QueuePurpose.GENERAL);
    WorkQueue ses = queue("СЭС и санитария", QueuePurpose.GENERAL);
    WorkQueue exteriorSecond = queue("внешние работы", QueuePurpose.GENERAL);
    WorkQueue unknownAfter = queue("legacy-after", QueuePurpose.GENERAL);

    assertThat(
            RepairRoutePhaseOrder.ordered(
                List.of(plumbing, unknownBefore, exteriorFirst, ses, exteriorSecond, unknownAfter),
                queue -> queue,
                "maintenance-service"))
        .containsExactly(ses, exteriorFirst, exteriorSecond, plumbing, unknownBefore, unknownAfter);
  }

  @Test
  void nonMaintenanceAndLogisticsRoutesRetainTheirRequestedOrder() {
    WorkQueue repair = queue("Сварка", QueuePurpose.GENERAL);
    WorkQueue unknown = queue("legacy", QueuePurpose.GENERAL);
    WorkQueue logistics = queue("Водители", QueuePurpose.LOGISTICS_DRIVER);
    List<WorkQueue> source = List.of(repair, unknown, logistics);

    assertThat(RepairRoutePhaseOrder.ordered(source, queue -> queue, "manual-ui"))
        .containsExactlyElementsOf(source);
    assertThat(RepairRoutePhaseOrder.ordered(source, queue -> queue, "maintenance-service"))
        .containsExactlyElementsOf(source);
  }

  @Test
  void globalDefinitionsKeepCanonicalPhasesBeforeCustomDefinitions() {
    QueueDefinition customFirst = definition("custom-first", 1);
    QueueDefinition plumbing = definition("сантехника", 2);
    QueueDefinition ses = definition("сэс и санитария", 3);
    QueueDefinition customSecond = definition("custom-second", 4);
    QueueDefinition welding = definition("сварка", 5);

    assertThat(
            RepairRoutePhaseOrder.orderedDefinitions(
                List.of(customFirst, plumbing, ses, customSecond, welding)))
        .containsExactly(ses, welding, plumbing, customFirst, customSecond);
  }

  private static WorkQueue queue(String name, QueuePurpose purpose) {
    QueueDefinition definition = definition(name, 0);
    definition.setPurpose(purpose);
    WorkQueue queue = new WorkQueue();
    queue.setDefinition(definition);
    return queue;
  }

  private static QueueDefinition definition(String name, int sortOrder) {
    QueueDefinition definition = new QueueDefinition();
    definition.setName(name);
    definition.setType(QueueType.REPAIR);
    definition.setPurpose(QueuePurpose.GENERAL);
    definition.setSortOrder(sortOrder);
    return definition;
  }
}
