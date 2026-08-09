package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.repository.PropertyDispositionDecisionRepository;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.PropertyDispositionFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import dev.buhanzaz.rwms.maintenance.service.WarehouseLifecycleOperations;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists one disposition transition together with its authoritative event-stream mutation.
 *
 * <p>This type does not decide which transition is valid. It provides the short local lock and
 * event parity mechanics used by creation, review, recovery, and processor callbacks.
 */
@Service
final class PropertyDispositionDecisionPersistence {
  private final PropertyDispositionDecisionRepository decisions;
  private final MaintenanceEventStore events;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final ObjectMapper mapper;
  private final WarehouseLifecycleOperations warehouseLifecycle;

  PropertyDispositionDecisionPersistence(
      PropertyDispositionDecisionRepository decisions,
      MaintenanceEventStore events,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      ObjectMapper mapper,
      WarehouseLifecycleOperations warehouseLifecycle) {
    this.decisions = decisions;
    this.events = events;
    this.projectionSnapshots = projectionSnapshots;
    this.mapper = mapper;
    this.warehouseLifecycle = warehouseLifecycle;
  }

  PropertyDispositionDecision locked(UUID decisionId, UUID expectedWarehouseId) {
    long streamVersion = events.lockCurrentVersion(
        MaintenanceAggregateType.PROPERTY_DISPOSITION, decisionId);
    PropertyDispositionDecision decision = decisions.findByIdForUpdate(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
    if (expectedWarehouseId != null && !expectedWarehouseId.equals(decision.getWarehouseId())) {
      throw new MaintenanceNotFoundException("Property disposition not found");
    }
    assertStreamParity(decision, streamVersion);
    return decision;
  }

  PropertyDispositionDecision lockForUpdate(UUID decisionId) {
    return decisions.findByIdForUpdate(decisionId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Property disposition not found"));
  }

  PropertyDispositionDecision persistRequested(PropertyDispositionDecision decision) {
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    events.initialize(
        MaintenanceAggregateType.PROPERTY_DISPOSITION,
        saved.getId(),
        saved.getVersion(),
        MaintenanceEventType.PROPERTY_DISPOSITION_REQUESTED,
        projectionSnapshots.propertyDisposition(saved),
        integrationFact(saved),
        projectionSnapshots.propertyDisposition(saved));
    warehouseLifecycle.recordOperation(
        saved.getWarehouseId(), saved.getId(), saved.getCreatedAt().atOffset(ZoneOffset.UTC));
    return saved;
  }

  PropertyDispositionDecision persistTransition(
      PropertyDispositionDecision decision, long expectedVersion, MaintenanceEventType eventType) {
    PropertyDispositionDecision saved = decisions.saveAndFlush(decision);
    append(saved, expectedVersion, eventType);
    return saved;
  }

  void append(
      PropertyDispositionDecision decision, long expectedVersion, MaintenanceEventType eventType) {
    events.append(
        MaintenanceAggregateType.PROPERTY_DISPOSITION,
        decision.getId(),
        expectedVersion,
        eventType,
        projectionSnapshots.propertyDisposition(decision),
        integrationFact(decision),
        projectionSnapshots.propertyDisposition(decision));
  }

  static MaintenanceEventStore.StreamRef stream(UUID id) {
    return new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.PROPERTY_DISPOSITION, id);
  }

  static void assertStreamParity(PropertyDispositionDecision decision, Long eventVersion) {
    if (eventVersion == null || decision.getVersion() != eventVersion) {
      throw conflict("Property disposition event stream is out of sync with local state");
    }
  }

  private Map<String, Object> integrationFact(PropertyDispositionDecision decision) {
    PropertyDispositionFact fact = new PropertyDispositionFact(
        decision.getId(),
        decision.getWarehouseId(),
        decision.getAssetKind(),
        decision.getAssetId(),
        decision.getKind(),
        decision.getSource(),
        decision.getState(),
        decision.getAssetEffectState(),
        decision.getRootRepairId(),
        decision.getSourceRepairId(),
        decision.getInventoryId(),
        decision.getFindingId(),
        decision.getMovementTaskId(),
        decision.getEffectId(),
        decision.getRecoveryVersion());
    return mapper.convertValue(fact, new TypeReference<Map<String, Object>>() {});
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }
}
