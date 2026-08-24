package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairTaskEvidence;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairTaskEvidenceRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RepairTaskEvidenceProjectionServiceTest {
  private final MaintenanceRepairRepository repairs = mock(MaintenanceRepairRepository.class);
  private final RepairStageRepository stages = mock(RepairStageRepository.class);
  private final RepairTaskEvidenceRepository evidence =
      mock(RepairTaskEvidenceRepository.class);
  private RepairTaskEvidenceProjectionService service;

  @BeforeEach
  void setUp() {
    service = new RepairTaskEvidenceProjectionService(repairs, stages, evidence);
  }

  @Test
  void projectsServerAttributedEvidenceOntoTheExactRepairStage() {
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID stageId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-07-26T10:00:00Z");
    OffsetDateTime recordedAt = OffsetDateTime.parse("2026-07-26T10:00:01Z");
    MaintenanceRepair repair = mock(MaintenanceRepair.class);
    when(repair.getWarehouseId()).thenReturn(warehouseId);
    RepairStage stage =
        new RepairStage(
            stageId,
            repairId,
            0,
            RepairStageKind.REPAIR_WORK,
            UUID.randomUUID(),
            "ELECTRIC",
            "REPAIR",
            null);
    stage.confirmTaskBoardRegistration(entryId, 1);
    when(repairs.findById(repairId)).thenReturn(Optional.of(repair));
    when(stages.findByRepairIdAndStageNo(repairId, 0)).thenReturn(Optional.of(stage));
    when(evidence.findById(evidenceId)).thenReturn(Optional.empty());
    when(evidence.save(any(RepairTaskEvidence.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.apply(
        evidenceId,
        1,
        repairId,
        entryId,
        taskId,
        0,
        warehouseId,
        workerId,
        groupId,
        mediaId,
        2,
        capturedAt,
        recordedAt,
        "READY");

    ArgumentCaptor<RepairTaskEvidence> saved =
        ArgumentCaptor.forClass(RepairTaskEvidence.class);
    verify(evidence).save(saved.capture());
    assertThat(saved.getValue().getRepairStageId()).isEqualTo(stageId);
    assertThat(saved.getValue().getTaskId()).isEqualTo(taskId);
    assertThat(saved.getValue().getRouteIndex()).isZero();
    assertThat(saved.getValue().getWorkerId()).isEqualTo(workerId);
    assertThat(saved.getValue().getWorkerGroupId()).isEqualTo(groupId);
    assertThat(saved.getValue().getMediaId()).isEqualTo(mediaId);
    assertThat(saved.getValue().getMediaGeneration()).isEqualTo(2);
    assertThat(saved.getValue().getEvidenceState()).isEqualTo("READY");
  }

  @Test
  void rejectsEvidenceWhoseEntryDoesNotBelongToTheStage() {
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    MaintenanceRepair repair = mock(MaintenanceRepair.class);
    when(repair.getWarehouseId()).thenReturn(warehouseId);
    RepairStage stage =
        new RepairStage(
            UUID.randomUUID(),
            repairId,
            0,
            RepairStageKind.REPAIR_WORK,
            UUID.randomUUID(),
            "REPAIR",
            "REPAIR",
            null);
    stage.confirmTaskBoardRegistration(UUID.randomUUID(), 1);
    when(repairs.findById(repairId)).thenReturn(Optional.of(repair));
    when(stages.findByRepairIdAndStageNo(repairId, 0)).thenReturn(Optional.of(stage));

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    1,
                    repairId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    warehouseId,
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    1,
                    OffsetDateTime.parse("2026-07-26T10:00:00Z"),
                    OffsetDateTime.parse("2026-07-26T10:00:01Z"),
                    "READY"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("queue entry");
  }

  @Test
  void advancesRecoveredOriginToThePreservedOriginalFact() {
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-08-22T10:00:00Z");
    OffsetDateTime recordedAt = capturedAt.plusSeconds(1);
    MaintenanceRepair repair = mock(MaintenanceRepair.class);
    when(repair.getWarehouseId()).thenReturn(warehouseId);
    RepairStage stage =
        new RepairStage(
            UUID.randomUUID(),
            repairId,
            0,
            RepairStageKind.REPAIR_WORK,
            UUID.randomUUID(),
            "INTERIOR",
            "REPAIR",
            null);
    stage.confirmTaskBoardRegistration(entryId, 1);
    when(repairs.findById(repairId)).thenReturn(Optional.of(repair));
    when(stages.findByRepairIdAndStageNo(repairId, 0)).thenReturn(Optional.of(stage));
    AtomicReference<RepairTaskEvidence> projected = new AtomicReference<>();
    when(evidence.findById(evidenceId))
        .thenAnswer(ignored -> Optional.ofNullable(projected.get()));
    when(evidence.save(any(RepairTaskEvidence.class)))
        .thenAnswer(
            invocation -> {
              RepairTaskEvidence value = invocation.getArgument(0);
              projected.set(value);
              return value;
            });

    service.apply(
        evidenceId,
        0,
        repairId,
        entryId,
        taskId,
        0,
        warehouseId,
        workerId,
        null,
        mediaId,
        1,
        capturedAt,
        recordedAt,
        "READY");
    service.apply(
        evidenceId,
        1,
        repairId,
        entryId,
        taskId,
        0,
        warehouseId,
        workerId,
        null,
        mediaId,
        1,
        capturedAt,
        recordedAt,
        "READY");

    assertThat(projected.get().getAggregateVersion()).isOne();
    assertThat(projected.get().getEvidenceId()).isEqualTo(evidenceId);
    assertThat(projected.get().getMediaId()).isEqualTo(mediaId);
  }
}
