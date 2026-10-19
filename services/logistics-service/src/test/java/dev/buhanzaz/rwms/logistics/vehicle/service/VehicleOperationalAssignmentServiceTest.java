package dev.buhanzaz.rwms.logistics.vehicle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanSnapshot;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanWorkflowState;
import dev.buhanzaz.rwms.logistics.domain.TransferReservationReadiness;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignment;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentMode;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentStatus;
import dev.buhanzaz.rwms.logistics.vehicle.repository.VehicleOperationalAssignmentRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Verifies vehicle-lock admission, ordering, placement chaining, and workflow idempotency rules. */
class VehicleOperationalAssignmentServiceTest {
  private static final UUID TRANSFER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SOURCE =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID DESTINATION =
      UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID TRIP_VEHICLE =
      UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID REPOSITIONED_VEHICLE =
      UUID.fromString("10000000-0000-0000-0000-000000000005");
  private static final OffsetDateTime DEPARTURE =
      OffsetDateTime.now(ZoneOffset.UTC)
          .plusDays(2)
          .withHour(8)
          .withMinute(0)
          .withSecond(0)
          .withNano(0);
  private static final OffsetDateTime ARRIVAL = DEPARTURE.plusHours(4);
  private static final Duration BUFFER = Duration.ofMinutes(40);

  @Mock VehicleOperationalAssignmentRepository repository;
  @Mock LogisticsTransactionLock transactionLock;

