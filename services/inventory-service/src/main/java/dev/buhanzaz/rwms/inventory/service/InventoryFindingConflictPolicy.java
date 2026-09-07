package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConflictView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CurrentItemSnapshot;

import dev.buhanzaz.rwms.inventory.domain.InspectionSource;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decodes inspected registry baselines and compares them with an inventory-local current snapshot.
 *
 * <p>The policy is deliberately free of persistence, workflow, and remote dependency calls so live
 * validation and completed-session projection apply the same conflict semantics.
 */
final class InventoryFindingConflictPolicy {
  private final ObjectMapper mapper;
  private final InventoryCanonicalJsonPort canonicalJson;

  InventoryFindingConflictPolicy(ObjectMapper mapper, InventoryCanonicalJsonPort canonicalJson) {
    this.mapper = mapper;
    this.canonicalJson = canonicalJson;
  }

  static boolean isTerminalDispositionStatus(String status) {
    return "WRITTEN_OFF".equals(status) || "LOST".equals(status);
  }

  List<ConflictView> conflictViews(
      InventoryFinding finding, UUID inventoryWarehouseId, CurrentItemSnapshot current) {
    if (finding.getInspection() == InspectionState.NOT_INSPECTED) {
      return List.of();
    }
    if (finding.preservesOperationalState(inventoryWarehouseId,
        current == null ? null : current.warehouseId(), current == null ? null : current.status())) {
      if (current == null) {
        return List.of(new ConflictView("RENTAL_ITEM_MISSING",
            "Бытовка отсутствует в актуальном реестре", finding.getAssetId().toString(), null));
      }
      if (isTerminalDispositionStatus(current.status())) {
        return List.of(new ConflictView("WRITTEN_OFF",
            "Бытовка списана; публикация осмотра недоступна", null, current.status()));
      }
      return List.of();
    }
    CurrentItemSnapshot baseline = inspectionBaselineSnapshot(finding);
    String currentFingerprint = semanticFingerprint(current);
    if (finding.getConflictResolutionStrategy() != null
        && currentFingerprint.equals(finding.getConflictResolutionCurrentSha256())) {
      return List.of();
    }
    List<ConflictView> conflicts = new ArrayList<>();
    if (current == null) {
      conflicts.add(
          new ConflictView(
              "RENTAL_ITEM_MISSING",
              "Бытовка отсутствует в актуальном реестре",
              baseline.assetId().toString(),
              null));
      return List.copyOf(conflicts);
    }
    if (!inventoryWarehouseId.equals(current.warehouseId())) {
      conflicts.add(
          new ConflictView(
              "OTHER_WAREHOUSE",
              "Бытовка относится к другому складу",
              inventoryWarehouseId.toString(),
              current.warehouseId().toString()));
    }
    if (!baseline.warehouseId().equals(current.warehouseId())) {
      conflicts.add(
          new ConflictView(
              "WAREHOUSE_CHANGED",
              "Склад бытовки изменился после осмотра",
              baseline.warehouseId().toString(),
              current.warehouseId().toString()));
    }
    if (!baseline.status().equals(current.status())) {
      String code =
          isTerminalDispositionStatus(current.status())
              ? "WRITTEN_OFF"
              : "RENTED".equals(current.status()) ? "RENTED" : "STATUS_CHANGED";
      conflicts.add(
          new ConflictView(
              code,
              "Статус бытовки изменился после осмотра",
              baseline.status(),
              current.status()));
    }
    if (!java.util.Objects.equals(baseline.tenantSnapshot(), current.tenantSnapshot())) {
      conflicts.add(
          new ConflictView(
              "TENANT_CHANGED",
              "Арендатор бытовки изменился после осмотра",
              baseline.tenantSnapshot(),
              current.tenantSnapshot()));
    }
    if (!baseline.displayCanonicalNumber().equals(current.displayCanonicalNumber())) {
      conflicts.add(
          new ConflictView(
              "NUMBER_CHANGED",
              "Номер бытовки изменился после осмотра",
              baseline.displayCanonicalNumber(),
              current.displayCanonicalNumber()));
    }
    if (!canonicalJsonTreeHash(passportWithoutTenant(baseline.passportSnapshot()))
        .equals(canonicalJsonTreeHash(passportWithoutTenant(current.passportSnapshot())))) {
      conflicts.add(
          new ConflictView(
              "PASSPORT_CHANGED",
              "Паспорт бытовки изменился после осмотра",
              write(passportWithoutTenant(baseline.passportSnapshot())),
              write(passportWithoutTenant(current.passportSnapshot()))));
    }
    if (!canonicalJsonTreeHash(baseline.contentsSnapshot())
        .equals(canonicalJsonTreeHash(current.contentsSnapshot()))) {
      conflicts.add(
          new ConflictView(
              "CONTENTS_CHANGED",
              "Состав бытовки изменился после осмотра",
              write(baseline.contentsSnapshot()),
              write(current.contentsSnapshot())));
    }
    if (!canonicalJsonTreeHash(baseline.repairsSnapshot())
        .equals(canonicalJsonTreeHash(current.repairsSnapshot()))) {
      conflicts.add(
          new ConflictView(
              "REPAIRS_CHANGED",
              "Ремонты бытовки изменились после осмотра",
              write(baseline.repairsSnapshot()),
              write(current.repairsSnapshot())));
    }
    return conflicts.stream().distinct().sorted(Comparator.comparing(ConflictView::code)).toList();
  }

