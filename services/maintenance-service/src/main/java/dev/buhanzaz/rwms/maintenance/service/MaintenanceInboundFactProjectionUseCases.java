package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.OperationLeaseFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.OperationLeaseFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Applies monotonic external media, rental-item and lease facts to maintenance projections. */
@Service
final class MaintenanceInboundFactProjectionUseCases {
  private final MediaFactProjectionRepository mediaFacts;
  private final RentalItemFactProjectionRepository rentalItemFacts;
  private final OperationLeaseFactProjectionRepository leaseFacts;
  private final MaintenanceRepairRepository repairs;
  private final MaintenanceEventStore events;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceRepairLifecycleSupport repairLifecycleSupport;

  MaintenanceInboundFactProjectionUseCases(
      MediaFactProjectionRepository mediaFacts,
      RentalItemFactProjectionRepository rentalItemFacts,
      OperationLeaseFactProjectionRepository leaseFacts,
      MaintenanceRepairRepository repairs,
      MaintenanceEventStore events,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceRepairLifecycleSupport repairLifecycleSupport) {
    this.mediaFacts = mediaFacts;
    this.rentalItemFacts = rentalItemFacts;
    this.leaseFacts = leaseFacts;
    this.repairs = repairs;
    this.events = events;
    this.eventPayloadSupport = eventPayloadSupport;
    this.reconciliationSupport = reconciliationSupport;
    this.repairLifecycleSupport = repairLifecycleSupport;
  }

  void applyInboundMediaFact(
      UUID mediaId,
      long generation,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      String status,
      String safeMetadata,
      long aggregateVersion) {
    String localStatus = switch (status) {
      case "UPLOADING", "PROCESSING" -> "PENDING";
      case "READY", "FAILED", "DELETED" -> status;
      default -> throw new IllegalArgumentException("Unsupported media status " + status);
    };
    MediaFactProjection fact = mediaFacts.findById(mediaId).orElseGet(() ->
        MediaFactProjection.create(
            mediaId, generation, ownerType, ownerId, warehouseId, localStatus,
            safeMetadata, aggregateVersion));
    if (fact.getAggregateVersion() < aggregateVersion) {
      fact.apply(
          generation, ownerType, ownerId, warehouseId, localStatus,
          safeMetadata, aggregateVersion);
    }
    mediaFacts.save(fact);
  }

  void applyInboundRentalItemFact(
      UUID rentalItemId,
      UUID warehouseId,
      String status,
      long aggregateVersion) {
    Optional<RentalItemFactProjection> current = rentalItemFacts.findById(rentalItemId);
    if (current.isEmpty() && (warehouseId == null || status == null)) {
      throw new IllegalStateException(
          "Partial rental-item fact cannot initialize the maintenance projection");
    }
    RentalItemFactProjection fact = current.orElseGet(() ->
        RentalItemFactProjection.create(rentalItemId, warehouseId, status, aggregateVersion));
    if (fact.getAggregateVersion() < aggregateVersion) {
      fact.apply(
          warehouseId == null ? fact.getWarehouseId() : warehouseId,
          status == null ? fact.getAssetStatus() : status,
          aggregateVersion);
    }
    rentalItemFacts.save(fact);
  }

  void applyInboundLeaseFact(
      UUID leaseId,
      UUID rentalItemId,
      long fencingToken,
      String state,
      long aggregateVersion) {
    Optional<OperationLeaseFactProjection> current = leaseFacts.findById(leaseId);
    OperationLeaseFactProjection fact = current.orElseGet(() ->
        OperationLeaseFactProjection.create(
            leaseId, rentalItemId, fencingToken, state, aggregateVersion));
    boolean advanced = current.isEmpty()
        || fact.apply(rentalItemId, fencingToken, state, aggregateVersion);
    leaseFacts.save(fact);
    if (!advanced || !"EXPIRED".equals(state)) return;
    List<MaintenanceRepair> affected = repairs.findAllByLeaseId(leaseId);
    if (affected.isEmpty()) return;
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(
        affected.stream().map(value -> repairLifecycleSupport.stream(value.getId())).toList());
    repairs.findAllByIdForUpdate(affected.stream().map(MaintenanceRepair::getId).toList())
        .forEach(repair -> {
          if (!rentalItemId.equals(repair.getRentalItemId())
              || !Long.valueOf(fencingToken).equals(repair.getFencingToken())) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_LEASE_CONFLICT", "Lease fact does not match local fencing truth");
          }
          boolean changed = repair.markReconciliationRequired();
          if (!"RECONCILIATION_REQUIRED".equals(repair.getLeaseReconciliationState())) {
            repair.markLeaseReconciliationRequired();
            changed = true;
          }
          if (!changed) return;
          MaintenanceRepair saved = repairs.saveAndFlush(repair);
          events.append(
              MaintenanceAggregateType.REPAIR,
              saved.getId(),
              versions.get(repairLifecycleSupport.stream(saved.getId())),
              reconciliationSupport.reconciliationEvent(saved, "RENEW_LEASE"),
              eventPayloadSupport.repairLocal(saved),
              eventPayloadSupport.repairFact(
                  reconciliationSupport.reconciliationEvent(saved, "RENEW_LEASE"), saved),
              eventPayloadSupport.repairSnapshot(saved));
        });
  }
}
