package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryCompletionStatistics;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.InventoryStatisticsLine;
import dev.buhanzaz.rwms.inventory.domain.InventoryValidationItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryValidationSnapshot;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryCompletionStatisticsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStatisticsLineRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationSnapshotRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Calculates and persists frozen completion statistics and preview-validation evidence.
 *
 * <p>All values derive from local findings and the supplied validated registry snapshot; callers
 * retain ownership of the surrounding completion transaction and acknowledgement fence.
 */
@Service
final class InventoryStatisticsService extends InventoryStatisticsWorkflowSupport {
  InventoryStatisticsService(
      InventoryFindingRepository findings,
      FindingPlanLineRepository planLines,
      InventoryValidationSnapshotRepository validationSnapshots,
      InventoryValidationItemRepository validationItems,
      InventoryCompletionStatisticsRepository completionStatistics,
      InventoryStatisticsLineRepository statisticsLines,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        findings,
        planLines,
        validationSnapshots,
        validationItems,
        completionStatistics,
        statisticsLines,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
  }

  FrozenStatistics calculateStatistics(
      InventorySession session,
      List<InventoryFinding> all,
      List<ValidatedFinding> validatedFindings) {
    Map<UUID, StatisticsReconciliation> reconciliationByFinding = new LinkedHashMap<>();
    for (ValidatedFinding finding : validatedFindings) {
      if (reconciliationByFinding.put(
              finding.findingId(),
              new StatisticsReconciliation(
                  validatedReconciliation(finding.currentSnapshot(), finding.conflicts())
                      == ReconciliationState.MISSING,
                  !finding.conflicts().isEmpty()))
          != null) {
        throw new IllegalStateException("Inventory validation contains duplicate findings");
      }
    }
    if (!reconciliationByFinding.keySet().equals(
        all.stream()
            .map(InventoryFinding::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))) {
      throw new IllegalStateException("Inventory validation does not cover every finding");
    }
    return calculateStatistics(session, all, reconciliationByFinding);
  }

  FrozenStatistics calculatePersistedStatistics(
      InventorySession session, List<InventoryFinding> all) {
    Map<UUID, StatisticsReconciliation> reconciliationByFinding = new LinkedHashMap<>();
    for (InventoryFinding finding : all) {
      ReconciliationState reconciliation = finding.getReconciliation();
      if (reconciliationByFinding.put(
              finding.getId(),
              new StatisticsReconciliation(
                  reconciliation == ReconciliationState.MISSING,
                  reconciliation == ReconciliationState.CONFLICT))
          != null) {
        throw new IllegalStateException("Inventory findings contain duplicate identifiers");
      }
    }
    return calculateStatistics(session, all, reconciliationByFinding);
  }