  String semanticFingerprint(CurrentItemSnapshot current) {
    if (current == null) return canonicalJson.sha256(Map.of("missing", true));
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("assetId", current.assetId());
    value.put("warehouseId", current.warehouseId());
    value.put("status", current.status());
    value.put("displayCanonicalNumber", current.displayCanonicalNumber());
    value.put("tenantSnapshot", current.tenantSnapshot());
    value.put("passportSnapshot", canonicalJsonValue(passportWithoutTenant(current.passportSnapshot())));
    value.put("contentsSnapshot", canonicalJsonValue(current.contentsSnapshot()));
    value.put("repairsSnapshot", canonicalJsonValue(current.repairsSnapshot()));
    return canonicalJson.sha256(value);
  }

  CurrentItemSnapshot inspectionBaselineSnapshot(InventoryFinding finding) {
    if (finding.getInspection() == InspectionState.NOT_INSPECTED
        || finding.getInspectionSource() == InspectionSource.LOGISTICS_RETURN) return null;
    if (finding.getAssetId() == null
        || finding.getInspectionAssetVersion() == null
        || finding.getInspectionWarehouseId() == null
        || finding.getInspectionStatus() == null
        || finding.getInspectionDisplayCanonicalNumber() == null
        || finding.getInspectionPassportSnapshot() == null
        || finding.getInspectionContentsSnapshot() == null
        || finding.getInspectionRepairsSnapshot() == null) {
      throw new IllegalStateException("Inspected finding is missing its registry baseline");
    }
    return new CurrentItemSnapshot(
        finding.getAssetId(),
        finding.getInspectionAssetVersion(),
        finding.getInspectionWarehouseId(),
        finding.getInspectionStatus(),
        finding.getInspectionDisplayCanonicalNumber(),
        finding.getInspectionTenantSnapshot(),
        boundedSafeSnapshot(finding.getInspectionPassportSnapshot(), false, "inspection passport"),
        boundedSafeSnapshot(finding.getInspectionContentsSnapshot(), true, "inspection contents"),
        boundedSafeSnapshot(finding.getInspectionRepairsSnapshot(), true, "inspection repairs"));
  }

  private JsonNode passportWithoutTenant(JsonNode passport) {
    if (passport == null || !passport.isObject()) return mapper.createObjectNode();
    ObjectNode result = ((ObjectNode) passport).deepCopy();
    result.remove("tenant");
    return result;
  }

  private JsonNode boundedSafeSnapshot(String value, boolean arrayAllowed, String name) {
    if (value == null || value.length() > 65_536) {
      throw new IllegalStateException("Persisted " + name + " snapshot exceeds the safe bound");
    }
    JsonNode parsed;
    try {
      parsed = mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted " + name + " snapshot is invalid", exception);
    }
    if (!parsed.isObject() && !(arrayAllowed && parsed.isArray())) {
      throw new IllegalStateException("Persisted " + name + " snapshot has an unsafe shape");
    }
    return parsed;
  }

  private String canonicalJsonTreeHash(JsonNode value) {
    return canonicalJson.sha256(canonicalJsonValue(value));
  }

  private Object canonicalJsonValue(JsonNode value) {
    try {
      return mapper.treeToValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory JSON cannot be canonicalized", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory value is not serializable", exception);
    }
  }
}
