package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.JsonNode;

/** Verifies bounded, idempotent startup scheduling of worker presentation snapshots. */
@ExtendWith(MockitoExtension.class)
class MaintenanceWorkerCoverReconciliationTest {
  @Mock MaintenanceRepairRepository repairs;
  @Mock RepairStageRepository repairStages;
  @Mock MaintenanceReconciliationStore reconciliations;
  @Mock PlatformTransactionManager transactionManager;
  @Mock TransactionStatus transactionStatus;

  private MaintenanceWorkerCoverReconciliation reconciliation;

  @BeforeEach
  void setUp() {
    lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    reconciliation =
        new MaintenanceWorkerCoverReconciliation(
            repairs, repairStages, reconciliations, transactionManager);
  }

  @Test
  void enqueuesOneStablePreStartUpdateForEligibleRepairWithoutASelectedCover() {
    UUID repairId = UUID.randomUUID();
    MaintenanceRepair repair = eligibleRepair(repairId);
    RepairStage stage = mock(RepairStage.class);
    when(stage.getState()).thenReturn(RepairStageState.QUEUED);
    when(stage.getTaskGenerationState()).thenReturn("GENERATED");
    when(stage.getExternalQueueEntryId()).thenReturn(UUID.randomUUID());
    when(stage.getTaskBoardVersion()).thenReturn(0L);
    when(repairs.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(repair)));
    when(repairs.findById(repairId)).thenReturn(Optional.of(repair));
    when(repairStages.findAllByRepairIdOrderByStageNo(repairId))
        .thenReturn(List.of(stage));

    reconciliation.enqueueWorkerPresentationSnapshots();

    ArgumentCaptor<UUID> key = ArgumentCaptor.forClass(UUID.class);
    verify(reconciliations)
        .enqueue(
            eq(repairId),
            eq("TASK_BOARD"),
            eq("REFRESH_WORKER_MEDIA"),
            key.capture(),
            eq(Map.of("repairId", repairId.toString())));
    assertThat(key.getValue())
        .isEqualTo(
            UUID.nameUUIDFromBytes(
                ("worker-presentation-v8:" + repairId)
                    .getBytes(StandardCharsets.UTF_8)));
    assertThat(key.getValue())
        .isNotEqualTo(
            UUID.nameUUIDFromBytes(
                ("worker-presentation-v5:" + repairId)
                    .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void retriesRepairLevelQuarantineWhenEveryQueuedStageMappingIsStillConfirmed() {
    UUID repairId = UUID.randomUUID();
    MaintenanceRepair repair = eligibleRepair(repairId);
    when(repair.getTaskGenerationState()).thenReturn("FAILED");
    RepairStage stage = mock(RepairStage.class);
    when(stage.getState()).thenReturn(RepairStageState.QUEUED);
    when(stage.getTaskGenerationState()).thenReturn("GENERATED");
    when(stage.getExternalQueueEntryId()).thenReturn(UUID.randomUUID());
    when(stage.getTaskBoardVersion()).thenReturn(0L);
    when(repairs.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(repair)));
    when(repairs.findById(repairId)).thenReturn(Optional.of(repair));
    when(repairStages.findAllByRepairIdOrderByStageNo(repairId))
        .thenReturn(List.of(stage));

    reconciliation.enqueueWorkerPresentationSnapshots();

    verify(reconciliations)
        .enqueue(
            eq(repairId),
            eq("TASK_BOARD"),
            eq("REFRESH_WORKER_MEDIA"),
            any(),
            eq(Map.of("repairId", repairId.toString())));
  }

  @Test
  void skipsRepairThatStartedAfterCandidateRead() {
    UUID repairId = UUID.randomUUID();
    MaintenanceRepair candidate = eligibleRepair(repairId);
    MaintenanceRepair started = mock(MaintenanceRepair.class);
    when(started.getExecutionState()).thenReturn(RepairExecutionState.IN_PROGRESS);
    when(repairs.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(candidate)));
    when(repairs.findById(repairId)).thenReturn(Optional.of(started));

    reconciliation.enqueueWorkerPresentationSnapshots();

    verify(reconciliations, never())
        .enqueue(any(), any(), any(), any(), any());
  }

