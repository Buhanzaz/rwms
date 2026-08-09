package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Orchestrates the two-phase repair-transfer departure and arrival workflows while keeping remote effects outside local lock transactions. */
@Service
public class MaintenanceTransferUseCases {
  private static final UUID LOGISTICS_SERVICE_SUBJECT =
      UUID.nameUUIDFromBytes("rwms:logistics-service".getBytes(StandardCharsets.UTF_8));
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceIdempotencyStore idempotency;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final MaintenanceTransferSupport transferSupport;

  public MaintenanceTransferUseCases(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceIdempotencyStore idempotency,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceCommandSupport commandSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      MaintenanceTransferSupport transferSupport) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.idempotency = idempotency;
    this.warehouseLifecycle = warehouseLifecycle;
    this.commandSupport = commandSupport;
    this.repairModelSupport = repairModelSupport;
    this.transferSupport = transferSupport;
  }

  public CreateResult<PrepareTransferRepairResponse> prepareTransferDeparture(
      UUID transferId,
      UUID lineId,
      UUID key,
      TransferRepairRequest request) {
    commandSupport.requireNoCallerTransaction("prepare a repair transfer departure");
    TransferDeparturePreflight preflight =
        commandSupport.inLocalTransaction(
            "repair transfer departure preflight",
            () -> prepareTransferDeparturePreflight(transferId, lineId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    TransferDeparturePlan plan = preflight.plan();
    if (plan.warehouseId() != null) {
      warehouseLifecycle.requireOutgoing(plan.warehouseId());
    }
    if (plan.leaseRelease() != null) {
      transferSupport.releaseTransferLease(plan.leaseRelease(), key);
    }
    return commandSupport.inLocalTransaction(
        "repair transfer departure finalization",
        () -> prepareTransferDepartureInTransaction(transferId, lineId, key, request, plan));
  }

  private TransferDeparturePreflight
      prepareTransferDeparturePreflight(
          UUID transferId, UUID lineId, UUID key, TransferRepairRequest request) {
    String scope = "transfer.prepare:" + transferId + ":" + lineId;
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new TransferDeparturePreflight(
          new CreateResult<>(
              commandSupport.read(replay.get(), PrepareTransferRepairResponse.class), true),
          null);
    }
    List<MaintenanceRepair> candidates =
        repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(request.rentalItemId());
    MaintenanceRepair active = repairModelSupport.requireSingleActiveRepair(candidates, false);
    return new TransferDeparturePreflight(null, transferSupport.transferDeparturePlan(candidates, active, request));
  }

  private CreateResult<PrepareTransferRepairResponse> prepareTransferDepartureInTransaction(
      UUID transferId,
      UUID lineId,
      UUID key,
      TransferRepairRequest request,
      TransferDeparturePlan expectedPlan) {
    String scope = "transfer.prepare:" + transferId + ":" + lineId;
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          commandSupport.read(replay.get(), PrepareTransferRepairResponse.class), true);
    }
    List<MaintenanceRepair> candidates =
        repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(request.rentalItemId());
    MaintenanceRepair active = repairModelSupport.requireSingleActiveRepair(candidates, false);
    TransferDeparturePlan currentPlan = transferSupport.transferDeparturePlan(candidates, active, request);
    transferSupport.requireMatchingTransferDeparturePlan(expectedPlan, currentPlan);
    if (active == null) {
      PrepareTransferRepairResponse response =
          new PrepareTransferRepairResponse(null, null, "FREE");
      idempotency.store(
          LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
      return new CreateResult<>(response, false);
    }
    List<MaintenanceRepair> chain = repairModelSupport.lockRepairChain(active);
    active = repairModelSupport.requireRepairInChain(chain, active.getId());
    transferSupport.requireMatchingTransferDeparturePlan(
        expectedPlan, transferSupport.transferDeparturePlan(chain, active, request));
    repairModelSupport.lockRepairStreams(chain);

    MaintenanceRepair leaseOwner = repairModelSupport.repairLifecycleOwner(chain, active);
    if (expectedPlan.leaseRelease() != null) {
      leaseOwner.releaseLease();
    }

    Map<UUID, Long> versions =
        chain.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MaintenanceRepair::getId, MaintenanceRepair::getVersion));
    for (MaintenanceRepair repair : chain) {
      repair.prepareWarehouseTransfer(
          transferId, lineId, request.targetWarehouseId());
    }
    List<MaintenanceRepair> saved = repairs.saveAllAndFlush(chain);
    transferSupport.appendRepairTransferEvents(
        saved, versions, MaintenanceEventType.REPAIR_TRANSFER_PREPARED);
    MaintenanceRepair savedActive = repairModelSupport.requireRepairInChain(saved, active.getId());
    warehouseLifecycle.recordOperation(
        request.sourceWarehouseId(),
        commandSupport.derived(key, "warehouse-transfer-departure"),
        savedActive.getUpdatedAt());
    PrepareTransferRepairResponse response =
        new PrepareTransferRepairResponse(
            savedActive.getId(), savedActive.getVersion(), "REPAIR");
    idempotency.store(
        LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  public TransferRepairArrivalPreflightResponse transferArrivalPreflight(
      UUID transferId, UUID lineId, TransferRepairRequest request) {
    commandSupport.requireNoCallerTransaction("preflight a repair transfer arrival");
    TransferArrivalPlan plan =
        commandSupport.inLocalTransaction(
            "repair transfer arrival routing prepare",
            () -> transferSupport.transferArrivalPlan(transferId, lineId, request));
    if (plan.activeRepairId() == null) {
      return new TransferRepairArrivalPreflightResponse(null, false, List.of());
    }
    TransferRoutingResult routing = transferSupport.resolveTransferArrivalRouting(plan);
    return commandSupport.inLocalTransaction(
        "repair transfer arrival routing finalization",
        () -> {
          TransferArrivalPlan current = transferSupport.transferArrivalPlan(transferId, lineId, request);
          transferSupport.requireMatchingTransferArrivalPlan(plan, current);
          return new TransferRepairArrivalPreflightResponse(
              current.activeRepairId(), true, routing.missingQueueDefinitionIds());
        });
  }

  public CreateResult<CompleteTransferRepairResponse> completeTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID key,
      CompleteTransferRepairRequest request) {
    commandSupport.requireNoCallerTransaction("complete a repair transfer arrival");
    TransferArrivalCompletionPreflight preflight =
        commandSupport.inLocalTransaction(
            "repair transfer arrival preflight",
            () -> completeTransferArrivalPreflight(transferId, lineId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    TransferArrivalPlan plan = preflight.plan();
    if (plan.activeRepairId() == null) {
      return commandSupport.inLocalTransaction(
          "repair transfer arrival empty finalization",
          () -> completeTransferArrivalInTransaction(transferId, lineId, key, request, plan, null));
    }
    warehouseLifecycle.requireIncoming(plan.targetWarehouseId());
    TransferRoutingResult routing = transferSupport.resolveTransferArrivalRouting(plan);
    if (!routing.missingQueueDefinitionIds().isEmpty()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_TARGET_QUEUE_MISSING",
          "The target warehouse is missing queues required by the active repair");
    }
    TransferArrivalRemoteResult remote = transferSupport.completeTransferArrivalRemote(plan, key, routing);
    return commandSupport.inLocalTransaction(
        "repair transfer arrival finalization",
        () -> completeTransferArrivalInTransaction(transferId, lineId, key, request, plan, remote));
  }

  private TransferArrivalCompletionPreflight
      completeTransferArrivalPreflight(
          UUID transferId, UUID lineId, UUID key, CompleteTransferRepairRequest request) {
    String scope = "transfer.complete:" + transferId + ":" + lineId;
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new TransferArrivalCompletionPreflight(
          new CreateResult<>(
              commandSupport.read(replay.get(), CompleteTransferRepairResponse.class), true),
          null);
    }
    TransferArrivalPlan plan =
        transferSupport.transferArrivalPlan(
            transferId,
            lineId,
            request.rentalItemId(),
            request.sourceWarehouseId(),
            request.targetWarehouseId(),
            request.rentalItemVersion());
    if (plan.activeRepairId() != null && request.priority() == null) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_TRANSFER_CONTINUATION_REQUIRED",
          "The receiving employee must select the active repair priority");
    }
    return new TransferArrivalCompletionPreflight(null, plan);
  }

  private CreateResult<CompleteTransferRepairResponse> completeTransferArrivalInTransaction(
      UUID transferId,
      UUID lineId,
      UUID key,
      CompleteTransferRepairRequest request,
      TransferArrivalPlan expectedPlan,
      TransferArrivalRemoteResult remote) {
    String scope = "transfer.complete:" + transferId + ":" + lineId;
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          commandSupport.read(replay.get(), CompleteTransferRepairResponse.class), true);
    }

    if (expectedPlan.activeRepairId() == null) {
      TransferArrivalPlan current =
          transferSupport.transferArrivalPlan(
              transferId,
              lineId,
              request.rentalItemId(),
              request.sourceWarehouseId(),
              request.targetWarehouseId(),
              request.rentalItemVersion());
      transferSupport.requireMatchingTransferArrivalPlan(expectedPlan, current);
      CompleteTransferRepairResponse response =
          new CompleteTransferRepairResponse(
              null, null, request.targetWarehouseId());
      idempotency.store(
          LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
      return new CreateResult<>(response, false);
    }
    List<MaintenanceRepair> observedChain =
        repairs.findAllByTransferLineIdOrderByCreatedAtAscIdAsc(lineId);
    TransferArrivalPlan observed =
        transferSupport.transferArrivalPlan(
            observedChain,
            transferId,
            lineId,
            request.rentalItemId(),
            request.sourceWarehouseId(),
            request.targetWarehouseId(),
            request.rentalItemVersion());
    transferSupport.requireMatchingTransferArrivalPlan(expectedPlan, observed);
    List<MaintenanceRepair> chain =
        repairs.findAllByIdForUpdate(
            observedChain.stream().map(MaintenanceRepair::getId).toList());
    List<MaintenanceRepair> postLockChain =
        repairs.findAllByTransferLineIdOrderByCreatedAtAscIdAsc(lineId);
    TransferArrivalPlan current =
        transferSupport.transferArrivalPlan(
            postLockChain,
            transferId,
            lineId,
            request.rentalItemId(),
            request.sourceWarehouseId(),
            request.targetWarehouseId(),
            request.rentalItemVersion());
    transferSupport.requireMatchingTransferArrivalPlan(expectedPlan, current);
    MaintenanceRepair active = repairModelSupport.requireRepairInChain(chain, expectedPlan.activeRepairId());
    RepairComplexity targetComplexity = expectedPlan.targetComplexity();
    if (request.priority() == null || remote == null) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_TRANSFER_CONTINUATION_REQUIRED",
          "The receiving employee must select the active repair priority");
    }
    transferSupport.validateTransferArrivalRemoteTruth(expectedPlan, remote);
    repairModelSupport.lockRepairStreams(chain);
    Map<UUID, Long> versions =
        chain.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MaintenanceRepair::getId, MaintenanceRepair::getVersion));

    MaintenanceRepair leaseOwner = repairModelSupport.repairLifecycleOwner(chain, active);
    if (targetComplexity == RepairComplexity.CAPITAL) {
      List<RepairStage> externalCapitalStages = new ArrayList<>();
      List<MaintenanceRepair> externalCapitalRepairs = new ArrayList<>();
      for (MaintenanceRepair repair : chain) {
        if (repair.getExecutionState() != RepairExecutionState.QUEUED
            && repair.getExecutionState() != RepairExecutionState.IN_PROGRESS) {
          continue;
        }
        List<RepairStage> stages =
            repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
        stages.forEach(RepairStage::completeAsExternalCapital);
        externalCapitalStages.addAll(stages);
        externalCapitalRepairs.add(repair);
      }
      if (!externalCapitalStages.isEmpty()) {
        repairStages.saveAllAndFlush(externalCapitalStages);
      }
      externalCapitalRepairs.forEach(
          MaintenanceRepair::completeAsExternalCapital);
    }

    for (MaintenanceRepair repair : chain) {
      repair.completeWarehouseTransfer(
          transferId,
          lineId,
          request.targetWarehouseId(),
          request.priority());
      Long taskVersion = remote.relocatedTaskVersions().get(repair.getId());
      if (taskVersion != null) {
        repair.markTaskRelocated(taskVersion);
      }
      repair.confirmRentalItemVersion(remote.asset().version());
    }
    leaseOwner.adoptLeaseAfterTransfer(
        remote.lease().leaseId(),
        remote.lease().version(),
        remote.lease().fencingToken(),
        remote.lease().expiresAt());
    List<MaintenanceRepair> saved = repairs.saveAllAndFlush(chain);
    transferSupport.appendRepairTransferEvents(
        saved, versions, MaintenanceEventType.REPAIR_TRANSFERRED);
    MaintenanceRepair savedActive = repairModelSupport.requireRepairInChain(saved, active.getId());
    warehouseLifecycle.recordOperation(
        request.targetWarehouseId(),
        commandSupport.derived(key, "warehouse-transfer-arrival"),
        savedActive.getUpdatedAt());
    CompleteTransferRepairResponse response =
        new CompleteTransferRepairResponse(
            savedActive.getId(),
            savedActive.getVersion(),
            request.targetWarehouseId());
    idempotency.store(
        LOGISTICS_SERVICE_SUBJECT, scope, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

}
