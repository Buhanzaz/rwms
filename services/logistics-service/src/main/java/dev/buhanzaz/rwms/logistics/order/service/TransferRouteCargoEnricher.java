package dev.buhanzaz.rwms.logistics.order.service;

import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftPlanRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationKind;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationRequest;

import dev.buhanzaz.rwms.logistics.domain.TransferPlan;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanWorkflowState;
import dev.buhanzaz.rwms.logistics.domain.TransferReservationReadiness;
import dev.buhanzaz.rwms.logistics.repository.TransferPlanRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds logistics-owned, already reserved transfer cargo to an immutable planner positioning leg.
 *
 * <p>The standalone optimizer remains side-effect free and cannot name transfer cargo. This owner
 * matches an exact source, destination, driver, vehicle and departure/arrival pair, then inserts
 * balanced load/unload operations without changing inventory or transfer state.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TransferRouteCargoEnricher {
  private final TransferPlanRepository transferPlans;

  /** Returns an equivalent command whose cross-warehouse shifts include matching ready cargo. */
  ApplyPlanningAssignmentsRequest enrich(ApplyPlanningAssignmentsRequest request) {
    List<PlanningDriverShiftPlanRequest> enriched =
        request.driverShiftPlans().stream().map(this::enrich).toList();
    return new ApplyPlanningAssignmentsRequest(
        request.warehouseId(),
        request.planId(),
        request.planVersion(),
        request.assignments(),
        enriched);
  }

  private PlanningDriverShiftPlanRequest enrich(PlanningDriverShiftPlanRequest plan) {
    if (plan.operations().stream().anyMatch(TransferRouteCargoEnricher::isTransferOperation)) {
      throw new OrderProblemException(
          HttpStatus.BAD_REQUEST,
          "TRANSFER_ROUTE_OPERATION_NOT_OWNER_GENERATED",
          "Межскладской груз в маршрут добавляет только RWMS после проверки резервов");
    }
    int inboundIndex = indexOf(plan.operations(), PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING);
    if (inboundIndex < 1) return plan;
    PlanningDriverShiftRouteOperationRequest origin = plan.operations().getFirst();
    PlanningDriverShiftRouteOperationRequest inbound = plan.operations().get(inboundIndex);
    UUID sourceWarehouseId = origin.warehouseId();
    UUID destinationWarehouseId = inbound.warehouseId();
    if (sourceWarehouseId == null
        || destinationWarehouseId == null
        || sourceWarehouseId.equals(destinationWarehouseId)) {
      return plan;
    }
    List<TransferPlan> cargo =
        transferPlans.findRouteReadyCargo(
            TransferPlanState.CONFIRMED,
            TransferReservationReadiness.RESERVED,
            TransferPlanWorkflowState.READY,
            plan.driverId(),
            plan.vehicle().id(),
            inbound.plannedDeparture(),
            inbound.plannedArrival(),
            sourceWarehouseId,
            destinationWarehouseId);
    if (cargo.isEmpty()) return plan;

    int cabinCount = 0;
    for (TransferPlan transfer : cargo) {
      cabinCount = Math.addExact(cabinCount, transfer.totalCabinCount());
    }
    Integer capacity = plan.vehicle().cabinCapacity();
    if (capacity == null) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "TRANSFER_ROUTE_CAPACITY_MISSING",
          "Для попутного перемещения не определена вместимость выбранного автомобиля");
    }
    if (cabinCount > capacity) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "TRANSFER_ROUTE_CAPACITY_EXCEEDED",
          "Попутный межскладской груз превышает вместимость выбранного автомобиля");
    }

    List<PlanningDriverShiftRouteOperationRequest> operations = new ArrayList<>();
    operations.add(origin);
    int load = origin.loadAfter();
    for (TransferPlan transfer : cargo) {
      int nextLoad = Math.addExact(load, transfer.totalCabinCount());
      operations.add(
          transferOperation(
              PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD,
              sourceWarehouseId,
              transfer.getDocument().getId(),
              origin.locationLabel(),
              inbound.plannedDeparture(),
              load,
              nextLoad));
      load = nextLoad;
    }
    operations.add(
        copyOperation(
            inbound,
            inbound.sequence(),
            load,
            load));
    for (TransferPlan transfer : cargo) {
      int nextLoad = Math.subtractExact(load, transfer.totalCabinCount());
      operations.add(
          transferOperation(
              PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD,
              destinationWarehouseId,
              transfer.getDocument().getId(),
              inbound.locationLabel(),
              inbound.plannedArrival(),
              load,
              nextLoad));
      load = nextLoad;
    }
    if (load != origin.loadBefore()) {
      throw new IllegalStateException("Transfer route load did not return to its planner baseline");
    }
    operations.addAll(plan.operations().subList(inboundIndex + 1, plan.operations().size()));
    List<PlanningDriverShiftRouteOperationRequest> resequenced =
        java.util.stream.IntStream.range(0, operations.size())
            .mapToObj(index -> copyOperation(operations.get(index), index + 1))
            .toList();
    return new PlanningDriverShiftPlanRequest(
        plan.sourceShiftId(),
        plan.sourcePlanId(),
        plan.sourcePlanVersion(),
        plan.warehouseId(),
        plan.routeOriginWarehouseId(),
        plan.supportWarehouseLinkId(),
        plan.driverId(),
        plan.driverName(),
        plan.workDate(),
        plan.vehicle(),
        plan.trailer(),
        plan.tripCount(),
        plan.routeDistanceMeters(),
        resequenced);
  }

  private static PlanningDriverShiftRouteOperationRequest transferOperation(
      PlanningDriverShiftRouteOperationKind kind,
      UUID warehouseId,
      UUID transferId,
      String label,
      java.time.OffsetDateTime at,
      int loadBefore,
      int loadAfter) {
    return new PlanningDriverShiftRouteOperationRequest(
        1,
        kind,
        warehouseId,
        null,
        transferId,
        label,
        at,
        at,
        loadBefore,
        loadAfter);
  }

  private static PlanningDriverShiftRouteOperationRequest copyOperation(
      PlanningDriverShiftRouteOperationRequest source, int sequence) {
    return copyOperation(source, sequence, source.loadBefore(), source.loadAfter());
  }

  private static PlanningDriverShiftRouteOperationRequest copyOperation(
      PlanningDriverShiftRouteOperationRequest source,
      int sequence,
      int loadBefore,
      int loadAfter) {
    return new PlanningDriverShiftRouteOperationRequest(
        sequence,
        source.kind(),
        source.warehouseId(),
        source.sourceTaskId(),
        source.sourceTransferId(),
        source.locationLabel(),
        source.plannedArrival(),
        source.plannedDeparture(),
        loadBefore,
        loadAfter);
  }

  private static int indexOf(
      List<PlanningDriverShiftRouteOperationRequest> operations,
      PlanningDriverShiftRouteOperationKind kind) {
    for (int index = 0; index < operations.size(); index++) {
      if (operations.get(index).kind() == kind) return index;
    }
    return -1;
  }

  private static boolean isTransferOperation(PlanningDriverShiftRouteOperationRequest operation) {
    return operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD
        || operation.kind() == PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD;
  }
}
