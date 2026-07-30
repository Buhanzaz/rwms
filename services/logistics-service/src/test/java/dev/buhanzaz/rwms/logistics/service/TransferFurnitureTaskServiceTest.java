package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReadinessState;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureTaskStatusView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.TransferFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.mapper.TransferFurnitureTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.TransferFurnitureMovementTaskRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class TransferFurnitureTaskServiceTest {
  private static final UUID TRANSFER_ID = UUID.fromString("00000000-0000-0000-0000-000000009101");

  @Test
  void reportsNotRequiredWhenNoFurnitureTasksAreLinked() {
    Fixture fixture = fixture();

    var readiness = fixture.service().readiness(TRANSFER_ID);

    assertThat(readiness.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(readiness.transferVersion()).isEqualTo(7);
    assertThat(readiness.state()).isEqualTo(TransferFurnitureReadinessState.NOT_REQUIRED);
    assertThat(readiness.tasks()).isEmpty();
    verifyNoInteractions(fixture.movementTasks());
  }

  @Test
  void reportsReadyWhenEveryLinkedFurnitureTaskIsCompletedAndMapsTaskDetails() {
    Fixture fixture = fixture(EquipmentMovementTaskState.COMPLETED, EquipmentMovementTaskState.COMPLETED);

    var readiness = fixture.service().readiness(TRANSFER_ID);

    assertThat(readiness.state()).isEqualTo(TransferFurnitureReadinessState.READY);
    assertThat(readiness.tasks())
        .extracting(TransferFurnitureTaskStatusView::taskState)
        .containsExactly(EquipmentMovementTaskState.COMPLETED, EquipmentMovementTaskState.COMPLETED);
    assertThat(readiness.tasks())
        .allSatisfy(
            task -> {
              assertThat(task.rentalItemId()).isNotNull();
              assertThat(task.unitNumber()).isNotBlank();
              assertThat(task.taskId()).isNotNull();
              assertThat(task.externalTaskId()).isNotNull();
              assertThat(task.lineCount()).isPositive();
            });
    assertThat(readiness.tasks().getFirst().taskBoardTaskId()).isNull();
    assertThat(readiness.tasks().get(1).taskBoardTaskId()).isNotNull();
  }

  @Test
  void reportsAwaitingCompletionWhenAnyLinkedTaskIsNonTerminal() {
    Fixture fixture = fixture(EquipmentMovementTaskState.COMPLETED, EquipmentMovementTaskState.EXECUTING);

    assertThat(fixture.service().readiness(TRANSFER_ID).state())
        .isEqualTo(TransferFurnitureReadinessState.AWAITING_TASK_COMPLETION);
  }

  @Test
  void reportsBlockedForEveryTerminalTaskStateOtherThanCompleted() {
    for (EquipmentMovementTaskState state :
        List.of(
            EquipmentMovementTaskState.CANCELLED,
            EquipmentMovementTaskState.EXPIRED,
            EquipmentMovementTaskState.CONFLICT,
            EquipmentMovementTaskState.RECONCILIATION_REQUIRED)) {
      Fixture fixture = fixture(state);

      assertThat(fixture.service().readiness(TRANSFER_ID).state())
          .as("state %s", state)
          .isEqualTo(TransferFurnitureReadinessState.BLOCKED);
    }
  }

  @Test
  void givesBlockedPrecedenceOverCompletedAndAwaitingTasks() {
    Fixture fixture =
        fixture(
            EquipmentMovementTaskState.COMPLETED,
            EquipmentMovementTaskState.AWAITING_WORKER,
            EquipmentMovementTaskState.CANCELLED);

    var readiness = fixture.service().readiness(TRANSFER_ID);

    assertThat(readiness.state()).isEqualTo(TransferFurnitureReadinessState.BLOCKED);
    assertThat(readiness.tasks())
        .extracting(TransferFurnitureTaskStatusView::taskState)
        .containsExactly(
            EquipmentMovementTaskState.COMPLETED,
            EquipmentMovementTaskState.AWAITING_WORKER,
            EquipmentMovementTaskState.CANCELLED);
  }

  private static Fixture fixture(EquipmentMovementTaskState... states) {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    TransferFurnitureMovementTaskRepository links = mock(TransferFurnitureMovementTaskRepository.class);
    CabinFurnitureTaskService cabinFurnitureTasks = mock(CabinFurnitureTaskService.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    LogisticsDocument transfer = mock(LogisticsDocument.class);
    when(transfer.getId()).thenReturn(TRANSFER_ID);
    when(transfer.getVersion()).thenReturn(7L);
    when(documents.findByIdAndDocumentType(TRANSFER_ID, LogisticsDocumentType.TRANSFER))
        .thenReturn(Optional.of(transfer));

    List<TransferFurnitureMovementTask> taskLinks = new ArrayList<>();
    for (int index = 0; index < states.length; index++) {
      int number = index + 1;
      UUID rentalItemId = UUID.nameUUIDFromBytes(("rental-item-" + number).getBytes());
      UUID taskId = UUID.nameUUIDFromBytes(("task-" + number).getBytes());
      taskLinks.add(
          TransferFurnitureMovementTask.create(
              transfer, rentalItemId, "CAB-" + number, taskId, number));
      EquipmentMovementTask task = mock(EquipmentMovementTask.class);
      when(task.getState()).thenReturn(states[index]);
      when(task.getExternalTaskId())
          .thenReturn(UUID.nameUUIDFromBytes(("external-task-" + number).getBytes()));
      when(task.getTaskBoardTaskId())
          .thenReturn(number == 1 ? null : UUID.nameUUIDFromBytes(("board-task-" + number).getBytes()));
      when(movementTasks.required(taskId)).thenReturn(task);
    }
    when(links.findAllByDocument_IdOrderByUnitNumberAsc(TRANSFER_ID)).thenReturn(taskLinks);

    return new Fixture(
        new TransferFurnitureTaskService(
            documents,
            links,
            cabinFurnitureTasks,
            movementTasks,
            Mappers.getMapper(TransferFurnitureTaskResponseMapper.class)),
        movementTasks);
  }

  private record Fixture(
      TransferFurnitureTaskService service, EquipmentMovementTaskService movementTasks) {}
}
