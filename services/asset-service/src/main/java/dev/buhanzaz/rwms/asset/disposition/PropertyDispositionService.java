package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.ApplyMaintenancePropertyDispositionRequest;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.CommandResult;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyAssetSnapshot;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionEffect;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionFence;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PrepareMaintenancePropertyDispositionRequest;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Asset's private maintenance boundary for an approved property disposition.
 *
 * <p>This compatibility facade preserves the controller-facing API while each collaborator owns
 * one cohesive read, PREPARE, or APPLY lifecycle. The collaborators retain the original local
 * transaction, fencing, idempotency, and remote-call boundaries.
 */
@Service
public class PropertyDispositionService {
  private final PropertyDispositionSnapshotService snapshots;
  private final PropertyDispositionPreparationService preparations;
  private final PropertyDispositionApplicationService applications;

  public PropertyDispositionService(
      PropertyDispositionSnapshotService snapshots,
      PropertyDispositionPreparationService preparations,
      PropertyDispositionApplicationService applications) {
    this.snapshots = snapshots;
    this.preparations = preparations;
    this.applications = applications;
  }

  public MaintenancePropertyAssetSnapshot snapshot(
      PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    return snapshots.snapshot(assetKind, assetId, warehouseId);
  }

  public CommandResult<MaintenancePropertyDispositionFence> prepare(
      UUID maintenanceSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      PrepareMaintenancePropertyDispositionRequest request) {
    return preparations.prepare(maintenanceSubjectId, decisionId, transportIdempotencyKey, request);
  }

  public CommandResult<MaintenancePropertyDispositionEffect> apply(
      UUID maintenanceSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      ApplyMaintenancePropertyDispositionRequest request) {
    return applications.apply(maintenanceSubjectId, decisionId, transportIdempotencyKey, request);
  }
}
