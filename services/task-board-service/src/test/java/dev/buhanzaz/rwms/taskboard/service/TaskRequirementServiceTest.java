package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class TaskRequirementServiceTest {
  @Test
  void checklistAndManagerRestoreUseCorrectedFrozenLinksAndPreserveUnrelatedStates() {
    var tasks = mock(BoardTaskRepository.class);
    var entries = mock(QueueEntryRepository.class);
    var sources = mock(TaskSyncSourceRepository.class);
    var maintenance = mock(MaintenanceTaskRequirementsGateway.class);
    var json = JsonMapper.builder().findAndAddModules().build();
    var service = new TaskRequirementService(tasks, entries, sources, maintenance, json,
        mock(TaskBoardQueuePositionCoordinator.class), mock(TaskBoardEventSourcing.class),
        mock(TaskBoardProjectionWriter.class));
    UUID warehouseId = UUID.randomUUID(), taskId = UUID.randomUUID(), repairId = UUID.randomUUID();
    UUID version = UUID.randomUUID(), workId = UUID.randomUUID(), materialId = UUID.randomUUID();
    UUID unrelatedId = UUID.randomUUID(), completedId = UUID.randomUUID(), entryId = UUID.randomUUID();
    UUID otherMissingId = UUID.randomUUID();
    var task = mock(BoardTask.class);
    var source = mock(TaskSyncSource.class);
    when(task.getId()).thenReturn(taskId);
    when(task.getWarehouseId()).thenReturn(warehouseId);
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));
    when(sources.findById(taskId)).thenReturn(Optional.of(source));
    when(source.getSourceType()).thenReturn(TaskSourceType.MAINTENANCE_REPAIR);
    when(source.getSourceId()).thenReturn(repairId);
    when(task.getRequirements()).thenReturn(json.writeValueAsString(List.of(
        new TaskRequirement(workId, "WORK", "Установка", version, workId, 120,
            List.of(materialId, unrelatedId, completedId, otherMissingId), List.of(entryId), "MISSING"),
        new TaskRequirement(materialId, "MATERIAL", "Вешалка", version, materialId, 0,
            List.of(workId, unrelatedId, completedId), List.of(entryId), "MISSING"),
        new TaskRequirement(unrelatedId, "WORK", "Другая", version, unrelatedId, 60,
            List.of(workId, materialId), List.of(entryId), "AVAILABLE"),
        new TaskRequirement(otherMissingId, "MATERIAL", "Другой отсутствующий материал", version, otherMissingId, 0,
            List.of(workId), List.of(entryId), "MISSING"),
        new TaskRequirement(completedId, "WORK", "Готовая", version, completedId, 60,
            List.of(workId, materialId), List.of(entryId), "COMPLETED"))));
    when(maintenance.read(warehouseId, repairId)).thenReturn(new MaintenanceTaskRequirementsGateway.Requirements(
        repairId, warehouseId, List.of(
            requirement(workId, "WORK", version, entryId, List.of(materialId)),
            requirement(materialId, "MATERIAL", version, entryId, List.of(workId)),
            requirement(unrelatedId, "WORK", version, entryId, List.of()),
            requirement(completedId, "WORK", version, entryId, List.of()),
            requirement(otherMissingId, "MATERIAL", version, entryId, List.of()))));

    var result = service.get(warehouseId, taskId).items();
    assertThat(result).filteredOn(item -> item.itemId().equals(workId)).singleElement().satisfies(item -> {
      assertThat(item.state()).isEqualTo("MISSING");
      assertThat(item.linkedItemIds()).containsExactly(materialId);
    });
    assertThat(result).filteredOn(item -> item.itemId().equals(unrelatedId)).singleElement().satisfies(item -> {
      assertThat(item.state()).isEqualTo("AVAILABLE");
      assertThat(item.linkedItemIds()).isEmpty();
    });
    assertThat(result).filteredOn(item -> item.itemId().equals(completedId)).singleElement()
        .satisfies(item -> assertThat(item.state()).isEqualTo("COMPLETED"));
    verify(task, never()).recordRequirements(anyString(), anyDouble());

    service.restore(task, List.of(workId, materialId));
    var persistedJson = ArgumentCaptor.forClass(String.class);
    verify(task).recordRequirements(persistedJson.capture(), anyDouble());
    List<TaskRequirement> restored = json.readValue(persistedJson.getValue(), new TypeReference<>() {});
    assertThat(restored).filteredOn(item -> item.itemId().equals(workId) || item.itemId().equals(materialId))
        .allSatisfy(item -> assertThat(item.state()).isEqualTo("RESTORED"));
    assertThat(restored).filteredOn(item -> item.itemId().equals(otherMissingId)).singleElement().satisfies(item -> {
      assertThat(item.state()).isEqualTo("MISSING");
      assertThat(item.linkedItemIds()).isEmpty();
    });
    assertThat(restored).filteredOn(item -> item.itemId().equals(unrelatedId)).singleElement()
        .satisfies(item -> assertThat(item.state()).isEqualTo("AVAILABLE"));
    assertThat(restored).filteredOn(item -> item.itemId().equals(completedId)).singleElement().satisfies(item -> {
      assertThat(item.state()).isEqualTo("COMPLETED");
      assertThat(item.plannedWorkSeconds()).isEqualTo(60);
    });
    verify(task).restoreRequirements(true);
  }

  private MaintenanceTaskRequirementsGateway.Requirement requirement(UUID id, String kind, UUID version,
      UUID entryId, List<UUID> linked) {
    return new MaintenanceTaskRequirementsGateway.Requirement(id, kind, "Source name", version, id,
        600, linked, List.of(entryId));
  }
}
