package dev.buhanzaz.rwms.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryDomainStateMachineTest {
  private static final String ACTOR =
      """
      {"subjectId":"00000000-0000-0000-0000-000000000701","principalType":"USER","profileRevision":null}
      """;

  @Test
  void sessionHasOnlyActiveToTerminalTransitions() {
    InventorySession session = session();

    session.cancel("reviewed", ACTOR);

    assertThat(session.getLifecycle()).isEqualTo(SessionLifecycle.CANCELLED);
    assertThatThrownBy(
            () ->
                session.complete(
                    "1".repeat(64), "2".repeat(64), OffsetDateTime.now(ZoneOffset.UTC), ACTOR))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void findingKeepsObservationPresenceAndRequiresFrozenWorkPlan() {
    UUID warehouseId = UUID.randomUUID();
    InventoryFinding finding =
        InventoryFinding.unexpected(
            UUID.randomUUID(),
            FindingOrigin.ADDED_NEW,
            UUID.randomUUID(),
            1L,
            warehouseId,
            "FREE",
            "Арендатор А",
            "AB-12",
            "AB12",
            ReconciliationState.CONFLICT,
            ACTOR);

    assertThatThrownBy(
            () ->
                finding.saveInspection(
                    InspectionState.WORK_STAGED,
                    ReconciliationState.MATCHED,
                    ObservationPresence.EXPLICIT_EMPTY,
                    "{}",
                    ObservationPresence.ABSENT,
                    null,
                    null,
                    ACTOR))
        .isInstanceOf(IllegalArgumentException.class);

    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.EXPLICIT_EMPTY,
        "{}",
        ObservationPresence.ABSENT,
        null,
        null,
        "Осмотрена",
        ACTOR);
    assertThat(finding.getPassportObservationState()).isEqualTo(ObservationPresence.EXPLICIT_EMPTY);
    assertThat(finding.getPassportObservation()).isEqualTo("{}");
    assertThat(finding.getEquipmentObservationState()).isEqualTo(ObservationPresence.ABSENT);
    assertThat(finding.getCurrentWarehouseId()).isEqualTo(warehouseId);
    assertThat(finding.getCurrentStatus()).isEqualTo("FREE");
    assertThat(finding.getCurrentTenantSnapshot()).isEqualTo("Арендатор А");
    assertThat(finding.getInspectionComment()).isEqualTo("Осмотрена");
  }

  @Test
  void findingOwnerProofRevisionChangesOnlyWhenLifecyclePayloadChanges() {
    InventoryFinding finding =
        InventoryFinding.unexpected(
            UUID.randomUUID(),
            FindingOrigin.ADDED_USED,
            null,
            null,
            "AB-12",
            "AB12",
            ReconciliationState.CONFLICT,
            ACTOR);

    assertThat(finding.isOwnerProofActive()).isTrue();
    assertThat(finding.getOwnerProofRevision()).isZero();
    assertThat(finding.transitionOwnerProof(true)).isFalse();
    assertThat(finding.getOwnerProofRevision()).isZero();

    assertThat(finding.transitionOwnerProof(false)).isTrue();
    assertThat(finding.isOwnerProofActive()).isFalse();
    assertThat(finding.getOwnerProofRevision()).isOne();
    assertThat(finding.transitionOwnerProof(false)).isFalse();
    assertThat(finding.getOwnerProofRevision()).isOne();

    assertThat(finding.transitionOwnerProof(true)).isTrue();
    assertThat(finding.isOwnerProofActive()).isTrue();
    assertThat(finding.getOwnerProofRevision()).isEqualTo(2);
  }

  @Test
  void conflictResolutionKeepsOrRebasesTheInspectionRegistrySnapshotExplicitly() {
    UUID warehouseId = UUID.randomUUID();
    InventoryFinding finding =
        InventoryFinding.unexpected(
            UUID.randomUUID(),
            FindingOrigin.UNEXPECTED_EXISTING,
            UUID.randomUUID(),
            6L,
            warehouseId,
            "FREE",
            null,
            "БЫТ-001",
            "БЫТ001",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.EXPLICIT_EMPTY,
        "{}",
        ObservationPresence.EXPLICIT_EMPTY,
        "[]",
        null,
        "Исходный осмотр",
        ACTOR);
    finding.refreshCurrentAsset(
        8L,
        warehouseId,
        "REPAIR",
        null,
        "БЫТ-001",
        "{\"finish\":\"OSB\"}",
        "[]",
        "[]",
        ReconciliationState.CONFLICT);

    assertThatThrownBy(
            () ->
                finding.resolveConflict(
                    ConflictResolutionStrategy.KEEP_INSPECTION,
                    "1".repeat(64),
                    " ",
                    ACTOR))
        .isInstanceOf(IllegalArgumentException.class);

    finding.resolveConflict(
        ConflictResolutionStrategy.KEEP_INSPECTION,
        "1".repeat(64),
        "Осмотр точнее реестра",
        ACTOR);
    assertThat(finding.getInspectionStatus()).isEqualTo("FREE");
    assertThat(finding.getCurrentStatus()).isEqualTo("REPAIR");
    assertThat(finding.getConflictResolutionStrategy())
        .isEqualTo(ConflictResolutionStrategy.KEEP_INSPECTION);
    assertThat(finding.getConflictResolutionReason()).isEqualTo("Осмотр точнее реестра");

    finding.resolveConflict(
        ConflictResolutionStrategy.ACCEPT_REGISTRY, "2".repeat(64), null, ACTOR);
    assertThat(finding.getInspectionAssetVersion()).isEqualTo(8L);
    assertThat(finding.getInspectionStatus()).isEqualTo("REPAIR");
    assertThat(finding.getInspectionPassportSnapshot()).isEqualTo("{\"finish\":\"OSB\"}");
    assertThat(finding.getConflictResolutionStrategy())
        .isEqualTo(ConflictResolutionStrategy.ACCEPT_REGISTRY);
  }

  private InventorySession session() {
    InventorySession session =
        InventorySession.start(
            UUID.randomUUID(),
            1,
            "Europe/Moscow",
            LocalDate.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "0".repeat(64),
            0,
            "1".repeat(64),
            UUID.randomUUID(),
            "Кладовщик Иван",
            ACTOR);
    session.beforeInsert();
    assertThat(session.getStartedByDisplayName()).isEqualTo("Кладовщик Иван");
    return session;
  }
}
