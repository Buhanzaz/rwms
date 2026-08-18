package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSource;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only projection of eligible publication targets and their plan summaries for a completed
 * inventory finding. It never changes maintenance state.
 */
@Component
final class InventoryPublicationPreflightProjection {
  private final RentalItemFactProjectionRepository rentalItems;
  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository estimateLines;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final InventoryPublicationSourceRepository sources;
  private final InventoryRepairSourceRepository legacySources;
  private final InventoryPublicationPlanValidation planValidation;
  private final ObjectMapper mapper;

  InventoryPublicationPreflightProjection(
      RentalItemFactProjectionRepository rentalItems,
      MaintenanceEstimateRepository estimates,
      EstimateLineRepository estimateLines,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      InventoryPublicationSourceRepository sources,
      InventoryRepairSourceRepository legacySources,
      InventoryPublicationPlanValidation planValidation,
      ObjectMapper mapper) {
    this.rentalItems = rentalItems;
    this.estimates = estimates;
    this.estimateLines = estimateLines;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.sources = sources;
    this.legacySources = legacySources;
    this.planValidation = planValidation;
    this.mapper = mapper;
  }

  InventoryPublicationPreflightResponse preflight(InventoryPublicationPreflightRequest request) {
    requireUniqueFindings(request.findings());
    List<InventoryPublicationPreflightFinding> findings = new ArrayList<>();
    for (InventoryPublicationFindingInput finding : request.findings()) {
      planValidation.validatePublication(request.warehouseId(), finding);
      RentalItemFactProjection asset = requireAsset(finding.assetId());
      assertCurrentAsset(request.warehouseId(), finding, asset);
      InventoryPublicationTargetKind targetKind = targetKind(asset);
      findings.add(
          new InventoryPublicationPreflightFinding(
              finding.findingId(), targetKind, candidates(finding.assetId(), request.warehouseId())));
    }
    return new InventoryPublicationPreflightResponse(
        request.inventoryId(),
        request.finalPlanVersion(),
        request.finalPlanSha256(),
        List.copyOf(findings));
  }

  private List<InventoryPublicationCandidate> candidates(UUID assetId, UUID warehouseId) {
    List<InventoryPublicationCandidate> result = new ArrayList<>();
    for (MaintenanceEstimate estimate : estimates.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(assetId)) {
      if (!warehouseId.equals(estimate.getWarehouseId())) continue;
      InventoryPublicationPlanSummary summary = estimateSummary(estimate);
      InventoryPublicationSource source = sources.findByEstimateId(estimate.getId()).orElse(null);
      boolean superseded = estimate.getInventorySupersededAt() != null;
      result.add(
          new InventoryPublicationCandidate(
              InventoryPublicationTargetKind.ESTIMATE,
              estimate.getId(),
              estimate.getId(),
              null,
              estimate.getVersion(),
              superseded ? "SUPERSEDED" : estimate.getState().name(),
              estimate.getState() != dev.buhanzaz.rwms.maintenance.domain.EstimateState.DRAFT,
              InventoryPublicationTargetSelection.active(estimate),
              estimate.getPriority(),
              estimate.getSourceParty(),
              source == null ? null : source.getPlanFingerprintSha256(),
              summary,
              estimate.isForceCapitalRepair()));
    }
    for (MaintenanceRepair repair : repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(assetId)) {
      if (!warehouseId.equals(repair.getWarehouseId())) continue;
      InventoryPublicationPlanSummary summary = repairSummary(repair);
      String fingerprint = sourceFingerprint(repair.getId());
      result.add(
          new InventoryPublicationCandidate(
              InventoryPublicationTargetKind.REPAIR,
              repair.getId(),
              null,
              repair.getId(),
              repair.getVersion(),
              repair.getExecutionState().name(),
              InventoryPublicationTargetSelection.prestartCandidateFor(repair) == null
                  && (repair.getExecutionState() == RepairExecutionState.IN_PROGRESS
                      || repair.getExecutionState() == RepairExecutionState.COMPLETED),
              InventoryPublicationTargetSelection.active(repair),
              repair.getPriority(),
              repair.getSourceParty(),
              fingerprint,
              summary,
              repair.isForceCapitalRepair()));
    }
    result.sort(
        Comparator.comparing((InventoryPublicationCandidate value) -> value.targetKind().name())
            .thenComparing(value -> value.targetId().toString()));
    return List.copyOf(result);
  }

