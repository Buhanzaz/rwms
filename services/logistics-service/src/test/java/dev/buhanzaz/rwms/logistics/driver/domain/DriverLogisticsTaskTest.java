package dev.buhanzaz.rwms.logistics.driver.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    UUID reservedAllocationId = UUID.randomUUID();
    UUID occupiedAllocationId = UUID.randomUUID();

    task.registerBoardTask(
        taskBoardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    task.reserveRepairPlace(reservedAllocationId, 0);
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
    task.markRepairPlaceEffect(occupiedAllocationId, 7);
    task.complete();

    assertThat(task.getState()).isEqualTo(DriverTaskState.COMPLETED);
    assertThat(task.getCompletionMediaId()).isEqualTo(mediaId);
    assertThat(task.isCoverApplied()).isTrue();
    assertThat(task.isRepairPlaceEffectApplied()).isTrue();
    assertThat(task.getRepairPlaceAllocationId()).isEqualTo(occupiedAllocationId);
    assertThat(task.getRepairPlaceAllocationVersion()).isEqualTo(7);
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

  @Test
  void transientRetryCounterSaturatesInsteadOfOverflowing() {
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    ReflectionTestUtils.setField(task, "retryCount", Integer.MAX_VALUE);

    task.retryAfterSeconds(32, "DEPENDENCY_TRANSIENT", 5);

    assertThat(task.getState()).isEqualTo(DriverTaskState.REGISTERING);
    assertThat(task.getRetryCount()).isEqualTo(5);
    assertThat(task.getFailureCode()).isEqualTo("DEPENDENCY_TRANSIENT");
    assertThat(task.getNextAttemptAt()).isNotNull();
  }

  @Test
  void unregisteredIntentCanBeCancelledWithoutDeletingItsWorkflowCheckpoint() {
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    task.retryAfterSeconds(8, "DEPENDENCY_TRANSIENT", 5);

    task.cancelBeforeExternalRegistration();

    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    assertThat(task.getNextAttemptAt()).isNull();
    assertThat(task.getFailureCode()).isNull();
    assertThatThrownBy(task::cancelBeforeExternalRegistration)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void registeredIntentCannotBeCancelledThroughTheLocalOnlyTransition() {
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    task.registerBoardTask(
        UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "SCHEDULED", null);

    assertThatThrownBy(task::cancelBeforeExternalRegistration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unregistered");
  }

  @Test
  void guardConfirmedCancellationClearsTheReleasedRepairPlaceCheckpoint() {
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    task.registerBoardTask(
        UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    task.reserveRepairPlace(UUID.randomUUID(), 4);
    task.requireReconciliation("MAINTENANCE_COMPENSATION_RELEASE_UNKNOWN");

    task.cancelAfterPreStartCancellation();

    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.getRepairPlaceAllocationVersion()).isNull();
    assertThat(task.getFailureCode()).isNull();
    assertThat(task.getNextAttemptAt()).isNull();
  }

  @Test
  void successfulBoardTransitionsClearTransientFailureAndRestoreNormalScheduling() {
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
    LocalDate scheduledDate = LocalDate.now(ZoneOffset.UTC);
    task.registerBoardTask(taskBoardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);

    task.retryAfterSeconds(32, "DEPENDENCY_TRANSIENT", 5);
    OffsetDateTime beforeCurrentConfirmation = OffsetDateTime.now(ZoneOffset.UTC);
    task.moveToCurrent(1, entryId, "WAITING");
    OffsetDateTime afterCurrentConfirmation = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(task.getState()).isEqualTo(DriverTaskState.CURRENT);
    assertThat(task.getRetryCount()).isZero();
    assertThat(task.getFailureCode()).isNull();
    assertThat(task.getNextAttemptAt())
        .isAfter(beforeCurrentConfirmation)
        .isBeforeOrEqualTo(afterCurrentConfirmation.plusSeconds(2));

    task.retryAfterSeconds(32, "DEPENDENCY_TRANSIENT", 5);
    OffsetDateTime beforeStatusConfirmation = OffsetDateTime.now(ZoneOffset.UTC);
    task.observeBoardTask(
        taskBoardTaskId,
        2,
        entryId,
        "WAITING",
        scheduledDate,
        "CURRENT",
        "ACTIVE",
        null);
    OffsetDateTime afterStatusConfirmation = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(task.getState()).isEqualTo(DriverTaskState.CURRENT);
    assertThat(task.getRetryCount()).isZero();
    assertThat(task.getFailureCode()).isNull();
    assertThat(task.getNextAttemptAt())
        .isAfter(beforeStatusConfirmation)
        .isBeforeOrEqualTo(afterStatusConfirmation.plusSeconds(2));

    task.retryAfterSeconds(32, "DEPENDENCY_TRANSIENT", 5);
    OffsetDateTime completionAt = OffsetDateTime.now(ZoneOffset.UTC);
    task.observeBoardTask(
        taskBoardTaskId,
        3,
        entryId,
        "DONE",
        scheduledDate,
        "CURRENT",
        "DONE",
        completionAt);

    assertThat(task.getState()).isEqualTo(DriverTaskState.FINALIZING);
    assertThat(task.getRetryCount()).isZero();
    assertThat(task.getFailureCode()).isNull();
    assertThat(task.isDue(OffsetDateTime.now(ZoneOffset.UTC))).isTrue();
  }

  @Test
  void fixedDateRetainsItsOperatorLowerBoundWhenRollingQueueMovesItLater() {
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    LocalDate selectedDate = LocalDate.now(ZoneOffset.UTC).plusDays(3);
    LocalDate capacityDate = selectedDate.plusDays(2);
    task.registerBoardTask(boardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    task.markFixedDate(selectedDate);

    task.observeBoardTask(
        boardTaskId,
        1,
        entryId,
        "WAITING",
        capacityDate,
        "SCHEDULED",
        "ACTIVE",
        null);

    assertThat(task.getPlanningMode()).isEqualTo(DriverTaskPlanningMode.FIXED_DATE);
    assertThat(task.getFixedDateLowerBound()).isEqualTo(selectedDate);
    assertThat(task.getScheduledDate()).isEqualTo(capacityDate);
  }

  @Test
  void manualPromotionHoldExpiresButItsReservationReleaseMarkerRemainsDurable() {
    DriverLogisticsTask task =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    task.markManualPromotionHold(1);
    ReflectionTestUtils.setField(
        task,
        "manualPromotionHoldUntil",
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));

    assertThat(task.hasManualPromotionHold()).isTrue();
    assertThat(task.isManualPromotionHeldAt(OffsetDateTime.now(ZoneOffset.UTC))).isFalse();
  }

  @Test
  void manualGeneralMovementRequiresCommentAndNeverAllocatesRepairCapacity() {
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                DriverLogisticsTask.create(
                    warehouseId,
                    cabinId,
                    null,
                    DriverTaskSourceType.MANUAL,
                    sourceId,
                    DriverTaskKind.GENERAL_MOVEMENT,
                    DriverTaskPlanningMode.AUTO,
                    LocalDate.now(ZoneOffset.UTC),
                    3,
                    "   ",
                    "БЫТ-777",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("comment");

    assertThatThrownBy(
            () ->
                DriverLogisticsTask.create(
                    warehouseId,
                    cabinId,
                    null,
                    DriverTaskSourceType.MANUAL,
                    sourceId,
                    DriverTaskKind.GENERAL_MOVEMENT,
                    DriverTaskPlanningMode.AUTO,
                    LocalDate.now(ZoneOffset.UTC),
                    3,
                    "x".repeat(1_001),
                    "БЫТ-777",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("comment");

    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            cabinId,
            null,
            DriverTaskSourceType.MANUAL,
            sourceId,
            DriverTaskKind.GENERAL_MOVEMENT,
            DriverTaskPlanningMode.AUTO,
            LocalDate.now(ZoneOffset.UTC),
            3,
            "  Переместить к воротам  ",
            "БЫТ-777",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));

    assertThat(task.getComment()).isEqualTo("Переместить к воротам");
    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.isRepairPlaceEffectApplied()).isTrue();
  }

  @Test
  void identityFreeDeliveryIsUnassignedWhileMovementRemainsShared() {
    DriverLogisticsTask shipment =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            UUID.randomUUID(),
            DriverTaskKind.SHIPMENT);
    DriverLogisticsTask returnTask =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            UUID.randomUUID(),
            DriverTaskKind.RETURN);
    DriverLogisticsTask transfer =
        create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            UUID.randomUUID(),
            DriverTaskKind.TRANSFER);

    assertThat(shipment.getDriverAudienceMode())
        .isEqualTo(DriverTaskAudienceMode.UNASSIGNED);
    assertThat(returnTask.getDriverAudienceMode())
        .isEqualTo(DriverTaskAudienceMode.UNASSIGNED);
    assertThat(transfer.getDriverAudienceMode())
        .isEqualTo(DriverTaskAudienceMode.WAREHOUSE_DRIVERS);
    assertThat(shipment.getPlannedDriverWorkerId()).isNull();
    assertThat(returnTask.getPlannedDriverWorkerId()).isNull();
    assertThat(transfer.getPlannedDriverWorkerId()).isNull();
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
        null,
        "БЫТ-001",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
  }
}
