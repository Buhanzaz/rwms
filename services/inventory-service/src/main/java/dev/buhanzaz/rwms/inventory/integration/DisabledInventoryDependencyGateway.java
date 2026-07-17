package dev.buhanzaz.rwms.inventory.integration;

import dev.buhanzaz.rwms.inventory.service.InventoryException;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class DisabledInventoryDependencyGateway implements InventoryDependencyGateway {
  private InventoryException unavailable() {
    return InventoryException.dependency("Inventory dependencies are disabled");
  }

  @Override
  public WarehouseMetadata warehouse(UUID warehouseId) {
    throw unavailable();
  }

  @Override
  public Capture createCapture(UUID key, CaptureRequest request) {
    throw unavailable();
  }

  @Override
  public CapturePage readCapture(UUID id, String cursor, int size) {
    throw unavailable();
  }

  @Override
  public void releaseCapture(UUID id) {
    throw unavailable();
  }

  @Override
  public NumberResolution resolveNumber(String number) {
    throw unavailable();
  }

  @Override
  public SourceAsset createSourceAsset(UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public Validation validateAssets(List<UUID> assetIds) {
    throw unavailable();
  }

  @Override
  public FrozenPlan freezePlan(UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public RepairUpsert upsertRepair(UUID inventoryId, UUID findingId, UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public boolean productionReady() {
    return false;
  }
}
