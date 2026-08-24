package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts a validated frozen inventory plan into deterministic estimate/repair line and stage
 * material, including the residual calculation for a started predecessor repair. Stage allocation
 * is canonicalized to one executable stage per physical routing queue ID.
 */
@Component
final class InventoryPublicationPlanMaterialization {
  private final RepairStageRepository repairStages;
  private final ObjectMapper mapper;

  InventoryPublicationPlanMaterialization(RepairStageRepository repairStages, ObjectMapper mapper) {
    this.repairStages = repairStages;
    this.mapper = mapper;
  }

  List<InventoryPublicationPublishedLine> publicationLines(
      InventoryPublicationSourceId sourceId, FrozenInventoryPlanSnapshot snapshot) {
    List<InventoryPublicationPublishedLine> result = new ArrayList<>();
    for (int index = 0; index < snapshot.lines().size(); index++) {
      InventoryPlanLineSnapshot line = snapshot.lines().get(index);
      UUID lineId = stableId(sourceId, "line", index);
      BigDecimal quantity = new BigDecimal(line.quantity());
      long totalMinor = quantity.multiply(BigDecimal.valueOf(line.unitPriceMinor()))
          .setScale(0, RoundingMode.HALF_UP)
          .longValueExact();
      int duration = new BigDecimal(line.normativeMinutes())
          .setScale(0, RoundingMode.CEILING)
          .intValueExact();
      CatalogNodeSnapshot catalog = line.aggregationKind() == InventoryPlanLineKind.CATALOG
          ? new CatalogNodeSnapshot(
              line.catalogVersionId(),
              line.catalogNodeId(),
              line.type() == InventoryPlanLineType.WORK
                  ? CatalogNodeType.WORK
                  : CatalogNodeType.MATERIAL,
              line.catalogNodeName(),
              line.unit(),
              money(line.unitPriceMinor()),
              duration,
              line.routing(),
              null,
              line.forcesCapitalRepair(),
              line.characteristic())
          : null;
      EstimateLineResponse response = new EstimateLineResponse(
          lineId,
          catalog,
          line.type() == InventoryPlanLineType.WORK ? EstimateLineType.WORK : EstimateLineType.MATERIAL,
          line.description(),
          line.unit(),
          quantity.stripTrailingZeros().toPlainString(),
          money(line.unitPriceMinor()),
          money(totalMinor),
          duration,
          line.type() == InventoryPlanLineType.WORK ? line.groupComment() : null,
          line.mediaReferences());
      result.add(
          new InventoryPublicationPublishedLine(
              index,
              line,
              response,
              quantity,
              line.unitPriceMinor(),
              catalog,
              line.catalogNodeId(),
              line.routing() == null ? null : line.routing().queueId().toString()));
    }
    return List.copyOf(result);
  }

  InventoryPublicationRepairPlan fullRepairPlan(
      InventoryPublicationSourceId sourceId,
      FrozenInventoryPlanSnapshot snapshot,
      List<MediaReferenceInput> sourceMedia) {
    List<InventoryPublicationPublishedLine> lines = publicationLines(sourceId, snapshot);
    return new InventoryPublicationRepairPlan(
        lines, allocateStages(snapshot, lines), sourceMedia, snapshot.coverMediaId());
  }

  List<InventoryPublicationPublishedStage> allocateStagesForEstimate(
      FrozenInventoryPlanSnapshot snapshot, List<InventoryPublicationPublishedLine> lines) {
    return allocateStages(snapshot, lines);
  }

