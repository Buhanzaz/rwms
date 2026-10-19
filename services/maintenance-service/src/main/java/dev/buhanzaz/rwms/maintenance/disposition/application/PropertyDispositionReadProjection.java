package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionLine;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionPlan;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionDecisionResponse;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionPage;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionRepairChainEntry;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentSnapshotLine;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.disposition.repository.PropertyDispositionDecisionRepository;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Builds immutable disposition responses and the processor's internal read projection.
 *
 * <p>The processor projection is deliberately package-local; only the facade constructs its
 * public nested compatibility records.
 */
@Service
final class PropertyDispositionReadProjection {
  private final PropertyDispositionDecisionRepository decisions;
  private final PropertyDispositionRepairChain repairChain;
  private final PropertyDispositionActorCodec actors;

  PropertyDispositionReadProjection(
      PropertyDispositionDecisionRepository decisions,
      PropertyDispositionRepairChain repairChain,
      PropertyDispositionActorCodec actors) {
    this.decisions = decisions;
    this.repairChain = repairChain;
    this.actors = actors;
  }

  PropertyDispositionDecisionResponse get(UUID decisionId, UUID warehouseId) {
    PropertyDispositionDecision decision = decisions.findByIdAndWarehouseId(decisionId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    return response(decision, repairChain.repairChain(decision.getRootRepairId()));
  }

  PropertyDispositionPage list(
      UUID warehouseId,
      PropertyDispositionKind kind,
      PropertyDispositionState state,
      int page,
      int size) {
    if (warehouseId == null || kind == null || page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Property disposition page request is invalid");
    }
    Page<PropertyDispositionDecision> result = state == null
        ? decisions.findAllByWarehouseIdAndKind(warehouseId, kind, PageRequest.of(page, size))
        : decisions.findAllByWarehouseIdAndKindAndState(
            warehouseId, kind, state, PageRequest.of(page, size));
    Map<UUID, List<MaintenanceRepair>> chains = repairChain.repairChains(result.getContent());
    List<PropertyDispositionDecisionResponse> values = result.getContent().stream()
        .map(value -> response(value, chains.getOrDefault(value.getRootRepairId(), List.of())))
        .toList();
    return new PropertyDispositionPage(values, page, size, result.getTotalElements());
  }

  ProcessingProjection processingProjection(UUID decisionId) {
    PropertyDispositionDecision decision = decisions.findById(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    List<MaintenanceRepair> chain = repairChain.repairChain(decision.getRootRepairId());
    boolean requiresAssetEffect = decision.requiresAssetEffect();
    MaintenanceDependencyGateway.PropertyDispositionLeaseProof leaseProof =
        requiresAssetEffect ? leaseProof(decision, chain) : null;
    List<MaintenanceDependencyGateway.PropertyDispositionContent> contents = decision.getContents().stream()
        .map(
            line ->
                new MaintenanceDependencyGateway.PropertyDispositionContent(
                    line.getEquipmentId(),
                    line.getExpectedBalanceVersion(),
                    line.getCurrentQuantity(),
                    line.getMoveQuantity()))
        .toList();
    MaintenanceDependencyGateway.PropertyDispositionPreparation preparation =
        requiresAssetEffect
            ? new MaintenanceDependencyGateway.PropertyDispositionPreparation(
                decision.getWarehouseId(),
                gatewayAssetKind(decision.getAssetKind()),
                decision.getAssetId(),
                gatewayDispositionKind(decision.getKind()),
                decision.getAssetKind() == PropertyDispositionAssetKind.CABIN
                    ? decision.getExpectedAssetVersion()
                    : null,
                decision.getExpectedSourceBalanceVersion(),
                decision.getQuantity(),
                decision.getMaintenanceCustodyClaimId(),
                decision.getMaintenanceCustodyVersion(),
                decision.getContentsMode() == null
                    ? null
                    : MaintenanceDependencyGateway.PropertyDispositionContentsMode.valueOf(
                        decision.getContentsMode().name()),
                contents,
                leaseProof)
            : null;
    MaintenanceDependencyGateway.PropertyEquipmentMovementCommand movement =
        decision.requiresMovement()
            ? new MaintenanceDependencyGateway.PropertyEquipmentMovementCommand(
                decision.getId(),
                decision.getWarehouseId(),
                unitNumber(decision.getAssetDisplayName()),
                Math.max(
                    15,
                    Math.multiplyExact(
                        15,
                        (int) contents.stream().filter(line -> line.moveQuantity() > 0).count())),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                contents.stream()
                    .filter(line -> line.moveQuantity() > 0)
                    .map(
                        line ->
                            new MaintenanceDependencyGateway.PropertyEquipmentMovementLine(
                                line.equipmentId(),
                                decision.getAssetId(),
                                line.expectedBalanceVersion(),
                                line.moveQuantity()))
                    .toList())
            : null;
    return new ProcessingProjection(
        decision.getId(),
        decision.getWarehouseId(),
        decision.getState(),
        decision.getMovementTaskId(),
        decision.requiresMovement(),
        requiresAssetEffect,
        preparation,
        movement,
        leaseReleaseCommand(decision, chain));
  }

  PropertyDispositionDecisionResponse response(
      PropertyDispositionDecision decision, List<MaintenanceRepair> chain) {
    CabinContentsDispositionPlan contentsPlan = decision.getContentsMode() == null
        ? null
        : new CabinContentsDispositionPlan(
            decision.getContentsMode(),
            decision.getContents().stream().map(this::contentResponse).toList());
    List<PropertyDispositionRepairChainEntry> chainResponse = chain.stream()
        .map(value -> new PropertyDispositionRepairChainEntry(value.getId(), value.getVersion()))
        .toList();
    return new PropertyDispositionDecisionResponse(
        decision.getId(),
        decision.getVersion(),
        decision.getRecoveryVersion(),
        decision.getWarehouseId(),
        decision.getAssetKind(),
        decision.getAssetId(),
        decision.getAssetDisplayName(),
        decision.getKind(),
        decision.getSource(),
        decision.getState(),
        decision.getReason(),
        decision.getEvidenceLink(),
        decision.getQuantity(),
        decision.getExpectedAssetVersion(),
        decision.getExpectedSourceBalanceVersion(),
        decision.getMaintenanceCustodyClaimId(),
        decision.getMaintenanceCustodyVersion(),
        contentsPlan,
        decision.getRootRepairId(),
        chainResponse,
        decision.getInventoryId(),
        decision.getFindingId(),
        decision.getAssetEffectState(),
        decision.getMovementTaskId(),
        decision.getFailureCode(),
        decision.getFailureDetail(),
        actors.actor(decision.getInitiatedByActorSnapshot()),
        decision.getCreatedAt(),
        actors.actorOrNull(decision.getReviewedByActorSnapshot()),
        decision.getReviewedAt(),
        decision.getReviewComment() == null
            ? decision.getRejectionReason()
            : decision.getReviewComment(),
        decision.getState() == PropertyDispositionState.EFFECTIVE ? decision.getUpdatedAt() : null,
        decision.getUpdatedAt());
  }

  PropertyDispositionDecisionResponse response(PropertyDispositionDecision decision) {
    return response(decision, repairChain.repairChain(decision.getRootRepairId()));
  }

  private MaintenanceDependencyGateway.PropertyDispositionLeaseProof leaseProof(
      PropertyDispositionDecision decision, List<MaintenanceRepair> chain) {
    if (decision.getAssetKind() != PropertyDispositionAssetKind.CABIN
        || (decision.getSource() != PropertyDispositionSource.REPAIR
            && decision.getSource() != PropertyDispositionSource.ESTIMATE)) {
      return null;
    }
    MaintenanceRepair root = chain.stream()
        .filter(value -> value.getId().equals(decision.getRootRepairId()))
        .findFirst()
        .orElseThrow(() -> conflict("Disposition root repair is missing"));
    if (root.getLeaseId() == null || root.getFencingToken() == null) {
      throw conflict("Repair-derived disposition has no active lease proof");
    }
    return new MaintenanceDependencyGateway.PropertyDispositionLeaseProof(
        root.getLeaseId(),
        root.getFencingToken(),
        root.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE",
        root.getEstimateId() == null ? root.getId() : root.getEstimateId());
  }

  private LeaseReleaseProjection leaseReleaseCommand(
      PropertyDispositionDecision decision, List<MaintenanceRepair> chain) {
    if (decision.getState() != PropertyDispositionState.EFFECTIVE
        || decision.getAssetKind() != PropertyDispositionAssetKind.CABIN
        || decision.getRootRepairId() == null) {
      return null;
    }
    MaintenanceRepair root = chain.stream()
        .filter(value -> value.getId().equals(decision.getRootRepairId()))
        .findFirst()
        .orElse(null);
    if (root == null
        || root.getLeaseId() == null
        || root.getLeaseVersion() == null
        || root.getFencingToken() == null
        || "RELEASED".equals(root.getLeaseReconciliationState())) {
      return null;
    }
    return new LeaseReleaseProjection(
        root.getLeaseId(),
        root.getLeaseVersion(),
        root.getFencingToken(),
        root.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE",
        root.getEstimateId() == null ? root.getId() : root.getEstimateId());
  }

  private CabinContentsDispositionLine contentResponse(PropertyDispositionContentSnapshotLine line) {
    return new CabinContentsDispositionLine(
        line.getEquipmentId(),
        line.getEquipmentName(),
        line.getEquipmentFormat(),
        line.getCurrentQuantity(),
        line.getMoveQuantity(),
        line.getCurrentQuantity() - line.getMoveQuantity(),
        line.getExpectedBalanceVersion());
  }

  private static MaintenanceDependencyGateway.PropertyAssetKind gatewayAssetKind(
      PropertyDispositionAssetKind value) {
    return MaintenanceDependencyGateway.PropertyAssetKind.valueOf(value.name());
  }

  private static MaintenanceDependencyGateway.PropertyDispositionKind gatewayDispositionKind(
      PropertyDispositionKind value) {
    return MaintenanceDependencyGateway.PropertyDispositionKind.valueOf(value.name());
  }

  private static String unitNumber(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Cabin display number is required for furniture movement");
    }
    String normalized = value.trim();
    if (normalized.length() > 64) {
      throw new IllegalArgumentException("Cabin display number is too long for furniture movement");
    }
    return normalized;
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }

  /** Package-local raw data from which the facade reconstructs its legacy processor record. */
  record ProcessingProjection(
      UUID decisionId,
      UUID warehouseId,
      PropertyDispositionState state,
      UUID movementTaskId,
      boolean requiresMovement,
      boolean requiresAssetEffect,
      MaintenanceDependencyGateway.PropertyDispositionPreparation preparation,
      MaintenanceDependencyGateway.PropertyEquipmentMovementCommand movementCommand,
      LeaseReleaseProjection leaseRelease) {}

  /** Internal fenced lease-release details for the processor projection. */
  record LeaseReleaseProjection(
      UUID leaseId, long expectedLeaseVersion, long fencingToken, String ownerType, UUID ownerId) {}
}
