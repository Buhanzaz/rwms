package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.disposition.repository.PropertyDispositionDecisionRepository;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.service.InventoryPublicationPrestartReplacementGuard;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Commits local state callbacks issued by the remote disposition processor.
 *
 * <p>Mixed disposition/repair callbacks preserve the original event-stream lock order before
 * mutating either aggregate; no remote call is made from this local transaction work.
 */
@Service
final class PropertyDispositionProcessingCallbacks {
  private final PropertyDispositionDecisionRepository decisions;
  private final PropertyDispositionDecisionPersistence persistence;
  private final PropertyDispositionRepairChain repairChain;
  private final PropertyDispositionRepairChainFinalization finalization;
  private final MaintenanceEventStore events;
  private final InventoryPublicationPrestartReplacementGuard inventoryReplacementGuard;

  PropertyDispositionProcessingCallbacks(
      PropertyDispositionDecisionRepository decisions,
      PropertyDispositionDecisionPersistence persistence,
      PropertyDispositionRepairChain repairChain,
      PropertyDispositionRepairChainFinalization finalization,
      MaintenanceEventStore events,
      InventoryPublicationPrestartReplacementGuard inventoryReplacementGuard) {
    this.decisions = decisions;
    this.persistence = persistence;
    this.repairChain = repairChain;
    this.finalization = finalization;
    this.events = events;
    this.inventoryReplacementGuard = inventoryReplacementGuard;
  }

  void startMovement(UUID decisionId, UUID taskId) {
    PropertyDispositionDecision decision = persistence.locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.startMovement(expectedVersion, taskId)) {
      return;
    }
    persistence.persistTransition(
        decision, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_MOVEMENT_PENDING);
  }

  void completeMovement(UUID decisionId) {
    PropertyDispositionDecision decision = persistence.locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.completeMovement(expectedVersion)) {
      return;
    }
    persistence.persistTransition(
        decision, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECT_PENDING);
  }

  void startAssetEffect(UUID decisionId) {
    PropertyDispositionDecision decision = persistence.locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.startAssetEffect(expectedVersion)) {
      return;
    }
    persistence.persistTransition(
        decision, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECT_PENDING);
  }

  void markEffective(UUID decisionId, UUID effectId) {
    PropertyDispositionDecision initial = decisions.findById(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    List<MaintenanceRepair> initialChain = initial.getAssetKind() == PropertyDispositionAssetKind.CABIN
        ? repairChain.repairChain(initial.getRootRepairId())
        : List.of();
    if (initialChain.stream()
        .anyMatch(repair -> inventoryReplacementGuard.blocksRepairExecution(repair.getId()))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair is being superseded by an authoritative inventory outcome");
    }
    List<MaintenanceEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(PropertyDispositionDecisionPersistence.stream(decisionId));
    initialChain.forEach(repair -> streams.add(PropertyDispositionRepairChain.stream(repair.getId())));
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(streams);
    PropertyDispositionDecision decision = persistence.lockForUpdate(decisionId);
    PropertyDispositionDecisionPersistence.assertStreamParity(
        decision, versions.get(PropertyDispositionDecisionPersistence.stream(decisionId)));
    List<MaintenanceRepair> chain = repairChain.lockRepairChain(initialChain, versions);
    long expectedVersion = decision.getVersion();
    if (!decision.markEffective(expectedVersion, effectId)) {
      return;
    }
    finalization.writeOff(chain, decision.getReason());
    persistence.persistTransition(
        decision, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECTIVE);
  }

  void markEffectiveWithoutAssetEffect(UUID decisionId) {
    long streamVersion = events.lockCurrentVersion(
        dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType.PROPERTY_DISPOSITION,
        decisionId);
    PropertyDispositionDecision decision = persistence.lockForUpdate(decisionId);
    PropertyDispositionDecisionPersistence.assertStreamParity(decision, streamVersion);
    long expectedVersion = decision.getVersion();
    if (!decision.markEffectiveWithoutAssetEffect(expectedVersion)) {
      return;
    }
    persistence.persistTransition(
        decision, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_EFFECTIVE);
  }

  void quarantine(UUID decisionId, String failureCode, String failureDetail) {
    PropertyDispositionDecision decision = persistence.locked(decisionId, null);
    long expectedVersion = decision.getVersion();
    if (!decision.quarantine(expectedVersion, failureCode, failureDetail)) {
      return;
    }
    persistence.persistTransition(
        decision, expectedVersion, MaintenanceEventType.PROPERTY_DISPOSITION_QUARANTINED);
  }

  void confirmLeaseReleased(UUID decisionId, UUID leaseId) {
    PropertyDispositionDecision initial = decisions.findById(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    if (initial.getState() != PropertyDispositionState.EFFECTIVE || initial.getRootRepairId() == null) {
      return;
    }
    List<MaintenanceRepair> initialChain = repairChain.repairChain(initial.getRootRepairId());
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(
        initialChain.stream().map(repair -> PropertyDispositionRepairChain.stream(repair.getId())).toList());
    List<MaintenanceRepair> chain = repairChain.lockRepairChain(initialChain, versions);
    finalization.releaseLease(chain, leaseId);
  }
}