  InventoryPublicationDeltaRepairPlan successorPlan(
      InventoryPublicationSourceId sourceId,
      FrozenInventoryPlanSnapshot snapshot,
      List<MediaReferenceInput> sourceMedia,
      MaintenanceRepair predecessor) {
    InventoryPublicationRepairPlan full = fullRepairPlan(sourceId, snapshot, sourceMedia);
    InventoryPublicationExistingAssignments assigned = predecessorAssignments(predecessor);
    Map<Integer, UUID> routes = new LinkedHashMap<>();
    for (InventoryPublicationPublishedStage allocation : full.allocations()) {
      for (InventoryPublicationPublishedLine line : allocation.lines()) {
        routes.put(line.sourceIndex(), allocation.stage().routing().queueId());
      }
    }

    List<InventoryPublicationPublishedLine> retained = new ArrayList<>();
    List<InventoryPublicationDeltaLine> decisions = new ArrayList<>();
    for (InventoryPublicationPublishedLine line : full.lines()) {
      UUID routeId = routes.get(line.sourceIndex());
      if (routeId == null) {
        throw new IllegalStateException("Inventory successor line has no allocated repair route");
      }
      InventoryPublicationSemanticLineKey key = semanticKey(line, routeId);
      BigDecimal requested = line.quantity();
      BigDecimal assignedQuantity = assigned.quantities().getOrDefault(key, BigDecimal.ZERO);
      BigDecimal deducted = requested.min(assignedQuantity);
      BigDecimal remaining = requested.subtract(deducted);
      if (deducted.signum() > 0) {
        BigDecimal available = assignedQuantity.subtract(deducted);
        if (available.signum() == 0) {
          assigned.quantities().remove(key);
        } else {
          assigned.quantities().put(key, available);
        }
      }

      InventoryPublicationDeltaDisposition disposition;
      if (remaining.signum() == 0) {
        disposition = InventoryPublicationDeltaDisposition.REMOVED_AS_ALREADY_PRESENT;
      } else if (deducted.signum() > 0) {
        disposition = InventoryPublicationDeltaDisposition.RETAINED_AFTER_DEDUCTION;
        retained.add(withQuantity(line, remaining));
      } else if (line.source().aggregationKind() == InventoryPlanLineKind.MANUAL
          && assigned.manualComparable().contains(manualComparableKey(line.source()))) {
        disposition = InventoryPublicationDeltaDisposition.RETAINED_AMBIGUOUS;
        retained.add(line);
      } else {
        disposition = InventoryPublicationDeltaDisposition.RETAINED;
        retained.add(line);
      }
      decisions.add(new InventoryPublicationDeltaLine(
          line.sourceIndex(),
          disposition,
          line.source().type(),
          line.source().aggregationKind() == InventoryPlanLineKind.CATALOG
              ? line.source().catalogNodeId() : null,
          quantityText(requested),
          quantityText(remaining)));
    }

    if (retained.isEmpty()) {
      return new InventoryPublicationDeltaRepairPlan(
          new InventoryPublicationRepairPlan(List.of(), List.of(), List.of(), null),
          new InventoryPublicationDelta(List.copyOf(decisions)));
    }
    List<InventoryPublicationPublishedStage> allocations = allocateStages(snapshot, retained).stream()
        .filter(allocation -> !allocation.lines().isEmpty())
        .toList();
    List<MediaReferenceInput> retainedMedia = retainedMedia(snapshot, retained);
    UUID requestedCoverMediaId = snapshot.coverMediaId();
    UUID coverMediaId = requestedCoverMediaId != null
            && retainedMedia.stream()
                .map(MediaReferenceInput::mediaId)
                .anyMatch(requestedCoverMediaId::equals)
        ? requestedCoverMediaId : null;
    return new InventoryPublicationDeltaRepairPlan(
        new InventoryPublicationRepairPlan(
            List.copyOf(retained), allocations, retainedMedia, coverMediaId),
        new InventoryPublicationDelta(List.copyOf(decisions)));
  }

  static InventoryPublicationDelta fullDelta(FrozenInventoryPlanSnapshot snapshot) {
    List<InventoryPublicationDeltaLine> lines = new ArrayList<>();
    for (int index = 0; index < snapshot.lines().size(); index++) {
      InventoryPlanLineSnapshot line = snapshot.lines().get(index);
      lines.add(new InventoryPublicationDeltaLine(
          index,
          InventoryPublicationDeltaDisposition.RETAINED,
          line.type(),
          line.aggregationKind() == InventoryPlanLineKind.CATALOG ? line.catalogNodeId() : null,
          quantityText(positiveQuantity(line.quantity(), "Frozen inventory line quantity")),
          quantityText(positiveQuantity(line.quantity(), "Frozen inventory line quantity"))));
    }
    return new InventoryPublicationDelta(List.copyOf(lines));
  }