  private VehicleOperationalAssignmentService service;
  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    service = new VehicleOperationalAssignmentService(repository, transactionLock);
    when(repository.saveAllAndFlush(anyList()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() throws Exception {
    mocks.close();
  }

  @Test
  void createsTripOnlyAndDistinctRepositionAssignmentsUnderVehicleLocks() {
    LogisticsDocument document = document();
    TransferPlanSnapshot plan =
        plan(
            TRIP_VEHICLE,
            new TransferPlanSnapshot.ResourceIntent(
                REPOSITIONED_VEHICLE,
                TransferResourceRepositionMode.PERMANENT,
                null));

    service.createForConfirmedTransfer(document, plan, BUFFER);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<VehicleOperationalAssignment>> created = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAllAndFlush(created.capture());
    assertThat(created.getValue())
        .extracting(VehicleOperationalAssignment::getMode)
        .containsExactlyInAnyOrder(
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            VehicleOperationalAssignmentMode.PERMANENT);
    assertThat(created.getValue())
        .filteredOn(value -> value.getMode() == VehicleOperationalAssignmentMode.TRIP_ONLY)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.getEffectiveFrom()).isEqualTo(ARRIVAL.plus(BUFFER));
              assertThat(value.getEffectiveUntil()).isEqualTo(ARRIVAL.plus(BUFFER));
            });
    verify(transactionLock)
        .acquireAll(
            List.of(
                "vehicle-operational-assignment:" + TRIP_VEHICLE,
                "vehicle-operational-assignment:" + REPOSITIONED_VEHICLE));
  }

  @Test
  void sameVehicleRepositionIntentReplacesTripOnlyReservation() {
    TransferPlanSnapshot plan =
        plan(
            TRIP_VEHICLE,
            new TransferPlanSnapshot.ResourceIntent(
                TRIP_VEHICLE,
                TransferResourceRepositionMode.TEMPORARY,
                ARRIVAL.plusDays(2)));

    service.createForConfirmedTransfer(document(), plan, BUFFER);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<VehicleOperationalAssignment>> created = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAllAndFlush(created.capture());
    assertThat(created.getValue())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.getVehicleId()).isEqualTo(TRIP_VEHICLE);
              assertThat(value.getMode()).isEqualTo(VehicleOperationalAssignmentMode.TEMPORARY);
              assertThat(value.getEffectiveUntil()).isEqualTo(ARRIVAL.plusDays(2));
            });
  }

  @Test
  void rejectsAnOverlappingVehicleReservationAfterTakingItsStableLock() {
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(
            List.of(
                VehicleOperationalAssignment.planned(
                    UUID.randomUUID(),
                    TRIP_VEHICLE,
                    SOURCE,
                    DESTINATION,
                    VehicleOperationalAssignmentMode.TRIP_ONLY,
                    DEPARTURE.minusHours(1),
                    DEPARTURE.plusHours(1),
                    DEPARTURE.plusHours(1),
                    DEPARTURE.minusDays(1))));

    assertThatThrownBy(
            () ->
                service.createForConfirmedTransfer(
                    document(),
                    plan(
                        TRIP_VEHICLE,
                        new TransferPlanSnapshot.ResourceIntent(
                            null, TransferResourceRepositionMode.NONE, null)),
                    BUFFER))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");

    verify(transactionLock)
        .acquireAll(List.of("vehicle-operational-assignment:" + TRIP_VEHICLE));
    verify(repository, never()).saveAllAndFlush(anyList());
  }

  @Test
  void allowsNonOverlappingFutureTripOnlyReservations() {
    VehicleOperationalAssignment earlierTrip =
        VehicleOperationalAssignment.planned(
            UUID.randomUUID(),
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            DEPARTURE.minusHours(4),
            DEPARTURE,
            DEPARTURE,
            DEPARTURE.minusDays(1));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(earlierTrip));

    service.createForConfirmedTransfer(
        document(),
        plan(
            TRIP_VEHICLE,
            new TransferPlanSnapshot.ResourceIntent(
                null, TransferResourceRepositionMode.NONE, null)),
        BUFFER);

    verify(repository).saveAllAndFlush(anyList());
  }

  @Test
  void rejectsNonOverlappingPendingTripsFromDifferentOperationalSources() {
    VehicleOperationalAssignment earlierTrip =
        VehicleOperationalAssignment.planned(
            UUID.randomUUID(),
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            DEPARTURE.minusHours(4),
            DEPARTURE,
            DEPARTURE,
            DEPARTURE.minusDays(1));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(earlierTrip));

    assertThatThrownBy(
            () ->
                service.createForConfirmedTransfer(
                    document(TRANSFER_ID, UUID.randomUUID(), DESTINATION),
                    plan(
                        TRIP_VEHICLE,
                        new TransferPlanSnapshot.ResourceIntent(
                            null, TransferResourceRepositionMode.NONE, null)),
                    BUFFER))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");
    verify(repository, never()).saveAllAndFlush(anyList());
  }

  @Test
  void pendingRepositionBlocksSpeculativeDownstreamUse() {
    VehicleOperationalAssignment pendingReposition =
        VehicleOperationalAssignment.planned(
            UUID.randomUUID(),
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.PERMANENT,
            DEPARTURE.minusHours(4),
            DEPARTURE.minusHours(1),
            null,
            DEPARTURE.minusDays(1));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(pendingReposition));

    assertThatThrownBy(
            () ->
                service.createForConfirmedTransfer(
                    document(),
                    plan(
                        TRIP_VEHICLE,
                        new TransferPlanSnapshot.ResourceIntent(
                            null, TransferResourceRepositionMode.NONE, null)),
                    BUFFER))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");
  }

  @Test
  void cannotStartLaterFutureTripBeforeItsEarlierPlannedCommitment() {
    UUID laterTransferId = UUID.randomUUID();
    VehicleOperationalAssignment earlier =
        VehicleOperationalAssignment.planned(
            TRANSFER_ID,
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            DEPARTURE,
            ARRIVAL.plus(BUFFER),
            ARRIVAL.plus(BUFFER),
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    OffsetDateTime laterDeparture = ARRIVAL.plus(BUFFER).plusMinutes(20);
    OffsetDateTime laterEffectiveFrom = laterDeparture.plusHours(2);
    VehicleOperationalAssignment later =
        VehicleOperationalAssignment.planned(
            laterTransferId,
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            laterDeparture,
            laterEffectiveFrom,
            laterEffectiveFrom,
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    when(repository.findVehicleIdsByTransferId(laterTransferId))
        .thenReturn(List.of(TRIP_VEHICLE));
    when(repository.findAllForUpdateByTransferId(laterTransferId)).thenReturn(List.of(later));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(earlier, later));

    assertThatThrownBy(() -> service.beginTransit(laterTransferId))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");
    assertThat(earlier.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.PLANNED);
    assertThat(later.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.PLANNED);
  }

  @Test
  void delayedEarlierTripBlocksLaterTripAtTransitRecheck() {
    UUID laterTransferId = UUID.randomUUID();
    OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime delayedEffectiveFrom = currentTime.minusHours(1);
    VehicleOperationalAssignment delayed =
        VehicleOperationalAssignment.planned(
            TRANSFER_ID,
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            currentTime.minusHours(3),
            delayedEffectiveFrom,
            delayedEffectiveFrom,
            currentTime.minusDays(1));
    VehicleOperationalAssignment later =
        VehicleOperationalAssignment.planned(
            laterTransferId,
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            DEPARTURE,
            ARRIVAL.plus(BUFFER),
            ARRIVAL.plus(BUFFER),
            DEPARTURE.minusDays(1));
    when(repository.findVehicleIdsByTransferId(laterTransferId))
        .thenReturn(List.of(TRIP_VEHICLE));
    when(repository.findAllForUpdateByTransferId(laterTransferId)).thenReturn(List.of(later));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(delayed, later));

    assertThatThrownBy(() -> service.beginTransit(laterTransferId))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");
    assertThat(later.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.PLANNED);
  }

  @Test
  void delayedEarlierTripCanStillStartBeforeItsLaterPlannedCommitment() {
    OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime earlierEffectiveFrom = currentTime.minusHours(2);
    VehicleOperationalAssignment earlier =
        VehicleOperationalAssignment.planned(
            TRANSFER_ID,
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            currentTime.minusHours(4),
            earlierEffectiveFrom,
            earlierEffectiveFrom,
            currentTime.minusDays(1));
    OffsetDateTime laterEffectiveFrom = currentTime.plusHours(1);
    VehicleOperationalAssignment later =
        VehicleOperationalAssignment.planned(
            UUID.randomUUID(),
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            currentTime.minusHours(1),
            laterEffectiveFrom,
            laterEffectiveFrom,
            currentTime.minusDays(1));
    when(repository.findVehicleIdsByTransferId(TRANSFER_ID))
        .thenReturn(List.of(TRIP_VEHICLE));
    when(repository.findAllForUpdateByTransferId(TRANSFER_ID)).thenReturn(List.of(earlier));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(earlier, later));

    service.beginTransit(TRANSFER_ID);

    assertThat(earlier.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.IN_TRANSIT);
    assertThat(later.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.PLANNED);
  }

  @Test
  void keepsActivePermanentUntilItsRepositionSuccessorActuallyArrives() {
    UUID nextTransferId = UUID.randomUUID();
    UUID nextDestination = UUID.randomUUID();
    OffsetDateTime firstDeparture = DEPARTURE.minusDays(4);
    OffsetDateTime firstEffectiveFrom = DEPARTURE.minusDays(3);
    VehicleOperationalAssignment predecessor =
        VehicleOperationalAssignment.planned(
            TRANSFER_ID,
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.PERMANENT,
            firstDeparture,
            firstEffectiveFrom,
            null,
            firstDeparture.minusDays(1));
    predecessor.beginTransit(firstDeparture.plusMinutes(1));
    predecessor.arrive(firstEffectiveFrom);
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(predecessor));

    service.createForConfirmedTransfer(
        document(nextTransferId, DESTINATION, nextDestination),
        plan(
            TRIP_VEHICLE,
            new TransferPlanSnapshot.ResourceIntent(
                TRIP_VEHICLE, TransferResourceRepositionMode.PERMANENT, null)),
        BUFFER);

    assertThat(predecessor.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.ACTIVE);
    assertThat(predecessor.getEffectiveUntil()).isNull();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<VehicleOperationalAssignment>> created = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAllAndFlush(created.capture());
    VehicleOperationalAssignment successor = created.getValue().getFirst();
    successor.beginTransit(OffsetDateTime.now(ZoneOffset.UTC));
    when(repository.findVehicleIdsByTransferId(nextTransferId)).thenReturn(List.of(TRIP_VEHICLE));
    when(repository.findAllForUpdateByTransferId(nextTransferId)).thenReturn(List.of(successor));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(predecessor, successor));

    service.arrive(nextTransferId);

    assertThat(predecessor.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.COMPLETED);
    assertThat(predecessor.getEffectiveUntil()).isEqualTo(ARRIVAL.plus(BUFFER));
    assertThat(successor.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.ACTIVE);
  }

  @Test
  void tripOnlyArrivalLeavesItsActivePlacementPredecessorUnchanged() {
    UUID tripTransferId = UUID.randomUUID();
    VehicleOperationalAssignment predecessor =
        activePlacement(
            TRANSFER_ID,
            VehicleOperationalAssignmentMode.PERMANENT,
            SOURCE,
            DESTINATION,
            null);
    VehicleOperationalAssignment trip =
        VehicleOperationalAssignment.planned(
            tripTransferId,
            TRIP_VEHICLE,
            DESTINATION,
            UUID.randomUUID(),
            VehicleOperationalAssignmentMode.TRIP_ONLY,
            DEPARTURE,
            ARRIVAL.plus(BUFFER),
            ARRIVAL.plus(BUFFER),
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    trip.beginTransit(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    when(repository.findVehicleIdsByTransferId(tripTransferId)).thenReturn(List.of(TRIP_VEHICLE));
    when(repository.findAllForUpdateByTransferId(tripTransferId)).thenReturn(List.of(trip));

    service.arrive(tripTransferId);

    assertThat(trip.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.COMPLETED);
    assertThat(predecessor.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.ACTIVE);
    assertThat(predecessor.getEffectiveUntil()).isNull();
  }

  @Test
  void expiredTemporaryResolvesToItsSourceAndKeepsItsEarlierEndWhenRepositioned() {
    UUID nextTransferId = UUID.randomUUID();
    OffsetDateTime originalEnd = DEPARTURE.minusHours(1);
    VehicleOperationalAssignment predecessor =
        activePlacement(
            TRANSFER_ID,
            VehicleOperationalAssignmentMode.TEMPORARY,
            SOURCE,
            DESTINATION,
            originalEnd);
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(predecessor));

    service.createForConfirmedTransfer(
        document(nextTransferId, SOURCE, UUID.randomUUID()),
        plan(
            TRIP_VEHICLE,
            new TransferPlanSnapshot.ResourceIntent(
                TRIP_VEHICLE, TransferResourceRepositionMode.PERMANENT, null)),
        BUFFER);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<VehicleOperationalAssignment>> created = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAllAndFlush(created.capture());
    VehicleOperationalAssignment successor = created.getValue().getFirst();
    successor.beginTransit(OffsetDateTime.now(ZoneOffset.UTC));
    when(repository.findVehicleIdsByTransferId(nextTransferId)).thenReturn(List.of(TRIP_VEHICLE));
    when(repository.findAllForUpdateByTransferId(nextTransferId)).thenReturn(List.of(successor));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(predecessor, successor));

    service.arrive(nextTransferId);

    assertThat(predecessor.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.COMPLETED);
    assertThat(predecessor.getEffectiveUntil()).isEqualTo(originalEnd);
    assertThat(successor.getStatus()).isEqualTo(VehicleOperationalAssignmentStatus.ACTIVE);
  }

  @Test
  void rejectsPermanentSuccessorFromAnyWarehouseExceptCurrentDestination() {
    UUID nextTransferId = UUID.randomUUID();
    VehicleOperationalAssignment predecessor =
        VehicleOperationalAssignment.planned(
            TRANSFER_ID,
            TRIP_VEHICLE,
            SOURCE,
            DESTINATION,
            VehicleOperationalAssignmentMode.PERMANENT,
            DEPARTURE.minusDays(4),
            DEPARTURE.minusDays(3),
            null,
            DEPARTURE.minusDays(5));
    predecessor.beginTransit(DEPARTURE.minusDays(4).plusMinutes(1));
    predecessor.arrive(DEPARTURE.minusDays(3));
    when(repository.findAllForUpdateByVehicleId(TRIP_VEHICLE))
        .thenReturn(List.of(predecessor));

    assertThatThrownBy(
            () ->
                service.createForConfirmedTransfer(
                    document(nextTransferId, SOURCE, UUID.randomUUID()),
                    plan(
                        TRIP_VEHICLE,
                        new TransferPlanSnapshot.ResourceIntent(
                            TRIP_VEHICLE, TransferResourceRepositionMode.PERMANENT, null)),
                    BUFFER))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");

    assertThat(predecessor.getEffectiveUntil()).isNull();
  }

  @Test
  void rejectsEmptyOrReversedPlanningWindowsBeforeQuerying() {
    assertThatThrownBy(() -> service.findPlanningWindow(SOURCE, ARRIVAL, ARRIVAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("windowStart must be before windowEnd");
    assertThatThrownBy(() -> service.findPlanningWindow(SOURCE, ARRIVAL, DEPARTURE))
        .isInstanceOf(IllegalArgumentException.class);

    verify(repository, never())
        .findLiveVehicleChainBefore(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  private static LogisticsDocument document() {
    return document(TRANSFER_ID, SOURCE, DESTINATION);
  }

  private static LogisticsDocument document(
      UUID transferId, UUID sourceWarehouseId, UUID destinationWarehouseId) {
    LogisticsDocument document = Mockito.mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(transferId);
    when(document.getWarehouseId()).thenReturn(sourceWarehouseId);
    when(document.getDestinationWarehouseId()).thenReturn(destinationWarehouseId);
    return document;
  }

  private static TransferPlanSnapshot plan(
      UUID tripVehicleId, TransferPlanSnapshot.ResourceIntent vehicleReposition) {
    return new TransferPlanSnapshot(
        UUID.randomUUID(),
        0,
        TransferPlanState.CONFIRMED,
        TransferReservationReadiness.RESERVING,
        TransferPlanWorkflowState.RESERVING,
        null,
        DEPARTURE,
        ARRIVAL,
        null,
        null,
        tripVehicleId,
        new TransferPlanSnapshot.ResourceIntent(null, TransferResourceRepositionMode.NONE, null),
        vehicleReposition,
        new TransferPlanSnapshot.Assignment(null, null, null),
        new TransferPlanSnapshot.Assignment(null, null, null),
        List.of(),
        List.of(),
        0,
        0);
  }

  private static VehicleOperationalAssignment activePlacement(
      UUID transferId,
      VehicleOperationalAssignmentMode mode,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      OffsetDateTime effectiveUntil) {
    OffsetDateTime travelStartsAt = DEPARTURE.minusDays(4);
    OffsetDateTime effectiveFrom = DEPARTURE.minusDays(3);
    VehicleOperationalAssignment assignment =
        VehicleOperationalAssignment.planned(
            transferId,
            TRIP_VEHICLE,
            sourceWarehouseId,
            destinationWarehouseId,
            mode,
            travelStartsAt,
            effectiveFrom,
            effectiveUntil,
            travelStartsAt.minusDays(1));
    assignment.beginTransit(travelStartsAt.plusMinutes(1));
    assignment.arrive(effectiveFrom);
    return assignment;
  }
}
