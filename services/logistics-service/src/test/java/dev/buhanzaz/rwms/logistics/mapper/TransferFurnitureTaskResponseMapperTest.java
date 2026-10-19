package dev.buhanzaz.rwms.logistics.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureTaskStatusView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.TransferFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class TransferFurnitureTaskResponseMapperTest {
  @Test
  void mapsTheLinkAndCurrentMovementTaskIntoThePublicStatusView() {
    UUID rentalItemId = UUID.fromString("00000000-0000-0000-0000-000000009201");
    UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000009202");
    UUID externalTaskId = UUID.fromString("00000000-0000-0000-0000-000000009203");
    UUID taskBoardTaskId = UUID.fromString("00000000-0000-0000-0000-000000009204");
    TransferFurnitureMovementTask link =
        TransferFurnitureMovementTask.create(
            mock(LogisticsDocument.class), rentalItemId, "CAB-201", taskId, 3);
    EquipmentMovementTask task = mock(EquipmentMovementTask.class);
    when(task.getExternalTaskId()).thenReturn(externalTaskId);
    when(task.getTaskBoardTaskId()).thenReturn(taskBoardTaskId);
    when(task.getState()).thenReturn(EquipmentMovementTaskState.AWAITING_WORKER);

    TransferFurnitureTaskStatusView status =
        Mappers.getMapper(TransferFurnitureTaskResponseMapper.class).toStatusView(link, task);

    assertThat(status)
        .isEqualTo(
            new TransferFurnitureTaskStatusView(
                rentalItemId,
                "CAB-201",
                taskId,
                externalTaskId,
                taskBoardTaskId,
                EquipmentMovementTaskState.AWAITING_WORKER,
                3));
  }
}