  FrozenStatistics calculateStatistics(
      InventorySession session,
      List<InventoryFinding> all,
      Map<UUID, StatisticsReconciliation> reconciliationByFinding) {
    int inspected =
        (int)
            all.stream()
                .filter(value -> value.getInspection() != InspectionState.NOT_INSPECTED)
                .count();
    int missing =
        (int)
            all.stream()
                .filter(
                    value ->
                        reconciliationByFinding.get(value.getId()).missing())
                .count();
    int ready =
        (int) all.stream().filter(value -> value.getInspection() == InspectionState.READY).count();
    int withWork =
        (int)
            all.stream()
                .filter(value -> value.getInspection() == InspectionState.WORK_STAGED)
                .count();
    int added =
        (int)
            all.stream()
                .filter(
                    value ->
                        value.getOrigin() == FindingOrigin.ADDED_NEW
                            || value.getOrigin() == FindingOrigin.ADDED_USED)
                .count();
    int unexpected =
        (int)
            all.stream()
                .filter(value -> value.getOrigin() == FindingOrigin.UNEXPECTED_EXISTING)
                .count();
    int conflicts =
        (int)
            reconciliationByFinding.values().stream()
                .filter(StatisticsReconciliation::conflict)
                .count();
    List<FindingPlanLine> activeLines = planLines.findActiveByInventoryId(session.getId());
    List<PlanTotal> totals =
        List.of(
            planTotal(activeLines, "WORK"), planTotal(activeLines, "MATERIAL"));
    BigDecimal workRaw = raw(totals, "WORK");
    BigDecimal materialRaw = raw(totals, "MATERIAL");
    long workMinor = workRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long materialMinor = materialRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long grandMinor = workRaw.add(materialRaw).setScale(0, RoundingMode.HALF_UP).longValueExact();
    int adjustment = Math.toIntExact(grandMinor - workMinor - materialMinor);
    BigDecimal normative =
        totals.stream()
            .map(PlanTotal::normative)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(3, RoundingMode.UNNECESSARY);
    OffsetDateTime statisticsAt = terminalAt(session);
    if (statisticsAt == null) {
      statisticsAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
    long duration =
        Math.max(0, ChronoUnit.SECONDS.between(session.getStartedAt(), statisticsAt));
    return new FrozenStatistics(
        session.getExpectedPopulationCount(),
        inspected,
        missing,
        ready,
        withWork,
        added,
        unexpected,
        conflicts,
        count(totals, "WORK"),
        count(totals, "MATERIAL"),
        workMinor,
        materialMinor,
        grandMinor,
        adjustment,
        normative.stripTrailingZeros().toPlainString(),
        duration,
        aggregateLines(activeLines));
  }

  List<StatisticsLine> aggregateLines(List<FindingPlanLine> activeLines) {
    Map<StatisticsKey, BigDecimal> quantities = new LinkedHashMap<>();
    for (FindingPlanLine line : activeLines) {
      StatisticsKey key =
          new StatisticsKey(
              line.getSourceKind(),
              line.getCatalogVersionId(),
              line.getCatalogNodeId(),
              line.getNormalizedDescription(),
              line.getLineType(),
              line.getUnit(),
              line.getUnitPriceMinor());
      quantities.merge(key, line.getQuantity(), BigDecimal::add);
    }
    Comparator<StatisticsKey> order =
        Comparator.comparing(StatisticsKey::type)
            .thenComparing(StatisticsKey::kind)
            .thenComparing(
                StatisticsKey::catalogVersionId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                StatisticsKey::catalogNodeId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                StatisticsKey::normalizedDescription,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(StatisticsKey::unit)
            .thenComparingLong(StatisticsKey::unitPriceMinor);
    return quantities.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(order))
        .map(
            entry -> {
              StatisticsKey key = entry.getKey();
              BigDecimal quantity = entry.getValue();
              long rowTotal =
                  quantity
                      .multiply(BigDecimal.valueOf(key.unitPriceMinor()))
                      .setScale(0, RoundingMode.HALF_UP)
                      .longValueExact();
              return new StatisticsLine(
                  key.kind(),
                  key.catalogVersionId(),
                  key.catalogNodeId(),
                  key.normalizedDescription(),
                  key.type(),
                  key.unit(),
                  key.unitPriceMinor(),
                  quantity.stripTrailingZeros().toPlainString(),
                  rowTotal);
            })
        .toList();
  }

  PlanTotal planTotal(List<FindingPlanLine> lines, String type) {
    List<FindingPlanLine> selected =
        lines.stream().filter(line -> type.equals(line.getLineType())).toList();
    BigDecimal raw =
        selected.stream()
            .map(
                line ->
                    line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal normative =
        selected.stream()
            .map(line -> line.getNormativeMinutes().multiply(line.getQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new PlanTotal(type, selected.size(), raw, normative);
  }

  private ReconciliationState validatedReconciliation(
      CurrentItemSnapshot currentSnapshot, List<ConflictView> conflicts) {
    if (currentSnapshot == null) {
      return ReconciliationState.MISSING;
    }
    return conflicts.isEmpty() ? ReconciliationState.MATCHED : ReconciliationState.CONFLICT;
  }

  void persistValidation(
      InventorySession session,
      InventoryDependencyGateway.Validation validation,
      String acknowledgement,
      Object snapshot) {
    validationItems.deleteByInventoryId(session.getId());
    validationSnapshots.saveAndFlush(
        new InventoryValidationSnapshot(
            session.getId(),
            session.getRevision(),
            validation.validationDigest(),
            acknowledgement,
            validation.validatedAt(),
            write(Map.of("preview", snapshot, "validation", validation))));
    Map<UUID, UUID> findingByAsset = new LinkedHashMap<>();
    for (InventoryFinding finding :
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId())) {
      if (finding.getAssetId() != null) findingByAsset.put(finding.getAssetId(), finding.getId());
    }
    for (InventoryDependencyGateway.ValidationItem item : validation.assets()) {
      UUID findingId = findingByAsset.get(item.assetId());
      if (findingId == null) {
        throw InventoryException.dependency("Asset validation returned an unrequested asset");
      }
      boolean foundInOwningWarehouse =
          item.found() && session.getWarehouseId().equals(item.warehouseId());
      validationItems.save(
          new InventoryValidationItem(
              session.getId(),
              item.assetId(),
              foundInOwningWarehouse,
              foundInOwningWarehouse ? item.version() : null,
              foundInOwningWarehouse ? item.status() : null,
              foundInOwningWarehouse ? item.warehouseId() : null,
              findingId));
    }
  }

  ValidationRecord validationRecord(UUID inventoryId) {
    InventoryValidationSnapshot value =
        validationSnapshots
            .findById(inventoryId)
            .orElseThrow(
                () ->
                    new InventoryException(
                        HttpStatus.CONFLICT,
                        "INVENTORY_ACKNOWLEDGEMENT_STALE",
                        "Completion preview is required"));
    JsonNode stored = read(value.getSnapshotBody());
    return new ValidationRecord(
        value.getValidationSha256(),
        value.getAcknowledgementSha256(),
        value.getSessionRevision(),
        stored.path("validation"),
        convert(stored.path("preview"), CompletionPreview.class));
  }

  void persistStatistics(InventorySession session, FrozenStatistics value) {
    completionStatistics.saveAndFlush(
        new InventoryCompletionStatistics(
            session.getId(),
            value.expectedCount(),
            value.inspectedCount(),
            value.missingCount(),
            value.readyCount(),
            value.withWorkCount(),
            value.addedCount(),
            value.unexpectedExistingCount(),
            value.conflictCount(),
            value.workLineCount(),
            value.materialLineCount(),
            value.workTotalMinor(),
            value.materialTotalMinor(),
            value.grandTotalMinor(),
            value.roundingAdjustmentMinor(),
            new BigDecimal(value.normativeMinutes()),
            value.durationSeconds()));
    for (StatisticsLine line : value.aggregateLines()) {
      statisticsLines.save(
          new InventoryStatisticsLine(
              session.getId(),
              line.aggregationKind(),
              line.catalogVersionId(),
              line.catalogNodeId(),
              line.normalizedDescription(),
              line.type(),
              line.unit(),
              line.unitPriceMinor(),
              new BigDecimal(line.quantity()),
              line.rowTotalMinor()));
    }
  }

  private BigDecimal raw(List<PlanTotal> values, String type) {
    return values.stream()
        .filter(value -> type.equals(value.type()))
        .map(PlanTotal::raw)
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  private int count(List<PlanTotal> values, String type) {
    return values.stream()
        .filter(value -> type.equals(value.type()))
        .mapToInt(PlanTotal::count)
        .findFirst()
        .orElse(0);
  }

  FrozenStatistics readStatistics(UUID inventoryId) {
    return completionStatistics
        .findById(inventoryId)
        .map(
            value ->
                new FrozenStatistics(
                    value.getExpectedCount(),
                    value.getInspectedCount(),
                    value.getMissingCount(),
                    value.getReadyCount(),
                    value.getWithWorkCount(),
                    value.getAddedCount(),
                    value.getUnexpectedExistingCount(),
                    value.getConflictCount(),
                    value.getWorkLineCount(),
                    value.getMaterialLineCount(),
                    value.getWorkTotalMinor(),
                    value.getMaterialTotalMinor(),
                    value.getGrandTotalMinor(),
                    value.getRoundingAdjustmentMinor(),
                    value.getNormativeMinutes().stripTrailingZeros().toPlainString(),
                    value.getDurationSeconds(),
                    readStatisticsLines(inventoryId)))
        .orElseGet(this::zeroStatistics);
  }

  List<StatisticsLine> readStatisticsLines(UUID inventoryId) {
    return statisticsLines
        .findAllByInventoryIdOrderByLineTypeAscCatalogVersionIdAscCatalogNodeIdAscNormalizedDescriptionAscUnitAscUnitPriceMinorAsc(
            inventoryId)
        .stream()
        .map(
            line ->
                new StatisticsLine(
                    line.getAggregationKind(),
                    line.getCatalogVersionId(),
                    line.getCatalogNodeId(),
                    line.getNormalizedDescription(),
                    line.getLineType(),
                    line.getUnit(),
                    line.getUnitPriceMinor(),
                    line.getQuantity().stripTrailingZeros().toPlainString(),
                    line.getRowTotalMinor()))
        .toList();
  }

  FrozenStatistics sumStatistics(List<FrozenStatistics> values) {
    if (values.isEmpty()) return zeroStatistics();
    long work =
        values.stream().map(FrozenStatistics::workTotalMinor).reduce(0L, Math::addExact);
    long material =
        values.stream().map(FrozenStatistics::materialTotalMinor).reduce(0L, Math::addExact);
    long grand =
        values.stream().map(FrozenStatistics::grandTotalMinor).reduce(0L, Math::addExact);
    return new FrozenStatistics(
        sumInt(values, FrozenStatistics::expectedCount),
        sumInt(values, FrozenStatistics::inspectedCount),
        sumInt(values, FrozenStatistics::missingCount),
        sumInt(values, FrozenStatistics::readyCount),
        sumInt(values, FrozenStatistics::withWorkCount),
        sumInt(values, FrozenStatistics::addedCount),
        sumInt(values, FrozenStatistics::unexpectedExistingCount),
        sumInt(values, FrozenStatistics::conflictCount),
        sumInt(values, FrozenStatistics::workLineCount),
        sumInt(values, FrozenStatistics::materialLineCount),
        work,
        material,
        grand,
        Math.toIntExact(grand - work - material),
        values.stream()
            .map(value -> new BigDecimal(value.normativeMinutes()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .stripTrailingZeros()
            .toPlainString(),
        values.stream().map(FrozenStatistics::durationSeconds).reduce(0L, Math::addExact),
        List.of());
  }

  FrozenStatistics summarizeStatistics(List<UUID> inventoryIds) {
    FrozenStatistics counts =
        sumStatistics(inventoryIds.stream().map(this::readStatistics).toList());
    List<InventoryStatisticsLine> lines =
        inventoryIds.stream()
            .flatMap(
                id ->
                    statisticsLines
                        .findAllByInventoryIdOrderByLineTypeAscCatalogVersionIdAscCatalogNodeIdAscNormalizedDescriptionAscUnitAscUnitPriceMinorAsc(
                            id)
                        .stream())
            .toList();
    BigDecimal workRaw = statisticsRaw(lines, "WORK");
    BigDecimal materialRaw = statisticsRaw(lines, "MATERIAL");
    long work = workRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long material = materialRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long grand = workRaw.add(materialRaw).setScale(0, RoundingMode.HALF_UP).longValueExact();
    return new FrozenStatistics(
        counts.expectedCount(),
        counts.inspectedCount(),
        counts.missingCount(),
        counts.readyCount(),
        counts.withWorkCount(),
        counts.addedCount(),
        counts.unexpectedExistingCount(),
        counts.conflictCount(),
        counts.workLineCount(),
        counts.materialLineCount(),
        work,
        material,
        grand,
        Math.toIntExact(grand - work - material),
        counts.normativeMinutes(),
        counts.durationSeconds(),
        aggregatePersistedLines(lines));
  }

  BigDecimal statisticsRaw(List<InventoryStatisticsLine> lines, String type) {
    return lines.stream()
        .filter(line -> type.equals(line.getLineType()))
        .map(
            line ->
                line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  List<StatisticsLine> aggregatePersistedLines(List<InventoryStatisticsLine> lines) {
    Map<StatisticsKey, BigDecimal> quantities = new LinkedHashMap<>();
    for (InventoryStatisticsLine line : lines) {
      StatisticsKey key =
          new StatisticsKey(
              line.getAggregationKind(),
              line.getCatalogVersionId(),
              line.getCatalogNodeId(),
              line.getNormalizedDescription(),
              line.getLineType(),
              line.getUnit(),
              line.getUnitPriceMinor());
      quantities.merge(key, line.getQuantity(), BigDecimal::add);
    }
    return quantities.entrySet().stream()
        .sorted(
            Map.Entry.comparingByKey(
                Comparator.comparing(StatisticsKey::type)
                    .thenComparing(StatisticsKey::kind)
                    .thenComparing(
                        StatisticsKey::catalogVersionId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        StatisticsKey::catalogNodeId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        StatisticsKey::normalizedDescription,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(StatisticsKey::unit)
                    .thenComparingLong(StatisticsKey::unitPriceMinor)))
        .map(
            entry -> {
              StatisticsKey key = entry.getKey();
              BigDecimal quantity = entry.getValue();
              return new StatisticsLine(
                  key.kind(),
                  key.catalogVersionId(),
                  key.catalogNodeId(),
                  key.normalizedDescription(),
                  key.type(),
                  key.unit(),
                  key.unitPriceMinor(),
                  quantity.stripTrailingZeros().toPlainString(),
                  quantity
                      .multiply(BigDecimal.valueOf(key.unitPriceMinor()))
                      .setScale(0, RoundingMode.HALF_UP)
                      .longValueExact());
            })
        .toList();
  }

  int sumInt(
      List<FrozenStatistics> values, java.util.function.ToIntFunction<FrozenStatistics> extractor) {
    return values.stream().mapToInt(extractor).reduce(0, Math::addExact);
  }

  FrozenStatistics zeroStatistics() {
    return new FrozenStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "0", 0, List.of());
  }

  /**
   * Captures the validated completion acknowledgement and the exact preview truth it approved.
   */
  record ValidationRecord(
      String validation,
      String acknowledgement,
      long sessionRevision,
      JsonNode validationTruth,
      CompletionPreview preview) {}

  /** Aggregated count and raw/normative quantity for one final-plan line family. */
  record PlanTotal(String type, int count, BigDecimal raw, BigDecimal normative) {}

  /** Reports whether persisted statistics are absent or conflict with the rebuilt projection. */
  record StatisticsReconciliation(boolean missing, boolean conflict) {}

  /** Canonical grouping identity for one work or material statistics line. */
  record StatisticsKey(
      String kind,
      UUID catalogVersionId,
      UUID catalogNodeId,
      String normalizedDescription,
      String type,
      String unit,
      long unitPriceMinor) {}
}