  private String sourceFingerprint(UUID repairId) {
    InventoryPublicationSource publication = sources.findByRepairId(repairId).orElse(null);
    if (publication != null) return publication.getPlanFingerprintSha256();
    InventoryRepairSource legacy = legacySources.findByRepairId(repairId).orElse(null);
    return legacy == null ? null : legacy.getPlanFingerprint();
  }

  private InventoryPublicationPlanSummary estimateSummary(MaintenanceEstimate estimate) {
    return summary(estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(
        estimate.getId(), estimate.getRevision()).stream()
        .map(
            line ->
                new InventoryPublicationLineAmount(
                    "WORK".equals(line.getLineType()),
                    line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor()))))
        .toList());
  }

  private InventoryPublicationPlanSummary repairSummary(MaintenanceRepair repair) {
    Map<UUID, EstimateLineResponse> lines = new LinkedHashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      for (EstimateLineResponse line : readList(stage.getWorkLines(), EstimateLineResponse.class)) {
        lines.putIfAbsent(line.id(), line);
      }
      for (EstimateLineResponse line : readList(stage.getMaterialLines(), EstimateLineResponse.class)) {
        lines.putIfAbsent(line.id(), line);
      }
    }
    return summary(lines.values().stream()
        .map(
            line ->
                new InventoryPublicationLineAmount(
                    line.lineType() == EstimateLineType.WORK,
                    new BigDecimal(line.quantity()).multiply(moneyToMinor(line.unitPrice()))))
        .toList());
  }

  private InventoryPublicationPlanSummary summary(List<InventoryPublicationLineAmount> values) {
    int work = 0;
    int material = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (InventoryPublicationLineAmount value : values) {
      if (value.work()) work++;
      else material++;
      total = total.add(value.totalMinor());
    }
    return new InventoryPublicationPlanSummary(
        work, material, total.setScale(0, RoundingMode.HALF_UP).longValueExact());
  }

  private RentalItemFactProjection requireAsset(UUID assetId) {
    return rentalItems.findById(assetId).orElseThrow(
        () -> new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "Current rental-item fact is unavailable"));
  }

  private <T> List<T> readList(String value, Class<T> type) {
    try {
      return mapper.readValue(
          value, mapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance plan content is invalid", exception);
    }
  }

  private static void assertCurrentAsset(
      UUID warehouseId, InventoryPublicationFindingInput finding, RentalItemFactProjection asset) {
    if (!warehouseId.equals(asset.getWarehouseId())
        || finding.assetVersion() != asset.getAggregateVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Current rental-item warehouse/version differs from completed inventory evidence");
    }
  }

  private static InventoryPublicationTargetKind targetKind(RentalItemFactProjection asset) {
    return "AFTER_RENT".equals(asset.getAssetStatus())
        ? InventoryPublicationTargetKind.ESTIMATE
        : InventoryPublicationTargetKind.REPAIR;
  }

  private static void requireUniqueFindings(List<InventoryPublicationFindingInput> findings) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (InventoryPublicationFindingInput finding : findings) {
      if (finding == null || finding.findingId() == null || !ids.add(finding.findingId())) {
        throw InventoryPublicationPlanValidation.invalid(
            "Inventory publication finding IDs must be unique");
      }
    }
  }

  private static BigDecimal moneyToMinor(String value) {
    try {
      return new BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalStateException("Stored maintenance money value is invalid", exception);
    }
  }
}

/** Summarized stored plan amount used only for the read-model candidate response. */
record InventoryPublicationLineAmount(boolean work, BigDecimal totalMinor) {}
