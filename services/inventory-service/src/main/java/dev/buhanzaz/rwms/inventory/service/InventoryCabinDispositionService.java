package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionCandidateKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionReview;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionReviewPhase;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionRow;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.repository.InventoryCabinDispositionReviewRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryCabinDispositionRowRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the exact return, shipment and automatic write-off decisions before furniture review.
 *
 * <p>The review source hash includes every active finding revision and classification fact. Any
 * cabin mutation therefore rebuilds only these derived rows while retaining inspections and
 * media. Confirmed rows are later copied into immutable final-plan evidence.
 */
@Service
final class InventoryCabinDispositionService extends InventoryTechnicalRuntimeSupport {
  private static final String AUTOMATIC_WRITE_OFF_REASON =
      "Бытовка не найдена при инвентаризации и не подтверждена как отгруженная";

  private final InventorySessionRepository sessions;
  private final InventoryFindingRepository findings;
  private final InventoryCabinDispositionReviewRepository reviews;
  private final InventoryCabinDispositionRowRepository rows;
  private final InventoryIdempotencyPort idempotency;

  InventoryCabinDispositionService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryCabinDispositionReviewRepository reviews,
      InventoryCabinDispositionRowRepository rows,
      InventoryIdempotencyPort idempotency,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.reviews = reviews;
    this.rows = rows;
    this.idempotency = idempotency;
  }

  /** Returns or rebuilds the current server-owned review for a MANAGE-scoped session. */
  InventoryCabinDispositionReviewView review(Jwt jwt, UUID inventoryId) {
    InventorySession scoped = requireScopedActive(inventoryId, authorizer.manageScope(jwt));
    authorizer.requireManage(jwt, scoped.getWarehouseId());
    ReviewState state = transactions.execute(ignored -> currentState(inventoryId));
    return view(state.session(), state.review(), state.rows());
  }

  /** Confirms every found-rented return under one exact candidate and revision fence. */
  InventoryCabinDispositionReviewView confirmReturns(
      Jwt jwt,
      UUID inventoryId,
      UUID idempotencyKey,
      ConfirmInventoryReturnsRequest request) {
    InventorySession scoped = requireScopedActive(inventoryId, authorizer.manageScope(jwt));
    authorizer.requireManage(jwt, scoped.getWarehouseId());
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.cabin-disposition.returns",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        InventoryCabinDispositionReviewView.class,
        () -> doConfirmReturns(inventoryId, request));
  }

  private InventoryCabinDispositionReviewView doConfirmReturns(
      UUID inventoryId, ConfirmInventoryReturnsRequest request) {
    return transactions.execute(
        ignored -> {
          ReviewState state = currentState(inventoryId);
          expectSessionRevision(state.session(), request.expectedSessionRevision());
          expectReviewRevision(state.review(), request.expectedReviewRevision());
          if (state.review().getPhase() != InventoryCabinDispositionReviewPhase.RETURNS) {
            throw InventoryException.conflict("Inventory return review is already completed");
          }
          List<InventoryCabinDispositionRow> candidates =
              candidateRows(state.rows(), InventoryCabinDispositionCandidateKind.RETURN);
          Map<UUID, InventoryReturnInput> submitted = uniqueReturns(request.returns());
          if (!submitted.keySet().equals(rowIds(candidates))) {
            throw InventoryException.conflict(
                "Return confirmation must contain every found rented cabin exactly once");
          }
          LocalDate today = warehouseToday(state.session());
          ArrayNode canonical = mapper.createArrayNode();
          for (InventoryCabinDispositionRow row : candidates) {
            InventoryReturnInput input = submitted.get(row.getFindingId());
            requireFindingRevision(row, input.expectedFindingRevision());
            requireNotFuture(input.returnedOn(), today, "Return date");
            String client = requiredClientSnapshot(input.clientSnapshot());
            ObjectNode formerRental = mapper.createObjectNode();
            formerRental.put("returnedOn", input.returnedOn().toString());
            formerRental.put("clientId", input.clientId().toString());
            formerRental.put("clientSnapshot", client);
            ObjectNode details = mapper.createObjectNode();
            details.set("formerRental", formerRental);
            row.decide(InventoryCabinDispositionKind.LOCAL, canonicalWrite(details));
            canonical.add(returnDecision(row, formerRental));
          }
          rows.saveAllAndFlush(candidates);
          state.review().confirmReturns(canonicalJsonTreeHash(canonical));
          InventoryCabinDispositionReview saved = reviews.saveAndFlush(state.review());
          return view(state.session(), saved, state.rows());
        });
  }

  /** Confirms selected shipments and turns every omitted missing candidate into a write-off. */
  InventoryCabinDispositionReviewView confirmShipments(
      Jwt jwt,
      UUID inventoryId,
      UUID idempotencyKey,
      ConfirmInventoryShipmentsRequest request) {
    InventorySession scoped = requireScopedActive(inventoryId, authorizer.manageScope(jwt));
    authorizer.requireManage(jwt, scoped.getWarehouseId());
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.cabin-disposition.shipments",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        InventoryCabinDispositionReviewView.class,
        () -> doConfirmShipments(inventoryId, request));
  }

  private InventoryCabinDispositionReviewView doConfirmShipments(
      UUID inventoryId, ConfirmInventoryShipmentsRequest request) {
    return transactions.execute(
        ignored -> {
          ReviewState state = currentState(inventoryId);
          expectSessionRevision(state.session(), request.expectedSessionRevision());
          expectReviewRevision(state.review(), request.expectedReviewRevision());
          if (state.review().getPhase() != InventoryCabinDispositionReviewPhase.SHIPMENTS) {
            throw InventoryException.conflict("Inventory shipment review is not active");
          }
          List<InventoryCabinDispositionRow> candidates =
              candidateRows(state.rows(), InventoryCabinDispositionCandidateKind.MISSING);
          Map<UUID, InventoryShipmentInput> submitted = uniqueShipments(request.shipments());
          if (!rowIds(candidates).containsAll(submitted.keySet())) {
            throw InventoryException.conflict(
                "Shipment confirmation contains a cabin outside the missing set");
          }
          LocalDate today = warehouseToday(state.session());
          ArrayNode canonical = mapper.createArrayNode();
          for (InventoryCabinDispositionRow row : candidates) {
            InventoryShipmentInput input = submitted.get(row.getFindingId());
            if (input == null) {
              ObjectNode writeOff = mapper.createObjectNode();
              writeOff.put("reason", AUTOMATIC_WRITE_OFF_REASON);
              writeOff.putNull("evidenceLink");
              ObjectNode details = mapper.createObjectNode();
              details.set("writeOff", writeOff);
              row.decide(InventoryCabinDispositionKind.WRITE_OFF, canonicalWrite(details));
              canonical.add(writeOffDecision(row, writeOff));
              continue;
            }
            requireFindingRevision(row, input.expectedFindingRevision());
            requireNotFuture(input.departedOn(), today, "Shipment date");
            String client = requiredClientSnapshot(input.clientSnapshot());
            ArrayNode furniture = canonicalFurniture(input.furniture());
            ObjectNode shipment = mapper.createObjectNode();
            shipment.put("departedOn", input.departedOn().toString());
            shipment.put("clientId", input.clientId().toString());
            shipment.put("clientSnapshot", client);
            shipment.set("furniture", furniture);
            ObjectNode details = mapper.createObjectNode();
            details.set("shipment", shipment);
            row.decide(InventoryCabinDispositionKind.SHIPMENT, canonicalWrite(details));
            canonical.add(shipmentDecision(row, shipment));
          }
          rows.saveAllAndFlush(candidates);
          state.review().confirmShipments(canonicalJsonTreeHash(canonical));
          InventoryCabinDispositionReview saved = reviews.saveAndFlush(state.review());
          return view(state.session(), saved, state.rows());
        });
  }

  /** Returns the exact completed decision map used while freezing a final plan. */
  Map<UUID, DispositionSnapshot> requireCompleted(
      InventorySession session, List<InventoryFinding> activeFindings) {
    ReviewState state =
        session.getLifecycle() == SessionLifecycle.ACTIVE
            ? transactions.execute(ignored -> currentState(session.getId()))
            : new ReviewState(
                session,
                reviews
                    .findById(session.getId())
                    .orElseThrow(
                        () ->
                            InventoryException.conflict(
                                "Inventory cabin disposition review is missing")),
                rows.findAllByInventoryIdOrderByFindingIdAsc(session.getId()));
    if (state.review().getPhase() != InventoryCabinDispositionReviewPhase.COMPLETED) {
      throw InventoryException.conflict(
          "Complete return and shipment review before continuing inventory");
    }
    if (!sourceSha256(activeFindings).equals(state.review().getSourceSha256())) {
      throw InventoryException.conflict("Inventory cabin disposition review is stale");
    }
    Map<UUID, DispositionSnapshot> result = new LinkedHashMap<>();
    for (InventoryCabinDispositionRow row : state.rows()) {
      if (row.getDispositionKind() == null || row.getDispositionDetails() == null) {
        throw InventoryException.conflict("Inventory cabin disposition review is incomplete");
      }
      result.put(
          row.getFindingId(),
          new DispositionSnapshot(
              row.getFindingId(),
              row.getFindingRevision(),
              row.getAssetId(),
              row.getAssetVersion(),
              row.getDispositionKind(),
              row.getDispositionDetails()));
    }
    if (!result.keySet().equals(
        activeFindings.stream().map(InventoryFinding::getId).collect(java.util.stream.Collectors.toSet()))) {
      throw InventoryException.conflict("Inventory cabin disposition set is incomplete");
    }
    return Map.copyOf(result);
  }

  /** Returns only cabins physically retained at the warehouse for furniture reconciliation. */
  Set<UUID> localFindingIds(InventorySession session, List<InventoryFinding> activeFindings) {
    Map<UUID, DispositionSnapshot> decisions = requireCompleted(session, activeFindings);
    Set<UUID> result = new HashSet<>();
    decisions.forEach(
        (findingId, decision) -> {
          if (decision.kind() == InventoryCabinDispositionKind.LOCAL) result.add(findingId);
        });
    return Set.copyOf(result);
  }

  /**
   * Carries completed decisions across the furniture observation revision created by the next
   * sequenced review step.
   *
   * <p>The prior full source vector, identity and candidate classification must still match. This
   * narrow carry-forward does not apply to cabin inspection, conflict or registry mutations, which
   * continue to rebuild the review.
   */
  void carryForwardFurnitureReview(
      UUID inventoryId,
      List<InventoryFinding> activeFindings,
      Map<UUID, Long> priorFindingRevisions) {
    InventoryCabinDispositionReview review =
        reviews
            .findByInventoryIdForUpdate(inventoryId)
            .orElseThrow(
                () -> InventoryException.conflict("Inventory cabin disposition review is missing"));
    if (review.getPhase() != InventoryCabinDispositionReviewPhase.COMPLETED
        || !sourceSha256(activeFindings, priorFindingRevisions).equals(review.getSourceSha256())) {
      throw InventoryException.conflict("Inventory cabin disposition evidence is stale");
    }
    Map<UUID, InventoryCabinDispositionRow> byFinding = new LinkedHashMap<>();
    for (InventoryCabinDispositionRow row :
        rows.findAllByInventoryIdOrderByFindingIdAsc(inventoryId)) {
      byFinding.put(row.getFindingId(), row);
    }
    if (!byFinding.keySet().equals(
        activeFindings.stream()
            .map(InventoryFinding::getId)
            .collect(java.util.stream.Collectors.toSet()))) {
      throw InventoryException.conflict("Inventory cabin disposition set is incomplete");
    }
    UUID warehouseId = sessions.findById(inventoryId).orElseThrow().getWarehouseId();
    for (InventoryFinding finding : activeFindings) {
      InventoryCabinDispositionRow row = byFinding.get(finding.getId());
      long priorRevision = priorFindingRevisions.getOrDefault(finding.getId(), finding.getRevision());
      InventoryCabinDispositionCandidateKind currentKind =
          finding.preservesOperationalState(warehouseId)
              ? InventoryCabinDispositionCandidateKind.PRESERVE : candidateKind(finding);
      if (row.getCandidateKind() != currentKind) {
        throw InventoryException.conflict("Inventory cabin disposition classification changed");
      }
      try {
        row.carryForwardFurnitureRevision(
            priorRevision,
            finding.getRevision(),
            finding.getAssetId(),
            finding.getAssetVersion());
      } catch (IllegalStateException exception) {
        throw InventoryException.conflict("Inventory cabin disposition evidence is stale");
      }
    }
    rows.saveAllAndFlush(byFinding.values());
    review.carryForwardFurnitureSource(sourceSha256(activeFindings));
    reviews.saveAndFlush(review);
  }

  private ReviewState currentState(UUID inventoryId) {
    InventorySession session =
        sessions
            .findByIdAndLifecycleForUpdate(inventoryId, SessionLifecycle.ACTIVE)
            .orElseThrow(() -> InventoryException.conflict("Inventory session is not active"));
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(inventoryId);
    String sourceSha256 = sourceSha256(active);
    InventoryCabinDispositionReview review =
        reviews.findByInventoryIdForUpdate(inventoryId).orElse(null);
    if (review != null && sourceSha256.equals(review.getSourceSha256())) {
      return new ReviewState(
          session, review, rows.findAllByInventoryIdOrderByFindingIdAsc(inventoryId));
    }
    List<InventoryCabinDispositionRow> rebuilt = rebuildRows(session, active);
    if (review == null) {
      review = reviews.saveAndFlush(InventoryCabinDispositionReview.start(inventoryId, sourceSha256));
    } else {
      rows.deleteByInventoryId(inventoryId);
      rows.flush();
      review.rebuild(sourceSha256);
      review = reviews.saveAndFlush(review);
    }
    rebuilt = rows.saveAllAndFlush(rebuilt);
    return new ReviewState(session, review, rebuilt);
  }

  private List<InventoryCabinDispositionRow> rebuildRows(
      InventorySession session, List<InventoryFinding> active) {
    List<InventoryCabinDispositionRow> result = new ArrayList<>();
    for (InventoryFinding finding : active) {
      if (finding.getAssetId() == null || finding.getAssetVersion() == null) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_CABIN_DISPOSITION_IDENTITY_MISSING",
            "Resolve every cabin identity before return and shipment review");
      }
      InventoryCabinDispositionCandidateKind candidate =
          finding.preservesOperationalState(session.getWarehouseId())
              ? InventoryCabinDispositionCandidateKind.PRESERVE
              : candidateKind(finding);
      result.add(
          InventoryCabinDispositionRow.candidate(
              session.getId(),
              finding.getId(),
              finding.getRevision(),
              finding.getAssetId(),
              finding.getAssetVersion(),
              finding.getDisplayCanonicalNumber(),
              candidate,
              canonicalWrite(localDetails())));
    }
    return result.stream().sorted(Comparator.comparing(InventoryCabinDispositionRow::getFindingId)).toList();
  }

  private InventoryCabinDispositionCandidateKind candidateKind(InventoryFinding finding) {
    if (finding.getInspection() == InspectionState.NOT_INSPECTED
        && (finding.getOrigin() == FindingOrigin.ADDED_NEW
            || finding.getOrigin() == FindingOrigin.ADDED_USED)) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_NEW_CABIN_NOT_INSPECTED",
          "Inspect every cabin added during inventory before completion review");
    }
    if (finding.getInspection() == InspectionState.NOT_INSPECTED
        || finding.getReconciliation() == ReconciliationState.MISSING) {
      return InventoryCabinDispositionCandidateKind.MISSING;
    }
    String inspectedStatus =
        finding.getInspectionStatus() == null
            ? finding.getCurrentStatus()
            : finding.getInspectionStatus();
    return "RENTED".equals(inspectedStatus)
        ? InventoryCabinDispositionCandidateKind.RETURN
        : InventoryCabinDispositionCandidateKind.LOCAL;
  }

  private String sourceSha256(List<InventoryFinding> active) {
    return sourceSha256(active, Map.of());
  }

  private String sourceSha256(
      List<InventoryFinding> active, Map<UUID, Long> findingRevisionOverrides) {
    List<Map<String, Object>> source = new ArrayList<>();
    for (InventoryFinding finding : active.stream().sorted(Comparator.comparing(InventoryFinding::getId)).toList()) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("findingId", finding.getId());
      value.put(
          "findingRevision",
          findingRevisionOverrides.getOrDefault(finding.getId(), finding.getRevision()));
      value.put("inspection", finding.getInspection());
      value.put("reconciliation", finding.getReconciliation());
      value.put("assetId", finding.getAssetId());
      value.put("assetVersion", finding.getAssetVersion());
      value.put("inspectionStatus", finding.getInspectionStatus());
      value.put("currentStatus", finding.getCurrentStatus());
      source.add(value);
    }
    return canonicalHash(source);
  }

  private InventoryCabinDispositionReviewView view(
      InventorySession session,
      InventoryCabinDispositionReview review,
      List<InventoryCabinDispositionRow> allRows) {
    return new InventoryCabinDispositionReviewView(
        session.getId(),
        session.getRevision(),
        review.getRevision(),
        review.getPhase(),
        candidateViews(allRows, InventoryCabinDispositionCandidateKind.RETURN),
        candidateViews(allRows, InventoryCabinDispositionCandidateKind.MISSING));
  }

  private List<InventoryCabinDispositionCandidateView> candidateViews(
      List<InventoryCabinDispositionRow> allRows,
      InventoryCabinDispositionCandidateKind candidateKind) {
    return candidateRows(allRows, candidateKind).stream()
        .map(
            row ->
                new InventoryCabinDispositionCandidateView(
                    row.getFindingId(),
                    row.getFindingRevision(),
                    row.getAssetId(),
                    row.getAssetVersion(),
                    row.getDisplayCanonicalNumber(),
                    row.getCandidateKind(),
                    row.getDispositionKind(),
                    row.getDispositionDetails() == null ? null : read(row.getDispositionDetails())))
        .toList();
  }

  private static List<InventoryCabinDispositionRow> candidateRows(
      List<InventoryCabinDispositionRow> values,
      InventoryCabinDispositionCandidateKind candidateKind) {
    return values.stream()
        .filter(value -> value.getCandidateKind() == candidateKind)
        .sorted(Comparator.comparing(InventoryCabinDispositionRow::getFindingId))
        .toList();
  }

  private static Set<UUID> rowIds(List<InventoryCabinDispositionRow> values) {
    return values.stream().map(InventoryCabinDispositionRow::getFindingId).collect(java.util.stream.Collectors.toSet());
  }

  private static Map<UUID, InventoryReturnInput> uniqueReturns(List<InventoryReturnInput> values) {
    Map<UUID, InventoryReturnInput> result = new LinkedHashMap<>();
    for (InventoryReturnInput value : values) {
      if (value == null || result.put(value.findingId(), value) != null) {
        throw new IllegalArgumentException("Return confirmation contains duplicates");
      }
    }
    return result;
  }

  private static Map<UUID, InventoryShipmentInput> uniqueShipments(
      List<InventoryShipmentInput> values) {
    Map<UUID, InventoryShipmentInput> result = new LinkedHashMap<>();
    for (InventoryShipmentInput value : values) {
      if (value == null || result.put(value.findingId(), value) != null) {
        throw new IllegalArgumentException("Shipment confirmation contains duplicates");
      }
    }
    return result;
  }

  private ArrayNode canonicalFurniture(List<InventoryShipmentFurnitureInput> values) {
    Set<UUID> ids = new HashSet<>();
    List<InventoryShipmentFurnitureInput> canonical = new ArrayList<>(values.size());
    for (InventoryShipmentFurnitureInput value : values) {
      if (value == null
          || value.equipmentId() == null
          || !ids.add(value.equipmentId())
          || value.catalogVersion() < 0
          || value.quantity() < 1) {
        throw new IllegalArgumentException("Shipment furniture must contain unique positive rows");
      }
      canonical.add(value);
    }
    canonical.sort(Comparator.comparing(InventoryShipmentFurnitureInput::equipmentId));
    ArrayNode result = mapper.createArrayNode();
    for (InventoryShipmentFurnitureInput value : canonical) {
      result
          .addObject()
          .put("equipmentId", value.equipmentId().toString())
          .put("catalogVersion", value.catalogVersion())
          .put("quantity", value.quantity());
    }
    return result;
  }

  private ObjectNode localDetails() {
    ObjectNode result = mapper.createObjectNode();
    result.putNull("formerRental");
    return result;
  }

  private ObjectNode returnDecision(InventoryCabinDispositionRow row, ObjectNode formerRental) {
    ObjectNode result = mapper.createObjectNode();
    result.put("findingId", row.getFindingId().toString());
    result.put("findingRevision", row.getFindingRevision());
    result.set("formerRental", formerRental);
    return result;
  }

  private ObjectNode shipmentDecision(InventoryCabinDispositionRow row, ObjectNode shipment) {
    ObjectNode result = mapper.createObjectNode();
    result.put("findingId", row.getFindingId().toString());
    result.put("findingRevision", row.getFindingRevision());
    result.put("dispositionKind", InventoryCabinDispositionKind.SHIPMENT.name());
    result.set("shipment", shipment);
    return result;
  }

  private ObjectNode writeOffDecision(InventoryCabinDispositionRow row, ObjectNode writeOff) {
    ObjectNode result = mapper.createObjectNode();
    result.put("findingId", row.getFindingId().toString());
    result.put("findingRevision", row.getFindingRevision());
    result.put("dispositionKind", InventoryCabinDispositionKind.WRITE_OFF.name());
    result.set("writeOff", writeOff);
    return result;
  }

  private static void requireFindingRevision(
      InventoryCabinDispositionRow row, long expectedFindingRevision) {
    if (row.getFindingRevision() != expectedFindingRevision) {
      throw InventoryException.conflict("Inventory disposition finding revision is stale");
    }
  }

  private static void requireNotFuture(LocalDate value, LocalDate today, String label) {
    if (value == null || value.isAfter(today)) {
      throw new IllegalArgumentException(label + " cannot be after the current warehouse date");
    }
  }

  private static String requiredClientSnapshot(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 512) {
      throw new IllegalArgumentException("Client snapshot is required");
    }
    return value.trim();
  }

  private static LocalDate warehouseToday(InventorySession session) {
    return LocalDate.now(ZoneId.of(session.getWarehouseTimeZone()));
  }

  private static void expectSessionRevision(InventorySession session, long expected) {
    if (session.getRevision() != expected) {
      throw InventoryException.conflict("Inventory revision is stale");
    }
  }

  private static void expectReviewRevision(
      InventoryCabinDispositionReview review, long expected) {
    if (review.getRevision() != expected) {
      throw InventoryException.conflict("Inventory disposition review revision is stale");
    }
  }

  private InventorySession requireScopedActive(
      UUID inventoryId, InventoryAuthorizer.WarehouseScope scope) {
    if (!scope.unrestricted() && scope.warehouseIds().isEmpty()) {
      throw InventoryException.notFound("Inventory session not found");
    }
    InventorySession session =
        (scope.unrestricted()
                ? sessions.findById(inventoryId)
                : sessions.findByIdAndWarehouseIdIn(inventoryId, scope.warehouseIds()))
            .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
    if (session.getLifecycle() != SessionLifecycle.ACTIVE) {
      throw InventoryException.conflict("Inventory session is not active");
    }
    return session;
  }

  /** Immutable final-plan copy of one completed disposition row. */
  record DispositionSnapshot(
      UUID findingId,
      long findingRevision,
      UUID assetId,
      long assetVersion,
      InventoryCabinDispositionKind kind,
      String details) {}

  /** Aggregate and rows loaded under one transaction and source fence. */
  private record ReviewState(
      InventorySession session,
      InventoryCabinDispositionReview review,
      List<InventoryCabinDispositionRow> rows) {}
}
