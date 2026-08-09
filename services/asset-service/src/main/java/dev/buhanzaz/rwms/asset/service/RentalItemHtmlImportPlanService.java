package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.*;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImport;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRow;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRowAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRowRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.FurnitureLine;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParsedRow;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.SourceValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Evaluates the durable HTML-import review plan and resolves its catalog, status, and furniture
 * mappings.
 *
 * <p>This service owns no media interaction or import-state recovery. Its resolved-row value is
 * consumed by the commit owner only after that owner has fenced and locked the durable import.
 */
@Service
public class RentalItemHtmlImportPlanService {
  private static final String UNSPECIFIED_CATALOG_VALUE = "—";
  private static final double AUTOMATIC_WORD_MATCH_THRESHOLD = 0.60d;
  private static final double AUTOMATIC_WORD_MATCH_MARGIN = 0.10d;

  private final RentalItemHtmlImportRepository imports;
  private final RentalItemHtmlImportRowRepository rows;
  private final RentalItemRepository rentalItems;
  private final CabinCatalogItemRepository catalog;
  private final EquipmentCatalogItemRepository equipment;
  private final RentalItemHtmlImportCodec codec;

  public RentalItemHtmlImportPlanService(
      RentalItemHtmlImportRepository imports,
      RentalItemHtmlImportRowRepository rows,
      RentalItemRepository rentalItems,
      CabinCatalogItemRepository catalog,
      EquipmentCatalogItemRepository equipment,
      RentalItemHtmlImportCodec codec) {
    this.imports = imports;
    this.rows = rows;
    this.rentalItems = rentalItems;
    this.catalog = catalog;
    this.equipment = equipment;
    this.codec = codec;
  }

  RentalItemHtmlImport updatePlan(UUID id, UpdateHtmlImportPlanRequest request) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    assertVersion(value, request.expectedVersion());
    if (value.getState() != RentalItemHtmlImportState.REVIEW_REQUIRED
        && value.getState() != RentalItemHtmlImportState.READY) {
      throw new AssetConflictException("HTML import plan can no longer be edited");
    }

    HtmlImportPlan plan = request.plan();
    validateMappingUniqueness(plan);
    List<RentalItemHtmlImportRow> importRows =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(id);
    Map<String, RentalItemHtmlImportRow> bySource =
        importRows.stream()
            .collect(Collectors.toMap(RentalItemHtmlImportRow::getSourceRowId, Function.identity()));
    Map<String, HtmlImportRowDecision> decisions =
        plan.rows().stream()
            .collect(
                Collectors.toMap(
                    HtmlImportRowDecision::sourceRowId,
                    Function.identity(),
                    (left, right) -> {
                      throw new IllegalArgumentException("HTML import row decisions must be unique");
                    },
                    LinkedHashMap::new));
    for (Map.Entry<String, HtmlImportRowDecision> entry : decisions.entrySet()) {
      RentalItemHtmlImportRow row = bySource.get(entry.getKey());
      if (row == null) throw new IllegalArgumentException("HTML import row was not found");
      HtmlImportRowDecision decision = entry.getValue();
      validateRowDecision(value.getWarehouseId(), row, decision);
      row.decide(
          decision.action(),
          decision.proposedNumber() == null
              ? row.getProposedNumber()
              : RentalItem.canonicalNumber(decision.proposedNumber()),
          decision.targetRentalItemId(),
          codec.write(decision));
    }
    rows.flush();

