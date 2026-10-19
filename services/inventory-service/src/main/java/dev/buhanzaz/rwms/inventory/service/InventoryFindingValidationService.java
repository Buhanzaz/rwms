package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and normalizes live asset truth before registry, furniture-review and completion decisions.
 *
 * <p>Remote validation remains outside local mutation transactions. Callers persist or fence the
 * resulting facts in their own use-case boundary.
 */
@Service
final class InventoryFindingValidationService extends InventoryFindingValidationWorkflowSupport {
  private final InventoryFindingConflictPolicy conflictPolicy;

  InventoryFindingValidationService(
      InventoryFindingRepository findings,
      FindingMediaReferenceRepository mediaReferences,
      InventoryMediaFactProjectionRepository mediaFacts,
      FindingPlanSnapshotRepository planSnapshots,
      InventoryDependencyGateway dependencies,
      InventoryFrozenPlanFingerprint frozenPlanFingerprint,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        findings,
        mediaReferences,
        mediaFacts,
        planSnapshots,
        dependencies,
        frozenPlanFingerprint,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
    conflictPolicy = new InventoryFindingConflictPolicy(mapper, canonicalJson);
  }

  RevisionState revisionState(
      InventorySession session,
      long expectedSessionRevision,
      List<RevisionExpectation> expectations) {
    expectRevision(session.getRevision(), expectedSessionRevision);
    List<InventoryFinding> all =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    Map<UUID, Long> expected = new LinkedHashMap<>();
    for (RevisionExpectation item : expectations) {
      if (expected.put(item.findingId(), item.expectedFindingRevision()) != null) {
        throw new IllegalArgumentException("Finding revision vector contains duplicates");
      }
    }
    if (all.size() != expected.size()) {
      throw InventoryException.conflict("Finding revision vector is incomplete");
    }
    for (InventoryFinding finding : all) {
      Long revision = expected.get(finding.getId());
      if (revision == null || revision != finding.getRevision()) {
        throw InventoryException.conflict("Finding revision vector is stale");
      }
      if (finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATE_PENDING
          || finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.PLAN_RESOLVE_PENDING) {
        throw InventoryException.conflict("Finding mutation is still in flight");
      }
    }
    return new RevisionState(
        all,
        all.stream()
            .map(value -> new RevisionExpectation(value.getId(), value.getRevision()))
            .toList());
  }

