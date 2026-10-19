package dev.buhanzaz.rwms.maintenance.disposition.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService.ProcessingView;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PropertyDispositionProcessorTest {
  private final PropertyDispositionProcessingStore processing =
      mock(PropertyDispositionProcessingStore.class);
  private final PropertyDispositionApplicationService dispositions =
      mock(PropertyDispositionApplicationService.class);
  private final MaintenanceDependencyGateway dependencies = mock(MaintenanceDependencyGateway.class);
  private final PropertyDispositionProcessor processor =
      new PropertyDispositionProcessor(processing, dispositions, dependencies);

  @Test
  void mismatchedAssetPrepareFenceQuarantinesBeforeAnyLocalStateTransition() {
    UUID decisionId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    PropertyDispositionProcessingStore.Claim claim =
        new PropertyDispositionProcessingStore.Claim(decisionId, UUID.randomUUID());
    MaintenanceDependencyGateway.PropertyDispositionPreparation preparation =
        new MaintenanceDependencyGateway.PropertyDispositionPreparation(
            warehouseId,
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            cabinId,
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            7L,
            null,
            null,
            null,
            List.of(),
            null);
    ProcessingView view = new ProcessingView(
        decisionId,
        warehouseId,
        PropertyDispositionState.APPROVED,
        null,
        false,
        true,
        preparation,
        null,
        null);
    when(processing.claimOne(any(), any())).thenReturn(Optional.of(claim));
    when(dispositions.processingView(decisionId)).thenReturn(view);
    when(dependencies.preparePropertyDisposition(any(), eq(decisionId), eq(preparation)))
        .thenReturn(new MaintenanceDependencyGateway.PropertyDispositionFence(
            decisionId,
            "PREPARED",
            "a".repeat(64),
            warehouseId,
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            UUID.randomUUID(),
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            List.of(),
            Instant.parse("2026-08-05T00:00:00Z"),
            null));

    processor.processOne();

    verify(dispositions)
        .quarantine(
            eq(decisionId),
            eq("PREPARE_FENCE_MISMATCH"),
            eq("Asset prepare response does not match the approved property disposition"));
    verify(processing)
        .quarantined(
            eq(claim),
            eq("PREPARE"),
            eq("PREPARE_FENCE_MISMATCH"),
            eq("Asset prepare response does not match the approved property disposition"));
    verify(dispositions, never()).startAssetEffect(any());
    verify(dispositions, never()).startMovement(any(), any());
  }

  @Test
  void foreignMovementTaskCannotAdvanceToAssetEffect() {
    UUID decisionId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    PropertyDispositionProcessingStore.Claim claim =
        new PropertyDispositionProcessingStore.Claim(decisionId, UUID.randomUUID());
    MaintenanceDependencyGateway.PropertyDispositionPreparation preparation =
        new MaintenanceDependencyGateway.PropertyDispositionPreparation(
            warehouseId,
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            cabinId,
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            7L,
            null,
            null,
            null,
            List.of(),
            null);
    ProcessingView view = new ProcessingView(
        decisionId,
        warehouseId,
        PropertyDispositionState.MOVEMENT_PENDING,
        taskId,
        true,
        true,
        preparation,
        null,
        null);
    when(processing.claimOne(any(), any())).thenReturn(Optional.of(claim));
    when(dispositions.processingView(decisionId)).thenReturn(view);
    when(dependencies.getPropertyEquipmentMovementTask(taskId))
        .thenReturn(new MaintenanceDependencyGateway.PropertyEquipmentMovementTask(
            taskId,
            warehouseId,
            "MAINTENANCE_DISPOSITION",
            UUID.randomUUID(),
            "COMPLETED",
            "COMPLETED",
            java.time.OffsetDateTime.parse("2026-08-05T00:00:00Z")));

    processor.processOne();

    verify(dispositions)
        .quarantine(
            eq(decisionId),
            eq("MOVEMENT_TASK_OWNER_MISMATCH"),
            eq("Furniture movement task belongs to another disposition"));
    verify(processing)
        .quarantined(
            eq(claim),
            eq("MOVEMENT"),
            eq("MOVEMENT_TASK_OWNER_MISMATCH"),
            eq("Furniture movement task belongs to another disposition"));
    verify(dispositions, never()).completeMovement(any());
  }

  @Test
  void fourthTransientDependencyFailureQuarantinesForReviewedRecovery() {
    UUID decisionId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    PropertyDispositionProcessingStore.Claim claim =
        new PropertyDispositionProcessingStore.Claim(decisionId, UUID.randomUUID(), 3);
    MaintenanceDependencyGateway.PropertyDispositionPreparation preparation =
        new MaintenanceDependencyGateway.PropertyDispositionPreparation(
            warehouseId,
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            cabinId,
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            7L,
            null,
            null,
            null,
            List.of(),
            null);
    ProcessingView view = new ProcessingView(
        decisionId,
        warehouseId,
        PropertyDispositionState.APPROVED,
        null,
        false,
        true,
        preparation,
        null,
        null);
    when(processing.claimOne(any(), any())).thenReturn(Optional.of(claim));
    when(dispositions.processingView(decisionId)).thenReturn(view);
    when(dependencies.preparePropertyDisposition(any(), eq(decisionId), eq(preparation)))
        .thenThrow(new MaintenanceDependencyException(HttpStatus.SERVICE_UNAVAILABLE, "offline"));

    processor.processOne();

    verify(dispositions).quarantine(decisionId, "DEPENDENCY_503", "offline");
    verify(processing).quarantined(claim, "PREPARE", "DEPENDENCY_503", "offline");
    verify(processing, never()).retryableFailure(any(), any(), any(), any(), any());
  }

  @Test
  void exhaustedLeaseReleaseKeepsAppliedDecisionEffectiveAndQuarantinesOnlyReconciliation() {
    UUID decisionId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    PropertyDispositionProcessingStore.Claim claim =
        new PropertyDispositionProcessingStore.Claim(decisionId, UUID.randomUUID(), 3);
    MaintenanceDependencyGateway.PropertyDispositionPreparation preparation =
        new MaintenanceDependencyGateway.PropertyDispositionPreparation(
            warehouseId,
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            cabinId,
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            7L,
            null,
            null,
            null,
            List.of(),
            null);
    PropertyDispositionApplicationService.LeaseReleaseCommand release =
        new PropertyDispositionApplicationService.LeaseReleaseCommand(
            leaseId, 5, 17, "MAINTENANCE_REPAIR", repairId);
    ProcessingView view = new ProcessingView(
        decisionId,
        warehouseId,
        PropertyDispositionState.EFFECTIVE,
        null,
        false,
        true,
        preparation,
        null,
        release);
    when(processing.claimOne(any(), any())).thenReturn(Optional.of(claim));
    when(dispositions.processingView(decisionId)).thenReturn(view);
    org.mockito.Mockito.doThrow(
            new MaintenanceDependencyException(HttpStatus.SERVICE_UNAVAILABLE, "offline"))
        .when(dependencies)
        .releaseLease(
            any(),
            eq(leaseId),
            eq(5L),
            eq(17L),
            eq("MAINTENANCE_REPAIR"),
            eq(repairId.toString()));

    processor.processOne();

    verify(dispositions, never()).quarantine(any(), any(), any());
    verify(processing).quarantined(claim, "RELEASE_LEASE", "DEPENDENCY_503", "offline");
    verify(processing, never()).retryableFailure(any(), any(), any(), any(), any());
  }

  @Test
  void approvedUnaccountedFurnitureBecomesEffectiveWithoutAnAssetServiceEffect() {
    UUID decisionId = UUID.randomUUID();
    PropertyDispositionProcessingStore.Claim claim =
        new PropertyDispositionProcessingStore.Claim(decisionId, UUID.randomUUID());
    ProcessingView view = new ProcessingView(
        decisionId,
        UUID.randomUUID(),
        PropertyDispositionState.APPROVED,
        null,
        false,
        false,
        null,
        null,
        null);
    when(processing.claimOne(any(), any())).thenReturn(Optional.of(claim));
    when(dispositions.processingView(decisionId)).thenReturn(view);

    processor.processOne();

    verify(dispositions).markEffectiveWithoutAssetEffect(decisionId);
    verify(processing).retrySoon(claim, "PREPARE");
    verifyNoInteractions(dependencies);
    verify(dispositions, never()).startAssetEffect(any());
    verify(dispositions, never()).startMovement(any(), any());
  }

  @Test
  void fourthLocalReadFailureQuarantinesTheRecoverableProcessingClaim() {
    UUID decisionId = UUID.randomUUID();
    PropertyDispositionProcessingStore.Claim claim =
        new PropertyDispositionProcessingStore.Claim(decisionId, UUID.randomUUID(), 3);
    when(processing.claimOne(any(), any())).thenReturn(Optional.of(claim));
    when(dispositions.processingView(decisionId))
        .thenThrow(new IllegalStateException("stream temporarily unavailable"));

    processor.processOne();

    verify(processing)
        .quarantined(
            claim,
            "READ",
            "LOCAL_OR_UNKNOWN",
            "stream temporarily unavailable");
    verify(processing, never()).retryableFailure(any(), any(), any(), any(), any());
    verify(dispositions, never()).quarantine(any(), any(), any());
  }
}
