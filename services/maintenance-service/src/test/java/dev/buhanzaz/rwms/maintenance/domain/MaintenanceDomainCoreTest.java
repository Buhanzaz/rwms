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
  void catalogLifecycleMakesPublishedVersionsImmutable() {
    CatalogVersion catalog = CatalogVersion.draft(
        UUID.randomUUID(), "a".repeat(64), 3, 2, "{}");
    catalog.activate();

    assertThat(catalog.getState()).isEqualTo(CatalogVersionState.ACTIVE);
    assertThatThrownBy(() -> catalog.replaceDraft(4, 3, "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable");
    catalog.supersede();
    assertThat(catalog.getState()).isEqualTo(CatalogVersionState.SUPERSEDED);
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
  }
}
