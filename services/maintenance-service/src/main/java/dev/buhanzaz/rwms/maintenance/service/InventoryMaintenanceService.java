package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public inventory-to-maintenance boundary for freezing immutable repair plans, publishing them
 * to repairs, and reading repair snapshots.
 *
 * <p>Command ownership remains in dedicated source workflows; this facade preserves the API
 * boundary and its read-only transaction declaration without becoming an orchestration hub.
 */
@Service
public class InventoryMaintenanceService {
  private final InventoryMaintenanceFreezeUseCases freezeUseCases;
  private final InventoryMaintenanceUpsertUseCases upsertUseCases;
  private final InventoryMaintenanceSnapshotProjection snapshotProjection;

  @Autowired
  InventoryMaintenanceService(
      InventoryMaintenanceFreezeUseCases freezeUseCases,
      InventoryMaintenanceUpsertUseCases upsertUseCases,
      InventoryMaintenanceSnapshotProjection snapshotProjection) {
    this.freezeUseCases = freezeUseCases;
    this.upsertUseCases = upsertUseCases;
    this.snapshotProjection = snapshotProjection;
  }

  /**
   * Freezes an inventory plan after any required routing preflight, retaining immutable source
   * identity and replay semantics.
   */
  public FreezeResult freeze(FreezeInventoryPlanRequest request) {
    InventoryMaintenanceFreezeResult result = freezeUseCases.freeze(request);
    return new FreezeResult(result.response(), result.replayed());
  }

  /** Returns semantic repair facts for the requested inventory assets. */
  @Transactional(readOnly = true)
  public InventoryRepairSnapshotsResponse repairSnapshots(
      InventoryRepairSnapshotRequest request) {
    return snapshotProjection.repairSnapshots(request);
  }

  /**
   * Binds a frozen plan to exactly one repair after immutable source, admission and routing checks.
   */
  public UpsertResult upsert(
      UUID inventoryId, UUID findingId, UpsertInventoryRepairRequest request) {
    InventoryMaintenanceUpsertResult result = upsertUseCases.upsert(inventoryId, findingId, request);
    return new UpsertResult(result.repairId(), result.source(), result.delivery(), result.replayed());
  }

  public record UpsertResult(
      UUID repairId,
      InventorySourceReference source,
      DeliverySnapshot delivery,
      boolean replayed) {}

  public record FreezeResult(FrozenInventoryPlanResponse response, boolean replayed) {}
}
