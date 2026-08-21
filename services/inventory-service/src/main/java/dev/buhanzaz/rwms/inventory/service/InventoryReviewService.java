package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FurnitureLossIntentState;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureLossIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureReconciliationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryReviewStage;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureLossIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns registry-review fencing and the furniture-review/reconciliation recovery workflow.
 *
 * <p>Furniture snapshots are checked before local review mutation. Completion can only dispatch
 * the persisted intents exposed here after its terminal transaction commits.
 */
@Service
final class InventoryReviewService extends InventoryReviewWorkflowSupport {
  private final InventoryFindingPersistenceService findingPersistence;
  private final InventoryCabinDispositionService cabinDispositions;

  InventoryReviewService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryFurnitureLossIntentRepository furnitureLosses,
      InventoryDependencyGateway dependencies,
      InventoryIdempotencyPort idempotency,
      InventoryFindingValidationService validationService,
      InventoryFindingPersistenceService findingPersistence,
      InventoryCabinDispositionService cabinDispositions,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        sessions,
        findings,
        furnitureReconciliations,
        furnitureLosses,
        dependencies,
        idempotency,
        validationService,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
    this.findingPersistence = findingPersistence;
    this.cabinDispositions = cabinDispositions;
  }

  public RegistryReviewView registryReview(
      Jwt jwt, UUID inventoryId, RegistryReviewRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    InventoryFindingValidationService.RevisionState revisions =
        validationService.revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    InventoryDependencyGateway.Validation validation = validationService.validateAssets(revisions.findings());
    List<ValidatedFinding> validated =
        validationService.validatedFindings(session, revisions.findings(), validation);

    // Do not return remote truth for a local revision vector that changed while dependencies were
    // being read. The caller refreshes and repeats this read-only review instead.
    validationService.revisionState(
        requireActive(inventoryId),
        request.expectedSessionRevision(),
        request.findingRevisions());
    return new RegistryReviewView(
        inventoryId,
        session.getRevision(),
        revisions.expectations(),
        validation.validatedAt(),
        validated);
  }

  public FurnitureReviewView startFurnitureReview(
      Jwt jwt,
      UUID inventoryId,
      UUID idempotencyKey,
      StartFurnitureReviewRequest request) {
    requireScopedSession(inventoryId, authorizer.editScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.furniture-review.start",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        FurnitureReviewView.class,
        () -> doStartFurnitureReview(jwt, inventoryId, request));
  }

  FurnitureReviewView doStartFurnitureReview(
      Jwt jwt, UUID inventoryId, StartFurnitureReviewRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    requireFurnitureReviewCanStart(session);
    InventoryFindingValidationService.RevisionState revisions =
        validationService.revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    InventoryDependencyGateway.Validation validation = validationService.validateAssets(revisions.findings());
    List<ValidatedFinding> validated =
        validationService.validatedFindings(session, revisions.findings(), validation);
    List<CompletionRisk> risks = validationService.risks(session, revisions.findings(), validated);
    cabinDispositions.requireCompleted(session, revisions.findings());
    validateFurnitureStageTransition(risks);
    InventoryDependencyGateway.FurnitureSnapshot snapshot =
        dependencies.furnitureSnapshot(
            session.getWarehouseId(), furnitureAssetIds(session, revisions.findings()));
    validateFurnitureSnapshot(session, revisions.findings(), snapshot);
    String snapshotBody = write(snapshot);
    InventorySession saved =
        transactions.execute(
            status -> {
              InventorySession locked = requireActive(inventoryId);
              requireFurnitureReviewCanStart(locked);
              InventoryFindingValidationService.RevisionState lockedRevisions =
                  validationService.revisionState(
                      locked, request.expectedSessionRevision(), request.findingRevisions());
              if (lockedRevisions.findings().size() != revisions.findings().size()) {
                throw InventoryException.conflict("Inventory finding set changed before furniture review");
              }
              locked.beginFurnitureReview(snapshot.snapshotSha256(), snapshotBody);
              return sessions.saveAndFlush(locked);
            });
    return furnitureReviewView(saved);
  }

  public FurnitureReviewView furnitureReview(Jwt jwt, UUID inventoryId) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    requireFurnitureReviewStage(session);
    return furnitureReviewView(session);
  }

  /**
   * Saves furniture observations and carries each finding's exact prior-revision media set to the
   * resulting non-media revision in the same transaction.
   */
  public FurnitureReviewView saveFurnitureReview(
      Jwt jwt, UUID inventoryId, SaveFurnitureReviewRequest request) {
    InventorySession session =
        requireLifecycle(
            requireScopedSession(inventoryId, authorizer.editScope(jwt)), SessionLifecycle.ACTIVE);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    requireFurnitureReviewStage(session);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    FurnitureReviewSubmission submission =
        validateFurnitureReviewSubmission(session, snapshot, request);
    InventorySession saved =
        transactions.execute(
            status -> {
              InventorySession locked = requireActive(inventoryId);
              requireFurnitureReviewStage(locked);
              expectRevision(locked.getRevision(), request.expectedSessionRevision());
              if (!request.assetSnapshotSha256().equals(locked.getFurnitureAssetSnapshotSha256())) {
                throw InventoryException.conflict("Furniture review snapshot is stale");
              }
              List<InventoryFinding> active =
                  findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(inventoryId);
              Map<UUID, InventoryFinding> byId =
                  active.stream()
                      .collect(
                          java.util.stream.Collectors.toMap(
                              InventoryFinding::getId,
                              java.util.function.Function.identity(),
                              (left, right) -> {
                                throw new IllegalStateException("Duplicate inventory finding");
                              },
                              LinkedHashMap::new));
              for (Map.Entry<UUID, Long> expected : submission.findingRevisions().entrySet()) {
                InventoryFinding finding = byId.get(expected.getKey());
                if (finding == null || finding.getRevision() != expected.getValue()) {
                  throw InventoryException.conflict("Furniture review finding revision is stale");
                }
              }
              Map<UUID, Long> mediaSourceRevisions = new LinkedHashMap<>();
              for (Map.Entry<UUID, FurnitureObservation> observation :
                  submission.equipmentObservationByFinding().entrySet()) {
                InventoryFinding finding = byId.get(observation.getKey());
                if (finding == null) {
                  throw InventoryException.conflict("Furniture review finding is no longer active");
                }
                mediaSourceRevisions.put(finding.getId(), finding.getRevision());
                finding.saveFurnitureObservation(
                    observation.getValue().presence(),
                    observation.getValue().body(),
                    actorJson(jwt));
              }
              List<InventoryFinding> reviewedFindings =
                  submission.equipmentObservationByFinding().keySet().stream()
                      .map(byId::get)
                      .toList();
              findings.saveAllAndFlush(reviewedFindings);
              for (InventoryFinding finding : reviewedFindings) {
                findingPersistence.carryForwardMediaReferences(
                    finding.getId(),
                    mediaSourceRevisions.get(finding.getId()),
                    finding.getRevision());
              }
              cabinDispositions.carryForwardFurnitureReview(
                  inventoryId, active, mediaSourceRevisions);
              locked.confirmFurnitureReview(
                  request.assetSnapshotSha256(),
                  submission.reviewSha256(),
                  submission.reviewBody(),
                  actorJson(jwt));
              return sessions.saveAndFlush(locked);
            });
    return furnitureReviewView(saved);
  }

  /** Returns the warehouse-local calendar used to derive final maintenance planning. */

  void validateFurnitureStageTransition(List<CompletionRisk> risks) {
    if (risks.stream().anyMatch(risk -> "CONFLICT".equals(risk.code()))) {
      throw InventoryException.conflict(
          "Урегулируйте конфликты реестра перед сверкой мебели");
    }
    if (risks.stream()
        .anyMatch(
            risk ->
                !"MISSING".equals(risk.code()) && !"NOT_INSPECTED".equals(risk.code()))) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Сверка мебели недоступна, пока в осмотре есть незавершённые изменения");
    }
  }

  /**
   * Furniture is reconciled only for cabins that are still physically in this inventory's
   * warehouse and are eligible for a capture. Accepted registry changes may legitimately leave a
   * historical finding active while its cabin has departed; such a finding is intentionally not a
   * member of the frozen furniture scope.
   */
  List<InventoryFinding> furnitureFindings(
      InventorySession session, List<InventoryFinding> values) {
    Set<UUID> localFindingIds = cabinDispositions.localFindingIds(session, values);
    return values.stream()
        .filter(value -> localFindingIds.contains(value.getId()))
        .filter(value -> value.getAssetId() != null)
        .filter(value -> session.getWarehouseId().equals(value.getCurrentWarehouseId()))
        .filter(value -> CAPTURE_STATUSES.contains(value.getCurrentStatus()))
        .toList();
  }

  List<UUID> furnitureAssetIds(
      InventorySession session, List<InventoryFinding> values) {
    return furnitureFindings(session, values).stream()
        .map(InventoryFinding::getAssetId)
        .distinct()
        .sorted()
        .toList();
  }

  void validateFurnitureSnapshot(
      InventorySession session,
      List<InventoryFinding> activeFindings,
      InventoryDependencyGateway.FurnitureSnapshot snapshot) {
    if (snapshot == null
        || !session.getWarehouseId().equals(snapshot.warehouseId())
        || snapshot.snapshotSha256() == null
        || !snapshot.snapshotSha256().matches("^[0-9a-f]{64}$")
        || snapshot.items() == null) {
      throw InventoryException.dependency("Asset-service returned malformed furniture snapshot");
    }
    Set<UUID> knownAssets = new HashSet<>(furnitureAssetIds(session, activeFindings));
    Set<UUID> equipmentIds = new HashSet<>();
    for (InventoryDependencyGateway.FurnitureSnapshotItem item : snapshot.items()) {
      if (item == null
          || item.equipmentId() == null
          || !equipmentIds.add(item.equipmentId())
          || item.catalogVersion() < 0
          || item.equipmentName() == null
          || item.equipmentName().isBlank()
          || item.currentStockQuantity() < 0
          || (item.stockBalanceVersion() != null && item.stockBalanceVersion() < 0)
          || (item.currentStockQuantity() > 0 && item.stockBalanceVersion() == null)
          || item.cabins() == null) {
        throw InventoryException.dependency("Asset-service returned malformed furniture item");
      }
      Set<UUID> cabinAssets = new HashSet<>();
      for (InventoryDependencyGateway.FurnitureSnapshotCabin cabin : item.cabins()) {
        if (cabin == null
            || cabin.assetId() == null
            || !knownAssets.contains(cabin.assetId())
            || !cabinAssets.add(cabin.assetId())
            || cabin.assetVersion() < 0
            || cabin.displayCanonicalNumber() == null
            || cabin.displayCanonicalNumber().isBlank()
            || cabin.status() == null
            || cabin.status().isBlank()
            || cabin.currentQuantity() < 0) {
          throw InventoryException.dependency("Asset-service returned malformed furniture cabin");
        }
      }
      if (!cabinAssets.equals(knownAssets)) {
        throw InventoryException.dependency(
            "Asset-service returned an incomplete furniture cabin snapshot");
      }
    }
  }

  InventoryDependencyGateway.FurnitureSnapshot furnitureSnapshot(InventorySession session) {
    if (session.getFurnitureAssetSnapshot() == null) {
      throw InventoryException.conflict("Furniture review has not started");
    }
    try {
      InventoryDependencyGateway.FurnitureSnapshot snapshot =
          convert(
              read(session.getFurnitureAssetSnapshot()),
              InventoryDependencyGateway.FurnitureSnapshot.class);
      if (!session.getFurnitureAssetSnapshotSha256().equals(snapshot.snapshotSha256())) {
        throw InventoryException.conflict("Stored furniture review snapshot is inconsistent");
      }
      return snapshot;
    } catch (IllegalArgumentException exception) {
      throw InventoryException.conflict("Stored furniture review snapshot is invalid");
    }
  }

  FurnitureReviewSubmission validateFurnitureReviewSubmission(
      InventorySession session,
      InventoryDependencyGateway.FurnitureSnapshot snapshot,
      SaveFurnitureReviewRequest request) {
    if (!request.assetSnapshotSha256().equals(session.getFurnitureAssetSnapshotSha256())) {
      throw InventoryException.conflict("Furniture review snapshot is stale");
    }
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    validateFurnitureSnapshot(session, active, snapshot);
    List<InventoryFinding> scopedFurnitureFindings = furnitureFindings(session, active);
    Map<UUID, InventoryFinding> findingByAsset = new LinkedHashMap<>();
    for (InventoryFinding finding : scopedFurnitureFindings) {
      if (findingByAsset.put(finding.getAssetId(), finding) != null) {
        throw new IllegalStateException("Inventory furniture asset is bound twice");
      }
    }
    Map<UUID, FurnitureReviewItemInput> submittedByEquipment = new LinkedHashMap<>();
    for (FurnitureReviewItemInput item : request.items()) {
      if (item == null
          || item.equipmentId() == null
          || item.catalogVersion() < 0
          || item.observedStockQuantity() < 0
          || item.cabins() == null
          || submittedByEquipment.put(item.equipmentId(), item) != null) {
        throw new IllegalArgumentException("Furniture review item set is invalid");
      }
    }
    if (submittedByEquipment.size() != snapshot.items().size()) {
      throw InventoryException.conflict("Furniture review item set is incomplete");
    }
    ObjectNode review = mapper.createObjectNode();
    review.put("assetSnapshotSha256", request.assetSnapshotSha256());
    ArrayNode reviewItems = review.putArray("items");
    Map<UUID, Long> findingRevisions = new LinkedHashMap<>();
    Map<UUID, List<ObjectNode>> observations = new LinkedHashMap<>();
    for (InventoryFinding finding : scopedFurnitureFindings) {
      observations.put(finding.getId(), new ArrayList<>());
    }
    for (InventoryDependencyGateway.FurnitureSnapshotItem source :
        snapshot.items().stream()
            .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId))
            .toList()) {
      FurnitureReviewItemInput submitted = submittedByEquipment.get(source.equipmentId());
      if (submitted == null || submitted.catalogVersion() != source.catalogVersion()) {
        throw InventoryException.conflict("Furniture review catalog item changed");
      }
      Map<UUID, FurnitureReviewCabinInput> cabinsByFinding = new LinkedHashMap<>();
      for (FurnitureReviewCabinInput cabin : submitted.cabins()) {
        if (cabin == null
            || cabin.findingId() == null
            || cabin.expectedFindingRevision() < 0
            || cabin.observedQuantity() < 0
            || cabinsByFinding.put(cabin.findingId(), cabin) != null) {
          throw new IllegalArgumentException("Furniture review cabin set is invalid");
        }
      }
      if (cabinsByFinding.size() != source.cabins().size()) {
        throw InventoryException.conflict("Furniture review cabin set is incomplete");
      }
      ObjectNode reviewItem = reviewItems.addObject();
      reviewItem.put("equipmentId", source.equipmentId().toString());
      reviewItem.put("catalogVersion", source.catalogVersion());
      reviewItem.put("observedStockQuantity", submitted.observedStockQuantity());
      ArrayNode reviewCabins = reviewItem.putArray("cabins");
      for (InventoryDependencyGateway.FurnitureSnapshotCabin sourceCabin :
          source.cabins().stream()
              .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotCabin::assetId))
              .toList()) {
        InventoryFinding finding = findingByAsset.get(sourceCabin.assetId());
        if (finding == null) {
          throw InventoryException.conflict("Furniture review cabin is no longer active");
        }
        FurnitureReviewCabinInput submittedCabin = cabinsByFinding.get(finding.getId());
        if (submittedCabin == null
            || submittedCabin.expectedFindingRevision() != finding.getRevision()) {
          throw InventoryException.conflict("Furniture review finding revision is stale");
        }
        Long previous = findingRevisions.put(finding.getId(), finding.getRevision());
        if (previous != null && previous.longValue() != finding.getRevision()) {
          throw new IllegalStateException("Furniture review repeats inconsistent finding revision");
        }
        ObjectNode reviewCabin = reviewCabins.addObject();
        reviewCabin.put("findingId", finding.getId().toString());
        reviewCabin.put("assetId", sourceCabin.assetId().toString());
        reviewCabin.put("observedQuantity", submittedCabin.observedQuantity());
        ObjectNode observation = mapper.createObjectNode();
        observation.put("equipmentId", source.equipmentId().toString());
        observation.put("catalogVersion", source.catalogVersion());
        observation.put("observedQuantity", submittedCabin.observedQuantity());
        observations.computeIfAbsent(finding.getId(), ignored -> new ArrayList<>()).add(observation);
      }
    }
    if (!submittedByEquipment.keySet().equals(
        snapshot.items().stream()
            .map(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId)
            .collect(java.util.stream.Collectors.toSet()))) {
      throw InventoryException.conflict("Furniture review contains an unknown catalog item");
    }
    Map<UUID, FurnitureObservation> observationJson = new LinkedHashMap<>();
    observations.forEach(
        (findingId, entries) -> {
          entries.sort(
              Comparator.<ObjectNode, UUID>comparing(
                      value -> UUID.fromString(value.path("equipmentId").asText()))
                  .thenComparingLong(value -> value.path("catalogVersion").asLong()));
          ArrayNode value = mapper.createArrayNode();
          entries.forEach(value::add);
          observationJson.put(
              findingId,
              new FurnitureObservation(
                  entries.isEmpty() ? ObservationPresence.EXPLICIT_EMPTY : ObservationPresence.PRESENT,
                  write(value)));
        });
    return new FurnitureReviewSubmission(
        write(review),
        canonicalJsonTreeHash(review),
        Map.copyOf(findingRevisions),
        Map.copyOf(observationJson));
  }

  FurnitureReviewView furnitureReviewView(InventorySession session) {
    requireFurnitureReviewStage(session);
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    List<InventoryFinding> scopedFurnitureFindings = furnitureFindings(session, active);
    Map<UUID, InventoryFinding> findingByAsset = new LinkedHashMap<>();
    for (InventoryFinding finding : scopedFurnitureFindings) {
      findingByAsset.put(finding.getAssetId(), finding);
    }
    JsonNode review =
        session.getFurnitureStockObservation() == null
            ? null
            : read(session.getFurnitureStockObservation());
    List<FurnitureReviewItemView> items = new ArrayList<>();
    for (InventoryDependencyGateway.FurnitureSnapshotItem item :
        snapshot.items().stream()
            .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId))
            .toList()) {
      List<FurnitureReviewCabinView> cabins = new ArrayList<>();
      for (InventoryDependencyGateway.FurnitureSnapshotCabin cabin :
          item.cabins().stream()
              .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotCabin::assetId))
              .toList()) {
        InventoryFinding finding = findingByAsset.get(cabin.assetId());
        if (finding == null) continue;
        long observed =
            reviewCabinQuantity(review, item.equipmentId(), finding.getId())
                .or(() -> findingFurnitureQuantity(finding, item.equipmentId(), item.catalogVersion()))
                .orElse(cabin.currentQuantity());
        cabins.add(
            new FurnitureReviewCabinView(
                finding.getId(),
                cabin.assetId(),
                cabin.displayCanonicalNumber(),
                cabin.status(),
                cabin.currentQuantity(),
                observed));
      }
      long observedStock =
          reviewStockQuantity(review, item.equipmentId()).orElse(item.currentStockQuantity());
      items.add(
          new FurnitureReviewItemView(
              item.equipmentId(),
              item.catalogVersion(),
              item.equipmentName(),
              item.currentStockQuantity(),
              observedStock,
              List.copyOf(cabins)));
    }
    return new FurnitureReviewView(
        session.getId(),
        session.getRevision(),
        session.getReviewStage(),
        session.getFurnitureAssetSnapshotSha256(),
        session.getFurnitureReviewSha256(),
        session.getFurnitureReviewSha256() != null,
        List.copyOf(items));
  }

  Optional<Long> reviewStockQuantity(JsonNode review, UUID equipmentId) {
    if (review == null || !review.isObject()) return Optional.empty();
    for (JsonNode item : review.path("items")) {
      if (equipmentId.toString().equals(item.path("equipmentId").asText())) {
        return nonNegativeLong(item.path("observedStockQuantity"));
      }
    }
    return Optional.empty();
  }

  Optional<Long> reviewCabinQuantity(JsonNode review, UUID equipmentId, UUID findingId) {
    if (review == null || !review.isObject()) return Optional.empty();
    for (JsonNode item : review.path("items")) {
      if (!equipmentId.toString().equals(item.path("equipmentId").asText())) continue;
      for (JsonNode cabin : item.path("cabins")) {
        if (findingId.toString().equals(cabin.path("findingId").asText())) {
          return nonNegativeLong(cabin.path("observedQuantity"));
        }
      }
    }
    return Optional.empty();
  }

  Optional<Long> findingFurnitureQuantity(
      InventoryFinding finding, UUID equipmentId, long catalogVersion) {
    if (finding.getEquipmentObservationState() == ObservationPresence.ABSENT
        || finding.getEquipmentObservation() == null) {
      return Optional.empty();
    }
    JsonNode observations = read(finding.getEquipmentObservation());
    if (!observations.isArray()) return Optional.empty();
    for (JsonNode observation : observations) {
      if (equipmentId.toString().equals(observation.path("equipmentId").asText())
          && catalogVersion == observation.path("catalogVersion").asLong(Long.MIN_VALUE)) {
        JsonNode canonicalQuantity = observation.get("quantity");
        return canonicalQuantity == null || canonicalQuantity.isNull()
            ? nonNegativeLong(observation.path("observedQuantity"))
            : nonNegativeLong(canonicalQuantity);
      }
    }
    return Optional.empty();
  }

  Optional<Long> nonNegativeLong(JsonNode value) {
    if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
      return Optional.empty();
    }
    return Optional.of(value.longValue());
  }

  FurnitureCompletionFact requireConfirmedCurrentFurnitureReview(
      InventorySession session, List<InventoryFinding> active) {
    FurnitureCompletionFact review = requireConfirmedFurnitureReview(session);
    requireCurrentFurnitureReviewSnapshot(session, active);
    return review;
  }

  FurnitureCompletionFact requireConfirmedFurnitureReview(InventorySession session) {
    requireFurnitureReviewStage(session);
    if (session.getFurnitureReviewSha256() == null || session.getFurnitureStockObservation() == null) {
      throw InventoryException.conflict("Furniture review must be confirmed before inventory completion");
    }
    if (!canonicalJsonTreeHash(read(session.getFurnitureStockObservation()))
        .equals(session.getFurnitureReviewSha256())) {
      throw InventoryException.conflict("Furniture review acknowledgement is inconsistent");
    }
    return new FurnitureCompletionFact(
        session.getFurnitureAssetSnapshotSha256(),
        session.getFurnitureReviewSha256(),
        read(session.getFurnitureStockObservation()));
  }

  void requireCurrentFurnitureReviewSnapshot(
      InventorySession session, List<InventoryFinding> active) {
    InventoryDependencyGateway.FurnitureSnapshot fresh =
        dependencies.furnitureSnapshot(session.getWarehouseId(), furnitureAssetIds(session, active));
    validateFurnitureSnapshot(session, active, fresh);
    if (!session.getFurnitureAssetSnapshotSha256().equals(fresh.snapshotSha256())) {
      throw InventoryException.conflict("Furniture review snapshot is stale");
    }
  }

  void requireLockedFurnitureReview(
      InventorySession session, FurnitureCompletionFact expected) {
    requireFurnitureReviewStage(session);
    if (!expected.assetSnapshotSha256().equals(session.getFurnitureAssetSnapshotSha256())
        || !expected.reviewSha256().equals(session.getFurnitureReviewSha256())
        || session.getFurnitureStockObservation() == null
        || !canonicalJsonTreeHash(read(session.getFurnitureStockObservation()))
            .equals(expected.reviewSha256())) {
      throw InventoryException.conflict("Furniture review changed before inventory completion");
    }
  }

  void requireCabinReviewStage(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.CABINS) {
      throw InventoryException.conflict("Cabin review is frozen after furniture review starts");
    }
  }

  void requireCabinOrFurnitureReviewStage(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.CABINS
        && session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      throw InventoryException.conflict("Inventory review stage is invalid");
    }
  }

  void requireFurnitureReviewCanStart(InventorySession session) {
    requireCabinOrFurnitureReviewStage(session);
    if (session.getReviewStage() == InventoryReviewStage.FURNITURE
        && session.getFurnitureReviewSha256() != null) {
      throw InventoryException.conflict("Furniture review is already confirmed");
    }
  }

  /**
   * Cabin facts and the furniture snapshot describe the same active population. Any saved cabin
   * inspection or resolved registry conflict therefore invalidates the frozen furniture review;
   * it never mutates or deletes the historical finding facts themselves.
   */
  void invalidateFurnitureReviewAfterCabinChange(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      return;
    }
    session.restartCabinReview();
    furnitureReconciliations.findById(session.getId()).ifPresent(furnitureReconciliations::delete);
    sessions.saveAndFlush(session);
  }

  void requireFurnitureReviewStage(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      throw InventoryException.conflict("Furniture review has not started");
    }
  }

  void createFurnitureLossIntents(InventorySession session) {
    if (session.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw new IllegalStateException("Furniture loss proposals require a completed inventory");
    }
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    JsonNode review = confirmedFurnitureReview(session);
    for (InventoryDependencyGateway.FurnitureSnapshotItem source : snapshot.items()) {
      long observed =
          reviewStockQuantity(review, source.equipmentId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Stored furniture review omits a warehouse stock quantity"));
      if (observed >= source.currentStockQuantity()) {
        continue;
      }
      if (source.stockBalanceVersion() == null) {
        throw new IllegalStateException(
            "A positive warehouse stock balance must carry its optimistic version");
      }
      long shortage = Math.subtractExact(source.currentStockQuantity(), observed);
      UUID findingId =
          stableUuid(
              "rwms:inventory:furniture-loss:"
                  + session.getId()
                  + ":"
                  + source.equipmentId());
      UUID idempotencyKey =
          stableUuid(
              "rwms:inventory:furniture-loss-delivery:"
                  + session.getId()
                  + ":"
                  + source.equipmentId());
      InventoryDependencyGateway.InventoryLossDispositionRequest request =
          new InventoryDependencyGateway.InventoryLossDispositionRequest(
              session.getId(),
              findingId,
              session.getWarehouseId(),
              source.equipmentId(),
              source.equipmentName(),
              source.catalogVersion(),
              shortage,
              source.stockBalanceVersion(),
              "Недостача «"
                  + source.equipmentName()
                  + "» по итогам инвентаризации "
                  + session.getId()
                  + ": ожидалось "
                  + source.currentStockQuantity()
                  + ", фактически "
                  + observed
                  + ".",
              null);
      String requestBody = canonicalWrite(request);
      furnitureLosses.saveAndFlush(
          InventoryFurnitureLossIntent.pending(
              findingId,
              session.getId(),
              session.getWarehouseId(),
              source.equipmentId(),
              idempotencyKey,
              canonicalJsonTreeHash(read(requestBody)),
              requestBody));
    }
  }

  JsonNode confirmedFurnitureReview(InventorySession session) {
    if (session.getFurnitureReviewSha256() == null || session.getFurnitureStockObservation() == null) {
      throw new IllegalStateException("Completed inventory has no confirmed furniture review");
    }
    JsonNode review = read(session.getFurnitureStockObservation());
    if (!review.isObject()
        || !session
            .getFurnitureAssetSnapshotSha256()
            .equals(review.path("assetSnapshotSha256").asText())
        || !session.getFurnitureReviewSha256().equals(canonicalJsonTreeHash(review))) {
      throw new IllegalStateException("Stored furniture review is invalid");
    }
    return review;
  }

  static UUID stableUuid(String source) {
    return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
  }

  void createFurnitureReconciliationIntent(InventorySession session) {
    if (session.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw new IllegalStateException("Furniture reconciliation requires a completed inventory");
    }
    InventoryDependencyGateway.FurnitureReconciliationRequest request =
        furnitureReconciliationRequest(session);
    if (request.items().isEmpty()) {
      return;
    }
    String requestBody = canonicalWrite(request);
    String requestSha256 = canonicalJsonTreeHash(read(requestBody));
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("rwms:inventory:furniture-reconciliation:"
                    + session.getId()
                    + ":"
                    + session.getFurnitureReviewSha256())
                .getBytes(StandardCharsets.UTF_8));
    InventoryFurnitureReconciliationIntent intent =
        InventoryFurnitureReconciliationIntent.pending(
            session.getId(),
            idempotencyKey,
            session.getFurnitureAssetSnapshotSha256(),
            session.getFurnitureReviewSha256(),
            requestSha256,
            requestBody);
    furnitureReconciliations.saveAndFlush(intent);
  }

  InventoryDependencyGateway.FurnitureReconciliationRequest furnitureReconciliationRequest(
      InventorySession session) {
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    JsonNode review = confirmedFurnitureReview(session);
    Map<UUID, JsonNode> reviewedItems = new LinkedHashMap<>();
    for (JsonNode item : review.path("items")) {
      UUID equipmentId = requiredUuid(item, "equipmentId", "furniture review equipment id");
      if (reviewedItems.put(equipmentId, item) != null) {
        throw new IllegalStateException("Stored furniture review repeats an equipment item");
      }
    }
    List<InventoryDependencyGateway.FurnitureReconciliationItem> items = new ArrayList<>();
    for (InventoryDependencyGateway.FurnitureSnapshotItem source :
        snapshot.items().stream()
            .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId))
            .toList()) {
      JsonNode reviewedItem = reviewedItems.remove(source.equipmentId());
      if (reviewedItem == null
          || requiredNonNegativeLong(
                  reviewedItem.path("catalogVersion"), "furniture review catalog version")
              != source.catalogVersion()) {
        throw new IllegalStateException("Stored furniture review does not match its snapshot");
      }
      Map<UUID, JsonNode> reviewedCabins = new LinkedHashMap<>();
      for (JsonNode cabin : reviewedItem.path("cabins")) {
        UUID assetId = requiredUuid(cabin, "assetId", "furniture review cabin asset id");
        if (reviewedCabins.put(assetId, cabin) != null) {
          throw new IllegalStateException("Stored furniture review repeats a cabin");
        }
      }
      List<InventoryDependencyGateway.FurnitureReconciliationCabin> cabins = new ArrayList<>();
      for (InventoryDependencyGateway.FurnitureSnapshotCabin sourceCabin :
          source.cabins().stream()
              .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotCabin::assetId))
              .toList()) {
        JsonNode reviewedCabin = reviewedCabins.remove(sourceCabin.assetId());
        if (reviewedCabin == null) {
          throw new IllegalStateException("Stored furniture review omits a cabin");
        }
        cabins.add(
            new InventoryDependencyGateway.FurnitureReconciliationCabin(
                sourceCabin.assetId(),
                requiredNonNegativeLong(
                    reviewedCabin.path("observedQuantity"), "furniture review cabin quantity")));
      }
      if (!reviewedCabins.isEmpty()) {
        throw new IllegalStateException("Stored furniture review contains an unknown cabin");
      }
      items.add(
          new InventoryDependencyGateway.FurnitureReconciliationItem(
              source.equipmentId(),
              source.catalogVersion(),
              Math.max(
                  source.currentStockQuantity(),
                  requiredNonNegativeLong(
                      reviewedItem.path("observedStockQuantity"),
                      "furniture review stock quantity")),
              List.copyOf(cabins)));
    }
    if (!reviewedItems.isEmpty()) {
      throw new IllegalStateException("Stored furniture review contains an unknown equipment item");
    }
    return new InventoryDependencyGateway.FurnitureReconciliationRequest(
        session.getWarehouseId(),
        session.getFurnitureAssetSnapshotSha256(),
        session.getFurnitureReviewSha256(),
        List.copyOf(items));
  }

  long requiredNonNegativeLong(JsonNode value, String field) {
    return nonNegativeLong(value)
        .orElseThrow(() -> new IllegalStateException("Persisted " + field + " is invalid"));
  }

  void dispatchFurnitureReconciliation(UUID inventoryId) {
    try {
      FurnitureReconciliationDispatch dispatch =
          independentTransactions.execute(
              status -> {
                InventoryFurnitureReconciliationIntent intent =
                    furnitureReconciliations.findByInventoryIdForUpdate(inventoryId).orElse(null);
                if (intent == null) {
                  return null;
                }
                if (intent.getState() != FurnitureReconciliationState.PENDING
                    && intent.getState() != FurnitureReconciliationState.TRANSIENT_FAILED) {
                  return null;
                }
                InventoryDependencyGateway.FurnitureReconciliationRequest request =
                    frozenFurnitureReconciliationRequest(intent);
                intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60));
                InventoryFurnitureReconciliationIntent saved = furnitureReconciliations.saveAndFlush(intent);
                return new FurnitureReconciliationDispatch(
                    saved.getInventoryId(),
                    saved.getIdempotencyKey(),
                    saved.getAttemptCount(),
                    request);
              });
      if (dispatch == null) return;
      try {
        dependencies.reconcileFurniture(
            dispatch.inventoryId(), dispatch.idempotencyKey(), dispatch.request());
      } catch (RuntimeException exception) {
        settleFurnitureReconciliationFailure(inventoryId, dispatch.attemptCount(), exception);
        return;
      }
      independentTransactions.executeWithoutResult(
          status -> {
            InventoryFurnitureReconciliationIntent intent =
                furnitureReconciliations
                    .findByInventoryIdForUpdate(inventoryId)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Furniture reconciliation intent disappeared during dispatch"));
            if (intent.getState() == FurnitureReconciliationState.PENDING
                && intent.getAttemptCount() == dispatch.attemptCount()) {
              intent.succeed();
              furnitureReconciliations.saveAndFlush(intent);
            }
          });
    } catch (RuntimeException exception) {
      log.warn("Furniture reconciliation dispatch deferred for inventory {}", inventoryId, exception);
      settleFurnitureReconciliationFailure(inventoryId, null, exception);
    }
  }

  InventoryDependencyGateway.FurnitureReconciliationRequest frozenFurnitureReconciliationRequest(
      InventoryFurnitureReconciliationIntent intent) {
    JsonNode requestBody = read(intent.getRequestBody());
    if (!canonicalJsonTreeHash(requestBody).equals(intent.getRequestSha256())) {
      throw new IllegalStateException("Furniture reconciliation request snapshot is inconsistent");
    }
    InventoryDependencyGateway.FurnitureReconciliationRequest request =
        convert(requestBody, InventoryDependencyGateway.FurnitureReconciliationRequest.class);
    if (!intent.getAssetSnapshotSha256().equals(request.expectedSnapshotSha256())
        || !intent.getReviewSha256().equals(request.reviewSha256())
        || request.warehouseId() == null
        || request.items() == null) {
      throw new IllegalStateException("Furniture reconciliation request snapshot is invalid");
    }
    for (InventoryDependencyGateway.FurnitureReconciliationItem item : request.items()) {
      if (item == null
          || item.equipmentId() == null
          || item.catalogVersion() < 0
          || item.stockQuantity() < 0
          || item.cabins() == null
          || item.cabins().stream()
              .anyMatch(cabin -> cabin == null || cabin.assetId() == null || cabin.quantity() < 0)) {
        throw new IllegalStateException("Furniture reconciliation request snapshot is invalid");
      }
    }
    return request;
  }

  void dispatchFurnitureLosses(UUID inventoryId) {
    for (InventoryFurnitureLossIntent intent :
        furnitureLosses.findAllByInventoryIdOrderByFindingIdAsc(inventoryId)) {
      if (intent.getState() == FurnitureLossIntentState.PENDING
          || intent.getState() == FurnitureLossIntentState.TRANSIENT_FAILED) {
        dispatchFurnitureLoss(intent.getFindingId());
      }
    }
  }

  void dispatchFurnitureLoss(UUID findingId) {
    FurnitureLossDispatch dispatch;
    try {
      dispatch =
          independentTransactions.execute(
              status -> {
                InventoryFurnitureLossIntent intent =
                    furnitureLosses.findByFindingIdForUpdate(findingId).orElse(null);
                if (intent == null
                    || (intent.getState() != FurnitureLossIntentState.PENDING
                        && intent.getState() != FurnitureLossIntentState.TRANSIENT_FAILED)) {
                  return null;
                }
                InventoryDependencyGateway.InventoryLossDispositionRequest request =
                    frozenFurnitureLossRequest(intent);
                intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60));
                InventoryFurnitureLossIntent saved = furnitureLosses.saveAndFlush(intent);
                return new FurnitureLossDispatch(
                    saved.getFindingId(),
                    saved.getIdempotencyKey(),
                    saved.getAttemptCount(),
                    request);
              });
    } catch (RuntimeException exception) {
      log.warn("Furniture loss proposal dispatch deferred for finding {}", findingId, exception);
      settleFurnitureLossFailure(findingId, null, exception);
      return;
    }
    if (dispatch == null) {
      return;
    }
    InventoryDependencyGateway.InventoryLossDisposition decision;
    try {
      decision =
          dependencies.createInventoryLossDisposition(
              dispatch.idempotencyKey(), dispatch.request());
    } catch (RuntimeException exception) {
      settleFurnitureLossFailure(findingId, dispatch.attemptCount(), exception);
      return;
    }
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryFurnitureLossIntent intent =
              furnitureLosses
                  .findByFindingIdForUpdate(findingId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Furniture loss intent disappeared during dispatch"));
          if (intent.getState() == FurnitureLossIntentState.PENDING
              && intent.getAttemptCount() == dispatch.attemptCount()) {
            intent.succeed(decision.id());
            furnitureLosses.saveAndFlush(intent);
          }
        });
  }

  InventoryDependencyGateway.InventoryLossDispositionRequest frozenFurnitureLossRequest(
      InventoryFurnitureLossIntent intent) {
    JsonNode requestBody = read(intent.getRequestBody());
    if (!canonicalJsonTreeHash(requestBody).equals(intent.getRequestSha256())) {
      throw new IllegalStateException("Furniture loss request snapshot is inconsistent");
    }
    InventoryDependencyGateway.InventoryLossDispositionRequest request =
        convert(requestBody, InventoryDependencyGateway.InventoryLossDispositionRequest.class);
    if (!intent.getInventoryId().equals(request.inventorySessionId())
        || !intent.getFindingId().equals(request.findingId())
        || !intent.getWarehouseId().equals(request.warehouseId())
        || !intent.getEquipmentId().equals(request.equipmentId())
        || request.equipmentName() == null
        || request.equipmentName().isBlank()
        || request.expectedAssetVersion() < 0
        || request.expectedSourceBalanceVersion() < 0
        || request.quantity() < 1
        || request.reason() == null
        || request.reason().isBlank()) {
      throw new IllegalStateException("Furniture loss request snapshot is invalid");
    }
    return request;
  }

  void settleFurnitureLossFailure(
      UUID findingId, Integer attemptCount, RuntimeException failure) {
    try {
      independentTransactions.executeWithoutResult(
          status -> {
            InventoryFurnitureLossIntent intent =
                furnitureLosses.findByFindingIdForUpdate(findingId).orElse(null);
            if (intent == null
                || intent.getState() != FurnitureLossIntentState.PENDING
                || (attemptCount != null && intent.getAttemptCount() != attemptCount)) {
              return;
            }
            if (failure instanceof InventoryException exception
                && (exception.status() == HttpStatus.CONFLICT
                    || exception.status() == HttpStatus.UNPROCESSABLE_ENTITY
                    || exception.status() == HttpStatus.BAD_REQUEST)) {
              intent.block(
                  exception.status() == HttpStatus.CONFLICT
                      ? "MAINTENANCE_DECISION_CONFLICT"
                      : "MAINTENANCE_REQUEST_REJECTED");
            } else if (failure instanceof IllegalStateException) {
              intent.block("FROZEN_REQUEST_CORRUPTED");
            } else {
              long retrySeconds = Math.min(300L, 15L * Math.max(1, intent.getAttemptCount()));
              intent.transientFailure(
                  "MAINTENANCE_SERVICE_UNAVAILABLE",
                  OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(retrySeconds));
            }
            furnitureLosses.saveAndFlush(intent);
          });
    } catch (RuntimeException persistenceFailure) {
      log.error(
          "Could not persist furniture loss retry state for finding {}",
          findingId,
          persistenceFailure);
    }
  }

  void settleFurnitureReconciliationFailure(
      UUID inventoryId, Integer attemptCount, RuntimeException failure) {
    try {
      independentTransactions.executeWithoutResult(
          status -> {
            InventoryFurnitureReconciliationIntent intent =
                furnitureReconciliations.findByInventoryIdForUpdate(inventoryId).orElse(null);
            if (intent == null
                || intent.getState() != FurnitureReconciliationState.PENDING
                || (attemptCount != null && intent.getAttemptCount() != attemptCount)) {
              return;
            }
            if (failure instanceof InventoryException exception
                && exception.status() == HttpStatus.CONFLICT) {
              intent.block("ASSET_SNAPSHOT_CONFLICT");
            } else {
              long retrySeconds = Math.min(300L, 15L * Math.max(1, intent.getAttemptCount()));
              intent.transientFailure(
                  "ASSET_SERVICE_UNAVAILABLE",
                  OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(retrySeconds));
            }
            furnitureReconciliations.saveAndFlush(intent);
          });
    } catch (RuntimeException persistenceFailure) {
      log.error(
          "Could not persist furniture reconciliation retry state for inventory {}",
          inventoryId,
          persistenceFailure);
    }
  }

  /** Reclaims pending furniture effects after an at-least-once remote dispatch failure. */
  @Scheduled(fixedDelayString = "${rwms.inventory.furniture-reconciliation-recovery-delay-ms:5000}")
  public void recoverFurnitureReconciliations() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (InventoryFurnitureReconciliationIntent intent :
        furnitureReconciliations
            .findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscInventoryIdAsc(
                List.of(
                    FurnitureReconciliationState.PENDING,
                    FurnitureReconciliationState.TRANSIENT_FAILED),
                now)) {
      dispatchFurnitureReconciliation(intent.getInventoryId());
    }
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.furniture-loss-recovery-delay-ms:5000}")
  public void recoverFurnitureLosses() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (InventoryFurnitureLossIntent intent :
        furnitureLosses
            .findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscFindingIdAsc(
                List.of(
                    FurnitureLossIntentState.PENDING,
                    FurnitureLossIntentState.TRANSIENT_FAILED),
                now)) {
      dispatchFurnitureLoss(intent.getFindingId());
    }
  }

  /** Normalized furniture-presence evidence extracted from an inventory review submission. */
  record FurnitureObservation(ObservationPresence presence, String body) {}

  /**
   * Canonical review payload and finding revisions used to fence one furniture-review decision.
   */
  record FurnitureReviewSubmission(
      String reviewBody,
      String reviewSha256,
      Map<UUID, Long> findingRevisions,
      Map<UUID, FurnitureObservation> equipmentObservationByFinding) {}

  /** Immutable asset and review evidence retained for post-completion furniture reconciliation. */
  record FurnitureCompletionFact(
      String assetSnapshotSha256, String reviewSha256, JsonNode observation) {}

  /** Stable retry payload for one maintenance-owned furniture-loss decision. */
  record FurnitureLossDispatch(
      UUID findingId,
      UUID idempotencyKey,
      int attemptCount,
      InventoryDependencyGateway.InventoryLossDispositionRequest request) {}

  /** Stable retry payload for one asset-owned inventory furniture reconciliation. */
  record FurnitureReconciliationDispatch(
      UUID inventoryId,
      UUID idempotencyKey,
      int attemptCount,
      InventoryDependencyGateway.FurnitureReconciliationRequest request) {}
}
