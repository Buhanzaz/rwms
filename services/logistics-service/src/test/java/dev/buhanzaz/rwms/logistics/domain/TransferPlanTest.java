package dev.buhanzaz.rwms.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Focused invariant tests for catalog cargo requirements and concrete allocation cardinality. */
class TransferPlanTest {
  private static final UUID ORIGIN = UUID.randomUUID();
  private static final UUID DESTINATION = UUID.randomUUID();
  private static final UUID TYPE = UUID.randomUUID();
  private static final UUID FURNITURE = UUID.randomUUID();

  @Test
  void calculatesPerCabinAndLooseFurnitureWithoutDoubleCountingEitherScope() {
    TransferPlan plan =
        TransferPlan.draft(
            transfer(),
            new TransferPlanDraft(
                departure(),
                departure().plusHours(4),
                null,
                null,
                null,
                none(),
                none(),
                List.of(
                    new TransferPlanDraft.CargoGroup(
                        TYPE,
                        null,
                        null,
                        List.of(),
                        true,
                        2,
                        List.of(new TransferPlanDraft.Furniture(FURNITURE, 4)),
                        List.of())),
                List.of(new TransferPlanDraft.LooseFurniture(FURNITURE, 3))));

    TransferPlanSnapshot snapshot = plan.snapshot();
    assertThat(snapshot.totalCabinCount()).isEqualTo(2);
    assertThat(snapshot.auditLineCount()).isEqualTo(3);
    assertThat(snapshot.cabinGroups().getFirst().furniturePerCabin().getFirst().totalQuantity())
        .isEqualTo(8);
    assertThat(snapshot.looseFurniture().getFirst().quantity()).isEqualTo(3);
  }

  @Test
  void draftMayRemainUnallocatedButConfirmationRequiresExactCardinality() {
    TransferPlan plan =
        TransferPlan.draft(
            transfer(),
            new TransferPlanDraft(
                departure(),
                departure().plusHours(4),
                null,
                null,
                null,
                none(),
                none(),
                List.of(
                    new TransferPlanDraft.CargoGroup(
                        TYPE, null, null, List.of(), null, 2, List.of(), List.of())),
                List.of()));

    assertThat(plan.getState()).isEqualTo(TransferPlanState.DRAFT);
    assertThatThrownBy(plan::confirm)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Allocated cabin count");
  }

  @Test
  void samePhysicalCabinCannotBeAllocatedAcrossGroups() {
    UUID asset = UUID.randomUUID();
    TransferPlanDraft.Allocation allocation = new TransferPlanDraft.Allocation(asset, 4);
    assertThatThrownBy(
            () ->
                TransferPlan.draft(
                    transfer(),
                    new TransferPlanDraft(
                        departure(),
                        departure().plusHours(4),
                        null,
                        null,
                        null,
                        none(),
                        none(),
                        List.of(
                            new TransferPlanDraft.CargoGroup(
                                TYPE,
                                null,
                                null,
                                List.of(),
                                null,
                                1,
                                List.of(),
                                List.of(allocation)),
                            new TransferPlanDraft.CargoGroup(
                                UUID.randomUUID(),
                                null,
                                null,
                                List.of(),
                                null,
                                1,
                                List.of(),
                                List.of(allocation))),
                        List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicated");
  }

  @Test
  void advancesFurnitureOnlyCargoThroughReservationTransitAndIdempotentCompletion() {
    UUID sourceBalance = UUID.randomUUID();
    UUID reservation = UUID.randomUUID();
    TransferPlan plan = furnitureOnlyPlan();

    plan.confirm();
    plan.freezeLooseFurnitureSource(1, sourceBalance, 3);
    plan.attachLooseFurnitureReservation(1, reservation, 4, sourceBalance);
    plan.markReserved();
    plan.markInTransit();
    plan.markInTransit();

    assertThat(plan.snapshot().workflowState()).isEqualTo(TransferPlanWorkflowState.IN_TRANSIT);
    assertThat(plan.snapshot().looseFurniture().getFirst().state())
        .isEqualTo(TransferLooseFurnitureState.IN_TRANSIT);

    plan.beginCompletion();
    plan.executeLooseFurniture(1, 5);
    plan.completeWorkflow();
    plan.beginCompletion();
    plan.completeWorkflow();

    assertThat(plan.snapshot().workflowState()).isEqualTo(TransferPlanWorkflowState.COMPLETED);
    assertThat(plan.snapshot().looseFurniture().getFirst().state())
        .isEqualTo(TransferLooseFurnitureState.EXECUTED);
  }

  @Test
  void releasesFurnitureReservationBeforeDepartureAndRejectsImplicitReturnAfterDeparture() {
    UUID sourceBalance = UUID.randomUUID();
    TransferPlan plan = furnitureOnlyPlan();
    plan.confirm();
    plan.freezeLooseFurnitureSource(1, sourceBalance, 7);
    plan.attachLooseFurnitureReservation(1, UUID.randomUUID(), 8, sourceBalance);
    plan.markReserved();

    plan.beginRelease();
    plan.releaseLooseFurniture(1, 9);
    plan.finishRelease();
    plan.finishRelease();

    assertThat(plan.snapshot().reservationReadiness())
        .isEqualTo(TransferReservationReadiness.RELEASED);
    assertThat(plan.snapshot().looseFurniture().getFirst().state())
        .isEqualTo(TransferLooseFurnitureState.RELEASED);

    TransferPlan travelling = furnitureOnlyPlan();
    travelling.confirm();
    travelling.freezeLooseFurnitureSource(1, sourceBalance, 10);
    travelling.attachLooseFurnitureReservation(1, UUID.randomUUID(), 11, sourceBalance);
    travelling.markReserved();
    travelling.markInTransit();

    assertThatThrownBy(travelling::beginRelease)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit destination decision");
  }

  private static LogisticsDocument transfer() {
    return LogisticsDocument.createTransfer(
        ORIGIN, DESTINATION, LocalDate.now(ZoneOffset.UTC), UUID.randomUUID(), UUID.randomUUID());
  }

  private static OffsetDateTime departure() {
    return OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
  }

  private static TransferPlanDraft.ResourceIntent none() {
    return new TransferPlanDraft.ResourceIntent(null, TransferResourceRepositionMode.NONE, null);
  }

  private static TransferPlan furnitureOnlyPlan() {
    return TransferPlan.draft(
        transfer(),
        new TransferPlanDraft(
            departure(),
            departure().plusHours(4),
            null,
            null,
            null,
            none(),
            none(),
            List.of(),
            List.of(new TransferPlanDraft.LooseFurniture(FURNITURE, 3))));
  }
}
