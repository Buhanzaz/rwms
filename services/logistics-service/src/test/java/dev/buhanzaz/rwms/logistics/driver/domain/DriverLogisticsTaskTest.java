package dev.buhanzaz.rwms.logistics.driver.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DriverLogisticsTaskTest {
  @Test
  void repairDeliveryCompletesOnlyAfterPhotoCoverAndFactualOccupation() {
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task =
        create(
            warehouseId,
            cabinId,
            repairId,
            DriverTaskSourceType.REPAIR,
            repairId,
            DriverTaskKind.DELIVER_TO_REPAIR);
    UUID taskBoardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID allocationId = UUID.randomUUID();

    task.registerBoardTask(
        taskBoardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    task.reserveRepairPlace(allocationId, 0);
    task.moveToCurrent(1, entryId, "WAITING");
    task.observeBoardTask(
        taskBoardTaskId,
        2,
        entryId,
        "DONE",
        LocalDate.now(ZoneOffset.UTC),
        "CURRENT",
        "DONE",
        OffsetDateTime.now(ZoneOffset.UTC));

    assertThat(task.getState()).isEqualTo(DriverTaskState.FINALIZING);
    assertThatThrownBy(task::complete).isInstanceOf(IllegalStateException.class);

    UUID evidenceId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    task.captureEvidence(evidenceId, mediaId, 1, entryId);
    task.markCoverApplied();
    task.markRepairPlaceEffect(allocationId, 1);
    task.complete();

    assertThat(task.getState()).isEqualTo(DriverTaskState.COMPLETED);
    assertThat(task.getCompletionMediaId()).isEqualTo(mediaId);
    assertThat(task.isCoverApplied()).isTrue();
    assertThat(task.isRepairPlaceEffectApplied()).isTrue();
  }

  @Test
  void capitalMovementNeverConsumesAnOrdinaryRepairPlace() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            repairId,
            DriverTaskSourceType.CAPITAL_REPAIR,
            repairId,
            DriverTaskKind.CAPITAL_TO_PRODUCTION);
    UUID entryId = UUID.randomUUID();
    task.registerBoardTask(
        UUID.randomUUID(),
        0,
        entryId,
        "DONE",
        "CURRENT",
        OffsetDateTime.now(ZoneOffset.UTC));
    task.captureEvidence(UUID.randomUUID(), UUID.randomUUID(), 1, entryId);
    task.markCoverApplied();
    task.complete();

    assertThat(task.getState()).isEqualTo(DriverTaskState.COMPLETED);
    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.isRepairPlaceEffectApplied()).isTrue();
  }

  @Test
  void taskBoardRescheduleUpdatesTheSinglePlanningDate() {
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    UUID taskBoardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    task.registerBoardTask(
        taskBoardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    LocalDate movedDate = LocalDate.now(ZoneOffset.UTC).plusDays(3);

    task.observeBoardTask(
        taskBoardTaskId,
        1,
        entryId,
        "WAITING",
        movedDate,
        "SCHEDULED",
        "ACTIVE",
        null);

    assertThat(task.getScheduledDate()).isEqualTo(movedDate);
    assertThat(task.getState()).isEqualTo(DriverTaskState.SCHEDULED);
  }

  private static DriverLogisticsTask create(
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      DriverTaskSourceType sourceType,
      UUID sourceId,
      DriverTaskKind kind) {
    return DriverLogisticsTask.create(
        warehouseId,
        cabinId,
        repairId,
        sourceType,
        sourceId,
        kind,
        DriverTaskPlanningMode.AUTO,
        LocalDate.now(ZoneOffset.UTC),
        3,
        "БЫТ-001",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
  }
}
