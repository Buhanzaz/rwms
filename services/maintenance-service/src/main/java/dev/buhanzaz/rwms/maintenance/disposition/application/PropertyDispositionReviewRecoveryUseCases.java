package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.ApprovePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionDecisionResponse;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RecoverPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RejectPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.disposition.repository.PropertyDispositionDecisionRepository;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import dev.buhanzaz.rwms.maintenance.service.WarehouseLifecycleOperations;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Executes human review and explicit recovery of a property disposition decision.
 *
 * <p>Warehouse admission and remote revalidation stay before the short transaction that locks
 * the decision stream and writes the durable review or recovery result.
 */
@Service
final class PropertyDispositionReviewRecoveryUseCases {
  private final PropertyDispositionDecisionRepository decisions;
  private final PropertyDispositionCommandBoundary commands;
  private final PropertyDispositionDecisionPersistence persistence;
  private final PropertyDispositionReadProjection readProjection;
  private final PropertyDispositionActorCodec actors;
  private final PropertyDispositionProcessingStore processing;
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseLifecycleOperations warehouseLifecycle;

  PropertyDispositionReviewRecoveryUseCases(
      PropertyDispositionDecisionRepository decisions,
      PropertyDispositionCommandBoundary commands,
      PropertyDispositionDecisionPersistence persistence,
      PropertyDispositionReadProjection readProjection,
      PropertyDispositionActorCodec actors,
      PropertyDispositionProcessingStore processing,
      MaintenanceDependencyGateway dependencies,
      WarehouseLifecycleOperations warehouseLifecycle) {
    this.decisions = decisions;
    this.commands = commands;
    this.persistence = persistence;
    this.readProjection = readProjection;
    this.actors = actors;
    this.processing = processing;
    this.dependencies = dependencies;
    this.warehouseLifecycle = warehouseLifecycle;
  }

  PropertyDispositionDecisionResponse approve(
      UUID decisionId, UUID warehouseId, ApprovePropertyDispositionRequest request) {
    require(request, "Property disposition approval request is required");
    ReviewPreflight preflight = reviewPreflight(decisionId, warehouseId);
    // Admission is a remote warehouse-service read. It must finish before the final local
    // transaction acquires the disposition stream and decision row locks.
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    return commands.requiredResult(
        commands.execute(() -> approveInTransaction(decisionId, warehouseId, request, preflight)));
  }

  PropertyDispositionDecisionResponse reject(
      UUID decisionId, UUID warehouseId, RejectPropertyDispositionRequest request) {
    require(request, "Property disposition rejection request is required");
    ReviewPreflight preflight = reviewPreflight(decisionId, warehouseId);
    // Keep the warehouse admission out of the transaction which locks the decision aggregate.
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    return commands.requiredResult(
        commands.execute(() -> rejectInTransaction(decisionId, warehouseId, request, preflight)));
  }