    PlanEvaluation evaluation = evaluatePlan(value, importRows, plan);
    HtmlImportPlan durablePlan =
        new HtmlImportPlan(
            plan.catalogMappings(), plan.equipmentMappings(), plan.statusMappings(), List.of());
    value.replacePlan(
        codec.write(durablePlan),
        evaluation.selectedCount(),
        evaluation.invalidCount(),
        evaluation.unresolvedCount());
    return imports.saveAndFlush(value);
  }

  void cancel(UUID id, long expectedVersion) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    assertVersion(value, expectedVersion);
    if (value.getState() != RentalItemHtmlImportState.DRAFT
        && value.getState() != RentalItemHtmlImportState.REVIEW_REQUIRED
        && value.getState() != RentalItemHtmlImportState.READY) {
      throw new AssetConflictException(
          "HTML import can no longer be cancelled because processing has started");
    }
    imports.delete(value);
    imports.flush();
  }

  List<HtmlImportSourceCandidate> sourceCandidates(
      List<ParsedRow> parsedRows,
      List<CabinCatalogItem> catalogItems,
      List<dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem> equipmentItems) {
    Map<CandidateKey, CandidateAccumulator> values = new LinkedHashMap<>();
    for (ParsedRow row : parsedRows) {
      candidate(values, HtmlImportCandidateKind.TYPE, row.rentalType(), false);
      candidate(values, HtmlImportCandidateKind.DIMENSION, row.dimension(), false);
      candidate(values, HtmlImportCandidateKind.FINISHING, row.finishing(), true);
      candidate(values, HtmlImportCandidateKind.CATEGORY, row.category(), false);
      row.characteristics()
          .forEach(
              value ->
                  candidate(values, HtmlImportCandidateKind.CHARACTERISTIC, value, false));
      String statusSource = candidateSource(row.status());
      if (statusSource != null) {
        accumulate(
            values,
            new CandidateKey(HtmlImportCandidateKind.STATUS, normalizedKey(statusSource)),
            statusSource,
            row.proposedStatus() == null ? null : row.proposedStatus().name(),
            true);
      }
      for (FurnitureLine line : row.furniture()) {
        accumulate(
            values,
            new CandidateKey(HtmlImportCandidateKind.EQUIPMENT, normalizedKey(line.sourceLabel())),
            line.sourceLabel(),
            line.sourceLabel(),
            false);
      }
    }

    Map<CabinCatalogKind, List<CabinCatalogItem>> catalogByKind =
        catalogItems.stream()
            .filter(CabinCatalogItem::isActive)
            .collect(Collectors.groupingBy(CabinCatalogItem::getKind));
    List<dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem> activeFurniture =
        equipmentItems.stream()
            .filter(item -> item.isActive() && item.getCategory() == EquipmentCategory.FURNITURE)
            .toList();

    List<HtmlImportSourceCandidate> result = new ArrayList<>(values.size());
    for (Map.Entry<CandidateKey, CandidateAccumulator> entry : values.entrySet()) {
      CandidateAccumulator candidate = entry.getValue();
      UUID suggestedTargetId = null;
      CabinCatalogKind catalogKind = catalogKind(entry.getKey().kind());
      String suggestedValue =
          candidate.suggestedValue == null ? candidate.sourceValue : candidate.suggestedValue;
      if (catalogKind != null) {
        suggestedTargetId =
            bestAutomaticTarget(
                    suggestedValue,
                    catalogByKind.getOrDefault(catalogKind, List.of()),
                    CabinCatalogItem::getName)
                .map(CabinCatalogItem::getId)
                .orElse(null);
      } else if (entry.getKey().kind() == HtmlImportCandidateKind.EQUIPMENT) {
        suggestedTargetId =
            bestAutomaticTarget(
                    suggestedValue,
                    activeFurniture,
                    dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getName)
                .map(dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getId)
                .orElse(null);
      }
      result.add(
          new HtmlImportSourceCandidate(
              entry.getKey().kind(),
              candidate.sourceValue,
              candidate.suggestedValue,
              candidate.occurrences,
              suggestedTargetId,
              candidate.required));
    }
    return result.stream()
        .sorted(
            Comparator.comparing((HtmlImportSourceCandidate value) -> value.kind().name())
                .thenComparing(HtmlImportSourceCandidate::sourceValue))
        .toList();
  }

  PlanEvaluation evaluatePlan(
      RentalItemHtmlImport value,
      List<RentalItemHtmlImportRow> importRows,
      HtmlImportPlan plan) {
    List<ParsedRow> parsedRows = importRows.stream().map(codec::parsed).toList();
    List<HtmlImportSourceCandidate> candidates =
        sourceCandidates(parsedRows, catalog.findAll(), equipment.findAllByOrderByNameAscIdAsc());
    int unresolvedMappings = 0;
    for (HtmlImportSourceCandidate candidate : candidates) {
      if (!candidateResolved(candidate, plan)) unresolvedMappings += 1;
    }

    Map<String, HtmlImportRowDecision> requestDecisions =
        plan.rows().stream()
            .collect(Collectors.toMap(HtmlImportRowDecision::sourceRowId, Function.identity()));
    Set<String> createNumbers = new HashSet<>();
    int selected = 0;
    int invalid = 0;
    int unresolvedRows = 0;
    for (RentalItemHtmlImportRow row : importRows) {
      HtmlImportRowDecision decision = requestDecisions.get(row.getSourceRowId());
      if (decision == null) decision = effectiveStoredDecision(row);
      RentalItemHtmlImportRowAction action =
          decision.action() == null ? row.getAction() : decision.action();
      if (action == RentalItemHtmlImportRowAction.EXCLUDE) continue;
      if (action == RentalItemHtmlImportRowAction.REVIEW) {
        invalid += 1;
        continue;
      }
      selected += 1;
      ParsedRow source = codec.parsed(row);
      if (!rowResolvable(value.getWarehouseId(), source, decision, plan)) {
        unresolvedRows += 1;
      }
      if (action == RentalItemHtmlImportRowAction.MERGE) {
        // A historical import record may still contain MERGE, but the active
        // intake path never mutates a live cabin.
        unresolvedRows += 1;
      } else if (action == RentalItemHtmlImportRowAction.CREATE) {
        String proposedNumber =
            decision.proposedNumber() == null ? source.proposedNumber() : decision.proposedNumber();
        try {
          String identityMatchKey = RentalItem.identityMatchKey(RentalItem.canonicalNumber(proposedNumber));
          if (!createNumbers.add(identityMatchKey)
              || rentalItems.existsByWarehouseIdAndIdentityMatchKey(
                  value.getWarehouseId(), identityMatchKey)) {
            unresolvedRows += 1;
          }
        } catch (IllegalArgumentException exception) {
          unresolvedRows += 1;
        }
      }
    }
    return new PlanEvaluation(selected, invalid, unresolvedMappings + unresolvedRows);
  }

  void rejectMergeRows(List<RentalItemHtmlImportRow> importRows) {
    for (RentalItemHtmlImportRow row : importRows) {
      HtmlImportRowDecision decision = codec.readDecision(row.getDecisionJson());
      if (row.getAction() == RentalItemHtmlImportRowAction.MERGE
          || (decision != null && decision.action() == RentalItemHtmlImportRowAction.MERGE)) {
        throw new AssetConflictException(
            "HTML import MERGE is retired; existing cabins must use their owning workflow");
      }
    }
  }

  ResolvedRow resolveRow(
      ParsedRow source,
      HtmlImportRowDecision decision,
      HtmlImportPlan plan,
      CommitCatalogs staged) {
    String number =
        RentalItem.canonicalNumber(
            decision.proposedNumber() == null ? source.proposedNumber() : decision.proposedNumber());
    CatalogSelection rentalType =
        resolveCatalog(
            CabinCatalogKind.TYPE, source.rentalType(), decision.rentalTypeId(), plan, staged, false);
    CatalogSelection dimension =
        resolveCatalog(
            CabinCatalogKind.DIMENSION,
            source.dimension(),
            decision.dimensionId(),
            plan,
            staged,
            false);
    CatalogSelection finishing =
        resolveCatalog(
            CabinCatalogKind.FINISHING,
            source.finishing(),
            decision.finishingId(),
            plan,
            staged,
            true);
    CatalogSelection category =
        resolveCatalog(
            CabinCatalogKind.CATEGORY, source.category(), decision.categoryId(), plan, staged, false);
    boolean categorySpecified = category != null;

    List<UUID> characteristics;
    if (decision.characteristicIds() != null) {
      characteristics =
          decision.characteristicIds().stream()
              .map(id -> requireCatalogTarget(id, CabinCatalogKind.CHARACTERISTIC).getId())
              .distinct()
              .toList();
    } else {
      List<UUID> values = new ArrayList<>();
      for (SourceValue characteristic : source.characteristics()) {
        CatalogSelection selected =
            resolveCatalog(
                CabinCatalogKind.CHARACTERISTIC, characteristic, null, plan, staged, false);
        if (selected != null && !values.contains(selected.id())) values.add(selected.id());
      }
      characteristics = List.copyOf(values);
    }
    boolean characteristicsSpecified =
        decision.characteristicIds() != null || !characteristics.isEmpty();

    Map<UUID, Long> equipmentQuantities = new LinkedHashMap<>();
    for (FurnitureLine line : source.furniture()) {
      UUID target = resolveEquipment(line.sourceLabel(), plan, staged);
      if (target != null) {
        equipmentQuantities.merge(target, (long) line.quantity(), Math::addExact);
      }
    }
    Boolean linoleum = decision.linoleum() == null ? source.linoleum() : decision.linoleum();
    String comment = decision.comment() == null ? source.comment() : decision.comment();
    return new ResolvedRow(
        number,
        rentalType == null ? null : rentalType.id(),
        dimension == null ? null : dimension.id(),
        finishing.id(),
        category == null ? null : category.id(),
        category == null ? null : category.name(),
        categorySpecified,
        characteristics,
        characteristicsSpecified,
        resolvedStatus(source, decision, plan),
        linoleum,
        comment,
        sourcePassport(source),
        Map.copyOf(equipmentQuantities));
  }

  static void assertVersion(RentalItemHtmlImport value, Long expected) {
    if (expected == null || expected < 0) {
      throw new IllegalArgumentException("expectedVersion is required");
    }
    if (value.getVersion() != expected) {
      throw new AssetConflictException("HTML import changed concurrently");
    }
  }

  static boolean candidateNeedsDecision(HtmlImportSourceCandidate candidate) {
    if (candidate.kind() != HtmlImportCandidateKind.STATUS) return false;
    if (candidate.suggestedValue() == null) return true;
    try {
      return !isHtmlImportManualStatus(RentalItemStatus.valueOf(candidate.suggestedValue()));
    } catch (IllegalArgumentException ignored) {
      return true;
    }
  }

  private boolean candidateResolved(HtmlImportSourceCandidate candidate, HtmlImportPlan plan) {
    if (candidate.kind() == HtmlImportCandidateKind.STATUS) {
      if (candidate.suggestedValue() != null) {
        try {
          return isHtmlImportManualStatus(RentalItemStatus.valueOf(candidate.suggestedValue()));
        } catch (IllegalArgumentException ignored) {
          return false;
        }
      }
      return plan.statusMappings().stream()
          .anyMatch(
              mapping ->
                  normalizedKey(mapping.sourceValue()).equals(normalizedKey(candidate.sourceValue()))
                      && isHtmlImportManualStatus(mapping.targetStatus()));
    }
    if (candidate.suggestedTargetId() != null) return true;
    if (candidate.kind() == HtmlImportCandidateKind.EQUIPMENT) {
      Optional<HtmlImportEquipmentMapping> mapping =
          plan.equipmentMappings().stream()
              .filter(
                  value ->
                      normalizedKey(value.sourceValue())
                          .equals(normalizedKey(candidate.sourceValue())))
              .findFirst();
      return mapping.isEmpty() || mapping.filter(this::validEquipmentMapping).isPresent();
    }
    CabinCatalogKind kind = catalogKind(candidate.kind());
    Optional<HtmlImportCatalogMapping> mapping =
        plan.catalogMappings().stream()
            .filter(
                value ->
                    value.kind() == kind
                        && normalizedKey(value.sourceValue())
                            .equals(normalizedKey(candidate.sourceValue())))
            .findFirst();
    return mapping.isEmpty() || validCatalogMapping(mapping.get());
  }

  private boolean rowResolvable(
      UUID warehouseId,
      ParsedRow source,
      HtmlImportRowDecision decision,
      HtmlImportPlan plan) {
    try {
      String number =
          decision.proposedNumber() == null ? source.proposedNumber() : decision.proposedNumber();
      RentalItem.canonicalNumber(number);
      requireResolvableCatalog(
          CabinCatalogKind.TYPE, source.rentalType(), decision.rentalTypeId(), plan, false);
      requireResolvableCatalog(
          CabinCatalogKind.DIMENSION, source.dimension(), decision.dimensionId(), plan, false);
      requireResolvableCatalog(
          CabinCatalogKind.FINISHING, source.finishing(), decision.finishingId(), plan, true);
      requireResolvableCatalog(
          CabinCatalogKind.CATEGORY, source.category(), decision.categoryId(), plan, false);
      for (SourceValue characteristic : source.characteristics()) {
        requireResolvableCatalog(
            CabinCatalogKind.CHARACTERISTIC, characteristic, null, plan, false);
      }
      requireResolvableStatus(source, decision, plan);
      for (FurnitureLine furniture : source.furniture()) {
        requireResolvableEquipment(furniture.sourceLabel(), plan);
      }
      if (decision.action() == RentalItemHtmlImportRowAction.CREATE) {
        if (decision.targetRentalItemId() != null) return false;
      } else if (decision.action() == RentalItemHtmlImportRowAction.MERGE) {
        return false;
      } else {
        return false;
      }
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private void validateMappingUniqueness(HtmlImportPlan plan) {
    Set<String> catalogKeys = new HashSet<>();
    for (HtmlImportCatalogMapping mapping : plan.catalogMappings()) {
      String key = mapping.kind() + ":" + normalizedKey(mapping.sourceValue());
      if (!catalogKeys.add(key)) {
        throw new IllegalArgumentException("HTML catalog mappings must be unique");
      }
      if (!validCatalogMapping(mapping)) {
        throw new IllegalArgumentException("HTML catalog mapping is invalid");
      }
    }
    Set<String> equipmentKeys = new HashSet<>();
    for (HtmlImportEquipmentMapping mapping : plan.equipmentMappings()) {
      if (!equipmentKeys.add(normalizedKey(mapping.sourceValue()))
          || !validEquipmentMapping(mapping)) {
        throw new IllegalArgumentException("HTML equipment mapping is invalid");
      }
    }
    Set<String> statusKeys = new HashSet<>();
    for (HtmlImportStatusMapping mapping : plan.statusMappings()) {
      if (!statusKeys.add(normalizedKey(mapping.sourceValue()))
          || !isHtmlImportManualStatus(mapping.targetStatus())) {
        throw new IllegalArgumentException("HTML status mapping is invalid");
      }
    }
  }

  private boolean validCatalogMapping(HtmlImportCatalogMapping mapping) {
    if (mapping == null || mapping.kind() == null || mapping.action() == null) return false;
    return switch (mapping.action()) {
      case MAP ->
          mapping.targetId() != null
              && catalog
                  .findById(mapping.targetId())
                  .filter(item -> item.isActive() && item.getKind() == mapping.kind())
                  .isPresent();
      case CREATE -> mapping.targetId() == null && normalized(mapping.stagedName(), 255) != null;
      case IGNORE -> mapping.targetId() == null;
    };
  }

  private boolean validEquipmentMapping(HtmlImportEquipmentMapping mapping) {
    if (mapping == null || mapping.action() == null) return false;
    return switch (mapping.action()) {
      case MAP ->
          mapping.targetId() != null
              && equipment
                  .findById(mapping.targetId())
                  .filter(
                      item -> item.isActive() && item.getCategory() == EquipmentCategory.FURNITURE)
                  .isPresent();
      case CREATE -> mapping.targetId() == null && normalized(mapping.stagedName(), 255) != null;
      case IGNORE -> mapping.targetId() == null;
    };
  }

  private void validateRowDecision(
      UUID warehouseId, RentalItemHtmlImportRow row, HtmlImportRowDecision decision) {
    if (!row.getSourceRowId().equals(decision.sourceRowId())) {
      throw new IllegalArgumentException("HTML import row identity changed");
    }
    if (decision.proposedNumber() != null) {
      RentalItem.canonicalNumber(decision.proposedNumber());
    }
    if (decision.action() == RentalItemHtmlImportRowAction.CREATE
        && decision.targetRentalItemId() != null) {
      throw new IllegalArgumentException("CREATE row cannot have a merge target");
    }
    if (decision.action() == RentalItemHtmlImportRowAction.MERGE) {
      throw new AssetConflictException(
          "HTML import MERGE is retired; existing cabins must use their owning workflow");
    }
    if (decision.action() == RentalItemHtmlImportRowAction.EXCLUDE
        || decision.action() == RentalItemHtmlImportRowAction.REVIEW) return;
    if (decision.status() != null && !isHtmlImportManualStatus(decision.status())) {
      throw new IllegalArgumentException(
          "HTML import can only set SALE, USED_SALE, FREE, WAREHOUSE, or OWN_NEEDS");
    }
  }

  private void requireResolvableCatalog(
      CabinCatalogKind kind,
      SourceValue source,
      UUID overrideId,
      HtmlImportPlan plan,
      boolean required) {
    String reference = catalogRef(kind, source, overrideId, plan, required);
    if (required && reference == null) {
      throw new IllegalArgumentException("Required HTML catalog value is unresolved");
    }
  }

  private String catalogRef(
      CabinCatalogKind kind,
      SourceValue source,
      UUID overrideId,
      HtmlImportPlan plan,
      boolean required) {
    if (overrideId != null) {
      CabinCatalogItem item =
          catalog
              .findById(overrideId)
              .filter(value -> value.isActive() && value.getKind() == kind)
              .orElseThrow(() -> new IllegalArgumentException("Catalog override is invalid"));
      return token(item.getId());
    }
    String sourceValue = candidateSource(source);
    if (sourceValue == null) {
      if (required && supportsUnspecifiedValue(kind)) {
        return token(requireUnspecifiedCatalog(kind).getId());
      }
      if (required) throw new IllegalArgumentException("Required source value is absent");
      return null;
    }
    Optional<HtmlImportCatalogMapping> mapping =
        plan.catalogMappings().stream()
            .filter(
                value ->
                    value.kind() == kind
                        && normalizedKey(value.sourceValue()).equals(normalizedKey(sourceValue)))
            .findFirst();
    if (mapping.isPresent()) {
      return switch (mapping.get().action()) {
        case MAP -> token(mapping.get().targetId());
        case CREATE -> "new:" + normalizedKey(mapping.get().stagedName());
        case IGNORE -> {
          if (required && supportsUnspecifiedValue(kind)) {
            yield token(requireUnspecifiedCatalog(kind).getId());
          }
          if (required) throw new IllegalArgumentException("Required source value cannot be ignored");
          yield null;
        }
      };
    }
    String suggestion =
        source == null || source.suggestedValue() == null ? sourceValue : source.suggestedValue();
    Optional<CabinCatalogItem> automatic = autoCatalog(kind, suggestion);
    if (automatic.isPresent()) return token(automatic.get().getId());
    if (required && supportsUnspecifiedValue(kind)) {
      return token(requireUnspecifiedCatalog(kind).getId());
    }
    if (required) throw new IllegalArgumentException("Source catalog value requires a mapping");
    return null;
  }

  private void requireResolvableStatus(
      ParsedRow source, HtmlImportRowDecision decision, HtmlImportPlan plan) {
    if (resolvedStatus(source, decision, plan) == null) {
      throw new IllegalArgumentException("Source status requires a mapping");
    }
  }

  private RentalItemStatus resolvedStatus(
      ParsedRow source, HtmlImportRowDecision decision, HtmlImportPlan plan) {
    if (decision.status() != null) {
      return isHtmlImportManualStatus(decision.status()) ? decision.status() : null;
    }
    if (source.proposedStatus() != null) {
      return isHtmlImportManualStatus(source.proposedStatus()) ? source.proposedStatus() : null;
    }
    String sourceValue = candidateSource(source.status());
    if (sourceValue == null) return null;
    RentalItemStatus mapped =
        plan.statusMappings().stream()
            .filter(value -> normalizedKey(value.sourceValue()).equals(normalizedKey(sourceValue)))
            .map(HtmlImportStatusMapping::targetStatus)
            .findFirst()
            .orElse(null);
    return isHtmlImportManualStatus(mapped) ? mapped : null;
  }

  private void requireResolvableEquipment(String sourceValue, HtmlImportPlan plan) {
    if (autoEquipment(sourceValue).isPresent()) return;
    Optional<HtmlImportEquipmentMapping> mapping =
        plan.equipmentMappings().stream()
            .filter(
                value ->
                    normalizedKey(value.sourceValue()).equals(normalizedKey(sourceValue)))
            .findFirst();
    if (mapping.isPresent() && !validEquipmentMapping(mapping.get())) {
      throw new IllegalArgumentException("Equipment mapping is invalid");
    }
  }

  private CatalogSelection resolveCatalog(
      CabinCatalogKind kind,
      SourceValue source,
      UUID overrideId,
      HtmlImportPlan plan,
      CommitCatalogs staged,
      boolean required) {
    if (overrideId != null) {
      CabinCatalogItem item = requireCatalogTarget(overrideId, kind);
      return new CatalogSelection(item.getId(), item.getName());
    }
    String sourceValue = candidateSource(source);
    if (sourceValue == null) {
      if (required && supportsUnspecifiedValue(kind)) {
        return unspecifiedSelection(kind);
      }
      if (required) throw new IllegalArgumentException("Required source value is absent");
      return null;
    }
    MappingKey key = new MappingKey(kind, normalizedKey(sourceValue));
    Optional<HtmlImportCatalogMapping> explicit =
        plan.catalogMappings().stream()
            .filter(
                mapping ->
                    mapping.kind() == kind
                        && normalizedKey(mapping.sourceValue()).equals(normalizedKey(sourceValue)))
            .findFirst();
    if (explicit.isPresent()) {
      if (explicit.get().action() == HtmlImportMappingAction.IGNORE) {
        if (required && supportsUnspecifiedValue(kind)) {
          return unspecifiedSelection(kind);
        }
        if (required) throw new IllegalArgumentException("Required source value was ignored");
        return null;
      }
      CatalogSelection selection = staged.catalogMappings().get(key);
      if (selection == null) {
        throw new IllegalStateException("Staged catalog mapping is missing");
      }
      return selection;
    }
    String suggestedValue =
        source.suggestedValue() == null ? candidateSource(source) : source.suggestedValue();
    Optional<CabinCatalogItem> automatic = autoCatalog(kind, suggestedValue);
    if (automatic.isPresent()) {
      return new CatalogSelection(automatic.get().getId(), automatic.get().getName());
    }
    if (required && supportsUnspecifiedValue(kind)) {
      return unspecifiedSelection(kind);
    }
    if (required) throw new IllegalArgumentException("Source catalog value is unresolved");
    return null;
  }

  private UUID resolveEquipment(
      String sourceValue, HtmlImportPlan plan, CommitCatalogs staged) {
    Optional<HtmlImportEquipmentMapping> explicit =
        plan.equipmentMappings().stream()
            .filter(
                mapping ->
                    normalizedKey(mapping.sourceValue()).equals(normalizedKey(sourceValue)))
            .findFirst();
    if (explicit.isPresent()) {
      if (explicit.get().action() == HtmlImportMappingAction.IGNORE) return null;
      UUID target = staged.equipmentMappings().get(normalizedKey(sourceValue));
      if (target == null) throw new IllegalStateException("Staged equipment mapping is missing");
      return target;
    }
    return autoEquipment(sourceValue)
        .map(dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getId)
        .orElse(null);
  }

  private CabinCatalogItem requireCatalogTarget(UUID id, CabinCatalogKind kind) {
    if (id == null) throw new IllegalArgumentException("Catalog target is required");
    return catalog
        .findById(id)
        .filter(item -> item.isActive() && item.getKind() == kind)
        .orElseThrow(() -> new AssetNotFoundException("Catalog target was not found"));
  }

  private CatalogSelection unspecifiedSelection(CabinCatalogKind kind) {
    CabinCatalogItem item = requireUnspecifiedCatalog(kind);
    return new CatalogSelection(item.getId(), item.getName());
  }

  private CabinCatalogItem requireUnspecifiedCatalog(CabinCatalogKind kind) {
    if (!supportsUnspecifiedValue(kind)) {
      throw new IllegalArgumentException("This cabin catalog does not support an unspecified value");
    }
    return catalog
        .findByKindAndNameNormalized(kind, normalizedKey(UNSPECIFIED_CATALOG_VALUE))
        .filter(CabinCatalogItem::isActive)
        .orElseThrow(
            () -> new IllegalStateException("Required HTML import placeholder is missing"));
  }

  private Optional<CabinCatalogItem> autoCatalog(CabinCatalogKind kind, String suggestedValue) {
    if (suggestedValue == null) return Optional.empty();
    Optional<CabinCatalogItem> exact =
        catalog
            .findByKindAndNameNormalized(kind, normalizedKey(suggestedValue))
            .filter(CabinCatalogItem::isActive);
    if (exact.isPresent()) return exact;
    return bestAutomaticTarget(
        suggestedValue,
        catalog.findAllByKindAndActiveTrueOrderByNameAscIdAsc(kind),
        CabinCatalogItem::getName);
  }

  private Optional<dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem> autoEquipment(
      String sourceValue) {
    if (sourceValue == null) return Optional.empty();
    return bestAutomaticTarget(
        sourceValue,
        equipment.findAllByOrderByNameAscIdAsc().stream()
            .filter(item -> item.isActive() && item.getCategory() == EquipmentCategory.FURNITURE)
            .toList(),
        dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem::getName);
  }

  private RentalItemHtmlImport requireImportForUpdate(UUID id) {
    return imports
        .findByIdForUpdate(id)
        .orElseThrow(() -> new AssetNotFoundException("HTML import was not found"));
  }

  HtmlImportRowDecision effectiveStoredDecision(RentalItemHtmlImportRow row) {
    HtmlImportRowDecision stored = codec.readDecision(row.getDecisionJson());
    if (stored != null) return stored;
    Long targetVersion =
        row.getTargetRentalItemId() == null
            ? null
            : rentalItems.findById(row.getTargetRentalItemId()).map(RentalItem::getVersion).orElse(null);
    return new HtmlImportRowDecision(
        row.getSourceRowId(),
        row.getAction(),
        row.getProposedNumber(),
        row.getTargetRentalItemId(),
        targetVersion,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of());
  }

  private static void candidate(
      Map<CandidateKey, CandidateAccumulator> target,
      HtmlImportCandidateKind kind,
      SourceValue source,
      boolean required) {
    String sourceValue = candidateSource(source);
    if (sourceValue == null) return;
    accumulate(
        target,
        new CandidateKey(kind, normalizedKey(sourceValue)),
        sourceValue,
        source.suggestedValue(),
        required);
  }

  private static void accumulate(
      Map<CandidateKey, CandidateAccumulator> target,
      CandidateKey key,
      String sourceValue,
      String suggestedValue,
      boolean required) {
    CandidateAccumulator current = target.get(key);
    if (current == null) {
      target.put(key, new CandidateAccumulator(sourceValue, suggestedValue, 1, required));
    } else {
      current.occurrences += 1;
      current.required = current.required || required;
      if (current.suggestedValue == null) current.suggestedValue = suggestedValue;
    }
  }

  private static String candidateSource(SourceValue source) {
    if (source == null) return null;
    String label = normalized(source.sourceLabel(), 255);
    if (label != null) return label;
    String suggestion = normalized(source.suggestedValue(), 255);
    if (suggestion != null) return suggestion;
    String sourceId = normalized(source.sourceId(), 255);
    return sourceId == null || "0".equals(sourceId) ? null : sourceId;
  }

  private static CabinCatalogKind catalogKind(HtmlImportCandidateKind kind) {
    return switch (kind) {
      case TYPE -> CabinCatalogKind.TYPE;
      case DIMENSION -> CabinCatalogKind.DIMENSION;
      case FINISHING -> CabinCatalogKind.FINISHING;
      case CATEGORY -> CabinCatalogKind.CATEGORY;
      case CHARACTERISTIC -> CabinCatalogKind.CHARACTERISTIC;
      case STATUS, EQUIPMENT -> null;
    };
  }

  static boolean isHtmlImportManualStatus(RentalItemStatus status) {
    return status == RentalItemStatus.SALE
        || status == RentalItemStatus.USED_SALE
        || status == RentalItemStatus.FREE
        || status == RentalItemStatus.WAREHOUSE
        || status == RentalItemStatus.OWN_NEEDS;
  }

  private static boolean supportsUnspecifiedValue(CabinCatalogKind kind) {
    return kind == CabinCatalogKind.TYPE
        || kind == CabinCatalogKind.DIMENSION
        || kind == CabinCatalogKind.FINISHING;
  }

  private static String token(UUID value) {
    return value == null ? null : "id:" + value;
  }

  static String normalizedKey(String value) {
    String normalized = normalized(value, 4000);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private static <T> Optional<T> bestAutomaticTarget(
      String sourceValue, Collection<T> targets, Function<T, String> label) {
    String sourceKey = normalizedKey(sourceValue);
    if (sourceKey.isEmpty() || targets.isEmpty()) return Optional.empty();

    Optional<T> exact =
        targets.stream().filter(target -> normalizedKey(label.apply(target)).equals(sourceKey)).findFirst();
    if (exact.isPresent()) return exact;

    Set<String> sourceWords = semanticWords(sourceValue);
    if (sourceWords.isEmpty()) return Optional.empty();

    ScoredTarget<T> best = null;
    ScoredTarget<T> runnerUp = null;
    for (T target : targets) {
      double score = wordSimilarity(sourceWords, semanticWords(label.apply(target)));
      ScoredTarget<T> candidate = new ScoredTarget<>(target, score);
      if (best == null || score > best.score()) {
        runnerUp = best;
        best = candidate;
      } else if (runnerUp == null || score > runnerUp.score()) {
        runnerUp = candidate;
      }
    }
    if (best == null || best.score() < AUTOMATIC_WORD_MATCH_THRESHOLD) {
      return Optional.empty();
    }
    if (runnerUp != null
        && runnerUp.score() >= AUTOMATIC_WORD_MATCH_THRESHOLD
        && best.score() - runnerUp.score() < AUTOMATIC_WORD_MATCH_MARGIN) {
      return Optional.empty();
    }
    return Optional.of(best.target());
  }

  private static Set<String> semanticWords(String value) {
    if (value == null) return Set.of();
    String normalized =
        value
            .toLowerCase(Locale.ROOT)
            .replace('ё', 'е')
            .replaceAll("(?iuU)блок\\s*[-–—]?\\s*контейнер", "бк")
            .replaceAll("(?iuU)сан\\.?\\s*блок", "санблок")
            .replaceAll(
                "(?iuU)(?<![\\p{L}\\p{N}])эл\\s*[-–—]?\\s*ка(?![\\p{L}\\p{N}])",
                "электрика кк")
            .replaceAll("(?iuU)(?:2|двух)\\s*[-–—]?\\s*ярусн\\p{L}*", "двухъярусная")
            .replaceAll("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})", " ")
            .replaceAll("[^\\p{L}\\p{N}]+", " ");
    Set<String> result = new LinkedHashSet<>();
    for (String word : normalized.split("\\s+")) {
      if (word.isBlank()
          || Set.of("и", "в", "на", "не", "эконом").contains(word)
          || (word.length() == 1 && !word.chars().allMatch(Character::isDigit))) {
        continue;
      }
      result.add(word);
    }
    return Set.copyOf(result);
  }

  private static double wordSimilarity(Set<String> left, Set<String> right) {
    if (left.isEmpty() || right.isEmpty()) return 0.0d;
    int shared = 0;
    for (String word : left) {
      if (right.contains(word)) shared += 1;
    }
    return (2.0d * shared) / (left.size() + right.size());
  }

  private static String normalized(String value, int maximum) {
    if (value == null) return null;
    String normalized = value.trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty() || normalized.length() > maximum) return null;
    return normalized;
  }

  /** Counts the selected, invalid, and unresolved rows that determine whether a plan may commit. */
  static record PlanEvaluation(int selectedCount, int invalidCount, int unresolvedCount) {}

  /** Normalized source classifier identity used to resolve a unique cabin-catalog target. */
  static record MappingKey(CabinCatalogKind kind, String sourceValue) {}

  /** Frozen catalog identifier and display name selected for a source classifier value. */
  static record CatalogSelection(UUID id, String name) {}

  /**
   * Validated cabin and equipment mappings frozen immediately before imported rows are
   * materialized.
   */
  static record CommitCatalogs(
      Map<MappingKey, CatalogSelection> catalogMappings, Map<String, UUID> equipmentMappings) {}

  /**
   * Fully resolved cabin row whose classifier, characteristic, passport, and equipment references
   * are safe to pass to the commit workflow.
   */
  static record ResolvedRow(
      String number,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String categoryName,
      boolean categorySpecified,
      List<UUID> characteristicIds,
      boolean characteristicsSpecified,
      RentalItemStatus status,
      Boolean linoleum,
      String comment,
      Map<String, Object> passport,
      Map<UUID, Long> equipmentQuantities) {}

  /** Candidate catalog target paired with its deterministic similarity score. */
  private record ScoredTarget<T>(T target, double score) {}

  /** Canonical candidate kind and normalized source value used for aggregation. */
  private record CandidateKey(HtmlImportCandidateKind kind, String sourceKey) {}

  /**
   * Mutable, request-local accumulator that combines duplicate source candidates before immutable
   * suggestions are emitted; it owns no persisted import state.
   */
  private static final class CandidateAccumulator {
    private final String sourceValue;
    private String suggestedValue;
    private long occurrences;
    private boolean required;

    private CandidateAccumulator(
        String sourceValue, String suggestedValue, long occurrences, boolean required) {
      this.sourceValue = sourceValue;
      this.suggestedValue = suggestedValue;
      this.occurrences = occurrences;
      this.required = required;
    }
  }

  private static Map<String, Object> sourcePassport(ParsedRow source) {
    Map<String, Object> passport = new LinkedHashMap<>();
    if (source.storageState() != null) {
      passport.put("storageState", source.storageState());
    }
    if (source.shipmentDate() != null) {
      passport.put("shipmentDate", source.shipmentDate().toString());
    }
    if (source.tenant() != null) passport.put("tenant", source.tenant());
    if (source.price() != null) passport.put("price", source.price());
    return Map.copyOf(passport);
  }
}
