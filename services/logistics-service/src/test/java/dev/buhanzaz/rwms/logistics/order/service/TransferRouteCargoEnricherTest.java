package dev.buhanzaz.rwms.logistics.order.service;

import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftPlanRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationKind;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleConfiguration;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.TransferPlan;
import dev.buhanzaz.rwms.logistics.repository.TransferPlanRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies owner-side insertion of reserved transfer cargo into one physical route. */
class TransferRouteCargoEnricherTest {
  private final TransferPlanRepository transferPlans = mock(TransferPlanRepository.class);
  private final TransferRouteCargoEnricher enricher = new TransferRouteCargoEnricher(transferPlans);

  @Test
  void insertsBalancedTransferLoadAndUnloadAroundInboundPositioning() {
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    UUID driverId = UUID.randomUUID();
    UUID vehicleId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 0, ZoneOffset.ofHours(3));
    OffsetDateTime arrival = departure.plusHours(4);
    TransferPlan transfer = mock(TransferPlan.class);
    LogisticsDocument transferDocument = mock(LogisticsDocument.class);
    when(transfer.totalCabinCount()).thenReturn(2);
    when(transfer.getDocument()).thenReturn(transferDocument);
    when(transferDocument.getId()).thenReturn(transferId);
    when(transferPlans.findRouteReadyCargo(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(transfer));
    ApplyPlanningAssignmentsRequest request =
        routeRequest(
            sourceWarehouseId,
            destinationWarehouseId,
            driverId,
            vehicleId,
            departure,
            arrival);

    PlanningDriverShiftPlanRequest enriched =
        enricher.enrich(request).driverShiftPlans().getFirst();

    assertThat(enriched.operations())
        .extracting(PlanningDriverShiftRouteOperationRequest::kind)
        .containsExactly(
            PlanningDriverShiftRouteOperationKind.ORIGIN_START,
            PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD,
            PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING,
            PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD,
            PlanningDriverShiftRouteOperationKind.DEPOT_LOAD);
    assertThat(enriched.operations().get(1).sourceTransferId()).isEqualTo(transferId);
    assertThat(enriched.operations().get(1).loadAfter()).isEqualTo(2);
    assertThat(enriched.operations().get(2).loadBefore()).isEqualTo(2);
    assertThat(enriched.operations().get(3).loadAfter()).isZero();
    assertThat(enriched.operations())
        .extracting(PlanningDriverShiftRouteOperationRequest::sequence)
        .containsExactly(1, 2, 3, 4, 5);
  }

