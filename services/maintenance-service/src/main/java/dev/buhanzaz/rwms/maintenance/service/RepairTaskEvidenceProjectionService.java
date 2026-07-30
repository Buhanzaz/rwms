package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairTaskEvidence;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairTaskEvidenceRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepairTaskEvidenceProjectionService {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository stages;
  private final RepairTaskEvidenceRepository evidence;

  public RepairTaskEvidenceProjectionService(
      MaintenanceRepairRepository repairs,
      RepairStageRepository stages,
      RepairTaskEvidenceRepository evidence) {
    this.repairs = repairs;
    this.stages = stages;
    this.evidence = evidence;
  }

  @Transactional
  public void apply(
      UUID evidenceId,
      long aggregateVersion,
      UUID repairId,
      UUID entryId,
      UUID taskId,
      int routeIndex,
      UUID warehouseId,
      UUID workerId,
      UUID workerGroupId,
      UUID mediaId,
      long mediaGeneration,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      String state) {
    MaintenanceRepair repair =
        repairs
            .findById(repairId)
            .orElseThrow(() -> new IllegalStateException("Evidence repair is missing"));
    if (!repair.getWarehouseId().equals(warehouseId)) {
      throw new IllegalStateException("Evidence warehouse does not match its repair");
    }
    RepairStage stage =
        stages
            .findByRepairIdAndStageNo(repairId, routeIndex)
            .orElseThrow(() -> new IllegalStateException("Evidence repair stage is missing"));
    if (stage.getExternalQueueEntryId() != null
        && !stage.getExternalQueueEntryId().equals(entryId)) {
      throw new IllegalStateException("Evidence queue entry does not match its repair stage");
    }
    RepairTaskEvidence value = evidence.findById(evidenceId).orElse(null);
    if (value == null) {
      value =
          RepairTaskEvidence.create(
              evidenceId,
              aggregateVersion,
              repairId,
              stage.getId(),
              entryId,
              taskId,
              routeIndex,
              workerId,
              workerGroupId,
              mediaId,
              mediaGeneration,
              capturedAt,
              recordedAt,
              state);
    } else {
      value.requireIdentity(
          repairId, stage.getId(), entryId, taskId, routeIndex, workerId, workerGroupId);
      value.apply(
          aggregateVersion,
          mediaId,
          mediaGeneration,
          capturedAt,
          recordedAt,
          state);
    }
    evidence.save(value);
  }
}
