package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Applies terminal repair-chain effects after an already-confirmed disposition transition. */
@Service
final class PropertyDispositionRepairChainFinalization {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final PropertyDispositionActorCodec actors;

  PropertyDispositionRepairChainFinalization(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      PropertyDispositionActorCodec actors) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.actors = actors;
  }

  void writeOff(List<MaintenanceRepair> chain, String reason) {
    for (MaintenanceRepair repair : chain) {
      if (repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
        continue;
      }
      if (repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED) {
        throw conflict("Accepted repair cannot be terminalized by a property disposition");
      }
      long expectedRepairVersion = repair.getVersion();
      repair.writeOff(reason, actors.actorJson());
      repair.markLeaseReconciliationRequired();
      MaintenanceRepair savedRepair = repairs.saveAndFlush(repair);
      appendRepairWriteOff(savedRepair, expectedRepairVersion);
    }
  }

  void releaseLease(List<MaintenanceRepair> chain, UUID leaseId) {
    for (MaintenanceRepair repair : chain) {
      if (!leaseId.equals(repair.getLeaseId())
          || "RELEASED".equals(repair.getLeaseReconciliationState())) {
        continue;
      }
      long expectedVersion = repair.getVersion();
      repair.releaseLease();
      MaintenanceRepair saved = repairs.saveAndFlush(repair);
      appendRepairWriteOff(saved, expectedVersion);
    }
  }

  private void appendRepairWriteOff(MaintenanceRepair repair, long expectedVersion) {
    events.append(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_WRITTEN_OFF,
        projectionSnapshots.repair(repair),
        eventFacts.repairPayload(
            MaintenanceEventType.REPAIR_WRITTEN_OFF,
            repair,
            repairStages.findAllByRepairIdOrderByStageNo(repair.getId())),
        projectionSnapshots.repair(repair));
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }
}
