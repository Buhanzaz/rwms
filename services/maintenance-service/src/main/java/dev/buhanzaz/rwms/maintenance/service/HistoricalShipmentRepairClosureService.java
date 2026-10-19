package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentRepairClosureRequest;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentRepairClosureResponse;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentClosureOutcome;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentRentalItemStatus;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.AssetSnapshot;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskKind;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.PreStartTaskCancellation;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.PreStartTaskCancellationOutcome;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable, retry-safe maintenance half of a historical rental shipment.
 *
 * <p>It first records a shipment-specific local audit marker, then calls task-board, logistics and
 * asset service outside a database transaction, and only then commits the terminal repair result.
 * A repeated logistics attempt resumes from the marker and always uses stable effect keys.
 */
@Service
public class HistoricalShipmentRepairClosureService {
  /** Fixed operator-visible reason required for every automatic closure caused by shipment. */
  public static final String AUTOMATIC_CLOSURE_REASON =
      "Автоматически закрыто в связи с отгрузкой.";

  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository stages;
  private final RepairPlaceService repairPlaces;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceEventStore events;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEventPayloadSupport eventPayloads;
  private final TransactionTemplate transactions;

  public HistoricalShipmentRepairClosureService(
      MaintenanceRepairRepository repairs,
      RepairStageRepository stages,
      RepairPlaceService repairPlaces,
      MaintenanceDependencyGateway dependencies,
      MaintenanceEventStore events,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEventPayloadSupport eventPayloads,
      PlatformTransactionManager transactionManager) {
    this.repairs = repairs;
    this.stages = stages;
    this.repairPlaces = repairPlaces;
    this.dependencies = dependencies;
    this.events = events;
    this.commandSupport = commandSupport;
    this.eventPayloads = eventPayloads;
    transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Closes maintenance work for exactly one logistics document. Network calls are deliberately
   * outside local transactions so a retry can resume after an ambiguous response.
   */
  public CloseResult close(
      UUID shipmentId,
      UUID idempotencyKey,
      HistoricalShipmentRepairClosureRequest request) {
    requireNoCallerTransaction();
    if (shipmentId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Historical shipment closure identity is required");
    }
    ClosurePlan plan = local(() -> prepare(shipmentId, request));
    if (plan.repairs().isEmpty()) {
      AssetSnapshot asset = closeAssetAndReleaseLease(plan, idempotencyKey);
      HistoricalShipmentClosureOutcome outcome =
          "RENTED".equals(asset.status())
              ? HistoricalShipmentClosureOutcome.ALREADY_RENTED
              : HistoricalShipmentClosureOutcome.NOT_REQUIRED;
      return new CloseResult(
          response(plan, asset, List.of(), outcome), true);
    }

    cancelRepairTasks(plan, idempotencyKey);
    cancelDriverMovements(plan, idempotencyKey);
    AssetSnapshot asset = closeAssetAndReleaseLease(plan, idempotencyKey);
    List<UUID> closedRepairIds = local(() -> finalizeClosure(plan));
    return new CloseResult(
        response(plan, asset, closedRepairIds, HistoricalShipmentClosureOutcome.CLOSED), false);
  }

  private ClosurePlan prepare(
      UUID shipmentId, HistoricalShipmentRepairClosureRequest request) {
    List<MaintenanceRepair> locked =
        repairs.findAllByRentalItemIdForUpdate(request.rentalItemId()).stream()
            .filter(repair -> request.warehouseId().equals(repair.getWarehouseId()))
            .sorted(Comparator.comparing(MaintenanceRepair::getId))
            .toList();
    List<RepairPlan> planned = new ArrayList<>();
    for (MaintenanceRepair repair : locked) {
      if (repair.getHistoricalShipmentDocumentId() != null
          && !shipmentId.equals(repair.getHistoricalShipmentDocumentId())) {
        throw conflict("Repair is already closing for another historical shipment");
      }
      if (repair.isClosedForHistoricalShipment(shipmentId)) continue;
      if (repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
          || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
        continue;
      }
      if (!repair.isClosingForHistoricalShipment(shipmentId)) {
        long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
        commandSupport.assertVersion(repair.getVersion(), expectedVersion);
        repair.beginHistoricalShipmentClosure(shipmentId, AUTOMATIC_CLOSURE_REASON);
        repairs.saveAndFlush(repair);
        events.append(
            MaintenanceAggregateType.REPAIR,
            repair.getId(),
            expectedVersion,
            MaintenanceEventType.REPAIR_PLAN_CHANGED,
            eventPayloads.decisionLocal(repair.getId(), AUTOMATIC_CLOSURE_REASON),
            eventPayloads.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, repair),
            eventPayloads.repairSnapshot(repair));
      }
      if ("GENERATED".equals(repair.getTaskGenerationState())
          && (repair.getExternalTaskId() == null || repair.getTaskBoardVersion() == null)) {
        throw conflict("Queued repair has incomplete task-board cancellation truth");
      }
      planned.add(RepairPlan.from(repair));
    }
    return new ClosurePlan(shipmentId, request.warehouseId(), request.rentalItemId(), List.copyOf(planned));
  }

