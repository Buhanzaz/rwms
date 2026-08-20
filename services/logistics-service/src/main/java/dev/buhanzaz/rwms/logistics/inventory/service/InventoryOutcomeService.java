package dev.buhanzaz.rwms.logistics.inventory.service;

import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeRequest;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeResponse;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryAssetOutcome;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeCommand.AssetOutcome;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomePreparationStore.Preparation;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Application orchestrator for applying or resuming one completed-inventory logistics outcome. */
@Service
@RequiredArgsConstructor
public class InventoryOutcomeService {
  private final InventoryOutcomePreparationStore preparationStore;
  private final InventoryOutcomeTaskStore taskStore;
  private final InventoryOutcomeTaskProcessor taskProcessor;
  private final ObjectMapper json;

  /**
   * Persists the complete local decision first, performs each remote action outside transactions,
   * and freezes the response only after every action is recoverable and terminal.
   */
  public ApplyInventoryOutcomeResponse apply(
      UUID inventoryId, UUID idempotencyKey, ApplyInventoryOutcomeRequest request) {
    InventoryOutcomeCommand command = canonical(inventoryId, idempotencyKey, request);
    String fingerprint = InventoryOutcomeChecksum.sha256(command);
    Preparation preparation =
        preparationStore.prepare(
            idempotencyKey, command, fingerprint, json.valueToTree(command));
    if (preparation.completedResponse() != null) return preparation.completedResponse();
    for (UUID actionId : taskStore.pendingActionIds(preparation.receiptId())) {
      taskProcessor.process(actionId);
    }
    return preparationStore.complete(preparation.receiptId(), preparation.replay());
  }

  private static InventoryOutcomeCommand canonical(
      UUID inventoryId, UUID idempotencyKey, ApplyInventoryOutcomeRequest request) {
    if (inventoryId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException(
          "Inventory identity, request and Idempotency-Key are required");
    }
    if (request.warehouseId() == null
        || request.inventoryCompletedAt() == null
        || request.finalPlanVersion() < 1
        || request.finalPlanSha256() == null
        || !request.finalPlanSha256().matches("[0-9a-f]{64}")
        || request.outcomes() == null
        || request.outcomes().isEmpty()
        || request.outcomes().size() > 5000) {
      throw new IllegalArgumentException("Completed inventory outcome request is invalid");
    }
    Set<UUID> findingIds = new HashSet<>();
    Set<UUID> assetIds = new HashSet<>();
    for (InventoryAssetOutcome outcome : request.outcomes()) {
      if (outcome == null
          || outcome.findingId() == null
          || outcome.assetId() == null
          || outcome.desiredStatus() == null
          || !findingIds.add(outcome.findingId())
          || !assetIds.add(outcome.assetId())) {
        throw new IllegalArgumentException(
            "Inventory outcomes require unique findingId and assetId values");
      }
    }
    List<AssetOutcome> outcomes =
        request.outcomes().stream()
            .map(
                outcome ->
                    new AssetOutcome(
                        outcome.findingId(),
                        outcome.assetId(),
                        outcome.desiredStatus().name()))
            .sorted(
                Comparator.comparing((AssetOutcome outcome) -> outcome.assetId().toString())
                    .thenComparing(outcome -> outcome.findingId().toString()))
            .toList();
    return new InventoryOutcomeCommand(
        inventoryId,
        request.warehouseId(),
        request
            .inventoryCompletedAt()
            .withOffsetSameInstant(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS),
        request.finalPlanVersion(),
        request.finalPlanSha256(),
        outcomes);
  }
}
