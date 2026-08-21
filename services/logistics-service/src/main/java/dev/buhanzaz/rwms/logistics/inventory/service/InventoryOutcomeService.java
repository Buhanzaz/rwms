package dev.buhanzaz.rwms.logistics.inventory.service;

import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeRequest;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeResponse;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryAssetOutcome;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeCommand.AssetOutcome;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeCommand.FormerRental;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeCommand.Shipment;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeCommand.ShipmentFurniture;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomePreparationStore.Preparation;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
          || outcome.dispositionKind() == null
          || outcome.desiredStatus() == null
          || !findingIds.add(outcome.findingId())
          || !assetIds.add(outcome.assetId())) {
        throw new IllegalArgumentException(
            "Inventory outcomes require unique findingId and assetId values");
      }
      validateDisposition(outcome);
    }
    List<AssetOutcome> outcomes =
        request.outcomes().stream()
            .map(
                outcome ->
                    new AssetOutcome(
                        outcome.findingId(),
                        outcome.assetId(),
                        outcome.dispositionKind().name(),
                        outcome.desiredStatus().name(),
                        canonicalFormerRental(outcome),
                        canonicalShipment(outcome)))
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

  private static void validateDisposition(InventoryAssetOutcome outcome) {
    switch (outcome.dispositionKind()) {
      case LOCAL -> {
        if (!Set.of("FREE", "REPAIR", "CAPITAL_REPAIR")
                .contains(outcome.desiredStatus().name())
            || outcome.shipment() != null) {
          throw new IllegalArgumentException("LOCAL inventory disposition is invalid");
        }
        if (outcome.formerRental() != null) {
          requireClient(
              outcome.formerRental().returnedOn(),
              outcome.formerRental().clientId(),
              outcome.formerRental().clientSnapshot(),
              "formerRental");
        }
      }
      case SHIPMENT -> {
        if (outcome.desiredStatus()
                != dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels
                    .InventoryDesiredStatus.RENTED
            || outcome.formerRental() != null
            || outcome.shipment() == null) {
          throw new IllegalArgumentException("SHIPMENT inventory disposition is invalid");
        }
        requireClient(
            outcome.shipment().departedOn(),
            outcome.shipment().clientId(),
            outcome.shipment().clientSnapshot(),
            "shipment");
        if (outcome.shipment().furniture() == null
            || outcome.shipment().furniture().size() > 100) {
          throw new IllegalArgumentException("Inventory shipment furniture is invalid");
        }
        Set<UUID> equipmentIds = new HashSet<>();
        outcome.shipment().furniture().forEach(
            furniture -> {
              if (furniture == null
                  || furniture.equipmentId() == null
                  || furniture.catalogVersion() == null
                  || furniture.catalogVersion() < 0
                  || furniture.quantity() == null
                  || furniture.quantity() < 1
                  || !equipmentIds.add(furniture.equipmentId())) {
                throw new IllegalArgumentException(
                    "Inventory shipment furniture IDs must be unique and quantities positive");
              }
            });
      }
      case WRITE_OFF -> {
        if (outcome.desiredStatus()
                != dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels
                    .InventoryDesiredStatus.WRITE_OFF_PENDING
            || outcome.formerRental() != null
            || outcome.shipment() != null) {
          throw new IllegalArgumentException("WRITE_OFF inventory disposition is invalid");
        }
      }
    }
  }

  private static FormerRental canonicalFormerRental(InventoryAssetOutcome outcome) {
    if (outcome.formerRental() == null) return null;
    return new FormerRental(
        outcome.formerRental().returnedOn(),
        outcome.formerRental().clientId(),
        normalizeSnapshot(outcome.formerRental().clientSnapshot(), "formerRental.clientSnapshot"));
  }

  private static Shipment canonicalShipment(InventoryAssetOutcome outcome) {
    if (outcome.shipment() == null) return null;
    return new Shipment(
        outcome.shipment().departedOn(),
        outcome.shipment().clientId(),
        normalizeSnapshot(outcome.shipment().clientSnapshot(), "shipment.clientSnapshot"),
        outcome.shipment().furniture().stream()
            .map(
                value ->
                    new ShipmentFurniture(
                        value.equipmentId(), value.catalogVersion(), value.quantity()))
            .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
            .toList());
  }

  private static void requireClient(
      java.time.LocalDate date, UUID clientId, String snapshot, String field) {
    if (date == null || clientId == null) {
      throw new IllegalArgumentException(field + " date and clientId are required");
    }
    normalizeSnapshot(snapshot, field + ".clientSnapshot");
  }

  private static String normalizeSnapshot(String value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > 512) {
      throw new IllegalArgumentException(field + " must contain 1 to 512 characters");
    }
    return normalized;
  }
}