  private void cancelRepairTasks(ClosurePlan plan, UUID idempotencyKey) {
    for (RepairPlan repair : plan.repairs()) {
      if (repair.taskBoardVersion() == null) continue;
      PreStartTaskCancellation cancellation =
          dependencies.cancelTaskIfPreStart(
              stableKey(idempotencyKey, "historical-shipment-task", repair.repairId()),
              repair.externalTaskId(),
              repair.taskBoardVersion(),
              AUTOMATIC_CLOSURE_REASON);
      if (cancellation == null || cancellation.outcome() == null) {
        throw unavailable("Task-board omitted historical shipment cancellation truth");
      }
      if (cancellation.outcome() == PreStartTaskCancellationOutcome.STARTED) {
        throw conflict("Repair task has already started and requires operational reconciliation");
      }
      if (cancellation.outcome() == PreStartTaskCancellationOutcome.VERSION_CONFLICT) {
        throw conflict("Repair task changed before historical shipment cancellation");
      }
    }
  }

  private void cancelDriverMovements(ClosurePlan plan, UUID idempotencyKey) {
    for (RepairPlan repair : plan.repairs()) {
      MaintenanceDriverTaskKind kind = repair.driverTaskKind();
      if (kind == null) continue;
      MaintenanceDriverTaskCompensation current =
          dependencies.maintenanceDriverTaskCompensation(repair.repairId(), kind);
      if (current == null || current.outcome() == null) {
        throw unavailable("Logistics omitted historical shipment driver-task truth");
      }
      if (current.outcome() == MaintenanceDriverTaskCompensationOutcome.PENDING) {
        current =
            dependencies.cancelMaintenanceDriverTaskCompensation(
                stableKey(idempotencyKey, "historical-shipment-driver", repair.repairId()),
                repair.repairId(),
                kind);
      }
      if (current == null || current.outcome() == null) {
        throw unavailable("Logistics omitted historical shipment driver cancellation truth");
      }
      if (current.outcome() == MaintenanceDriverTaskCompensationOutcome.STARTED
          || current.outcome() == MaintenanceDriverTaskCompensationOutcome.COMPLETED
          || current.outcome() == MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED) {
        throw conflict("Repair movement is already active and requires operational reconciliation");
      }
      if (current.outcome() == MaintenanceDriverTaskCompensationOutcome.PENDING) {
        throw unavailable("Logistics did not resolve a pending repair movement cancellation");
      }
    }
  }

