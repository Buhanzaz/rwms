package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CurrentItemSnapshot;
import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.inventory.domain.ConflictResolutionStrategy;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InventoryFindingConflictPolicyTest {
  private static final String ACTOR =
      "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\",\"principalType\":\"USER\",\"profileRevision\":null}";

  private final ObjectMapper mapper = new ObjectMapper();
  private final InventoryFindingConflictPolicy policy =
      new InventoryFindingConflictPolicy(mapper, new InventoryCanonicalJsonService(mapper));

  @Test
  void excludesAssetVersionAndPassportTenantFromConflictFingerprint() {
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    InventoryFinding finding = inspectedFinding(inventoryId, assetId, warehouseId);
    CurrentItemSnapshot baseline = policy.inspectionBaselineSnapshot(finding);
    CurrentItemSnapshot versionOnlyChange =
        new CurrentItemSnapshot(
            assetId,
            2L,
            warehouseId,
            "FREE",
            "AB-12",
            "Tenant A",
            mapper.readTree("{\"tenant\":\"Tenant B\"}"),
            mapper.readTree("[]"),
            mapper.readTree("[]"));

    assertThat(policy.semanticFingerprint(versionOnlyChange))
        .isEqualTo(policy.semanticFingerprint(baseline));
    assertThat(policy.conflictViews(finding, warehouseId, versionOnlyChange)).isEmpty();
  }

  @Test
  void reportsMissingCurrentAsset() {
    UUID warehouseId = UUID.randomUUID();
    InventoryFinding finding = inspectedFinding(UUID.randomUUID(), UUID.randomUUID(), warehouseId);

    assertThat(policy.conflictViews(finding, warehouseId, null))
        .extracting(conflict -> conflict.code())
        .containsExactly("RENTAL_ITEM_MISSING");
  }

  @Test
  void sortsMultipleConflictsByCode() {
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    InventoryFinding finding = inspectedFinding(inventoryId, assetId, warehouseId);
    CurrentItemSnapshot current =
        new CurrentItemSnapshot(
            assetId,
            1L,
            warehouseId,
            "LOST",
            "AB-12",
            "Tenant B",
            mapper.readTree("{\"model\":\"changed\"}"),
            mapper.readTree("[{\"name\":\"changed\"}]"),
            mapper.readTree("[{\"id\":\"repair\"}]"));

    assertThat(policy.conflictViews(finding, warehouseId, current))
        .extracting(conflict -> conflict.code())
        .containsExactly(
            "CONTENTS_CHANGED",
            "PASSPORT_CHANGED",
            "REPAIRS_CHANGED",
            "TENANT_CHANGED",
            "WRITTEN_OFF");
  }

  @Test
  void hidesCurrentConflictWhenItsSavedResolutionMatchesFingerprint() {
    UUID warehouseId = UUID.randomUUID();
    InventoryFinding finding = inspectedFinding(UUID.randomUUID(), UUID.randomUUID(), warehouseId);
    CurrentItemSnapshot current =
        new CurrentItemSnapshot(
            finding.getAssetId(),
            2L,
            warehouseId,
            "REPAIR",
            "AB-12",
            "Tenant A",
            mapper.readTree("{}"),
            mapper.readTree("[]"),
            mapper.readTree("[]"));
    finding.resolveConflict(
        ConflictResolutionStrategy.KEEP_INSPECTION,
        policy.semanticFingerprint(current),
        "The inspection remains authoritative",
        ACTOR);

    assertThat(policy.conflictViews(finding, warehouseId, current)).isEmpty();
  }

  @Test
  void leavesUninspectedFindingsWithoutConflicts() {
    UUID warehouseId = UUID.randomUUID();
    InventoryFinding finding =
        InventoryFinding.unexpected(
            UUID.randomUUID(),
            FindingOrigin.ADDED_NEW,
            UUID.randomUUID(),
            1L,
            warehouseId,
            "FREE",
            "Tenant A",
            "AB-12",
            "AB12",
            ReconciliationState.CONFLICT,
            ACTOR);

    assertThat(finding.getInspection()).isEqualTo(InspectionState.NOT_INSPECTED);
    assertThat(policy.conflictViews(finding, warehouseId, null)).isEmpty();
  }

  private InventoryFinding inspectedFinding(UUID inventoryId, UUID assetId, UUID warehouseId) {
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.ADDED_NEW,
            assetId,
            1L,
            warehouseId,
            "FREE",
            "Tenant A",
            "AB-12",
            "AB12",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        null,
        ACTOR);
    return finding;
  }
}