  @Test
  void retainsQuarantinedStableRefreshAndContinuesStartupPass() {
    UUID quarantinedRepairId = UUID.randomUUID();
    UUID acceptedRepairId = UUID.randomUUID();
    MaintenanceRepair quarantinedRepair = eligibleRepair(quarantinedRepairId);
    MaintenanceRepair acceptedRepair = eligibleRepair(acceptedRepairId);
    RepairStage queuedStage = mock(RepairStage.class);
    when(queuedStage.getState()).thenReturn(RepairStageState.QUEUED);
    when(queuedStage.getTaskGenerationState()).thenReturn("GENERATED");
    when(queuedStage.getExternalQueueEntryId()).thenReturn(UUID.randomUUID());
    when(queuedStage.getTaskBoardVersion()).thenReturn(0L);
    when(repairs.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(quarantinedRepair, acceptedRepair)));
    when(repairs.findById(quarantinedRepairId))
        .thenReturn(Optional.of(quarantinedRepair));
    when(repairs.findById(acceptedRepairId)).thenReturn(Optional.of(acceptedRepair));
    when(repairStages.findAllByRepairIdOrderByStageNo(any()))
        .thenReturn(List.of(queuedStage));
    doThrow(
            new MaintenanceConflictException(
                "MAINTENANCE_RECONCILIATION_QUARANTINED",
                "Stable reconciliation work is quarantined and requires reviewed resume"))
        .when(reconciliations)
        .enqueue(
            eq(quarantinedRepairId),
            eq("TASK_BOARD"),
            eq("REFRESH_WORKER_MEDIA"),
            any(),
            any());

    reconciliation.enqueueWorkerPresentationSnapshots();

    verify(reconciliations)
        .enqueue(
            eq(acceptedRepairId),
            eq("TASK_BOARD"),
            eq("REFRESH_WORKER_MEDIA"),
            any(),
            eq(Map.of("repairId", acceptedRepairId.toString())));
  }

  @Test
  void presentationRefreshFailureDoesNotDegradeRepairDeliveryState() {
    assertThat(
            MaintenanceReconciliationUseCases.affectsRepairDeliveryState(
                "REFRESH_WORKER_MEDIA"))
        .isFalse();
    assertThat(MaintenanceReconciliationUseCases.affectsRepairDeliveryState("UPDATE_TASK"))
        .isTrue();
  }

  @Test
  void workerTaskTitleUsesMaintenanceOwnedComplexity() {
    assertThat(MaintenanceTaskBoardSupport.workerTaskTitle(RepairComplexity.LIGHT))
        .isEqualTo("Лёгкий ремонт");
    assertThat(MaintenanceTaskBoardSupport.workerTaskTitle(RepairComplexity.MEDIUM))
        .isEqualTo("Средний ремонт");
    assertThat(MaintenanceTaskBoardSupport.workerTaskTitle(RepairComplexity.COMPLEX))
        .isEqualTo("Тяжёлый ремонт");
    assertThat(MaintenanceTaskBoardSupport.workerTaskTitle(RepairComplexity.CAPITAL))
        .isEqualTo("Капитальный ремонт");
  }

  @Test
  void dispatchesPresentationRefreshThroughTheExistingPreStartUpdateWorkflow() {
    MaintenanceDependencyGateway dependencies = mock(MaintenanceDependencyGateway.class);
    MaintenanceReconciliationSupport support = mock(MaintenanceReconciliationSupport.class);
    MaintenanceAssetReconciliationUseCases asset =
        mock(MaintenanceAssetReconciliationUseCases.class);
    MaintenanceTaskReconciliationUseCases task =
        mock(MaintenanceTaskReconciliationUseCases.class);
    MaintenanceRepairLifecycleReconciliationUseCases lifecycle =
        mock(MaintenanceRepairLifecycleReconciliationUseCases.class);
    UUID repairId = UUID.randomUUID();
    MaintenanceReconciliationStore.WorkItem work =
        new MaintenanceReconciliationStore.WorkItem(
            UUID.randomUUID(),
            repairId,
            "TASK_BOARD",
            "REFRESH_WORKER_MEDIA",
            UUID.randomUUID(),
            "PENDING",
            0,
            OffsetDateTime.now(),
            mock(JsonNode.class),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(reconciliations.claimNextDue(any(Duration.class)))
        .thenReturn(Optional.of(work));
    MaintenanceReconciliationUseCases useCases =
        new MaintenanceReconciliationUseCases(
            reconciliations,
            dependencies,
            transactionManager,
            support,
            asset,
            task,
            lifecycle);

    assertThat(useCases.reconcileOneTask()).isTrue();

    verify(task).reconcileTaskClaim(work, true);
  }

  private static MaintenanceRepair eligibleRepair(UUID repairId) {
    MaintenanceRepair repair = mock(MaintenanceRepair.class);
    when(repair.getId()).thenReturn(repairId);
    when(repair.getExecutionState()).thenReturn(RepairExecutionState.QUEUED);
    when(repair.getTaskGenerationState()).thenReturn("GENERATED");
    when(repair.getExternalTaskId()).thenReturn(UUID.randomUUID());
    when(repair.getTaskBoardVersion()).thenReturn(0L);
    return repair;
  }
}