  PropertyDispositionDecisionResponse recover(
      UUID decisionId, UUID warehouseId, RecoverPropertyDispositionRequest request) {
    require(request, "Property disposition recovery request is required");
    PropertyDispositionDecision observed = decisions.findByIdAndWarehouseId(decisionId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    boolean processingQuarantined = processing.isQuarantined(decisionId);
    if (request.expectedVersion() == null || request.expectedVersion() != observed.getVersion()
        || (observed.getState() != PropertyDispositionState.QUARANTINED
            && !(processingQuarantined && isProcessingState(observed.getState())))) {
      throw conflict("Property disposition recovery is stale or not quarantined");
    }
    warehouseLifecycle.requireOutgoing(observed.getWarehouseId());
    // Human recovery is deliberately not a blind retry: re-read the owning asset and, where
    // applicable, the logistics task before the local recovery transition is committed.
    dependencies.getPropertyAssetSnapshot(
        gatewayAssetKind(observed.getAssetKind()), observed.getAssetId(), observed.getWarehouseId());
    if (observed.getState() == PropertyDispositionState.MOVEMENT_PENDING
        || observed.getQuarantineResumeState() == PropertyDispositionState.MOVEMENT_PENDING) {
      if (observed.getMovementTaskId() == null) {
        throw conflict("Quarantined movement decision has no logistics task identity");
      }
      dependencies.getPropertyEquipmentMovementTask(observed.getMovementTaskId());
    }
    return commands.requiredResult(
        commands.execute(() -> recoverInTransaction(decisionId, warehouseId, request)));
  }

  private PropertyDispositionDecisionResponse approveInTransaction(
      UUID decisionId,
      UUID warehouseId,
      ApprovePropertyDispositionRequest request,
      ReviewPreflight preflight) {
    PropertyDispositionDecision decision = persistence.locked(decisionId, warehouseId);
    requireSameReviewPreflight(decision, preflight);
    if (!decision.approve(request.expectedVersion(), actors.actorJson(), request.comment())) {
      return readProjection.response(decision);
    }
    PropertyDispositionDecision saved = persistence.persistTransition(
        decision, request.expectedVersion(), MaintenanceEventType.PROPERTY_DISPOSITION_APPROVED);
    processing.requeue(saved.getId());
    return readProjection.response(saved);
  }

  private PropertyDispositionDecisionResponse rejectInTransaction(
      UUID decisionId,
      UUID warehouseId,
      RejectPropertyDispositionRequest request,
      ReviewPreflight preflight) {
    PropertyDispositionDecision decision = persistence.locked(decisionId, warehouseId);
    requireSameReviewPreflight(decision, preflight);
    if (!decision.reject(request.expectedVersion(), actors.actorJson(), request.reason())) {
      return readProjection.response(decision);
    }
    PropertyDispositionDecision saved = persistence.persistTransition(
        decision, request.expectedVersion(), MaintenanceEventType.PROPERTY_DISPOSITION_REJECTED);
    return readProjection.response(saved);
  }

  private ReviewPreflight reviewPreflight(UUID decisionId, UUID warehouseId) {
    require(decisionId, "Property disposition ID is required");
    require(warehouseId, "Warehouse ID is required");
    PropertyDispositionDecision observed = decisions.findByIdAndWarehouseId(decisionId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    return new ReviewPreflight(
        observed.getId(), observed.getWarehouseId(), observed.getVersion(), observed.getState());
  }

  private PropertyDispositionDecisionResponse recoverInTransaction(
      UUID decisionId, UUID warehouseId, RecoverPropertyDispositionRequest request) {
    PropertyDispositionDecision decision = persistence.locked(decisionId, warehouseId);
    long expectedVersion = request.expectedVersion();
    if (decision.getState() != PropertyDispositionState.QUARANTINED) {
      if (!processing.isQuarantined(decisionId)) {
        throw conflict("Property disposition processing is not quarantined");
      }
      decision.recoverProcessingReconciliation(
          expectedVersion,
          request.expectedRecoveryVersion(),
          actors.actorJson(),
          request.reason());
    } else {
      decision.recover(
          expectedVersion,
          request.expectedRecoveryVersion(),
          actors.actorJson(),
          request.reason());
    }
    PropertyDispositionDecision saved = persistence.persistTransition(
        decision, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_RECOVERED);
    processing.requeue(saved.getId());
    return readProjection.response(saved);
  }

  private static void requireSameReviewPreflight(
      PropertyDispositionDecision decision, ReviewPreflight preflight) {
    if (!decision.getId().equals(preflight.decisionId())
        || !decision.getWarehouseId().equals(preflight.warehouseId())
        || decision.getVersion() != preflight.version()
        || decision.getState() != preflight.state()) {
      throw versionConflict("Property disposition changed during warehouse admission");
    }
  }

  private static boolean isProcessingState(PropertyDispositionState state) {
    return state == PropertyDispositionState.APPROVED
        || state == PropertyDispositionState.MOVEMENT_PENDING
        || state == PropertyDispositionState.EFFECT_PENDING
        || state == PropertyDispositionState.EFFECTIVE;
  }

  private static MaintenanceDependencyGateway.PropertyAssetKind gatewayAssetKind(
      PropertyDispositionAssetKind value) {
    return MaintenanceDependencyGateway.PropertyAssetKind.valueOf(value.name());
  }

  private static void require(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }

  private static MaintenanceConflictException versionConflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_VERSION_CONFLICT", message);
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }

  /** Observed review fence that is rechecked after remote warehouse admission. */
  private record ReviewPreflight(
      UUID decisionId, UUID warehouseId, long version, PropertyDispositionState state) {}
}
