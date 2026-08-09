package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Validates remote reconciliation truth and maintains bounded local recovery state. */
@Service
final class MaintenanceReconciliationSupport {
  private static final Duration LEASE_RENEWAL_GUARD = Duration.ofMinutes(5);
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceReconciliationStore reconciliations;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;

  MaintenanceReconciliationSupport(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceReconciliationStore reconciliations,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceRepairModelSupport repairModelSupport) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.reconciliations = reconciliations;
    this.commandSupport = commandSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.repairModelSupport = repairModelSupport;
  }

  protected boolean enqueueDueLeaseRenewal() {
    Optional<MaintenanceRepair> candidate =
        repairs
            .findLeaseRenewalCandidateForUpdateSkipLocked(
                OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(LEASE_RENEWAL_GUARD),
                org.springframework.data.domain.PageRequest.of(0, 1))
            .stream()
            .findFirst();
    if (candidate.isEmpty()) return false;
    MaintenanceRepair repair = candidate.get();
    reconciliations.enqueue(
        repair.getId(),
        "ASSET",
        "RENEW_LEASE",
        commandSupport.stableOperationKey("renew-lease", repair.getLeaseId(), repair.getLeaseVersion()),
        Map.of("repairId", repair.getId().toString()));
    return true;
  }

  protected void recordReconciliationFailure(
      MaintenanceReconciliationStore.WorkItem work, boolean quarantined) {
    if (work.repairId() == null) return;
    MaintenanceRepair repair = repairModelSupport.requireRepair(work.repairId());
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    commandSupport.assertVersion(repair.getVersion(), expectedVersion);
    boolean changed;
    if ("TASK_BOARD".equals(work.dependency())) {
      repair.markTaskDeliveryFailed(quarantined);
      List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
      stages.forEach(stage -> stage.markTaskDeliveryFailed(quarantined));
      repairStages.saveAllAndFlush(stages);
      changed = true;
    } else {
      changed = quarantined
          ? repair.markReconciliationQuarantined()
          : repair.markReconciliationRequired();
    }
    if (!changed) return;
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        reconciliationEvent(saved, work.operation()),
        eventPayloadSupport.repairLocal(saved),
        eventPayloadSupport.repairFact(reconciliationEvent(saved, work.operation()), saved),
        eventPayloadSupport.repairSnapshot(saved));
  }

  protected Optional<MaintenanceDependencyGateway.LeaseSnapshot> localLease(
      MaintenanceRepair repair,
      List<MaintenanceRepair> sources,
      String ownerType,
      String ownerId) {
    List<MaintenanceRepair> candidates = new ArrayList<>();
    candidates.add(repair);
    candidates.addAll(sources);
    List<MaintenanceRepair> withLease = candidates.stream()
        .filter(value -> value.getLeaseId() != null)
        .toList();
    if (withLease.isEmpty()) return Optional.empty();
    MaintenanceRepair selected = withLease.getFirst();
    for (MaintenanceRepair candidate : withLease) {
      if (!selected.getLeaseId().equals(candidate.getLeaseId())
          || !selected.getFencingToken().equals(candidate.getFencingToken())) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_LEASE_CONFLICT", "Repair chain contains divergent lease snapshots");
      }
    }
    return Optional.of(new MaintenanceDependencyGateway.LeaseSnapshot(
        selected.getLeaseId(), selected.getLeaseVersion(), selected.getRentalItemId(), ownerType,
        UUID.fromString(ownerId), selected.getFencingToken(), selected.getLeaseExpiresAt()));
  }

  protected void reconcileLeaseProjection(
      MaintenanceRepair repair,
      long expectedVersion,
      long assetVersion,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      boolean released,
      MaintenanceEventType eventType) {
    repair.confirmRentalItemVersion(assetVersion);
    if (repair.getLeaseId() != null) repair.renewLease(lease.version(), lease.expiresAt());
    if (released) {
      repair.releaseLease();
    } else {
      repair.markReconciled();
    }
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        eventType,
        eventPayloadSupport.repairLocal(saved),
        eventPayloadSupport.repairFact(eventType, saved),
        eventPayloadSupport.repairSnapshot(saved));
  }

  protected static MaintenanceEventType transitionEvent(
      MaintenanceRepair repair, String transition) {
    return switch (transition) {
      case "EMPTY_REPAIR_TO_FREE" -> MaintenanceEventType.REPAIR_PLAN_CHANGED;
      case "PENDING_ACCEPTANCE" -> MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE;
      case "ACCEPT_TO_FREE" -> MaintenanceEventType.REPAIR_ACCEPTED;
      case "WRITE_OFF" -> MaintenanceEventType.REPAIR_WRITTEN_OFF;
      default -> throw new IllegalArgumentException("Unsupported asset transition " + transition);
    };
  }

  protected static MaintenanceEventType renewalEvent(MaintenanceRepair repair) {
    return repair.getExecutionState() == RepairExecutionState.CANCELLED
        ? MaintenanceEventType.REPAIR_PLAN_CHANGED
        : MaintenanceEventType.REPAIR_QUEUED;
  }

  protected static MaintenanceEventType reconciliationEvent(
      MaintenanceRepair repair, String operation) {
    if ("UPDATE_TASK".equals(operation)
        || "SYNC_REPAIR_COMPLEXITY_STATUS".equals(operation)) {
      return MaintenanceEventType.REPAIR_PLAN_CHANGED;
    }
    if ("ACCEPT_TO_FREE".equals(operation)
        || repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED) {
      return MaintenanceEventType.REPAIR_ACCEPTED;
    }
    if ("WRITE_OFF".equals(operation)
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      return MaintenanceEventType.REPAIR_WRITTEN_OFF;
    }
    if ("PENDING_ACCEPTANCE".equals(operation)) {
      return MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE;
    }
    return repair.getExecutionState() == RepairExecutionState.DRAFT
        || repair.getExecutionState() == RepairExecutionState.CANCELLED
        ? MaintenanceEventType.REPAIR_PLAN_CHANGED
        : MaintenanceEventType.REPAIR_QUEUED;
  }

  protected static void validateLeaseTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    validateLeaseTruth(
        repair.getRentalItemId(), lease, ownerType, ownerId);
  }

  protected static void validateLeaseTruth(
      MaintenanceEstimate estimate,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    validateLeaseTruth(
        estimate.getRentalItemId(), lease, ownerType, ownerId);
  }

  protected static void validateLeaseTruth(
      UUID rentalItemId,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    if (lease == null || lease.leaseId() == null
        || !rentalItemId.equals(lease.rentalItemId())
        || !ownerType.equals(lease.ownerType())
        || !UUID.fromString(ownerId).equals(lease.ownerId())
        || lease.version() < 0 || lease.fencingToken() < 1 || lease.expiresAt() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease truth does not match the maintenance owner");
    }
  }

  protected static void validateRenewedLeaseTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      String ownerType,
      String ownerId) {
    validateLeaseTruth(repair, lease, ownerType, ownerId);
    long expectedVersion = Math.addExact(repair.getLeaseVersion(), 1);
    if (!repair.getLeaseId().equals(lease.leaseId())
        || repair.getFencingToken() != lease.fencingToken()
        || lease.version() != expectedVersion) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease renewal does not preserve the current fence and version");
    }
  }

  protected static void validateAssetTruth(
      MaintenanceRepair repair, MaintenanceDependencyGateway.AssetSnapshot asset) {
    validateAssetTruth(repair, asset, repair.getRentalItemVersionSnapshot());
  }

  protected static void validateAssetTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      long expectedVersion) {
    validateAssetTruth(repair, asset, expectedVersion, false);
  }

  protected static void validateAssetTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      long expectedVersion,
      boolean allowUnchangedVersion) {
    if (asset == null || !repair.getRentalItemId().equals(asset.rentalItemId())
        || !repair.getWarehouseId().equals(asset.warehouseId())
        || asset.version() < expectedVersion
        || (!allowUnchangedVersion && asset.version() == expectedVersion)) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not match the expected rental item");
    }
  }

  protected static void validateAssetTruth(
      MaintenanceEstimate estimate, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null || !estimate.getRentalItemId().equals(asset.rentalItemId())
        || !estimate.getWarehouseId().equals(asset.warehouseId())
        || asset.version() <= estimate.getRentalItemVersionSnapshot()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not advance the expected rental item");
    }
  }

  protected static void validateTaskTruth(
      MaintenanceRepair repair,
      MaintenanceDependencyGateway.TaskSnapshot task,
      int expectedStages) {
    if (task == null || !repair.getExternalTaskId().equals(task.externalTaskId())
        || task.version() < 0 || !"ACTIVE".equals(task.state())
        || task.stages() == null || task.stages().size() != expectedStages) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board truth does not match the maintenance repair");
    }
  }

  protected static boolean hasNoRecordedCabinContents(
      UUID rentalItemId,
      UUID warehouseId,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    requireMatchingCabinForFurniture(rentalItemId, warehouseId, snapshot);
    return snapshot.contents().isEmpty();
  }

  protected static void requireMatchingCabinForFurniture(
      UUID rentalItemId,
      UUID warehouseId,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (snapshot == null
        || snapshot.assetKind() != MaintenanceDependencyGateway.PropertyAssetKind.CABIN
        || !rentalItemId.equals(snapshot.assetId())
        || !warehouseId.equals(snapshot.warehouseId())
        || snapshot.contents() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service returned another cabin while selecting repair furniture");
    }
  }
}
