package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureReconciliationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryReviewStage;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.InventoryValidationSnapshot;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.mapper.InventorySessionMapper;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMembershipMovementRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationSnapshotRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds inventory session, finding and publication projections from persisted local facts.
 *
 * <p>The collaborator does not change workflow state or call remote dependencies. It maps
 * completed validation evidence separately from the mutable-session projection.
 */
@Service
final class InventoryProjectionService extends InventoryProjectionWorkflowSupport {
  InventoryProjectionService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryPublicationIntentRepository publications,
      InventoryExpectedItemRepository expectedItems,
      InventoryMembershipMovementRepository membershipMovements,
      FindingMediaReferenceRepository mediaReferences,
      InventoryValidationSnapshotRepository validationSnapshots,
      InventorySessionMapper sessionMapper,
      InventoryStatisticsService statisticsService,
      FindingPlanSnapshotRepository planSnapshots,
      FindingPlanLineRepository planLines,
      FindingPlanStageRepository planStages,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        sessions,
        findings,
        furnitureReconciliations,
        publications,
        expectedItems,
        membershipMovements,
        mediaReferences,
        validationSnapshots,
        sessionMapper,
        statisticsService,
        planSnapshots,
        planLines,
        planStages,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
  }

  String aggregatePublicationState(List<PublicationView> intents) {
    List<PublicationView> required =
        intents.stream().filter(value -> value.state() != PublicationState.NOT_REQUIRED).toList();
    if (required.isEmpty()) return "NOT_REQUESTED";
    if (required.stream().allMatch(value -> value.state() == PublicationState.SUCCEEDED)) {
      return "SUCCEEDED";
    }
    if (required.stream().anyMatch(value -> value.state() == PublicationState.SUCCEEDED)) {
      return "PARTIAL";
    }
    if (required.stream()
        .anyMatch(
            value ->
                value.state() == PublicationState.READY
                    || value.state() == PublicationState.PENDING
                    || value.state() == PublicationState.TRANSIENT_FAILED)) {
      return "PENDING";
    }
    return "BLOCKED";
  }

  private static boolean isTerminalDispositionStatus(String status) {
    return "WRITTEN_OFF".equals(status) || "LOST".equals(status);
  }

  List<ConflictView> conflictViews(
      InventoryFinding finding,
      UUID inventoryWarehouseId,
      CurrentItemSnapshot current) {
    if (finding.getInspection() == InspectionState.NOT_INSPECTED) {
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
    return conflicts.stream()
        .distinct()
        .sorted(Comparator.comparing(ConflictView::code))
        .toList();
  }

  String semanticFingerprint(CurrentItemSnapshot current) {
    if (current == null) return canonicalHash(Map.of("missing", true));
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("assetId", current.assetId());
    value.put("warehouseId", current.warehouseId());
    value.put("status", current.status());
    value.put("displayCanonicalNumber", current.displayCanonicalNumber());
    value.put("tenantSnapshot", current.tenantSnapshot());
    value.put("passportSnapshot", canonicalJsonValue(passportWithoutTenant(current.passportSnapshot())));
    value.put("contentsSnapshot", canonicalJsonValue(current.contentsSnapshot()));
    value.put("repairsSnapshot", canonicalJsonValue(current.repairsSnapshot()));
    return canonicalHash(value);
  }

  JsonNode passportWithoutTenant(JsonNode passport) {
    if (passport == null || !passport.isObject()) return mapper.createObjectNode();
    ObjectNode result = ((ObjectNode) passport).deepCopy();
    result.remove("tenant");
    return result;
  }

  CurrentItemSnapshot inspectionBaselineSnapshot(InventoryFinding finding) {
    if (finding.getInspection() == InspectionState.NOT_INSPECTED) return null;
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

  SessionView sessionView(InventorySession value) {
    Set<UUID> inventoryIds = Set.of(value.getId());
    SessionCounts counts =
        sessionCounts(inventoryIds).getOrDefault(value.getId(), SessionCounts.EMPTY);
    List<PublicationView> publicationViews =
        sessionPublicationViews(inventoryIds).getOrDefault(value.getId(), List.of());
    FrozenStatistics statistics =
        value.getLifecycle() == SessionLifecycle.COMPLETED ? statisticsService.readStatistics(value.getId()) : null;
    CancellationAudit cancellation =
        value.getLifecycle() == SessionLifecycle.CANCELLED
            ? new CancellationAudit(value.getCancellationReason(), value.getCancelledAt())
            : null;
    return new SessionView(
        value.getId(),
        value.getRevision(),
        value.getWarehouseId(),
        value.getWarehouseVersion(),
        value.getWarehouseTimeZone(),
        sessionMapper.toInventoryActorView(value),
        value.getBusinessDate(),
        value.getLifecycle(),
        value.getReviewStage(),
        furnitureReconciliationState(value),
        value.getExpectedPopulationCount(),
        counts.findingCount(),
        counts.inspectedCount(),
        value.getStartedAt(),
        terminalAt(value),
        aggregatePublicationState(publicationViews),
        membershipMovements
            .findAllByInventoryIdOrderByOccurredAtAscIdAsc(value.getId())
            .stream()
            .map(sessionMapper::toMembershipMovementView)
            .toList(),
        statistics,
        cancellation);
  }

  SessionSummary sessionSummary(
      InventorySession value, SessionCounts counts, List<PublicationView> publicationViews) {
    return new SessionSummary(
        value.getId(),
        value.getRevision(),
        value.getWarehouseId(),
        value.getWarehouseVersion(),
        value.getWarehouseTimeZone(),
        sessionMapper.toInventoryActorView(value),
        value.getBusinessDate(),
        value.getLifecycle(),
        value.getReviewStage(),
        furnitureReconciliationState(value),
        value.getExpectedPopulationCount(),
        counts.findingCount(),
        counts.inspectedCount(),
        value.getStartedAt(),
        terminalAt(value),
        aggregatePublicationState(publicationViews));
  }

  FurnitureReconciliationState furnitureReconciliationState(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      return FurnitureReconciliationState.NOT_REQUIRED;
    }
    return furnitureReconciliations
        .findById(session.getId())
        .map(InventoryFurnitureReconciliationIntent::getState)
        .orElse(
            session.getFurnitureReviewSha256() == null || !furnitureReviewHasItems(session)
                ? FurnitureReconciliationState.NOT_REQUIRED
                : FurnitureReconciliationState.READY);
  }

  boolean furnitureReviewHasItems(InventorySession session) {
    if (session.getFurnitureStockObservation() == null) {
      return false;
    }
    JsonNode review = read(session.getFurnitureStockObservation());
    return review.isObject() && review.path("items").isArray() && !review.path("items").isEmpty();
  }

  FindingView findingView(InventoryFinding value) {
    return findingViews(List.of(value)).getFirst();
  }

  FindingView findingView(InventoryFinding value, ValidatedFinding validation) {
    return findingViews(List.of(value), Map.of(value.getId(), validation)).getFirst();
  }

  List<FindingView> findingViews(List<InventoryFinding> values) {
    return findingViews(values, Map.of());
  }

  List<FindingView> findingViews(
      List<InventoryFinding> values, Map<UUID, ValidatedFinding> currentOverrides) {
    if (values.isEmpty()) return List.of();
    Set<UUID> findingIds =
        values.stream()
            .map(InventoryFinding::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<UUID> inventoryIds =
        values.stream()
            .map(InventoryFinding::getInventoryId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, UUID> warehouseByInventory = new LinkedHashMap<>();
    Set<UUID> completedInventoryIds = new LinkedHashSet<>();
    for (InventorySession session : sessions.findAllById(inventoryIds)) {
      warehouseByInventory.put(session.getId(), session.getWarehouseId());
      if (session.getLifecycle() == SessionLifecycle.COMPLETED) {
        completedInventoryIds.add(session.getId());
      }
    }
    Map<UUID, ExpectedItemSnapshot> expectedByFinding = new LinkedHashMap<>();
    for (InventoryExpectedItem expected :
        expectedItems.findAllByFindingIdInOrderByFindingId(findingIds)) {
      InventoryFinding finding =
          values.stream()
              .filter(value -> value.getId().equals(expected.getFindingId()))
              .findFirst()
              .orElseThrow();
      UUID warehouseId = warehouseByInventory.get(finding.getInventoryId());
      if (warehouseId == null) {
        throw new IllegalStateException("Finding session is missing");
      }
      expectedByFinding.put(
          expected.getFindingId(), expectedItemSnapshot(expected, warehouseId));
    }
    Map<UUID, List<MediaReference>> mediaByFinding = new LinkedHashMap<>();
    Map<UUID, Set<UUID>> readyImageIdsByFinding = new LinkedHashMap<>();
    for (FindingMediaReference reference : mediaReferences.findActiveByFindingIds(findingIds)) {
      mediaByFinding
          .computeIfAbsent(reference.getFindingId(), ignored -> new ArrayList<>())
          .add(new MediaReference(reference.getMediaId(), reference.getGeneration()));
      if ("IMAGE".equals(reference.getMediaKind())) {
        readyImageIdsByFinding
            .computeIfAbsent(reference.getFindingId(), ignored -> new LinkedHashSet<>())
            .add(reference.getMediaId());
      }
    }
    Map<UUID, PublicationView> publicationByFinding = new LinkedHashMap<>();
    for (InventoryPublicationIntent publication :
        publications.findAllByFindingIdInOrderByFindingId(findingIds)) {
      publicationByFinding.put(publication.getFindingId(), publicationView(publication));
    }
    InventoryPlanProjectionSupport.PlanProjectionData planData = activePlanData(findingIds);
    Map<UUID, List<FindingPlanLine>> linesByFinding = planData.lines();
    Map<UUID, List<FindingPlanStage>> stagesByFinding = planData.stages();
    Map<UUID, FrozenPlanView> planByFinding = new LinkedHashMap<>();
    for (FindingPlanSnapshot snapshot : planData.snapshots().values()) {
      planByFinding.put(
          snapshot.getFindingId(),
          frozenPlanView(
              snapshot,
              linesByFinding.getOrDefault(snapshot.getFindingId(), List.of()),
              stagesByFinding.getOrDefault(snapshot.getFindingId(), List.of())));
    }
    Map<UUID, ValidatedFinding> validatedByFinding =
        completedValidatedFindings(completedInventoryIds);
    validatedByFinding.putAll(currentOverrides);
    return values.stream()
        .map(
            value ->
                findingView(
                    value,
                    warehouseByInventory.get(value.getInventoryId()),
                    expectedByFinding.get(value.getId()),
                    planByFinding.get(value.getId()),
                    mediaByFinding.getOrDefault(value.getId(), List.of()),
                    readyImageIdsByFinding.getOrDefault(value.getId(), Set.of()),
                    publicationByFinding.get(value.getId()),
                    validatedByFinding.get(value.getId())))
        .toList();
  }

  Map<UUID, ValidatedFinding> completedValidatedFindings(
      Set<UUID> completedInventoryIds) {
    Map<UUID, ValidatedFinding> result = new LinkedHashMap<>();
    if (completedInventoryIds.isEmpty()) return result;
    for (InventoryValidationSnapshot snapshot :
        validationSnapshots.findAllById(completedInventoryIds)) {
      JsonNode body = read(snapshot.getSnapshotBody());
      JsonNode values = body.path("preview").path("validatedFindings");
      if (!values.isArray()) {
        throw new IllegalStateException(
            "Completed inventory validation snapshot is missing validated findings");
      }
      for (JsonNode value : values) {
        ValidatedFinding finding = convert(value, ValidatedFinding.class);
        if (result.put(finding.findingId(), finding) != null) {
          throw new IllegalStateException("Completed inventory validation projection is duplicated");
        }
      }
    }
    return result;
  }

  ReconciliationState validatedReconciliation(
      CurrentItemSnapshot currentSnapshot, List<ConflictView> conflicts) {
    if (currentSnapshot == null) {
      return ReconciliationState.MISSING;
    }
    return conflicts.isEmpty() ? ReconciliationState.MATCHED : ReconciliationState.CONFLICT;
  }

  FindingView findingView(
      InventoryFinding value,
      UUID inventoryWarehouseId,
      ExpectedItemSnapshot expectedSnapshot,
      FrozenPlanView frozenPlan,
      List<MediaReference> media,
      Set<UUID> readyImageIds,
      PublicationView publication,
      ValidatedFinding validatedFinding) {
    if (value.getOrigin() != FindingOrigin.EXPECTED) expectedSnapshot = null;
    if (value.getInspection() == InspectionState.WORK_STAGED && frozenPlan == null) {
      throw new IllegalStateException("WORK_STAGED finding is missing its frozen plan");
    }
    if (value.getInspection() != InspectionState.WORK_STAGED) frozenPlan = null;
    CurrentItemSnapshot currentSnapshot =
        validatedFinding == null
            ? currentItemSnapshot(value)
            : validatedFinding.currentSnapshot();
    List<ConflictView> conflicts =
        validatedFinding == null
            ? conflictViews(value, inventoryWarehouseId, currentSnapshot)
            : List.copyOf(validatedFinding.conflicts());
    ReconciliationState reconciliation =
        validatedFinding == null
            ? value.getReconciliation()
            : validatedReconciliation(currentSnapshot, conflicts);
    return new FindingView(
        value.getId(),
        value.getInventoryId(),
        value.getRevision(),
        value.getOrigin(),
        value.getInspection(),
        value.getInspection() == InspectionState.NOT_INSPECTED ? null : "INVENTORY",
        reconciliation,
        value.getAssetId(),
        value.getAssetVersion(),
        value.getDisplayCanonicalNumber(),
        value.getIdentityMatchKey(),
        observation(value.getPassportObservationState(), value.getPassportObservation()),
        observation(value.getEquipmentObservationState(), value.getEquipmentObservation()),
        value.getMutationState(),
        value.getMaintenancePlanFingerprintSha256(),
        value.getInspectionComment(),
        expectedSnapshot,
        inspectionBaselineSnapshot(value),
        currentSnapshot,
        conflicts,
        conflictResolutionView(value, currentSnapshot),
        frozenPlan,
        coverMediaId(value, media, readyImageIds),
        List.copyOf(media),
        publication);
  }

  UUID coverMediaId(
      InventoryFinding finding, List<MediaReference> media, Set<UUID> readyImageIds) {
    if (finding.getCoverMediaId() != null
        && readyImageIds.contains(finding.getCoverMediaId())) {
      return finding.getCoverMediaId();
    }
    return media.stream()
        .map(MediaReference::mediaId)
        .filter(readyImageIds::contains)
        .findFirst()
        .orElse(null);
  }

  ExpectedItemSnapshot expectedItemSnapshot(
      InventoryExpectedItem value, UUID warehouseId) {
    JsonNode passport = boundedSafeSnapshot(value.getPassportSnapshot(), false, "passport");
    return new ExpectedItemSnapshot(
        value.getAssetId(),
        value.getAssetVersion(),
        warehouseId,
        value.getAssetStatus(),
        value.getDisplayCanonicalNumber(),
        tenantSnapshot(passport),
        passport,
        boundedSafeSnapshot(value.getContentsSnapshot(), true, "contents"));
  }

  CurrentItemSnapshot currentItemSnapshot(InventoryFinding value) {
    return currentItemSnapshot(
        value,
        value.getCurrentRepairsSnapshot() == null
            ? mapper.createArrayNode()
            : boundedSafeSnapshot(value.getCurrentRepairsSnapshot(), true, "current repairs"));
  }

  CurrentItemSnapshot currentItemSnapshot(
      InventoryFinding value, JsonNode repairsSnapshot) {
    if (value.getAssetId() == null) return null;
    if (value.getCurrentWarehouseId() == null || value.getCurrentStatus() == null) {
      return null;
    }
    return new CurrentItemSnapshot(
        value.getAssetId(),
        value.getAssetVersion(),
        value.getCurrentWarehouseId(),
        value.getCurrentStatus(),
        value.getCurrentDisplayCanonicalNumber() == null
            ? value.getDisplayCanonicalNumber()
            : value.getCurrentDisplayCanonicalNumber(),
        value.getCurrentTenantSnapshot(),
        value.getCurrentPassportSnapshot() == null
            ? mapper.createObjectNode()
            : boundedSafeSnapshot(value.getCurrentPassportSnapshot(), false, "current passport"),
        value.getCurrentContentsSnapshot() == null
            ? mapper.createArrayNode()
            : boundedSafeSnapshot(value.getCurrentContentsSnapshot(), true, "current contents"),
        repairsSnapshot);
  }

  ConflictResolutionView conflictResolutionView(
      InventoryFinding finding, CurrentItemSnapshot current) {
    if (finding.getConflictResolutionStrategy() == null) return null;
    if (finding.getConflictResolvedAt() == null
        || finding.getConflictResolutionCurrentSha256() == null) {
      throw new IllegalStateException("Conflict resolution audit is incomplete");
    }
    if (!semanticFingerprint(current)
        .equals(finding.getConflictResolutionCurrentSha256())) {
      return null;
    }
    return new ConflictResolutionView(
        finding.getConflictResolutionStrategy(),
        finding.getConflictResolutionReason(),
        finding.getConflictResolvedAt());
  }

  FrozenPlanView frozenPlanView(
      FindingPlanSnapshot snapshot,
      List<FindingPlanLine> lines,
      List<FindingPlanStage> stages) {
    JsonNode source = read(snapshot.getSourceSnapshot());
    boolean sourceMovementToRepair =
        requiredBoolean(source, "movementToRepair", "frozen plan movement to repair");
    LogisticsPlanningMode sourceLogisticsPlanningMode =
        nullableLogisticsPlanningMode(
            source, "logisticsPlanningMode", "frozen plan logistics planning mode");
    LocalDate sourceLogisticsScheduledDate =
        nullableLocalDate(
            source, "logisticsScheduledDate", "frozen plan logistics scheduled date");
    if (!LogisticsPlanningMode.validInboundPlanning(
            sourceMovementToRepair, sourceLogisticsPlanningMode, sourceLogisticsScheduledDate)
        || sourceMovementToRepair != snapshot.isMovementToRepair()
        || sourceLogisticsPlanningMode != snapshot.getLogisticsPlanningMode()
        || !java.util.Objects.equals(
            sourceLogisticsScheduledDate, snapshot.getLogisticsScheduledDate())) {
      throw new IllegalStateException("Persisted frozen plan movement snapshot is invalid");
    }
    int priority = source.path("priority").asInt(-1);
    if (priority < 1 || priority > 5) {
      throw new IllegalStateException("Persisted frozen plan priority is invalid");
    }
    UUID coverMediaId =
        source.hasNonNull("coverMediaId")
            ? requiredUuid(source, "coverMediaId", "frozen plan cover media id")
            : null;
    JsonNode sourceLines = source.path("lines");
    JsonNode sourceStages = source.path("stages");
    List<FrozenPlanLineView> lineViews =
        lines.stream()
            .map(
                line -> {
                  JsonNode sourceLine = sourceLines.path(line.getLineNo());
                  return
                    new FrozenPlanLineView(
                        line.getId(),
                        line.getSourceKind(),
                        line.getLineType(),
                        line.getCatalogVersionId(),
                        line.getCatalogNodeId(),
                        line.getDescription(),
                        line.getNormalizedDescription(),
                        line.getUnit(),
                        exactDecimal(line.getQuantity()),
                        line.getUnitPriceMinor(),
                        exactDecimal(line.getNormativeMinutes()),
                        nullableText(sourceLine.get("groupComment")),
                        frozenPlanLineMediaReferences(sourceLine));
                })
            .toList();
    List<FrozenPlanStageView> stageViews =
        stages.stream()
            .map(
                stage -> {
                  JsonNode sourceStage = sourceStages.path(stage.getStageNo());
                  return
                    new FrozenPlanStageView(
                        requiredUuid(sourceStage, "id", "frozen plan stage id"),
                        stage.getStageNo(),
                        stage.getCatalogNodeId(),
                        stage.getCatalogNodeName(),
                        stage.getStageKind(),
                        stage.getRoutingQueueId(),
                        stage.getRoutingQueueName(),
                        stage.getRoutingQueueType(),
                        stage.isPhotoRequired(),
                        sourceStage.path("normativeDurationMinutes").asInt(0));
                })
            .toList();
    return new FrozenPlanView(
        snapshot.getPlanMode(),
        snapshot.getCatalogVersionId(),
        snapshot.getFingerprint(),
        priority,
        coverMediaId,
        snapshot.isMovementToRepair(),
        snapshot.getLogisticsPlanningMode(),
        snapshot.getLogisticsScheduledDate(),
        lineViews,
        stageViews);
  }

  List<MediaReference> frozenPlanLineMediaReferences(JsonNode sourceLine) {
    if (sourceLine.isMissingNode() || sourceLine.isNull()) return List.of();
    if (!sourceLine.isObject()) {
      throw new IllegalStateException("Persisted frozen plan line is invalid");
    }
    JsonNode references = sourceLine.path("mediaReferences");
    if (references.isMissingNode() || references.isNull()) return List.of();
    if (!references.isArray() || references.size() > 100) {
      throw new IllegalStateException("Persisted frozen plan line media is invalid");
    }
    List<MediaReference> result = new ArrayList<>();
    Set<UUID> seenMediaIds = new HashSet<>();
    for (JsonNode reference : references) {
      if (!reference.isObject()) {
        throw new IllegalStateException("Persisted frozen plan line media is invalid");
      }
      UUID mediaId = requiredUuid(reference, "mediaId", "frozen plan line media id");
      long generation = reference.path("generation").asLong(-1);
      if (generation < 0 || !seenMediaIds.add(mediaId)) {
        throw new IllegalStateException("Persisted frozen plan line media is invalid");
      }
      result.add(new MediaReference(mediaId, generation));
    }
    return List.copyOf(result);
  }

  Map<UUID, SessionCounts> sessionCounts(Set<UUID> inventoryIds) {
    if (inventoryIds.isEmpty()) return Map.of();
    Map<UUID, SessionCounts> result = new LinkedHashMap<>();
    for (InventoryFindingRepository.InventoryFindingCounts counts :
        findings.countByInventoryIds(inventoryIds, InspectionState.NOT_INSPECTED)) {
      result.put(
          counts.getInventoryId(),
          new SessionCounts(counts.getFindingCount(), counts.getInspectedCount()));
    }
    return result;
  }

  Map<UUID, List<PublicationView>> sessionPublicationViews(Set<UUID> inventoryIds) {
    if (inventoryIds.isEmpty()) return Map.of();
    Map<UUID, List<PublicationView>> result = new LinkedHashMap<>();
    for (InventoryPublicationIntent publication :
        publications.findAllByInventoryIdInOrderByInventoryIdAscFindingIdAsc(inventoryIds)) {
      result
          .computeIfAbsent(publication.getInventoryId(), ignored -> new ArrayList<>())
          .add(publicationView(publication));
    }
    return result;
  }

  /** Summarizes total and inspected findings for one inventory-session projection. */
  record SessionCounts(long findingCount, long inspectedCount) {
    static final SessionCounts EMPTY = new SessionCounts(0, 0);
  }

  PublicationView publicationView(InventoryPublicationIntent value) {
    return new PublicationView(
        value.getId(),
        value.getInventoryId(),
        value.getFindingId(),
        value.getRevision(),
        value.getState(),
        value.getSourceRevision(),
        value.getAttemptCount(),
        value.getFinalPlanVersion(),
        value.getTargetKind(),
        value.getTargetId(),
        value.getMaintenanceEstimateId(),
        value.getMaintenanceRepairId(),
        value.getMaintenanceOutcome(),
        value.getMaintenanceResult() == null ? null : read(value.getMaintenanceResult()),
        value.getBlockedFailureCode());
  }
}
