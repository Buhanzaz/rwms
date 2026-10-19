package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Executes one claimed asset ensure without retaining a maintenance database transaction. */
@Component
public class FurnitureEquipmentLinkProcessor {
  private static final Logger log = LoggerFactory.getLogger(FurnitureEquipmentLinkProcessor.class);
  private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
  private static final int BATCH_SIZE = 25;

  private final FurnitureEquipmentLinkStore links;
  private final MaintenanceDependencyGateway dependencies;

  public FurnitureEquipmentLinkProcessor(
      FurnitureEquipmentLinkStore links, MaintenanceDependencyGateway dependencies) {
    this.links = links;
    this.dependencies = dependencies;
  }

  /** Synchronous best-effort processing used by a catalog command after its intent commit. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public boolean processExact(UUID nodeId) {
    Optional<FurnitureEquipmentLinkStore.WorkItem> claimed =
        links.claimExact(nodeId, CLAIM_LEASE);
    if (claimed.isEmpty()) return false;
    process(claimed.orElseThrow());
    return true;
  }

  @Scheduled(
      fixedDelayString = "${rwms.maintenance.furniture-equipment-link.delay:2s}",
      initialDelayString = "${rwms.maintenance.furniture-equipment-link.initial-delay:0s}")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void reconcileDue() {
    if (!dependencies.productionReady()) return;
    for (int index = 0; index < BATCH_SIZE; index++) {
      Optional<FurnitureEquipmentLinkStore.WorkItem> claimed =
          links.claimNextDue(CLAIM_LEASE);
      if (claimed.isEmpty()) return;
      FurnitureEquipmentLinkStore.WorkItem work = claimed.orElseThrow();
      try {
        process(work);
      } catch (RuntimeException failure) {
        log.warn(
            "Furniture equipment auto-link reconciliation failed: nodeId={}, warehouseId={}",
            work.nodeId(),
            work.warehouseId(),
            failure);
      }
    }
  }

  private void process(FurnitureEquipmentLinkStore.WorkItem work) {
    try {
      MaintenanceDependencyGateway.FurnitureEquipmentSnapshot snapshot =
          work.expectedEquipmentVersion() == null && work.maximumPerCabin() == null
              ? dependencies.ensureFurnitureEquipment(work.nodeId(), work.requestedName())
              : dependencies.ensureFurnitureEquipment(
                  work.nodeId(),
                  work.requestedName(),
                  work.expectedEquipmentVersion(),
                  work.maximumPerCabin());
      if (snapshot == null
          || snapshot.equipmentId() == null
          || snapshot.equipmentName() == null
          || !work.requestedName().equals(snapshot.equipmentName().trim())) {
        UUID observedId = snapshot == null ? null : snapshot.equipmentId();
        String observedName = snapshot == null ? null : snapshot.equipmentName();
        if ((observedId == null) != (observedName == null || observedName.isBlank())) {
          observedId = null;
          observedName = null;
        }
        links.mappingConflict(
            work,
            observedId,
            observedName,
            "Asset-service returned a mapping that does not match the immutable node UUID/name");
        throw new MaintenanceConflictException(
            "FURNITURE_EQUIPMENT_LINK_CONFLICT",
            "Asset-service returned conflicting furniture equipment truth");
      }
      links.confirmed(
          work,
          snapshot.equipmentId(),
          snapshot.equipmentName(),
          snapshot.equipmentVersion(),
          snapshot.maximumPerCabin());
    } catch (MaintenanceConflictException conflict) {
      throw conflict;
    } catch (RuntimeException failure) {
      try {
        links.failed(work, failure);
      } catch (MaintenanceConflictException staleClaim) {
        log.debug("Furniture equipment link claim changed: nodeId={}", work.nodeId());
      }
      if (failure instanceof MaintenanceDependencyException dependency) throw dependency;
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Furniture equipment auto-link could not be confirmed",
          failure);
    }
  }
}
