package dev.buhanzaz.rwms.maintenance.disposition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PropertyDispositionDecisionTest {
  private static final String ACTOR = "{\"subjectId\":\"admin-1\",\"principalType\":\"USER\"}";
  private static final String HASH = "a".repeat(64);

  @Test
  void cabinDecisionCapturesFrozenContentsAndValidatesQuantityBounds() {
    UUID equipmentId = UUID.randomUUID();
    PropertyDispositionDecision decision =
        PropertyDispositionDecision.initiate(
            cabinDraft(
                PropertyDispositionContentsMode.MOVE_SELECTED_TO_STOCK,
                List.of(line(equipmentId, 4, 2))));

    assertThat(decision.getState()).isEqualTo(PropertyDispositionState.PENDING_APPROVAL);
    assertThat(decision.getAssetEffectState())
        .isEqualTo(PropertyDispositionAssetEffectState.NOT_STARTED);
    assertThat(decision.requiresMovement()).isTrue();
    assertThat(decision.getContents())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.getEquipmentId()).isEqualTo(equipmentId);
              assertThat(value.getCurrentQuantity()).isEqualTo(4);
              assertThat(value.getMoveQuantity()).isEqualTo(2);
              assertThat(value.getExpectedBalanceVersion()).isEqualTo(7);
            });
    assertThatThrownBy(() -> decision.getContents().add(null))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThatThrownBy(
            () ->
                PropertyDispositionDecision.initiate(
                    cabinDraft(
                        PropertyDispositionContentsMode.MOVE_SELECTED_TO_STOCK,
                        List.of(line(UUID.randomUUID(), 2, 3)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PropertyDispositionDecision.initiate(
                    cabinDraft(
                        PropertyDispositionContentsMode.DISPOSE_WITH_CABIN,
                        List.of(line(UUID.randomUUID(), 2, 1)))))
        .isInstanceOf(IllegalArgumentException.class);
    UUID duplicate = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                PropertyDispositionDecision.initiate(
                    cabinDraft(
                        PropertyDispositionContentsMode.MOVE_SELECTED_TO_STOCK,
                        List.of(line(duplicate, 2, 1), line(duplicate, 1, 0)))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equipmentDecisionRequiresPositiveQuantityAndBalanceFence() {
    assertThatThrownBy(
            () ->
                PropertyDispositionDecision.initiate(
                    new PropertyDispositionDecisionDraft(
                        UUID.randomUUID(),
                        PropertyDispositionAssetKind.EQUIPMENT,
                        UUID.randomUUID(),
                        "Heater",
                        PropertyDispositionKind.LOSS,
                        PropertyDispositionSource.MANUAL,
                        4,
                        null,
                        1L,
                        "Missing after verification",
                        null,
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        HASH,
                        ACTOR,
                        null,
                        List.of())))
        .isInstanceOf(IllegalArgumentException.class);

    PropertyDispositionDecision decision = equipmentDraft();
    assertThat(decision.getQuantity()).isEqualTo(3L);
    assertThat(decision.getExpectedSourceBalanceVersion()).isEqualTo(9L);
    assertThat(decision.getContents()).isEmpty();
  }

  @Test
  void approvalRecordsOnlyAdminDecisionAndAllowsEmptyReviewComment() {
    PropertyDispositionDecision decision = equipmentDraft();

    assertThat(decision.approve(0, ACTOR, null)).isTrue();
    assertThat(decision.getState()).isEqualTo(PropertyDispositionState.APPROVED);
    assertThat(decision.getReviewComment()).isNull();
    assertThat(decision.getAssetEffectState())
        .isEqualTo(PropertyDispositionAssetEffectState.NOT_STARTED);
    assertThat(decision.approve(0, ACTOR, null)).isFalse();

    assertThatThrownBy(() -> decision.reject(0, ACTOR, "changed my mind"))
        .isInstanceOf(PropertyDispositionConflictException.class);

    PropertyDispositionDecision rejected = equipmentDraft();
    assertThatThrownBy(() -> rejected.reject(0, ACTOR, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rejected.reject(0, ACTOR, "Evidence is insufficient")).isTrue();
    assertThat(rejected.getState()).isEqualTo(PropertyDispositionState.REJECTED);
    assertThat(rejected.reject(99, ACTOR, "Evidence is insufficient")).isFalse();
  }

  @Test
  void selectedContentsMustCompleteLogisticsBeforeAssetEffectThenBecomeEffective() {
    PropertyDispositionDecision decision =
        PropertyDispositionDecision.initiate(
            cabinDraft(
                PropertyDispositionContentsMode.MOVE_SELECTED_TO_STOCK,
                List.of(line(UUID.randomUUID(), 5, 3))));
    UUID movementTaskId = UUID.randomUUID();
    UUID effectId = UUID.randomUUID();

    decision.approve(0, ACTOR, "Move reusable furniture first");
    assertThatThrownBy(() -> decision.startAssetEffect(0))
        .isInstanceOf(PropertyDispositionConflictException.class);
    assertThat(decision.startMovement(0, movementTaskId)).isTrue();
    assertThat(decision.getState()).isEqualTo(PropertyDispositionState.MOVEMENT_PENDING);
    assertThat(decision.completeMovement(0)).isTrue();
    assertThat(decision.getState()).isEqualTo(PropertyDispositionState.EFFECT_PENDING);
    assertThat(decision.getAssetEffectState())
        .isEqualTo(PropertyDispositionAssetEffectState.PENDING);
    assertThat(decision.markEffective(0, effectId)).isTrue();
    assertThat(decision.getState()).isEqualTo(PropertyDispositionState.EFFECTIVE);
    assertThat(decision.getAssetEffectState())
        .isEqualTo(PropertyDispositionAssetEffectState.APPLIED);
    assertThat(decision.markEffective(999, effectId)).isFalse();
    assertThatThrownBy(() -> decision.markEffective(0, UUID.randomUUID()))
        .isInstanceOf(PropertyDispositionConflictException.class);
  }

  @Test
  void quarantineRequiresExplicitFencedRecoveryAndResumesTheCorrectEffectState() {
    PropertyDispositionDecision decision = equipmentDraft();
    decision.approve(0, ACTOR, null);
    decision.startAssetEffect(0);
    assertThat(decision.quarantine(0, "ASSET_TIMEOUT", "No effect receipt after retry window"))
        .isTrue();

    assertThat(decision.getState()).isEqualTo(PropertyDispositionState.QUARANTINED);
    assertThat(decision.getAssetEffectState())
        .isEqualTo(PropertyDispositionAssetEffectState.QUARANTINED);
    assertThat(decision.getQuarantineResumeState())
        .isEqualTo(PropertyDispositionState.EFFECT_PENDING);
    assertThatThrownBy(() -> decision.recover(0, 1, ACTOR, "verified remote outcome"))
        .isInstanceOf(PropertyDispositionVersionConflictException.class);

    decision.recover(0, 0, ACTOR, "Verified no remote effect was applied");
    assertThat(decision.getRecoveryVersion()).isEqualTo(1);
    assertThat(decision.getState()).isEqualTo(PropertyDispositionState.EFFECT_PENDING);
    assertThat(decision.getAssetEffectState())
        .isEqualTo(PropertyDispositionAssetEffectState.PENDING);
    assertThat(decision.getFailureCode()).isEqualTo("ASSET_TIMEOUT");
    assertThatThrownBy(() -> decision.recover(0, 0, ACTOR, "retry"))
        .isInstanceOf(PropertyDispositionVersionConflictException.class);
  }

  @Test
  void inventoryDecisionRequiresTheDurableFindingIdentity() {
    assertThatThrownBy(
            () ->
                PropertyDispositionDecision.initiate(
                    new PropertyDispositionDecisionDraft(
                        UUID.randomUUID(),
                        PropertyDispositionAssetKind.EQUIPMENT,
                        UUID.randomUUID(),
                        "Inventory chair",
                        PropertyDispositionKind.LOSS,
                        PropertyDispositionSource.INVENTORY,
                        2,
                        3L,
                        1L,
                        "Inventory shortage",
                        null,
                        null,
                        null,
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        HASH,
                        ACTOR,
                        null,
                        List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void manualDispositionUsesExactSubjectBoundPermanentReplayIdentity() {
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    PropertyDispositionDecision decision = manualEquipmentDraft(subjectId, idempotencyKey, HASH);

    assertThat(decision.matchesManualReplay(subjectId, idempotencyKey, HASH)).isTrue();
    assertThat(decision.hasManualReplayMismatch(subjectId, idempotencyKey, "b".repeat(64)))
        .isTrue();
    assertThat(decision.matchesManualReplay(UUID.randomUUID(), idempotencyKey, HASH)).isFalse();
    assertThatThrownBy(
            () ->
                PropertyDispositionDecision.initiate(
                    new PropertyDispositionDecisionDraft(
                        UUID.randomUUID(),
                        PropertyDispositionAssetKind.EQUIPMENT,
                        UUID.randomUUID(),
                        "Heater",
                        PropertyDispositionKind.LOSS,
                        PropertyDispositionSource.MANUAL,
                        1,
                        2L,
                        1L,
                        "No subject-bound key",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        HASH,
                        ACTOR,
                        null,
                        List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PropertyDispositionDecision equipmentDraft() {
    return manualEquipmentDraft(UUID.randomUUID(), UUID.randomUUID(), HASH);
  }

  private static PropertyDispositionDecision manualEquipmentDraft(
      UUID subjectId, UUID idempotencyKey, String requestSha256) {
    return PropertyDispositionDecision.initiate(
        new PropertyDispositionDecisionDraft(
            UUID.randomUUID(),
            PropertyDispositionAssetKind.EQUIPMENT,
            UUID.randomUUID(),
            "Heater",
            PropertyDispositionKind.LOSS,
            PropertyDispositionSource.MANUAL,
            4,
            9L,
            3L,
            "Missing after warehouse verification",
            "https://evidence.example/incident-15",
            null,
            null,
            null,
            null,
            subjectId,
            idempotencyKey,
            requestSha256,
            ACTOR,
            null,
            List.of()));
  }

  private static PropertyDispositionDecisionDraft cabinDraft(
      PropertyDispositionContentsMode mode,
      List<PropertyDispositionContentSnapshotLineDraft> contents) {
    return new PropertyDispositionDecisionDraft(
        UUID.randomUUID(),
        PropertyDispositionAssetKind.CABIN,
        UUID.randomUUID(),
        "Cabin A-17",
        PropertyDispositionKind.WRITE_OFF,
        PropertyDispositionSource.REPAIR,
        3,
        null,
        null,
        "Beyond economical repair",
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        HASH,
        ACTOR,
        mode,
        contents);
  }

  private static PropertyDispositionContentSnapshotLineDraft line(
      UUID equipmentId, long currentQuantity, long moveQuantity) {
    return new PropertyDispositionContentSnapshotLineDraft(
        equipmentId, "Foldable chair", "pcs", currentQuantity, moveQuantity, 7);
  }
}