  private AssetSnapshot closeAssetAndReleaseLease(ClosurePlan plan, UUID idempotencyKey) {
    AssetSnapshot asset = dependencies.getRentalItemSnapshot(plan.rentalItemId());
    if (!plan.warehouseId().equals(asset.warehouseId())) {
      throw conflict("Historical shipment rental item belongs to another warehouse");
    }
    RepairPlan leaseOwner = plan.leaseOwner();
    if (isMaintenanceRepairStatus(asset.status())) {
      if (leaseOwner == null) {
        throw conflict("Active repair asset has no maintenance operation lease");
      }
      AssetSnapshot changed =
          dependencies.fencedStatus(
              stableKey(idempotencyKey, "historical-shipment-asset", leaseOwner.repairId()),
              asset.rentalItemId(),
              asset.warehouseId(),
              asset.version(),
              leaseOwner.leaseId(),
              leaseOwner.fencingToken(),
              "MAINTENANCE_REPAIR",
              leaseOwner.repairId().toString(),
              "HISTORICAL_SHIPMENT_TO_FREE",
              false);
      if (!"FREE".equals(changed.status())) {
        throw unavailable("Asset service did not release the historical shipment cabin");
      }
      asset = changed;
    } else if ("RENTED".equals(asset.status()) && plan.repairs().isEmpty()) {
      return asset;
    } else if (!"FREE".equals(asset.status())) {
      throw conflict("Rental item cannot be closed for historical shipment from status " + asset.status());
    }
    if (leaseOwner != null) {
      dependencies.releaseLease(
          stableKey(idempotencyKey, "historical-shipment-release-lease", leaseOwner.repairId()),
          leaseOwner.leaseId(),
          leaseOwner.leaseVersion(),
          leaseOwner.fencingToken(),
          "MAINTENANCE_REPAIR",
          leaseOwner.repairId().toString());
    }
    return asset;
  }