  @Test
  void keepsFurnitureOnlyTransferActionsWithoutChangingTheCabinLoad() {
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 0, ZoneOffset.ofHours(3));
    OffsetDateTime arrival = departure.plusHours(4);
    TransferPlan transfer = mock(TransferPlan.class);
    LogisticsDocument transferDocument = mock(LogisticsDocument.class);
    when(transfer.totalCabinCount()).thenReturn(0);
    when(transfer.getDocument()).thenReturn(transferDocument);
    when(transferDocument.getId()).thenReturn(transferId);
    when(transferPlans.findRouteReadyCargo(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(transfer));

    PlanningDriverShiftPlanRequest enriched =
        enricher
            .enrich(
                routeRequest(
                    sourceWarehouseId,
                    destinationWarehouseId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    departure,
                    arrival))
            .driverShiftPlans()
            .getFirst();

    assertThat(enriched.operations())
        .extracting(
            PlanningDriverShiftRouteOperationRequest::kind,
            PlanningDriverShiftRouteOperationRequest::loadBefore,
            PlanningDriverShiftRouteOperationRequest::loadAfter)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                PlanningDriverShiftRouteOperationKind.ORIGIN_START, 0, 0),
            org.assertj.core.groups.Tuple.tuple(
                PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD, 0, 0),
            org.assertj.core.groups.Tuple.tuple(
                PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING, 0, 0),
            org.assertj.core.groups.Tuple.tuple(
                PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD, 0, 0),
            org.assertj.core.groups.Tuple.tuple(
                PlanningDriverShiftRouteOperationKind.DEPOT_LOAD, 0, 0));
    assertThat(enriched.operations().get(1).sourceTransferId()).isEqualTo(transferId);
    assertThat(enriched.operations().get(3).sourceTransferId()).isEqualTo(transferId);
  }

  @Test
  void rejectsPlannerSuppliedTransferIdentityBeforeRepositoryLookup() {
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 0, ZoneOffset.ofHours(3));
    ApplyPlanningAssignmentsRequest source =
        routeRequest(
            sourceWarehouseId,
            destinationWarehouseId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            departure,
            departure.plusHours(4));
    PlanningDriverShiftPlanRequest plan = source.driverShiftPlans().getFirst();
    PlanningDriverShiftRouteOperationRequest injected =
        new PlanningDriverShiftRouteOperationRequest(
            1,
            PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD,
            sourceWarehouseId,
            null,
            UUID.randomUUID(),
            "Недоверенная погрузка",
            departure,
            departure,
            0,
            1);
    PlanningDriverShiftPlanRequest injectedPlan = copyPlan(plan, List.of(injected));

    assertThatThrownBy(
            () ->
                enricher.enrich(
                    new ApplyPlanningAssignmentsRequest(
                        source.warehouseId(),
                        source.planId(),
                        source.planVersion(),
                        source.assignments(),
                        List.of(injectedPlan))))
        .isInstanceOf(OrderProblemException.class)
        .hasMessageContaining("только RWMS");
  }

  @Test
  void rejectsMatchedCabinCargoAboveTheExactVehicleCapacity() {
    TransferPlan transfer = mock(TransferPlan.class);
    LogisticsDocument transferDocument = mock(LogisticsDocument.class);
    when(transfer.totalCabinCount()).thenReturn(2);
    when(transfer.getDocument()).thenReturn(transferDocument);
    when(transferDocument.getId()).thenReturn(UUID.randomUUID());
    when(transferPlans.findRouteReadyCargo(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(transfer));
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 0, ZoneOffset.ofHours(3));

    assertThatThrownBy(
            () ->
                enricher.enrich(
                    routeRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        departure,
                        departure.plusHours(4),
                        1)))
        .isInstanceOf(OrderProblemException.class)
        .hasMessageContaining("превышает вместимость");
  }

  private static ApplyPlanningAssignmentsRequest routeRequest(
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      UUID driverId,
      UUID vehicleId,
      OffsetDateTime departure,
      OffsetDateTime arrival) {
    return routeRequest(
        sourceWarehouseId,
        destinationWarehouseId,
        driverId,
        vehicleId,
        departure,
        arrival,
        2);
  }

  private static ApplyPlanningAssignmentsRequest routeRequest(
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      UUID driverId,
      UUID vehicleId,
      OffsetDateTime departure,
      OffsetDateTime arrival,
      int cabinCapacity) {
    PlanningDriverShiftVehicleRequest vehicle =
        new PlanningDriverShiftVehicleRequest(
            vehicleId,
            "SPB-04",
            "А001АА78",
            "TRUCK",
            "КАМАЗ",
            "65117",
            PlanningDriverShiftVehicleConfiguration.TRUCK_WITH_TRAILER,
            cabinCapacity,
            null);
    List<PlanningDriverShiftRouteOperationRequest> operations =
        List.of(
            operation(
                1,
                PlanningDriverShiftRouteOperationKind.ORIGIN_START,
                sourceWarehouseId,
                "Санкт-Петербург",
                departure,
                departure),
            operation(
                2,
                PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING,
                destinationWarehouseId,
                "Региональный склад",
                arrival,
                departure),
            operation(
                3,
                PlanningDriverShiftRouteOperationKind.DEPOT_LOAD,
                destinationWarehouseId,
                "Региональный склад",
                arrival.plusMinutes(30),
                arrival.plusMinutes(50)));
    PlanningDriverShiftPlanRequest plan =
        new PlanningDriverShiftPlanRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            destinationWarehouseId,
            sourceWarehouseId,
            UUID.randomUUID(),
            driverId,
            "Петров Алексей",
            LocalDate.of(2026, 9, 14),
            vehicle,
            null,
            1,
            400_000,
            operations);
    return new ApplyPlanningAssignmentsRequest(
        sourceWarehouseId, UUID.randomUUID(), 1L, List.of(), List.of(plan));
  }

  private static PlanningDriverShiftPlanRequest copyPlan(
      PlanningDriverShiftPlanRequest source,
      List<PlanningDriverShiftRouteOperationRequest> operations) {
    return new PlanningDriverShiftPlanRequest(
        source.sourceShiftId(),
        source.sourcePlanId(),
        source.sourcePlanVersion(),
        source.warehouseId(),
        source.routeOriginWarehouseId(),
        source.supportWarehouseLinkId(),
        source.driverId(),
        source.driverName(),
        source.workDate(),
        source.vehicle(),
        source.trailer(),
        source.tripCount(),
        source.routeDistanceMeters(),
        operations);
  }

  private static PlanningDriverShiftRouteOperationRequest operation(
      int sequence,
      PlanningDriverShiftRouteOperationKind kind,
      UUID warehouseId,
      String label,
      OffsetDateTime arrival,
      OffsetDateTime departure) {
    return new PlanningDriverShiftRouteOperationRequest(
        sequence, kind, warehouseId, null, null, label, arrival, departure, 0, 0);
  }
}
