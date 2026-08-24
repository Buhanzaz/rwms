package dev.buhanzaz.rwms.maintenance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceDomainCoreTest {
  private static final String ACTOR = """
      {"subjectId":"00000000-0000-0000-0000-000000000006","principalType":"USER","profileRevision":null}
      """;

  @Test
  void catalogLifecycleAllowsActiveEditsButKeepsSupersededVersionsImmutable() {
    CatalogVersion catalog = CatalogVersion.draft(
        UUID.randomUUID(), "a".repeat(64), 3, 2, "{}");
    catalog.activate();

    assertThat(catalog.getState()).isEqualTo(CatalogVersionState.ACTIVE);
    catalog.replaceCatalog(4, 3, "{}");
    assertThat(catalog.getNodeCount()).isEqualTo(4);
    assertThat(catalog.getLinkCount()).isEqualTo(3);
    catalog.supersede();
    assertThat(catalog.getState()).isEqualTo(CatalogVersionState.SUPERSEDED);
    assertThatThrownBy(() -> catalog.replaceCatalog(5, 4, "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable");
  }

  @Test
  void completedEstimateIsImmutableAndAmendmentAdvancesExactlyOneRevision() {
    MaintenanceEstimate estimate = MaintenanceEstimate.create(
        UUID.randomUUID(), UUID.randomUUID(), 7, UUID.randomUUID(),
        LocalDate.of(2026, 7, 16), "opaque source", null, ACTOR);
    estimate.complete(UUID.randomUUID());

    assertThatThrownBy(() -> estimate.replaceMetadata(
        LocalDate.of(2026, 7, 17), null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable");
    estimate.replaceCompletedMetadata(
        LocalDate.of(2026, 7, 18), "amended source", null, estimate.getRepairId());
    assertThat(estimate.getRevision()).isEqualTo(2);
    assertThat(estimate.getCompletedAt()).isNotNull();
  }

  @Test
  void reworkDoesNotChangeSourceUntilItIsQueuedAndTerminalDecisionIsAudited() {
    MaintenanceRepair source = MaintenanceRepair.primary(
        UUID.randomUUID(), UUID.randomUUID(), 4, null, RepairOrigin.DIRECT_REPAIR,
        LocalDate.of(2026, 7, 16), "opaque source", ACTOR);
    OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15);
    source.queue(UUID.randomUUID(), 0, 9, expires);
    source.completeForAcceptance();

    MaintenanceRepair child = MaintenanceRepair.rework(source, "local rework reason", ACTOR);
    assertThat(source.getAcceptanceState()).isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(child.getKind()).isEqualTo(RepairKind.REWORK);
    assertThat(child.getReworkReason()).isEqualTo("local rework reason");

    source.enterRework();
    source.returnFromRework();
    source.accept("local decision", ACTOR);
    assertThat(source.getAcceptanceState()).isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertThat(source.getDecisionReason()).isEqualTo("local decision");
    assertThat(source.getDecisionActorRef()).isEqualTo(ACTOR);
  }

  @Test
  void taskBoardOwnsEntryIdAndConfirmedMappingCannotBeReplaced() {
    RepairStage stage = new RepairStage(
        UUID.randomUUID(), UUID.randomUUID(), 0, RepairStageKind.REPAIR_WORK,
        UUID.randomUUID(), "REPAIR", "REPAIR", null);
    assertThat(stage.getExternalQueueEntryId()).isNull();
    stage.queued();
    UUID entryId = UUID.randomUUID();
    stage.confirmTaskBoardRegistration(entryId, 3);

    assertThat(stage.getExternalQueueEntryId()).isEqualTo(entryId);
    assertThat(stage.getTaskGenerationState()).isEqualTo("GENERATED");
    assertThatThrownBy(() -> stage.confirmTaskBoardRegistration(UUID.randomUUID(), 4))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be replaced");

    UUID replacementEntryId = UUID.randomUUID();
    stage.confirmPreStartTaskBoardReplacement(replacementEntryId, 4);
    assertThat(stage.getExternalQueueEntryId()).isEqualTo(replacementEntryId);
    assertThat(stage.getTaskBoardVersion()).isEqualTo(4);
    stage.started(5);
    assertThatThrownBy(
            () ->
                stage.confirmPreStartTaskBoardReplacement(
                    UUID.randomUUID(), 6))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("queued stage");

    RepairStage unconfirmed = new RepairStage(
        UUID.randomUUID(), UUID.randomUUID(), 0, RepairStageKind.REPAIR_WORK,
        UUID.randomUUID(), "REPAIR", "REPAIR", null);
    unconfirmed.queued();
    assertThatThrownBy(
            () ->
                unconfirmed.confirmPreStartTaskBoardReplacement(
                    UUID.randomUUID(), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("confirmed mapping");
  }

  @Test
  void historicalShipmentClosesOrdinaryStageWithoutWorkerEvidenceAndCancelsCapitalStage() {
    RepairStage ordinary = new RepairStage(
        UUID.randomUUID(), UUID.randomUUID(), 0, RepairStageKind.REPAIR_WORK,
        UUID.randomUUID(), "REPAIR", "REPAIR", null);
    RepairStage capital = new RepairStage(
        UUID.randomUUID(), UUID.randomUUID(), 0, RepairStageKind.REPAIR_WORK,
        UUID.randomUUID(), "REPAIR", "REPAIR", null);

    ordinary.closeForHistoricalShipment(false);
    capital.closeForHistoricalShipment(true);

    assertThat(ordinary.getState()).isEqualTo(RepairStageState.DONE);
    assertThat(ordinary.getCompletedEventId()).isNull();
    assertThat(ordinary.getCompletedAt()).isNotNull();
    assertThat(ordinary.getTaskGenerationState()).isEqualTo("NOT_REQUIRED");
    assertThat(capital.getState()).isEqualTo(RepairStageState.CANCELLED);
    assertThat(capital.getCompletedEventId()).isNull();
    assertThat(capital.getCompletedAt()).isNull();
    assertThat(capital.getTaskGenerationState()).isEqualTo("NOT_REQUIRED");
  }

  @Test
  void repairPriorityIsSelectedOnlyBeforeQueueing() {
    MaintenanceRepair repair =
        MaintenanceRepair.primary(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            null,
            RepairOrigin.DIRECT_REPAIR,
            LocalDate.of(2026, 7, 24),
            null,
            ACTOR);

    repair.selectPriority(1);
    assertThat(repair.getPriority()).isEqualTo(1);
    repair.queue(
        UUID.randomUUID(), 0, 1, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));

    assertThatThrownBy(() -> repair.selectPriority(2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before queueing");
  }

  @Test
  void repairLogisticsPlanningIsCanonicalAndImmutableAfterQueueing() {
    MaintenanceRepair repair =
        MaintenanceRepair.primary(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            null,
            RepairOrigin.DIRECT_REPAIR,
            LocalDate.of(2026, 8, 1),
            null,
            ACTOR);
    LocalDate scheduledDate = LocalDate.of(2026, 8, 3);

    repair.selectMovementToRepair(
        true, RepairLogisticsPlanningMode.FIXED_DATE, scheduledDate);

    assertThat(repair.isMovementToRepair()).isTrue();
    assertThat(repair.getLogisticsPlanningMode())
        .isEqualTo(RepairLogisticsPlanningMode.FIXED_DATE);
    assertThat(repair.getLogisticsScheduledDate())
        .isEqualTo(scheduledDate);
    assertThatThrownBy(
            () ->
                repair.selectMovementToRepair(
                    true,
                    RepairLogisticsPlanningMode.AUTO,
                    scheduledDate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inconsistent");
    repair.queue(
        UUID.randomUUID(),
        0,
        1,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
    assertThatThrownBy(
            () ->
                repair.selectMovementToRepair(
                    true,
                    RepairLogisticsPlanningMode.AUTO,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before repair queueing");
  }

  @Test
  void pendingCapitalReclassificationCanReturnToOrdinaryBeforeExternalExecution() {
    MaintenanceRepair repair =
        MaintenanceRepair.primary(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            null,
            RepairOrigin.DIRECT_REPAIR,
            LocalDate.of(2026, 7, 31),
            null,
            ACTOR);
    repair.queue(
        UUID.randomUUID(),
        0,
        1,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
    repair.markReclassifyingCapital();

    assertThat(repair.getReclassificationState())
        .isEqualTo(RepairReclassificationState.RECLASSIFYING_CAPITAL);
    assertThat(repair.stabilizeOrdinaryClassification()).isTrue();
    assertThat(repair.getReclassificationState())
        .isEqualTo(RepairReclassificationState.STABLE);
    assertThat(repair.stabilizeOrdinaryClassification()).isFalse();
  }
}
