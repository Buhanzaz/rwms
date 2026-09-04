package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies idempotency-key derivation for the durable driver completion relay. */
class DriverTaskProcessorTest {
  @Test
  void outerTransactionCommitsIntentBeforeAnyCancellationIo() {
    UUID id = UUID.randomUUID();
    when(store.nextWork(id))
        .thenReturn(
            Optional.of(
                new DriverTaskWorkflowStore.ExpiryWork(
                    id, id, LocalDate.now(ZoneOffset.UTC).minusDays(1))));
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThat(processor.processUntilIdle(id)).isZero();
      org.mockito.Mockito.verifyNoInteractions(dependencies);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(false);
    }
  }

  @Test
  void directExecutionChecksTheWarehouseDayBeforeSelectingAnyWork() {
    UUID taskId = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    when(store.expirableTripWarehouse(taskId)).thenReturn(Optional.of(warehouse));
    when(dependencies.warehouseTimeZoneAt(org.mockito.ArgumentMatchers.eq(warehouse), any()))
        .thenAnswer(
            call ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    warehouse, "UTC", call.getArgument(1)));
    when(store.nextWork(taskId)).thenReturn(Optional.empty());
    processor.processUntilIdle(taskId);
    var order = org.mockito.Mockito.inOrder(store);
    order.verify(store).requestTripExpiry(taskId, LocalDate.now(ZoneOffset.UTC));
    order.verify(store).nextWork(taskId);
  }

  @Test
  void newerAuthoritativeDateIsReconciledInsteadOfCancelled() {
    UUID taskId = UUID.randomUUID();
    UUID externalId = UUID.randomUUID();
    LocalDate old = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    var current = mock(LogisticsDependencyGateway.DriverBoardTask.class);
    when(current.status()).thenReturn("ACTIVE");
    when(current.entryStatus()).thenReturn("WAITING");
    when(current.scheduledDate()).thenReturn(old.plusDays(2));
    when(store.nextWork(taskId))
        .thenReturn(
            Optional.of(new DriverTaskWorkflowStore.ExpiryWork(taskId, externalId, old)),
            Optional.empty());
    when(dependencies.readDriverTask(externalId)).thenReturn(current);
    processor.processUntilIdle(taskId);
    verify(store).confirmStatus(taskId, current);
    verify(dependencies, org.mockito.Mockito.never())
        .cancelDriverTask(any(), any(Long.class), any());
  }

  @Test
  void expiredTripIsCancelledWithTheFreshVersionAndCargoRecordedBeforeRemoteEffect() {
    UUID taskId = UUID.randomUUID();
    UUID externalId = UUID.randomUUID();
    var work =
        new DriverTaskWorkflowStore.ExpiryWork(
            taskId, externalId, LocalDate.now(ZoneOffset.UTC).minusDays(1));
    var active = mock(LogisticsDependencyGateway.DriverBoardTask.class);
    var cancelled = mock(LogisticsDependencyGateway.DriverBoardTask.class);
    when(active.status()).thenReturn("ACTIVE");
    when(active.entryStatus()).thenReturn("IN_PROGRESS");
    when(active.taskVersion()).thenReturn(12L);
    when(store.nextWork(taskId)).thenReturn(Optional.of(work), Optional.empty());
    when(dependencies.readDriverTask(externalId)).thenReturn(active);
    when(dependencies.cancelDriverTask(
            org.mockito.ArgumentMatchers.eq(externalId),
            org.mockito.ArgumentMatchers.eq(12L),
            any(String.class)))
        .thenReturn(cancelled);
    processor.processUntilIdle(taskId);
    var order = org.mockito.Mockito.inOrder(store, dependencies);
    order.verify(store).observeExpiredTripCargo(taskId, active);
    order
        .verify(dependencies)
        .cancelDriverTask(
            org.mockito.ArgumentMatchers.eq(externalId),
            org.mockito.ArgumentMatchers.eq(12L),
            any(String.class));
    order.verify(store).confirmStatus(taskId, cancelled);
  }

  @Test
  void expiredTripThatFinishedOrWasAlreadyCancelledIsOnlyReconciled() {
    for (String status : List.of("DONE", "CANCELLED")) {
      UUID taskId = UUID.randomUUID();
      UUID externalId = UUID.randomUUID();
      var current = mock(LogisticsDependencyGateway.DriverBoardTask.class);
      when(current.status()).thenReturn(status);
      when(store.nextWork(taskId))
          .thenReturn(
              Optional.of(
                  new DriverTaskWorkflowStore.ExpiryWork(
                      taskId, externalId, LocalDate.now(ZoneOffset.UTC).minusDays(1))),
              Optional.empty());
      when(dependencies.readDriverTask(externalId)).thenReturn(current);
      processor.processUntilIdle(taskId);
      verify(store).confirmStatus(taskId, current);
    }
    verify(dependencies, org.mockito.Mockito.never())
        .cancelDriverTask(any(), any(Long.class), any());
  }

  private final DriverTaskWorkflowStore store = mock(DriverTaskWorkflowStore.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final DriverTransferExecutionService transferExecution =
      mock(DriverTransferExecutionService.class);
  private final DriverTaskProcessor processor =
      new DriverTaskProcessor(store, dependencies, transferExecution);

  @Test
  void registrationForwardsTheDurableStructuredWorkerContent() {
    UUID taskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 9, 14);
    DriverTaskWorkerContent content =
        new DriverTaskWorkerContent(
            "Склад A → Склад B",
            List.of(
                new DriverTaskWorkerContent.Work(
                    UUID.randomUUID(), "Загрузить №172", 1, null, null, null)),
            List.of(),
            List.of());
    LogisticsDependencyGateway.DriverTaskAudience audience =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null);
    DriverTaskWorkflowStore.RegisterWork work =
        new DriverTaskWorkflowStore.RegisterWork(
            taskId,
            warehouseId,
            externalTaskId,
            queueDefinitionId,
            "1 бытовка",
            "Переместить бытовку между складами",
            "Перемещение",
            date,
            3,
            audience,
            content,
            null);
    LogisticsDependencyGateway.DriverBoardTask board =
        mock(LogisticsDependencyGateway.DriverBoardTask.class);
    when(store.nextWork(taskId)).thenReturn(Optional.of(work), Optional.empty());
    when(dependencies.registerDriverTask(
            warehouseId,
            externalTaskId,
            taskId,
            "Переместить бытовку между складами",
            "1 бытовка",
            "Перемещение",
            queueDefinitionId,
            date,
            3,
            audience,
            content,
            null))
        .thenReturn(board);

    assertThat(processor.processUntilIdle(taskId)).isEqualTo(1);

    verify(dependencies)
        .registerDriverTask(
            warehouseId,
            externalTaskId,
            taskId,
            "Переместить бытовку между складами",
            "1 бытовка",
            "Перемещение",
            queueDefinitionId,
            date,
            3,
            audience,
            content,
            null);
    verify(store).confirmRegistration(taskId, board);
  }

  @Test
  void groupedShipmentUsesOneDistinctMediaCommandKeyPerCabin() {
    UUID taskId = UUID.randomUUID();
    UUID firstCabinId = UUID.randomUUID();
    UUID secondCabinId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    DriverTaskWorkflowStore.CoverWork first =
        new DriverTaskWorkflowStore.CoverWork(taskId, firstCabinId, entryId, mediaId, true);
    DriverTaskWorkflowStore.CoverWork second =
        new DriverTaskWorkflowStore.CoverWork(taskId, secondCabinId, entryId, mediaId, true);
    when(store.nextWork(taskId))
        .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
    when(dependencies.setCabinCoverFromTaskEvidence(any(), any(), any(), any()))
        .thenReturn(
            coverChange(firstCabinId, entryId, mediaId), coverChange(secondCabinId, entryId, mediaId));

    assertThat(processor.processUntilIdle(taskId)).isEqualTo(2);

    ArgumentCaptor<UUID> commandKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> cabinIds = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .setCabinCoverFromTaskEvidence(commandKeys.capture(), cabinIds.capture(), any(), any());
    assertThat(cabinIds.getAllValues()).containsExactly(firstCabinId, secondCabinId);
    assertThat(commandKeys.getAllValues())
        .containsExactly(
            derivedCoverKey(taskId, firstCabinId), derivedCoverKey(taskId, secondCabinId))
        .doesNotHaveDuplicates();
  }

  @Test
  void legacyOneCabinCoverRetainsItsOriginalIdempotencyKey() {
    UUID taskId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    when(store.nextWork(taskId))
        .thenReturn(
            Optional.of(
                new DriverTaskWorkflowStore.CoverWork(
                    taskId, cabinId, entryId, mediaId, false)),
            Optional.empty());
    when(dependencies.setCabinCoverFromTaskEvidence(any(), any(), any(), any()))
        .thenReturn(coverChange(cabinId, entryId, mediaId));

    assertThat(processor.processUntilIdle(taskId)).isEqualTo(1);

    ArgumentCaptor<UUID> commandKey = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies)
        .setCabinCoverFromTaskEvidence(commandKey.capture(), any(), any(), any());
    assertThat(commandKey.getValue())
        .isEqualTo(
            UUID.nameUUIDFromBytes(
                ("driver-task:cover:" + taskId).getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void transferLifecycleWorkUsesTheLogisticsOwnedExecutor() {
    UUID taskId = UUID.randomUUID();
    DriverTaskWorkflowStore.TransferDepartureWork departure =
        new DriverTaskWorkflowStore.TransferDepartureWork(
            taskId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            3L);
    DriverTaskWorkflowStore.TransferArrivalWork arrival =
        new DriverTaskWorkflowStore.TransferArrivalWork(
            taskId,
            departure.actorId(),
            departure.documentId(),
            departure.lineId(),
            4,
            5L,
            UUID.randomUUID(),
            7,
            3);
    when(store.nextWork(taskId))
        .thenReturn(Optional.of(departure), Optional.of(arrival), Optional.empty());

    assertThat(processor.processUntilIdle(taskId)).isEqualTo(2);

    verify(transferExecution).depart(departure);
    verify(transferExecution).arrive(arrival);
  }

  @Test
  void reconciliationUsesTheAuthoritativeTaskBoardSnapshot() {
    UUID taskId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    LogisticsDependencyGateway.DriverBoardTask board =
        mock(LogisticsDependencyGateway.DriverBoardTask.class);
    when(store.recoverableReconciliationExternalTaskId(taskId))
        .thenReturn(Optional.of(externalTaskId));
    when(dependencies.readDriverTask(externalTaskId)).thenReturn(board);

    processor.reconcileFromTaskBoard(taskId);

    verify(store).confirmReconciliationStatus(taskId, board);
  }

  private static LogisticsDependencyGateway.CabinCoverChange coverChange(
      UUID cabinId, UUID entryId, UUID mediaId) {
    return new LogisticsDependencyGateway.CabinCoverChange(
        cabinId,
        UUID.randomUUID(),
        mediaId,
        1,
        entryId,
        0,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static UUID derivedCoverKey(UUID taskId, UUID cabinId) {
    return UUID.nameUUIDFromBytes(
        ("driver-task:cover:" + cabinId + ":" + taskId).getBytes(StandardCharsets.UTF_8));
  }
}