  private InventoryPublicationExistingAssignments predecessorAssignments(MaintenanceRepair predecessor) {
    Map<InventoryPublicationSemanticLineKey, BigDecimal> quantities = new LinkedHashMap<>();
    Set<InventoryPublicationManualComparableKey> manualComparable = new HashSet<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(predecessor.getId())) {
      addPredecessorAssignments(
          quantities, manualComparable, stage, readList(stage.getWorkLines(), EstimateLineResponse.class));
      addPredecessorAssignments(
          quantities,
          manualComparable,
          stage,
          readList(stage.getMaterialLines(), EstimateLineResponse.class));
    }
    return new InventoryPublicationExistingAssignments(quantities, manualComparable);
  }

  private static void addPredecessorAssignments(
      Map<InventoryPublicationSemanticLineKey, BigDecimal> quantities,
      Set<InventoryPublicationManualComparableKey> manualComparable,
      RepairStage stage,
      List<EstimateLineResponse> lines) {
    for (EstimateLineResponse line : lines) {
      InventoryPlanLineType type = line.lineType() == EstimateLineType.WORK
          ? InventoryPlanLineType.WORK : InventoryPlanLineType.MATERIAL;
      BigDecimal quantity = positiveQuantity(line.quantity(), "Stored predecessor line quantity");
      InventoryPublicationSemanticLineKey key;
      if (line.catalogSnapshot() != null) {
        key = InventoryPublicationSemanticLineKey.catalog(type, line.catalogSnapshot().nodeId());
      } else {
        key = InventoryPublicationSemanticLineKey.manual(
            type,
            canonicalManualSignature(
                line.description(),
                line.unit(),
                moneyToMinor(line.unitPrice()).longValueExact(),
                line.normativeMinutes(),
                line.comment(),
                stage.getRoutingQueueId()));
        manualComparable.add(manualComparableKey(type, line.description(), line.unit()));
      }
      quantities.merge(key, quantity, BigDecimal::add);
    }
  }

  private List<InventoryPublicationPublishedStage> allocateStages(
      FrozenInventoryPlanSnapshot snapshot, List<InventoryPublicationPublishedLine> lines) {
    List<InventoryPublicationPublishedStage> allocations =
        RepairPhaseSequence.canonicalInventoryStages(snapshot.stages()).stream()
        .map(InventoryPublicationPublishedStage::new)
        .toList();
    Set<Integer> allocated = new HashSet<>();
    for (InventoryPublicationPublishedStage allocation : allocations) {
      InventoryPublicationPublishedLine primary = firstAvailable(
          lines,
          allocated,
          candidate -> candidate.work() && matchesCatalogNode(candidate, allocation.stage()));
      if (primary == null) {
        primary = firstAvailable(
            lines, allocated, candidate -> matchesCatalogNode(candidate, allocation.stage()));
      }
      if (primary == null) {
        primary = firstAvailable(
            lines,
            allocated,
            candidate -> candidate.work() && matchesRoute(candidate, allocation.stage()));
      }
      if (primary != null) {
        allocation.add(primary);
        allocated.add(primary.sourceIndex());
      }
    }
    for (InventoryPublicationPublishedLine line : lines) {
      if (!line.work() || allocated.contains(line.sourceIndex())) continue;
      InventoryPublicationPublishedStage target = routeStageFor(allocations, line);
      if (target == null) {
        throw InventoryPublicationPlanValidation.invalid(
            "Inventory work line has no selected repair-work route");
      }
      target.add(line);
      allocated.add(line.sourceIndex());
    }
    for (InventoryPublicationPublishedLine line : lines) {
      if (line.work() || allocated.contains(line.sourceIndex())) continue;
      InventoryPublicationPublishedStage target = directCatalogStageFor(allocations, line);
      if (target == null) target = routeStageFor(allocations, line);
      if (target == null) {
        throw InventoryPublicationPlanValidation.invalid(
            "Inventory material line has no selected repair-work route");
      }
      target.add(line);
      allocated.add(line.sourceIndex());
    }
    if (allocated.size() != lines.size()) {
      throw InventoryPublicationPlanValidation.invalid(
          "Inventory publication plan did not allocate every line exactly once");
    }
    return allocations;
  }

  private <T> List<T> readList(String value, Class<T> type) {
    try {
      return mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance plan content is invalid", exception);
    }
  }

  private static InventoryPublicationPublishedLine firstAvailable(
      List<InventoryPublicationPublishedLine> lines,
      Set<Integer> allocated,
      Predicate<InventoryPublicationPublishedLine> predicate) {
    return lines.stream()
        .filter(line -> !allocated.contains(line.sourceIndex()))
        .filter(predicate)
        .findFirst()
        .orElse(null);
  }

  private static InventoryPublicationSemanticLineKey semanticKey(
      InventoryPublicationPublishedLine line, UUID routeId) {
    if (line.source().aggregationKind() == InventoryPlanLineKind.CATALOG) {
      if (line.source().catalogNodeId() == null) {
        throw new IllegalStateException("Catalog inventory successor line has no catalog node");
      }
      return InventoryPublicationSemanticLineKey.catalog(
          line.source().type(), line.source().catalogNodeId());
    }
    return InventoryPublicationSemanticLineKey.manual(
        line.source().type(),
        canonicalManualSignature(
            line.source().description(),
            line.source().unit(),
            line.source().unitPriceMinor(),
            new BigDecimal(line.source().normativeMinutes())
                .setScale(0, RoundingMode.CEILING)
                .intValueExact(),
            line.source().groupComment(),
            routeId));
  }

  private static InventoryPublicationManualComparableKey manualComparableKey(
      InventoryPlanLineSnapshot line) {
    return manualComparableKey(line.type(), line.description(), line.unit());
  }

  private static InventoryPublicationManualComparableKey manualComparableKey(
      InventoryPlanLineType type, String description, String unit) {
    return new InventoryPublicationManualComparableKey(type, canonicalText(description), canonicalText(unit));
  }

  private static boolean matchesCatalogNode(
      InventoryPublicationPublishedLine line, InventoryPlanStageSnapshot stage) {
    return line.source().catalogNodeId() != null
        && line.source().catalogNodeId().equals(stage.catalogNodeId());
  }

  private static boolean matchesRoute(
      InventoryPublicationPublishedLine line, InventoryPlanStageSnapshot stage) {
    return line.source().routing() != null
        && line.source().routing().queueId().equals(stage.routing().queueId());
  }

  private static InventoryPublicationPublishedStage directCatalogStageFor(
      List<InventoryPublicationPublishedStage> allocations, InventoryPublicationPublishedLine line) {
    return allocations.stream()
        .filter(allocation -> matchesCatalogNode(line, allocation.stage()))
        .filter(allocation -> matchesRoute(line, allocation.stage()))
        .findFirst()
        .orElse(null);
  }

  private static InventoryPublicationPublishedStage routeStageFor(
      List<InventoryPublicationPublishedStage> allocations, InventoryPublicationPublishedLine line) {
    List<InventoryPublicationPublishedStage> matching = allocations.stream()
        .filter(allocation -> matchesRoute(line, allocation.stage()))
        .toList();
    if (matching.isEmpty()) return null;
    return matching.stream()
        .filter(allocation -> allocation.lastSourceIndex() < line.sourceIndex())
        .max(Comparator.comparingInt(InventoryPublicationPublishedStage::lastSourceIndex))
        .orElse(matching.getFirst());
  }

  private static InventoryPublicationPublishedLine withQuantity(
      InventoryPublicationPublishedLine line, BigDecimal quantity) {
    if (quantity.signum() <= 0) {
      throw new IllegalArgumentException("Retained inventory successor quantity must be positive");
    }
    EstimateLineResponse response = line.response();
    long totalMinor = quantity.multiply(BigDecimal.valueOf(line.unitPriceMinor()))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
    EstimateLineResponse adjusted = new EstimateLineResponse(
        response.id(),
        response.catalogSnapshot(),
        response.lineType(),
        response.description(),
        response.unit(),
        quantityText(quantity),
        money(line.unitPriceMinor()),
        money(totalMinor),
        response.normativeMinutes(),
        response.comment(),
        response.mediaReferences());
    return new InventoryPublicationPublishedLine(
        line.sourceIndex(),
        line.source(),
        adjusted,
        quantity,
        line.unitPriceMinor(),
        line.catalogSnapshot(),
        line.catalogNodeId(),
        line.queueRef());
  }

  private static List<MediaReferenceInput> retainedMedia(
      FrozenInventoryPlanSnapshot snapshot, List<InventoryPublicationPublishedLine> retained) {
    Map<UUID, MediaReferenceInput> values = new LinkedHashMap<>();
    snapshot.mediaReferences().forEach(reference -> values.put(reference.mediaId(), reference));
    retained.forEach(line -> line.source().mediaReferences().forEach(
        reference -> values.put(reference.mediaId(), reference)));
    return List.copyOf(values.values());
  }

  private static String canonicalManualSignature(
      String description,
      String unit,
      long unitPriceMinor,
      int normativeMinutes,
      String groupComment,
      UUID routeId) {
    if (routeId == null || unitPriceMinor < 0 || normativeMinutes < 0) {
      throw new IllegalStateException("Manual inventory successor signature is incomplete");
    }
    return canonicalText(description)
        + "\u001f"
        + canonicalText(unit)
        + "\u001f"
        + unitPriceMinor
        + "\u001f"
        + normativeMinutes
        + "\u001f"
        + canonicalText(groupComment)
        + "\u001f"
        + routeId;
  }

  private static String canonicalText(String value) {
    if (value == null || value.isBlank()) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  private static BigDecimal positiveQuantity(String value, String subject) {
    try {
      BigDecimal parsed = new BigDecimal(value);
      if (parsed.signum() <= 0) {
        throw new IllegalArgumentException(subject + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalStateException(subject + " is invalid", exception);
    }
  }

  private static String quantityText(BigDecimal quantity) {
    return quantity.stripTrailingZeros().toPlainString();
  }

  private static BigDecimal moneyToMinor(String value) {
    try {
      return new BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalStateException("Stored maintenance money value is invalid", exception);
    }
  }

  private static String money(long value) {
    return BigDecimal.valueOf(value, 2).setScale(2).toPlainString();
  }

  private static UUID stableId(InventoryPublicationSourceId source, String type, int index) {
    return UUID.nameUUIDFromBytes(
        (source.getInventoryId() + ":" + source.getFinalPlanVersion() + ":" + source.getFindingId()
                + ":" + type + ":" + index)
            .getBytes(StandardCharsets.UTF_8));
  }
}

/** Deterministic repair payload derived from a validated immutable inventory plan. */
record InventoryPublicationRepairPlan(
    List<InventoryPublicationPublishedLine> lines,
    List<InventoryPublicationPublishedStage> allocations,
    List<MediaReferenceInput> sourceMedia,
    UUID coverMediaId) {}

/** Residual repair payload together with its immutable retained/removed-line explanation. */
record InventoryPublicationDeltaRepairPlan(
    InventoryPublicationRepairPlan plan, InventoryPublicationDelta delta) {}

/** Stable catalog/manual identity used only while calculating a successor delta. */
record InventoryPublicationSemanticLineKey(
    InventoryPlanLineType type, UUID catalogNodeId, String manualSignature) {
  static InventoryPublicationSemanticLineKey catalog(InventoryPlanLineType type, UUID catalogNodeId) {
    if (type == null || catalogNodeId == null) {
      throw new IllegalArgumentException("Catalog inventory successor identity is incomplete");
    }
    return new InventoryPublicationSemanticLineKey(type, catalogNodeId, null);
  }

  static InventoryPublicationSemanticLineKey manual(InventoryPlanLineType type, String manualSignature) {
    if (type == null || manualSignature == null || manualSignature.isBlank()) {
      throw new IllegalArgumentException("Manual inventory successor identity is incomplete");
    }
    return new InventoryPublicationSemanticLineKey(type, null, manualSignature);
  }
}

/** Coarser manual-line identity used to explain non-deductible ambiguous residuals. */
record InventoryPublicationManualComparableKey(
    InventoryPlanLineType type, String description, String unit) {}

/** Locked predecessor quantities and comparable manual entries. */
record InventoryPublicationExistingAssignments(
    Map<InventoryPublicationSemanticLineKey, BigDecimal> quantities,
    Set<InventoryPublicationManualComparableKey> manualComparable) {}

/** One deterministic line persisted into an estimate or a repair-stage snapshot. */
record InventoryPublicationPublishedLine(
    int sourceIndex,
    InventoryPlanLineSnapshot source,
    EstimateLineResponse response,
    BigDecimal quantity,
    long unitPriceMinor,
    CatalogNodeSnapshot catalogSnapshot,
    UUID catalogNodeId,
    String queueRef) {
  boolean work() {
    return response.lineType() == EstimateLineType.WORK;
  }
}

/** Mutable allocation accumulator confined to one plan-materialization invocation. */
final class InventoryPublicationPublishedStage {
  private final InventoryPlanStageSnapshot stage;
  private final List<InventoryPublicationPublishedLine> lines = new ArrayList<>();

  InventoryPublicationPublishedStage(InventoryPlanStageSnapshot stage) {
    this.stage = stage;
  }

  InventoryPlanStageSnapshot stage() {
    return stage;
  }

  List<InventoryPublicationPublishedLine> lines() {
    return List.copyOf(lines);
  }

  List<EstimateLineResponse> workLines() {
    return lines.stream().filter(InventoryPublicationPublishedLine::work)
        .map(InventoryPublicationPublishedLine::response).toList();
  }

  List<EstimateLineResponse> materialLines() {
    return lines.stream().filter(line -> !line.work())
        .map(InventoryPublicationPublishedLine::response).toList();
  }

  UUID primaryLineId() {
    return workLines().stream().map(EstimateLineResponse::id).findFirst().orElse(null);
  }

  void add(InventoryPublicationPublishedLine line) {
    lines.add(line);
  }

  int lastSourceIndex() {
    return lines.stream().mapToInt(InventoryPublicationPublishedLine::sourceIndex)
        .max().orElse(Integer.MIN_VALUE);
  }
}