  InventoryDependencyGateway.Validation validateAssets(List<InventoryFinding> all) {
    List<UUID> assetIds =
        all.stream()
            .map(InventoryFinding::getAssetId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    if (assetIds.isEmpty()) {
      return new InventoryDependencyGateway.Validation(
          OffsetDateTime.now(ZoneOffset.UTC), canonicalHash(List.of()), List.of());
    }
    InventoryDependencyGateway.Validation validation = dependencies.validateAssets(assetIds);
    if (validation.assets() == null
        || validation.assets().size() != assetIds.size()
        || !validation.validationDigest().matches("^[0-9a-f]{64}$")
        || validation.assets().stream()
            .anyMatch(item -> item.found() && !RENTAL_ITEM_STATUSES.contains(item.status()))) {
      throw InventoryException.dependency("Asset-service returned malformed validation truth");
    }
    return new InventoryDependencyGateway.Validation(
        validation.validatedAt(),
        semanticValidationDigest(validation.assets()),
        validation.assets());
  }

  String semanticValidationDigest(
      List<InventoryDependencyGateway.ValidationItem> assets) {
    return semanticValidationDigest(mapper.valueToTree(assets));
  }

  String semanticValidationDigest(JsonNode assets) {
    return canonicalHash(semanticValidationAssets(assets));
  }

  List<Object> semanticValidationAssets(JsonNode assets) {
    if (assets == null || !assets.isArray()) {
      throw new IllegalArgumentException("Inventory validation assets must be an array");
    }
    List<Object> semantic = new ArrayList<>();
    for (JsonNode asset : assets) {
      if (!asset.isObject()) {
        throw new IllegalArgumentException("Inventory validation asset must be an object");
      }
      ObjectNode value = ((ObjectNode) asset).deepCopy();
      value.remove("version");
      semantic.add(convert(value, Object.class));
    }
    return List.copyOf(semantic);
  }

  List<CompletionRisk> risks(
      InventorySession session,
      List<InventoryFinding> all,
      List<ValidatedFinding> validatedFindings) {
    Map<UUID, ValidatedFinding> current =
        validatedFindings.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ValidatedFinding::findingId,
                    java.util.function.Function.identity(),
                    (left, right) -> {
                      throw new IllegalStateException("Duplicate validated finding");
                    },
                    LinkedHashMap::new));
    List<CompletionRisk> result = new ArrayList<>();
    for (InventoryFinding finding : all) {
      ValidatedFinding truth = current.get(finding.getId());
      if (truth == null) {
        throw new IllegalStateException("Inventory validation does not cover every finding");
      }
      if (truth.currentSnapshot() == null) {
        result.add(new CompletionRisk(finding.getId(), "MISSING"));
      }
      if (!truth.conflicts().isEmpty()) {
        result.add(new CompletionRisk(finding.getId(), "CONFLICT"));
      }
      if (finding.getInspection() == InspectionState.NOT_INSPECTED) {
        result.add(new CompletionRisk(finding.getId(), "NOT_INSPECTED"));
      }
      if (finding.getInspection() == InspectionState.WORK_STAGED) {
        FindingPlanSnapshot plan = activePlanSnapshot(finding).orElse(null);
        if (finding.getMaintenancePlanFingerprintSha256() == null
            || plan == null
            || !finding.getMaintenancePlanFingerprintSha256().equals(plan.getFingerprint())
            || !plan.getFingerprint().equals(frozenPlanFingerprint.sha256(read(plan.getSourceSnapshot())))) {
          result.add(new CompletionRisk(finding.getId(), "PLAN_STALE"));
        }
      }
      for (FindingMediaReference reference :
          mediaReferences.findAllByFindingIdAndFindingRevisionOrderByMediaIdAscGenerationAsc(
              finding.getId(), finding.getRevision())) {
        if (mediaFacts
            .findByMediaIdAndGenerationAndOwnerTypeAndOwnerIdAndWarehouseIdAndMediaStatus(
                reference.getMediaId(),
                reference.getGeneration(),
                "INVENTORY_FINDING",
                finding.getId(),
                session.getWarehouseId(),
                "READY")
            .isEmpty()) {
          result.add(new CompletionRisk(finding.getId(), "MEDIA_NOT_READY"));
          break;
        }
      }
      if (finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATE_PENDING
          || finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.PLAN_RESOLVE_PENDING) {
        result.add(new CompletionRisk(finding.getId(), "MUTATION_IN_FLIGHT"));
      }
    }
    return result.stream()
        .distinct()
        .sorted(Comparator.comparing(CompletionRisk::findingId).thenComparing(CompletionRisk::code))
        .toList();
  }

  List<ValidatedFinding> validatedFindings(
      InventorySession session,
      List<InventoryFinding> all,
      InventoryDependencyGateway.Validation validation) {
    return validatedFindings(session, all, validation, false);
  }

  List<ValidatedFinding> validatedFindings(
      InventorySession session,
      List<InventoryFinding> all,
      InventoryDependencyGateway.Validation validation,
      boolean includeUninspectedRepairs) {
    Map<UUID, InventoryDependencyGateway.ValidationItem> currentByAsset =
        new LinkedHashMap<>();
    for (InventoryDependencyGateway.ValidationItem item : validation.assets()) {
      currentByAsset.put(item.assetId(), item);
    }
    Map<UUID, JsonNode> repairsByAsset =
        currentRepairSnapshots(all, includeUninspectedRepairs);
    return all.stream()
        .map(
            finding -> {
              InventoryDependencyGateway.ValidationItem item =
                  finding.getAssetId() == null
                      ? null
                      : currentByAsset.get(finding.getAssetId());
              CurrentItemSnapshot current =
                  currentItemSnapshot(
                      item,
                      finding.getAssetId() == null
                          ? mapper.createArrayNode()
                          : repairsByAsset.getOrDefault(
                              finding.getAssetId(), mapper.createArrayNode()));
              return new ValidatedFinding(
                  finding.getId(),
                  current,
                  conflictPolicy.conflictViews(finding, session.getWarehouseId(), current));
            })
        .toList();
  }

  Map<UUID, JsonNode> currentRepairSnapshots(
      List<InventoryFinding> all, boolean includeUninspected) {
    List<UUID> assetIds =
        all.stream()
            .filter(
                value ->
                    includeUninspected
                        || value.getInspection() != InspectionState.NOT_INSPECTED)
            .map(InventoryFinding::getAssetId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    if (assetIds.isEmpty()) return Map.of();
    InventoryDependencyGateway.RepairSnapshots response =
        dependencies.repairSnapshots(assetIds);
    if (response == null
        || response.assets() == null
        || !response.assets().stream()
            .map(InventoryDependencyGateway.RepairAssetSnapshot::assetId)
            .toList()
            .equals(assetIds)) {
      throw InventoryException.dependency(
          "Maintenance-service returned incomplete inventory repair truth");
    }
    Map<UUID, JsonNode> result = new LinkedHashMap<>();
    for (InventoryDependencyGateway.RepairAssetSnapshot asset : response.assets()) {
      JsonNode snapshot = mapper.valueToTree(asset.repairs());
      if (!snapshot.isArray() || result.put(asset.assetId(), snapshot) != null) {
        throw InventoryException.dependency(
            "Maintenance-service returned malformed inventory repair truth");
      }
    }
    return result;
  }

  CurrentItemSnapshot currentItemSnapshot(
      InventoryDependencyGateway.ValidationItem item, JsonNode repairsSnapshot) {
    if (item == null || !item.found()) return null;
    return new CurrentItemSnapshot(
        item.assetId(),
        item.version(),
        item.warehouseId(),
        item.status(),
        item.displayCanonicalNumber(),
        item.tenantSnapshot(),
        item.passportSnapshot(),
        item.contentsSnapshot(),
        repairsSnapshot);
  }

  CurrentItemSnapshot currentItemSnapshot(
      InventoryDependencyGateway.AssetSnapshot item) {
    if (item == null) return null;
    return new CurrentItemSnapshot(
        item.assetId(),
        item.version(),
        item.warehouseId(),
        item.status(),
        item.displayCanonicalNumber(),
        item.tenantSnapshot(),
        mapper.createObjectNode(),
        mapper.createArrayNode(),
        mapper.createArrayNode());
  }

  ValidatedFinding validatedFinding(
      InventorySession session, InventoryFinding finding, CurrentItemSnapshot current) {
    return new ValidatedFinding(
        finding.getId(),
        current,
        conflictPolicy.conflictViews(finding, session.getWarehouseId(), current));
  }

  String numberResolutionOutcome(
      UUID inventoryWarehouseId, CurrentItemSnapshot current, boolean existingFinding) {
    if (current == null) return existingFinding ? "MISSING_CONFLICT" : "NOT_FOUND";
    if (!inventoryWarehouseId.equals(current.warehouseId())) {
      return "CROSS_WAREHOUSE_CONFLICT";
    }
    if (InventoryFindingConflictPolicy.isTerminalDispositionStatus(current.status())) {
      return "EXCLUDED_STATUS_CONFLICT";
    }
    return "MATCHED";
  }

  ConflictView blockingInspectionConflict(
      UUID inventoryWarehouseId, CurrentItemSnapshot current) {
    if (current == null) {
      return new ConflictView(
          "RENTAL_ITEM_MISSING",
          "Бытовка отсутствует в актуальном реестре",
          null,
          null);
    }
    if (!inventoryWarehouseId.equals(current.warehouseId())) {
      return new ConflictView(
          "OTHER_WAREHOUSE",
          "Бытовка относится к другому складу",
          inventoryWarehouseId.toString(),
          current.warehouseId().toString());
    }
    if (InventoryFindingConflictPolicy.isTerminalDispositionStatus(current.status())) {
      return new ConflictView(
          "WRITTEN_OFF",
          "LOST".equals(current.status()) ? "Бытовка утеряна" : "Бытовка списана",
          null,
          current.status());
    }
    return null;
  }

  /**
   * Couples the findings locked for validation with their persisted revision expectations so one
   * command cannot validate against a mixed revision set.
   */
  record RevisionState(
      List<InventoryFinding> findings, List<RevisionExpectation> expectations) {}
}