  private List<UUID> finalizeClosure(ClosurePlan plan) {
    List<UUID> closed = new ArrayList<>();
    for (RepairPlan repairPlan : plan.repairs()) {
      MaintenanceRepair repair =
          repairs
              .findAllByIdForUpdate(List.of(repairPlan.repairId()))
              .stream()
              .findFirst()
              .orElseThrow(() -> new MaintenanceNotFoundException("Historical shipment repair not found"));
      if (repair.isClosedForHistoricalShipment(plan.shipmentId())) {
        closed.add(repair.getId());
        continue;
      }
      if (!repair.isClosingForHistoricalShipment(plan.shipmentId())) {
        throw conflict("Historical shipment closure marker was replaced");
      }
      long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
      commandSupport.assertVersion(repair.getVersion(), expectedVersion);
      boolean capitalRepair = repairPlan.capitalRepair();
      List<RepairStage> repairStages = stages.findAllByRepairIdOrderByStageNo(repair.getId());
      repairStages.forEach(stage -> stage.closeForHistoricalShipment(capitalRepair));
      stages.saveAllAndFlush(repairStages);
      repair.closeForHistoricalShipment(plan.shipmentId(), capitalRepair, AUTOMATIC_CLOSURE_REASON);
      if (repairPlan.hasLease()) repair.releaseLease();
      repairPlaces.releaseForHistoricalShipment(plan.warehouseId(), repair.getId());
      repairs.saveAndFlush(repair);
      events.append(
          MaintenanceAggregateType.REPAIR,
          repair.getId(),
          expectedVersion,
          MaintenanceEventType.REPAIR_PLAN_CHANGED,
          eventPayloads.decisionLocal(repair.getId(), AUTOMATIC_CLOSURE_REASON),
          eventPayloads.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, repair),
          eventPayloads.repairSnapshot(repair));
      closed.add(repair.getId());
    }
    return List.copyOf(closed);
  }

  private static boolean isMaintenanceRepairStatus(String status) {
    return "REPAIR".equals(status)
        || "CAPITAL_REPAIR".equals(status)
        || "WAITING_REPAIR_CHECK".equals(status);
  }

  private static UUID stableKey(UUID root, String operation, UUID repairId) {
    return UUID.nameUUIDFromBytes(
        (root + ":" + operation + ":" + repairId).getBytes(StandardCharsets.UTF_8));
  }

  private static MaintenanceConflictException conflict(String detail) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", detail);
  }

  private static MaintenanceDependencyException unavailable(String detail) {
    return new MaintenanceDependencyException(
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  private <T> T local(java.util.function.Supplier<T> action) {
    T result = transactions.execute(ignored -> action.get());
    if (result == null) throw new IllegalStateException("Historical shipment transaction returned no result");
    return result;
  }

  private static void requireNoCallerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Historical shipment closure cannot call remote dependencies inside a transaction");
    }
  }

  private static HistoricalShipmentRepairClosureResponse response(
      ClosurePlan plan,
      AssetSnapshot asset,
      List<UUID> repairIds,
      HistoricalShipmentClosureOutcome outcome) {
    boolean free = asset != null && "FREE".equals(asset.status());
    boolean alreadyRented = asset != null && "RENTED".equals(asset.status());
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() < 0
        || (!free && !alreadyRented)
        || (alreadyRented
            && (outcome != HistoricalShipmentClosureOutcome.ALREADY_RENTED
                || !repairIds.isEmpty()))
        || (free && outcome == HistoricalShipmentClosureOutcome.ALREADY_RENTED)) {
      throw unavailable("Asset service did not return historical shipment closure truth");
    }
    return new HistoricalShipmentRepairClosureResponse(
        plan.shipmentId(),
        plan.warehouseId(),
        plan.rentalItemId(),
        asset.version(),
        alreadyRented
            ? HistoricalShipmentRentalItemStatus.RENTED
            : HistoricalShipmentRentalItemStatus.FREE,
        List.copyOf(repairIds),
        outcome);
  }

  /** Controller-facing result that keeps replay signalling out of the transport model. */
  public record CloseResult(HistoricalShipmentRepairClosureResponse response, boolean replayed) {}

  /** Immutable local snapshot used only outside a transaction while remote effects run. */
  private record ClosurePlan(
      UUID shipmentId, UUID warehouseId, UUID rentalItemId, List<RepairPlan> repairs) {
    RepairPlan leaseOwner() {
      List<RepairPlan> owners = repairs.stream().filter(RepairPlan::hasLease).toList();
      if (owners.size() > 1) {
        throw conflict("Historical shipment has more than one active maintenance lease");
      }
      return owners.isEmpty() ? null : owners.getFirst();
    }
  }

  /** Exact repair and remote-effect fences captured under the maintenance row lock. */
  private record RepairPlan(
      UUID repairId,
      UUID externalTaskId,
      Long taskBoardVersion,
      boolean capitalRepair,
      MaintenanceDriverTaskKind driverTaskKind,
      UUID leaseId,
      Long leaseVersion,
      Long fencingToken) {
    static RepairPlan from(MaintenanceRepair repair) {
      boolean capital =
          repair.isForceCapitalRepair()
              || repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL;
      MaintenanceDriverTaskKind driverKind =
          capital
              ? MaintenanceDriverTaskKind.CAPITAL_TO_PRODUCTION
              : repair.isMovementToRepair() ? MaintenanceDriverTaskKind.DELIVER_TO_REPAIR : null;
      if ((repair.getLeaseId() == null)
          != (repair.getLeaseVersion() == null
              || repair.getFencingToken() == null)) {
        throw conflict("Maintenance repair has an incomplete operation lease");
      }
      return new RepairPlan(
          repair.getId(),
          repair.getExternalTaskId(),
          repair.getTaskBoardVersion(),
          capital,
          driverKind,
          repair.getLeaseId(),
          repair.getLeaseVersion(),
          repair.getFencingToken());
    }

    boolean hasLease() {
      return leaseId != null && leaseVersion != null && fencingToken != null;
    }
  }
}
