package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.ConflictResolutionStrategy;
import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovement;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.InventorySourceAttachment;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMembershipMovementRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns mutable cabin-finding workflow: membership projection, source-asset creation, inspection
 * evidence, media generations and registry-conflict decisions.
 *
 * <p>Remote reads and source/frozen-plan calls stay outside local transactions; each local
 * mutation then rechecks its revision fence before emitting finding facts.
 */
@Service
final class InventoryFindingService extends InventoryFindingWorkflowSupport {
  private final InventoryFindingConflictPolicy conflictPolicy;

  InventoryFindingService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryExpectedItemRepository expectedItems,
      InventoryMembershipMovementRepository membershipMovements,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryIdempotencyPort idempotency,
      InventoryFrozenPlanFingerprint frozenPlanFingerprint,
      InventoryFindingValidationService validationService,
      InventoryReviewService reviewService,
      InventoryPlanningService planningService,
      InventoryProjectionService projectionService,
      InventoryFindingPersistenceService findingPersistence,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        sessions,
        findings,
        expectedItems,
        membershipMovements,
        dependencies,
        events,
        idempotency,
        frozenPlanFingerprint,
        validationService,
        reviewService,
        planningService,
        projectionService,
        findingPersistence,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
    conflictPolicy = new InventoryFindingConflictPolicy(mapper, canonicalJson);
  }

  /**
   * Applies an at-least-once asset membership signal under a local transaction after reading
   * the current asset snapshot. The inbox supplies the correlation and causation identity.
   */
  public void reconcileAssetMembership(
      UUID assetId,
      OpaqueActorReference sourceActor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt) {
    reconcileAssetMembership(assetId, (MembershipSignal) null, sourceActor, correlationId, causationId, occurredAt);
  }

  void reconcileAssetMembership(
      UUID assetId,
      MembershipSignal signal,
      OpaqueActorReference sourceActor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt) {
    if (assetId == null
        || correlationId == null
        || causationId == null
        || occurredAt == null) {
      throw new IllegalArgumentException("Asset membership event identity is incomplete");
    }
    Optional<InventoryDependencyGateway.LiveAssetSnapshot> current =
        dependencies.currentAsset(assetId);
    if (current.isPresent() && !RENTAL_ITEM_STATUSES.contains(current.get().status())) {
      throw InventoryException.dependency("Asset-service returned an unknown rental-item status");
    }
    if (signal != null && (current.isEmpty() || current.get().version() < signal.version())) {
      throw InventoryException.dependency("Asset-service snapshot has not reached the membership event");
    }
    OpaqueActorReference actor = normalizedAssetActor(sourceActor);
    MembershipSignal effectiveSignal = signal != null || current.isEmpty() ? signal
        : new MembershipSignal(current.get().version(), current.get().warehouseId(), current.get().status());
    transactions.executeWithoutResult(
        ignored ->
            reconcileAssetMembership(
                assetId,
                current.orElse(null),
                actor,
                correlationId,
                causationId,
                occurredAt,
                null,
                effectiveSignal));
  }

  /**
   * Reconciles one complete warehouse capture inside the caller's fenced local transaction.
   *
   * <p>The capture is authoritative for eligible membership: active target-session assets absent
   * from it are journalled as departures, while every captured member follows the same local
   * arrival/snapshot transition as an asset membership event. No remote dependency is called and
   * no finding, inspection, media reference or movement history is deleted.
   */
  void reconcileCapturedMembership(
      InventorySession targetSession,
      List<InventoryDependencyGateway.CaptureMember> capturedMembers,
      OpaqueActorReference sourceActor,
      UUID correlationId,
      UUID operationId,
      OffsetDateTime occurredAt) {
    if (targetSession == null
        || targetSession.getLifecycle() != SessionLifecycle.ACTIVE
        || capturedMembers == null
        || correlationId == null
        || operationId == null
        || occurredAt == null) {
      throw new IllegalArgumentException("Captured inventory membership input is incomplete");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Captured inventory membership requires the fenced command transaction");
    }
    Map<UUID, InventoryDependencyGateway.CaptureMember> capturedByAsset =
        new LinkedHashMap<>();
    Set<String> capturedMatchKeys = new HashSet<>();
    for (InventoryDependencyGateway.CaptureMember member :
        capturedMembers.stream()
            .sorted(Comparator.comparing(InventoryDependencyGateway.CaptureMember::assetId))
            .toList()) {
      if (member == null
          || member.assetId() == null
          || !targetSession.getWarehouseId().equals(member.warehouseId())
          || !CAPTURE_STATUSES.contains(member.status())
          || member.identityMatchKey() == null
          || member.identityMatchKey().isBlank()
          || capturedByAsset.put(member.assetId(), member) != null
          || !capturedMatchKeys.add(member.identityMatchKey())) {
        throw InventoryException.dependency("Asset capture membership is invalid");
      }
    }

    OpaqueActorReference actor = normalizedAssetActor(sourceActor);
    List<UUID> activeAssetIds =
        findings.findAllByInventoryIdForUpdateOrderById(targetSession.getId()).stream()
            .filter(InventoryFinding::isMembershipActive)
            .map(InventoryFinding::getAssetId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    for (UUID assetId : activeAssetIds) {
      if (!capturedByAsset.containsKey(assetId)) {
        reconcileAssetMembership(
            assetId,
            null,
            actor,
            correlationId,
            capturedMembershipCausation(operationId, assetId),
            occurredAt,
            targetSession.getId());
      }
    }
    for (InventoryDependencyGateway.CaptureMember member : capturedByAsset.values()) {
      reconcileAssetMembership(
          member.assetId(),
          new InventoryDependencyGateway.LiveAssetSnapshot(
              member.assetId(),
              member.version(),
              member.warehouseId(),
              member.status(),
              member.displayCanonicalNumber(),
              member.identityMatchKey(),
              tenantSnapshot(member.passportSnapshot()),
              member.passportSnapshot(),
              member.contentsSnapshot()),
          actor,
          correlationId,
          capturedMembershipCausation(operationId, member.assetId()),
          occurredAt,
          null);
    }
  }

  private UUID capturedMembershipCausation(UUID operationId, UUID assetId) {
    return UUID.nameUUIDFromBytes(
        ("rwms:inventory:refresh:" + operationId + ":" + assetId)
            .getBytes(StandardCharsets.UTF_8));
  }

  private void reconcileAssetMembership(
      UUID assetId,
      InventoryDependencyGateway.LiveAssetSnapshot current,
      OpaqueActorReference actor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt) {
    reconcileAssetMembership(
        assetId, current, actor, correlationId, causationId, occurredAt, null);
  }

  private void reconcileAssetMembership(
      UUID assetId,
      InventoryDependencyGateway.LiveAssetSnapshot current,
      OpaqueActorReference actor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt,
      UUID departureInventoryId) {
    reconcileAssetMembership(assetId, current, actor, correlationId, causationId, occurredAt,
        departureInventoryId, null);
  }

  private void reconcileAssetMembership(
      UUID assetId,
      InventoryDependencyGateway.LiveAssetSnapshot current,
      OpaqueActorReference actor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt,
      UUID departureInventoryId,
      MembershipSignal signal) {
    UUID currentWarehouseId = signal != null ? signal.warehouseId() : current == null ? null : current.warehouseId();
    String membershipStatus = signal != null ? signal.status() : current == null ? null : current.status();
    boolean eligible = current != null && CAPTURE_STATUSES.contains(membershipStatus);

    for (InventoryFinding finding :
        findings.findInActiveSessionsByAssetIdForUpdate(assetId)) {
      if (departureInventoryId != null
          && !departureInventoryId.equals(finding.getInventoryId())) {
        continue;
      }
      InventorySession session =
          sessions
              .findByIdAndLifecycleForUpdate(
                  finding.getInventoryId(), SessionLifecycle.ACTIVE)
              .orElse(null);
      if (session == null) continue;
      long mediaSourceRevision = finding.getRevision();
      UUID previousEventWarehouse = finding.getMembershipEventWarehouseId();
      String previousEventStatus = finding.getMembershipEventStatus();
      if (signal != null && !finding.advanceMembershipEvent(signal.version(), signal.warehouseId(), signal.status())) {
        continue;
      }
      boolean previouslyPresent = session.getWarehouseId().equals(previousEventWarehouse)
          && physicallyPresentStatus(previousEventStatus);
      boolean departed =
          current == null
              || !eligible
              || !currentWarehouseId.equals(session.getWarehouseId());
      boolean physicalDeparture = currentWarehouseId != null
          && (!currentWarehouseId.equals(session.getWarehouseId())
              || "RENTED".equals(membershipStatus) || "IN_TRANSFER".equals(membershipStatus));
      if (physicalDeparture) finding.retainInspectionBeforeDeparture();
      UUID previousWarehouseId = finding.getCurrentWarehouseId();
      String previousStatus = finding.getCurrentStatus();
      String previousTenant = finding.getCurrentTenantSnapshot();
      boolean snapshotChanged =
          !java.util.Objects.equals(
                  finding.getAssetVersion(), current == null ? null : current.version())
              || !java.util.Objects.equals(
                  finding.getCurrentWarehouseId(),
                  current == null ? null : current.warehouseId())
              || !java.util.Objects.equals(
                  finding.getCurrentStatus(), current == null ? null : current.status())
              || !java.util.Objects.equals(
                  finding.getCurrentTenantSnapshot(),
                  current == null ? null : current.tenantSnapshot())
              || !java.util.Objects.equals(
                  finding.getCurrentDisplayCanonicalNumber(),
                  current == null ? null : current.displayCanonicalNumber())
              || !storedJsonEquals(
                  finding.getCurrentPassportSnapshot(),
                  current == null ? null : current.passportSnapshot())
              || !storedJsonEquals(
                  finding.getCurrentContentsSnapshot(),
                  current == null ? null : current.contentsSnapshot());
      boolean preserveExplicitObservation = current == null
          && (finding.isExplicitObservation() || finding.getInspection() != InspectionState.NOT_INSPECTED);
      boolean snapshotRefreshed =
          snapshotChanged
              && (current == null || finding.getAssetVersion() == null || current.version() >= finding.getAssetVersion())
              && !preserveExplicitObservation
              && (finding.getMutationState()
                      == dev.buhanzaz.rwms.inventory.domain.MutationState.IDLE
                  || finding.getMutationState()
                      == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATED);
      if (snapshotRefreshed) {
        JsonNode repairs =
            current == null || finding.getCurrentRepairsSnapshot() == null
                ? mapper.createArrayNode()
                : boundedSafeSnapshot(
                    finding.getCurrentRepairsSnapshot(), true, "current repairs");
        CurrentItemSnapshot live =
            current == null
                ? null
                : new CurrentItemSnapshot(
                    current.assetId(),
                    current.version(),
                    current.warehouseId(),
                    current.status(),
                    current.displayCanonicalNumber(),
                    current.tenantSnapshot(),
                    current.passportSnapshot(),
                    current.contentsSnapshot(),
                    repairs);
        ReconciliationState nextReconciliation =
            current == null
                ? ReconciliationState.MISSING
                : finding.getInspection() == InspectionState.NOT_INSPECTED
                    ? ReconciliationState.MATCHED
                    : conflictPolicy.conflictViews(finding, session.getWarehouseId(), live).isEmpty()
                        ? ReconciliationState.MATCHED
                        : ReconciliationState.CONFLICT;
        finding.refreshCurrentAsset(
            current == null ? null : current.version(),
            current == null ? null : current.warehouseId(),
            current == null ? null : current.status(),
            current == null ? null : current.tenantSnapshot(),
            current == null ? null : current.displayCanonicalNumber(),
            current == null ? null : json(current.passportSnapshot()),
            current == null ? null : json(current.contentsSnapshot()),
            current == null
                ? null
                : finding.getCurrentRepairsSnapshot() == null
                    ? "[]"
                    : finding.getCurrentRepairsSnapshot(),
            nextReconciliation);
      }
      // Automatic EXPECTED population follows registry departures. Explicit operator observations
      // remain in the table and final-plan population: an eligibility filter or later capture may
      // not erase the fact that the cabin was physically found during this inventory.
      boolean membershipChanged = departed && !finding.isExplicitObservation()
          && finding.getInspection() == InspectionState.NOT_INSPECTED && finding.changeMembership(false);
      boolean departureMovement = membershipChanged || (physicalDeparture && previouslyPresent);
      boolean arrivalMovement = signal != null && currentWarehouseId.equals(session.getWarehouseId())
          && physicallyPresentStatus(membershipStatus) && !previouslyPresent;
      if (!snapshotRefreshed && !membershipChanged && signal == null && !departureMovement) continue;
      InventoryFinding savedFinding = findings.saveAndFlush(finding);
      findingPersistence.carryForwardMediaReferences(
          savedFinding.getId(), mediaSourceRevision, savedFinding.getRevision());
      if (membershipChanged && finding.getOrigin() == FindingOrigin.EXPECTED) {
        session.changeExpectedPopulation(-1);
      }
      reviewService.invalidateFurnitureReviewAfterCabinChange(session);
      planningService.invalidateFinalPlan(session);
      session.touch();
      sessions.saveAndFlush(session);
      if (departureMovement) {
        if (!finding.getOrigin().isInventoryAddition()) {
          membershipMovements.saveAndFlush(
              InventoryMembershipMovement.departed(
                  session.getId(),
                  causationId,
                  assetId,
                  finding.getOrigin(),
                  finding.getDisplayCanonicalNumber(),
                  session.getWarehouseId(),
                  currentWarehouseId != null && !currentWarehouseId.equals(session.getWarehouseId())
                      ? currentWarehouseId
                      : null,
                  membershipStatus == null ? previousStatus : membershipStatus,
                  current == null ? previousTenant : current.tenantSnapshot(),
                  occurredAt));
        }
        appendFindingFacts(
            finding,
            session,
            actor,
            "inventory.finding.membership-departed.v1",
            correlationId,
            causationId);
      } else if (arrivalMovement) {
        if (!finding.getOrigin().isInventoryAddition()) {
          membershipMovements.saveAndFlush(InventoryMembershipMovement.arrived(
              session.getId(), causationId, assetId, finding.getOrigin(),
              finding.getDisplayCanonicalNumber(), previousEventWarehouse, session.getWarehouseId(),
              membershipStatus, current.tenantSnapshot(), occurredAt));
        }
        appendFindingFacts(finding, session, actor, "inventory.finding.membership-refreshed.v1",
            correlationId, causationId);
      } else if (snapshotRefreshed) {
        appendFindingFacts(finding, session, actor, "inventory.finding.membership-refreshed.v1",
            correlationId, causationId);
      }
    }

    if (!eligible) return;
    InventorySession session =
        sessions
            .findByWarehouseIdAndLifecycleForUpdate(
                currentWarehouseId, SessionLifecycle.ACTIVE)
            .orElse(null);
    if (session == null) return;
    InventoryFinding existing =
        findings
            .findActiveByInventoryIdAndIdentityMatchKeyForUpdate(
                session.getId(), current.identityMatchKey())
            .orElse(null);
    if (existing != null) {
      if (existing.getAssetId() == null) {
        return;
      }
      if (!assetId.equals(existing.getAssetId())) {
        throw InventoryException.conflict(
            "Live inventory asset identity is already bound to another cabin");
      }
      boolean snapshotChanged =
          !java.util.Objects.equals(existing.getAssetVersion(), current.version())
              || !java.util.Objects.equals(
                  existing.getCurrentWarehouseId(), current.warehouseId())
              || !java.util.Objects.equals(existing.getCurrentStatus(), current.status())
              || !java.util.Objects.equals(
                  existing.getCurrentTenantSnapshot(), current.tenantSnapshot());
      if (!snapshotChanged) return;
      long mediaSourceRevision = existing.getRevision();
      existing.refreshCurrentAsset(
          current.version(),
          current.warehouseId(),
          current.status(),
          current.tenantSnapshot(),
          current.displayCanonicalNumber(),
          json(current.passportSnapshot()),
          json(current.contentsSnapshot()),
          existing.getCurrentRepairsSnapshot() == null
              ? "[]"
              : existing.getCurrentRepairsSnapshot(),
          ReconciliationState.MATCHED);
      InventoryFinding savedFinding = findings.saveAndFlush(existing);
      findingPersistence.carryForwardMediaReferences(
          savedFinding.getId(), mediaSourceRevision, savedFinding.getRevision());
      reviewService.invalidateFurnitureReviewAfterCabinChange(session);
      planningService.invalidateFinalPlan(session);
      session.touch();
      sessions.saveAndFlush(session);
      appendFindingFacts(
          existing,
          session,
          actor,
          "inventory.finding.membership-refreshed.v1",
          correlationId,
          causationId);
      return;
    }

    UUID expectedId = UUID.randomUUID();
    InventoryFinding finding =
        InventoryFinding.expected(
            session.getId(),
            expectedId,
            current.assetId(),
            signal == null ? current.version() : signal.version(),
            currentWarehouseId,
            membershipStatus,
            current.tenantSnapshot(),
            current.displayCanonicalNumber(),
            current.identityMatchKey(),
            write(actor));
    finding.refreshCurrentAsset(
        current.version(),
        current.warehouseId(),
        current.status(),
        current.tenantSnapshot(),
        current.displayCanonicalNumber(),
        json(current.passportSnapshot()),
        json(current.contentsSnapshot()),
        "[]",
        ReconciliationState.MATCHED);
    finding = findings.saveAndFlush(finding);
    expectedItems.saveAndFlush(
        new InventoryExpectedItem(
            expectedId,
            session.getId(),
            finding.getId(),
            Math.addExact(expectedItems.maximumOrder(session.getId()), 1),
            current.assetId(),
            current.version(),
            membershipStatus,
            current.displayCanonicalNumber(),
            current.identityMatchKey(),
            current.passportSnapshot() == null ? "{}" : json(current.passportSnapshot()),
            current.contentsSnapshot() == null ? "[]" : json(current.contentsSnapshot())));
    session.changeExpectedPopulation(1);
    reviewService.invalidateFurnitureReviewAfterCabinChange(session);
    planningService.invalidateFinalPlan(session);
    session.touch();
    sessions.saveAndFlush(session);
    membershipMovements.saveAndFlush(
        InventoryMembershipMovement.arrived(
            session.getId(),
            causationId,
            assetId,
            finding.getOrigin(),
            finding.getDisplayCanonicalNumber(),
            null,
            session.getWarehouseId(),
            membershipStatus,
            current.tenantSnapshot(),
            occurredAt));
    appendFindingFacts(
        finding,
        session,
        actor,
        "inventory.finding.added.v1",
        correlationId,
        causationId);
  }

  public NumberResolutionView resolveNumber(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, ResolveNumberRequest request) {
    requireScopedSession(inventoryId, authorizer.editScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.resolve-number",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        NumberResolutionView.class,
        () -> doResolveNumber(jwt, inventoryId, idempotencyKey, request));
  }

  /** Ordered producer facts remain separate from the potentially newer HTTP read projection. */
  record MembershipSignal(long version, UUID warehouseId, String status) {}

  private static boolean physicallyPresentStatus(String status) {
    return status != null && !Set.of("RENTED", "IN_TRANSFER", "LOST", "WRITTEN_OFF").contains(status);
  }

  /**
   * Imports logistics-owned inspection proof inside the caller's locked active-session transaction.
   * Earlier inventory photos/plans remain historical and are never copied into this new revision.
   */
  InventoryFinding importNormalReturnInspection(
      InventorySession session,
      InventoryDependencyGateway.NormalReturnInspectionLine proofLine,
      InventoryDependencyGateway.LiveAssetSnapshot current,
      NormalReturnImportContext context) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || session.getLifecycle() != SessionLifecycle.ACTIVE
        || !proofLine.assetId().equals(current.assetId())
        || current.version() < proofLine.assetVersion()) {
      throw new IllegalStateException("Return inspection import requires fenced current asset proof");
    }
    List<InventoryFinding> sessionFindings = findings.findAllByInventoryIdForUpdateOrderById(session.getId());
    InventoryFinding finding = sessionFindings.stream()
        .filter(value -> proofLine.assetId().equals(value.getAssetId()))
        .sorted(Comparator.comparing(InventoryFinding::isMembershipActive).reversed())
        .findFirst().orElse(null);
    if (finding != null && finding.getInspection() != InspectionState.NOT_INSPECTED) {
      Long inspectedVersion = finding.getInspectionSource() == dev.buhanzaz.rwms.inventory.domain.InspectionSource.LOGISTICS_RETURN
          ? finding.getExternalInspectionAssetVersion() : finding.getInspectionAssetVersion();
      if (inspectedVersion != null && inspectedVersion >= proofLine.assetVersion()) return finding;
    }
    boolean created = finding == null;
    if (created) {
      if (sessionFindings.stream().anyMatch(value -> value.isMembershipActive()
          && value.getIdentityMatchKey().equals(current.identityMatchKey()))) {
        throw InventoryException.conflict("Return cabin number is already bound to another inventory finding");
      }
      UUID expectedId = UUID.randomUUID();
      finding = InventoryFinding.expected(session.getId(), expectedId, current.assetId(), current.version(),
          current.warehouseId(), current.status(), current.tenantSnapshot(),
          current.displayCanonicalNumber(), current.identityMatchKey(), write(context.actor()));
      finding = findings.saveAndFlush(finding);
      expectedItems.saveAndFlush(new InventoryExpectedItem(expectedId, session.getId(), finding.getId(),
          Math.addExact(expectedItems.maximumOrder(session.getId()), 1), current.assetId(), current.version(),
          current.status(), current.displayCanonicalNumber(), current.identityMatchKey(),
          json(current.passportSnapshot()), json(current.contentsSnapshot())));
      session.changeExpectedPopulation(1);
    } else if (finding.changeMembership(true) && finding.getOrigin() == FindingOrigin.EXPECTED) {
      session.changeExpectedPopulation(1);
    }
    if (finding.getAssetVersion() == null || current.version() >= finding.getAssetVersion()) {
      finding.refreshCurrentAsset(current.version(), current.warehouseId(), current.status(),
          current.tenantSnapshot(), current.displayCanonicalNumber(), json(current.passportSnapshot()),
          json(current.contentsSnapshot()), finding.getCurrentRepairsSnapshot() == null ? "[]" : finding.getCurrentRepairsSnapshot(),
          ReconciliationState.MATCHED);
    }
    finding.importReturnInspection(proofLine.assetVersion(), write(context.actor()));
    finding = findings.saveAndFlush(finding);
    reviewService.invalidateFurnitureReviewAfterCabinChange(session);
    planningService.invalidateFinalPlan(session);
    session.touch();
    sessions.saveAndFlush(session);
    if (created && !context.arrivedAt().isBefore(session.getStartedAt())) {
      membershipMovements.saveAndFlush(InventoryMembershipMovement.arrived(session.getId(),
          context.sourceEventId(), current.assetId(), finding.getOrigin(), finding.getDisplayCanonicalNumber(),
          null, session.getWarehouseId(), proofLine.status(), null, context.arrivedAt()));
    }
    appendFindingFacts(finding, session, context.actor(),
        created ? "inventory.finding.added.v1" : "inventory.finding.inspection-saved.v1",
        context.correlationId(), context.sourceEventId());
    return finding;
  }

  private NumberResolutionView doResolveNumber(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, ResolveNumberRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    reviewService.requireCabinReviewStage(session);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryDependencyGateway.NumberResolution resolved =
        dependencies.resolveNumber(session.getWarehouseId(), request.submittedNumber());
    if (resolved.asset() != null && !RENTAL_ITEM_STATUSES.contains(resolved.asset().status())) {
      throw InventoryException.dependency("Asset-service returned an unknown rental-item status");
    }
    InventoryFinding existing =
        findings
            .findByInventoryIdAndIdentityMatchKeyAndMembershipActiveTrue(
                inventoryId, resolved.identityMatchKey())
            .orElse(null);
    if (existing != null) {
      CurrentItemSnapshot current = validationService.currentItemSnapshot(resolved.asset());
      ValidatedFinding validation = validationService.validatedFinding(session, existing, current);
      return new NumberResolutionView(
          resolved.displayCanonicalNumber(),
          resolved.identityMatchKey(),
          validationService.numberResolutionOutcome(session.getWarehouseId(), current, true),
          projectionService.findingView(existing, validation));
    }
    if (!resolved.found() || resolved.asset() == null) {
      return new NumberResolutionView(
          resolved.displayCanonicalNumber(), resolved.identityMatchKey(), "NOT_FOUND", null);
    }
    InventoryDependencyGateway.AssetSnapshot asset = resolved.asset();
    String outcome;
    ReconciliationState reconciliation;
    if (!session.getWarehouseId().equals(asset.warehouseId())) {
      outcome = "CROSS_WAREHOUSE_CONFLICT";
      reconciliation = ReconciliationState.MISSING;
    } else if (InventoryFindingConflictPolicy.isTerminalDispositionStatus(asset.status())) {
      outcome = "EXCLUDED_STATUS_CONFLICT";
      reconciliation = ReconciliationState.MISSING;
    } else {
      outcome = "MATCHED";
      reconciliation = ReconciliationState.MATCHED;
    }
    String actorJson = actorJson(jwt);
    OpaqueActorReference actor = actor(jwt);
    InventoryFinding created;
    try {
      created =
          transactions.execute(
              status -> {
                InventorySession locked = requireActive(inventoryId);
                expectRevision(locked.getRevision(), request.expectedSessionRevision());
                InventoryFinding value =
                    findings.saveAndFlush(
                        InventoryFinding.unexpected(
                            inventoryId,
                            FindingOrigin.UNEXPECTED_EXISTING,
                            asset.assetId(),
                            asset.version(),
                            asset.warehouseId(),
                            asset.status(),
                            asset.tenantSnapshot(),
                            asset.displayCanonicalNumber(),
                            asset.identityMatchKey(),
                            reconciliation,
                            actorJson));
                locked.touch();
                sessions.saveAndFlush(locked);
                if ("MATCHED".equals(outcome)) {
                  membershipMovements.saveAndFlush(
                      InventoryMembershipMovement.arrived(
                          locked.getId(),
                          idempotencyKey,
                          asset.assetId(),
                          value.getOrigin(),
                          value.getDisplayCanonicalNumber(),
                          null,
                          locked.getWarehouseId(),
                          asset.status(),
                          asset.tenantSnapshot(),
                          OffsetDateTime.now(ZoneOffset.UTC)));
                }
                appendFindingFacts(value, locked, actor, "inventory.finding.added.v1");
                return value;
              });
    } catch (DataIntegrityViolationException race) {
      created =
          findings
              .findByInventoryIdAndIdentityMatchKeyAndMembershipActiveTrue(
                  inventoryId, resolved.identityMatchKey())
              .orElseThrow(() -> InventoryException.conflict("Number resolution raced"));
    }
    return new NumberResolutionView(
        resolved.displayCanonicalNumber(),
        resolved.identityMatchKey(),
        outcome,
        projectionService.findingView(created));
  }

  public FindingView createAsset(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      CreateFindingAssetRequest request) {
    requireScopedSession(inventoryId, authorizer.editScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "finding.create-source",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "findingId", findingId, "request", request),
        HttpStatus.OK.value(),
        FindingView.class,
        () -> doCreateAsset(jwt, inventoryId, findingId, idempotencyKey, request));
  }

  private FindingView doCreateAsset(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      CreateFindingAssetRequest request) {
    InventorySession session = requireSession(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    if (session.getLifecycle() == SessionLifecycle.ACTIVE) {
      reviewService.requireCabinReviewStage(session);
    }
    if (request.origin() != FindingOrigin.ADDED_NEW
        && request.origin() != FindingOrigin.ADDED_USED) {
      throw new IllegalArgumentException("Created asset origin must be ADDED_NEW or ADDED_USED");
    }
    if (request.sourceRevision() != Math.addExact(request.expectedFindingRevision(), 1)) {
      throw new IllegalArgumentException("Source revision must follow the expected finding revision");
    }
    String display = canonicalDisplayNumber(request.displayCanonicalNumber());
    String matchKey = display.replace(" ", "").replace("-", "");
    String actorJson = actorJson(jwt);
    ObjectNode remoteRequest =
        sourceAssetRequest(inventoryId, findingId, session, display, request.safePassport());
    String requestHash = canonicalHash(remoteRequest);
    InventorySourceAttachment recovery =
        findingPersistence.findSourceAttachment(inventoryId, findingId).orElse(null);
    if (recovery != null) {
      if (!requestHash.equals(recovery.getRequestSha256())) {
        throw InventoryException.conflict("Finding source request is already bound");
      }
      InventoryFinding recoveredFinding = requireFinding(inventoryId, findingId);
      if (recovery.isAttached()) {
        if (!recovery.getCreatedAssetId().equals(recoveredFinding.getAssetId())
            || !recovery.getCreatedAssetVersion().equals(recoveredFinding.getAssetVersion())) {
          throw new IllegalStateException("Attached source asset differs from its finding");
        }
        return projectionService.findingView(recoveredFinding);
      }
    } else {
      if (session.getLifecycle() != SessionLifecycle.ACTIVE) {
        throw InventoryException.conflict("Only an active session may create a finding asset");
      }
      expectRevision(session.getRevision(), request.expectedSessionRevision());
    }
    transactions.executeWithoutResult(
        status -> {
          InventorySession locked = requireSession(inventoryId);
          InventoryFinding pending =
              findings.findByIdAndInventoryId(findingId, inventoryId).orElse(null);
          if (pending == null) {
            if (locked.getLifecycle() != SessionLifecycle.ACTIVE) {
              throw InventoryException.conflict("Only an active session may create a finding asset");
            }
            expectRevision(locked.getRevision(), request.expectedSessionRevision());
            if (request.expectedFindingRevision() != 0) {
              throw InventoryException.conflict("New finding revision must start at zero");
            }
            pending =
                findings.saveAndFlush(
                    InventoryFinding.unexpectedWithId(
                        findingId,
                        inventoryId,
                        request.origin(),
                        display,
                        matchKey,
                        actorJson));
            locked.touch();
            sessions.saveAndFlush(locked);
            appendFindingFacts(pending, locked, actor(jwt), "inventory.finding.added.v1");
          } else {
            InventorySourceAttachment existingAttachment =
                findingPersistence.findSourceAttachment(inventoryId, findingId).orElse(null);
            if (existingAttachment == null) {
              if (locked.getLifecycle() != SessionLifecycle.ACTIVE) {
                throw InventoryException.conflict(
                    "Only an active session may create a finding asset");
              }
              expectRevision(locked.getRevision(), request.expectedSessionRevision());
              expectRevision(pending.getRevision(), request.expectedFindingRevision());
            }
          }
          InventorySourceAttachment attachment =
              findingPersistence.findSourceAttachment(inventoryId, findingId).orElse(null);
          if (attachment == null) {
            findingPersistence.saveSourceAttachment(
                InventorySourceAttachment.pending(
                    inventoryId,
                    findingId,
                    request.sourceRevision(),
                    idempotencyKey,
                    requestHash));
          } else if (!requestHash.equals(attachment.getRequestSha256())) {
            throw InventoryException.conflict("Finding source request is already bound");
          }
        });
    InventoryDependencyGateway.SourceAsset remote =
        dependencies.createSourceAsset(idempotencyKey, remoteRequest);
    if (!RENTAL_ITEM_STATUSES.contains(remote.asset().status())) {
      throw InventoryException.dependency("Asset-service returned an unknown rental-item status");
    }
    if (request.origin() == FindingOrigin.ADDED_NEW && !"FREE".equals(remote.asset().status())) {
      throw InventoryException.dependency("A newly created inventory cabin must be FREE");
    }
    InventoryFinding attached =
        transactions.execute(
            status -> {
              InventoryFinding finding = requireFinding(inventoryId, findingId);
              if (finding.getAssetId() == null) {
                if (finding.getMutationState()
                    != dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATE_PENDING) {
                  throw InventoryException.conflict("Finding source-create state is inconsistent");
                }
                long mediaSourceRevision = finding.getRevision();
                finding.attachCreatedAsset(
                    remote.asset().assetId(),
                    remote.asset().version(),
                    remote.asset().warehouseId(),
                    remote.asset().status(),
                    remote.asset().tenantSnapshot());
                InventoryFinding savedFinding = findings.saveAndFlush(finding);
                findingPersistence.carryForwardMediaReferences(
                    savedFinding.getId(), mediaSourceRevision, savedFinding.getRevision());
              }
              InventorySourceAttachment attachment =
                  findingPersistence
                      .findSourceAttachment(inventoryId, findingId)
                      .orElseThrow(
                          () -> new IllegalStateException("Finding source attachment is missing"));
              if (!requestHash.equals(attachment.getRequestSha256())) {
                throw InventoryException.conflict("Finding source request changed");
              }
              attachment.attach(
                  remote.asset().assetId(), remote.asset().version(), canonicalHash(remote));
              findingPersistence.saveSourceAttachment(attachment);
              InventorySession currentSession = requireSession(inventoryId);
              boolean expectedActive = currentSession.getLifecycle() == SessionLifecycle.ACTIVE;
              if (finding.isOwnerProofActive() != expectedActive) {
                throw new IllegalStateException("Finding owner proof lifecycle is inconsistent");
              }
              appendOwnerProof(finding, currentSession.getWarehouseId(), actor(jwt));
              return finding;
            });
    return projectionService.findingView(attached);
  }

  public FindingView saveInspection(
      Jwt jwt, UUID inventoryId, UUID findingId, SaveInspectionRequest request) {
    InventorySession session =
        requireLifecycle(
            requireScopedSession(inventoryId, authorizer.editScope(jwt)), SessionLifecycle.ACTIVE);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryFinding finding = requireFinding(inventoryId, findingId);
    expectRevision(finding.getRevision(), request.expectedFindingRevision());
    InventoryDependencyGateway.Validation currentValidation = validationService.validateAssets(List.of(finding));
    ValidatedFinding currentTruth =
        validationService.validatedFindings(session, List.of(finding), currentValidation, true).getFirst();
    ConflictView blockingConflict =
        validationService.blockingInspectionConflict(session.getWarehouseId(), currentTruth.currentSnapshot());
    if (blockingConflict != null) {
      throw InventoryException.conflict(
          "Нельзя сохранить осмотр: " + blockingConflict.message());
    }
    validateObservation(request.passportObservation(), false);
    validateObservation(request.equipmentObservation(), true);
    validatePlanSelection(request.inspection(), request.planSelection());
    SaveInspectionRequest canonicalRequest =
        currentMediaRequest(session, finding, request);
    validateCoverMedia(canonicalRequest);
    if (request.inspection() == InspectionState.READY
        && currentTruth.currentSnapshot() != null
        && "AFTER_RENT".equals(currentTruth.currentSnapshot().status())
        && canonicalRequest.media().isEmpty()) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Чтобы зафиксировать найденную после аренды бытовку, загрузите хотя бы одну фотографию");
    }
    validateReadyMedia(
        findingId,
        session.getWarehouseId(),
        canonicalRequest.media(),
        canonicalRequest.coverMediaId());
    InventoryDependencyGateway.FrozenPlan plan = null;
    if (request.inspection() == InspectionState.WORK_STAGED) {
      plan =
          dependencies.freezePlan(
              UUID.randomUUID(), freezeRequest(session, finding, canonicalRequest));
    } else if (request.inspection() != InspectionState.READY) {
      throw new IllegalArgumentException("Inspection must be READY or WORK_STAGED");
    }
    if (plan != null
        && (!session.getWarehouseId().equals(plan.warehouseId())
            || !session.getId().equals(plan.inventoryId())
            || !finding.getId().equals(plan.findingId())
            || Math.addExact(finding.getRevision(), 1) != plan.sourceRevision()
            || !frozenPlanFingerprint.sha256(plan.snapshot()).equals(plan.fingerprint()))) {
      throw InventoryException.dependency("Maintenance-service returned mismatched frozen plan");
    }
    InventoryDependencyGateway.FrozenPlan frozenPlan = plan;
    InventoryFinding saved =
        transactions.execute(
            status -> {
              InventorySession lockedSession = requireActive(inventoryId);
              reviewService.requireCabinOrFurnitureReviewStage(lockedSession);
              InventoryFinding lockedFinding = requireFinding(inventoryId, findingId);
              expectRevision(lockedSession.getRevision(), request.expectedSessionRevision());
              expectRevision(lockedFinding.getRevision(), request.expectedFindingRevision());
              CurrentItemSnapshot current = currentTruth.currentSnapshot();
              lockedFinding.refreshCurrentAsset(
                  current == null ? null : current.assetVersion(),
                  current == null ? null : current.warehouseId(),
                  current == null ? null : current.status(),
                  current == null ? null : current.tenantSnapshot(),
                  current == null ? null : current.displayCanonicalNumber(),
                  current == null ? null : json(current.passportSnapshot()),
                  current == null ? null : json(current.contentsSnapshot()),
                  current == null ? null : json(current.repairsSnapshot()),
                  current == null ? ReconciliationState.MISSING : ReconciliationState.MATCHED);
              lockedFinding.saveInspection(
                  request.inspection(),
                  lockedFinding.getReconciliation(),
                  request.passportObservation().presence(),
                  json(request.passportObservation().value()),
                  request.equipmentObservation().presence(),
                  json(request.equipmentObservation().value()),
                  frozenPlan == null ? null : frozenPlan.fingerprint(),
                  request.comment(),
                  canonicalRequest.coverMediaId(),
                  actorJson(jwt));
              InventoryFinding result = findings.saveAndFlush(lockedFinding);
              persistMedia(result, canonicalRequest.media());
              if (frozenPlan != null) {
                persistPlan(result, canonicalRequest.planSelection(), frozenPlan);
              }
              reviewService.invalidateFurnitureReviewAfterCabinChange(lockedSession);
              planningService.invalidateFinalPlan(lockedSession);
              appendFindingFacts(
                  result, lockedSession, actor(jwt), "inventory.finding.inspection-saved.v1");
              return result;
            });
    ValidatedFinding savedTruth =
        validationService.validatedFinding(session, saved, currentTruth.currentSnapshot());
    return projectionService.findingView(saved, savedTruth);
  }

  /**
   * Resolves reconciliation state and carries the finding's exact prior-revision media set across
   * the resulting non-media revision bump in the same transaction.
   */
  public FindingView resolveConflict(
      Jwt jwt, UUID inventoryId, UUID findingId, ResolveConflictRequest request) {
    InventorySession session =
        requireLifecycle(
            requireScopedSession(inventoryId, authorizer.editScope(jwt)), SessionLifecycle.ACTIVE);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryFinding finding = requireFinding(inventoryId, findingId);
    expectRevision(finding.getRevision(), request.expectedFindingRevision());
    if (finding.getInspection() == InspectionState.NOT_INSPECTED) {
      throw InventoryException.conflict(
          "Сначала проверьте бытовку, затем разрешайте изменения реестра");
    }
    InventoryDependencyGateway.Validation validation = validationService.validateAssets(List.of(finding));
    ValidatedFinding currentTruth =
        validationService.validatedFindings(session, List.of(finding), validation).getFirst();
    if (currentTruth.conflicts().isEmpty()) {
      throw InventoryException.conflict("Актуального конфликта реестра больше нет");
    }
    if (request.strategy() == ConflictResolutionStrategy.ACCEPT_REGISTRY
        && currentTruth.currentSnapshot() == null) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Отсутствующую в реестре бытовку нельзя принять как новую базовую версию");
    }
    if (request.strategy() == ConflictResolutionStrategy.KEEP_INSPECTION
        && (request.reason() == null || request.reason().isBlank())) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Укажите причину сохранения данных осмотра");
    }
    CurrentItemSnapshot current = currentTruth.currentSnapshot();
    String fingerprint = conflictPolicy.semanticFingerprint(current);
    InventoryFinding saved =
        transactions.execute(
            ignored -> {
              InventorySession lockedSession = requireActive(inventoryId);
              InventoryFinding lockedFinding = requireFinding(inventoryId, findingId);
              expectRevision(lockedSession.getRevision(), request.expectedSessionRevision());
              expectRevision(lockedFinding.getRevision(), request.expectedFindingRevision());
              long mediaSourceRevision = lockedFinding.getRevision();
              lockedFinding.refreshCurrentAsset(
                  current == null ? null : current.assetVersion(),
                  current == null ? null : current.warehouseId(),
                  current == null ? null : current.status(),
                  current == null ? null : current.tenantSnapshot(),
                  current == null ? null : current.displayCanonicalNumber(),
                  current == null ? null : json(current.passportSnapshot()),
                  current == null ? null : json(current.contentsSnapshot()),
                  current == null ? null : json(current.repairsSnapshot()),
                  current == null ? ReconciliationState.MISSING : ReconciliationState.CONFLICT);
              lockedFinding.resolveConflict(
                  request.strategy(), fingerprint, request.reason(), actorJson(jwt));
              InventoryFinding result = findings.saveAndFlush(lockedFinding);
              findingPersistence.carryForwardMediaReferences(
                  result.getId(), mediaSourceRevision, result.getRevision());
              reviewService.invalidateFurnitureReviewAfterCabinChange(lockedSession);
              planningService.invalidateFinalPlan(lockedSession);
              appendFindingFacts(
                  result,
                  lockedSession,
                  actor(jwt),
                  "inventory.finding.inspection-saved.v1");
              return result;
            });
    return projectionService.findingView(
        saved, validationService.validatedFinding(session, saved, current));
  }

  void appendFindingFacts(
      InventoryFinding finding,
      InventorySession session,
      OpaqueActorReference actor,
      String eventType) {
    appendFindingFacts(finding, session, actor, eventType, correlationId(), null);
  }

  /**
   * Appends the audit fact for a completed-session observation repair without reopening media owner
   * authorization that completion already closed.
   */
  void appendCompletedObservationRestored(
      InventoryFinding finding, InventorySession session, OpaqueActorReference actor) {
    appendFindingFact(
        finding,
        session,
        actor,
        "inventory.finding.membership-restored.v1",
        correlationId(),
        null,
        false);
  }

  private void appendFindingFacts(
      InventoryFinding finding,
      InventorySession session,
      OpaqueActorReference actor,
      String eventType,
      UUID correlationId,
      UUID causationId) {
    appendFindingFact(finding, session, actor, eventType, correlationId, causationId, true);
  }

  private void appendFindingFact(
      InventoryFinding finding,
      InventorySession session,
      OpaqueActorReference actor,
      String eventType,
      UUID correlationId,
      UUID causationId,
      boolean appendOwnerProof) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("inventoryId", finding.getInventoryId().toString());
    payload.put("findingId", finding.getId().toString());
    payload.put("warehouseId", session.getWarehouseId().toString());
    payload.put("sessionRevision", session.getRevision());
    payload.put("findingRevision", finding.getRevision());
    payload.put("origin", finding.getOrigin().name());
    payload.put("inspection", finding.getInspection().name());
    payload.put("reconciliation", finding.getReconciliation().name());
    payload.put("membershipActive", finding.isMembershipActive());
    if (finding.getAssetId() == null) payload.putNull("assetId");
    else payload.put("assetId", finding.getAssetId().toString());
    payload.put(
        "sourceAttached",
        finding.getMutationState()
            == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATED);
    payload.put("mediaCount", mediaCount(finding));
    if (finding.getMaintenancePlanFingerprintSha256() == null) {
      payload.putNull("planFingerprintSha256");
    } else {
      payload.put("planFingerprintSha256", finding.getMaintenancePlanFingerprintSha256());
    }
    InventoryEventStore.AppendResult appended;
    if ("inventory.finding.added.v1".equals(eventType)) {
      appended =
          events.initialize(
              "FINDING",
              finding.getId(),
              eventType,
              SESSION_TOPIC,
              payload,
              correlationId,
              causationId,
              actor);
    } else {
      appended =
          events.append(
              "FINDING",
              finding.getId(),
              events.currentVersion("FINDING", finding.getId()),
              eventType,
              SESSION_TOPIC,
              payload,
              correlationId,
              causationId,
              actor);
    }
    if (appendOwnerProof) {
      appendOwnerProof(finding, session.getWarehouseId(), actor, appended.aggregateVersion());
    }
  }

  void appendOwnerProof(
      InventoryFinding finding, UUID warehouseId, OpaqueActorReference actor) {
    appendOwnerProof(
        finding, warehouseId, actor, events.currentVersion("FINDING", finding.getId()));
  }

  private void appendOwnerProof(
      InventoryFinding finding,
      UUID warehouseId,
      OpaqueActorReference actor,
      long expectedEventVersion) {
    appendOwnerProof(
        finding,
        warehouseId,
        actor,
        expectedEventVersion,
        correlationId(),
        null);
  }

  private void appendOwnerProof(
      InventoryFinding finding,
      UUID warehouseId,
      OpaqueActorReference actor,
      long expectedEventVersion,
      UUID correlationId,
      UUID causationId) {
    events.append(
        "FINDING",
        finding.getId(),
        expectedEventVersion,
        "inventory.finding.owner-proof.v1",
        SESSION_TOPIC,
        ownerProofPayload(finding, warehouseId),
        correlationId,
        causationId,
        actor);
  }

  ObjectNode ownerProofPayload(InventoryFinding finding, UUID warehouseId) {
    ObjectNode proof = mapper.createObjectNode();
    proof.put("ownerType", "INVENTORY_FINDING");
    proof.put("ownerId", finding.getId().toString());
    proof.put("warehouseId", warehouseId.toString());
    proof.put("ownerRevision", finding.getOwnerProofRevision());
    proof.put("active", finding.isOwnerProofActive());
    return proof;
  }

  private ObjectNode sourceAssetRequest(
      UUID inventoryId,
      UUID findingId,
      InventorySession session,
      String display,
      JsonNode safePassport) {
    ObjectNode request = mapper.createObjectNode();
    request.put("inventoryId", inventoryId.toString());
    request.put("findingId", findingId.toString());
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("number", display);
    if (safePassport != null && safePassport.isObject()) {
      safePassport
          .properties()
          .forEach(
              entry -> {
                if (!Set.of("inventoryId", "findingId", "warehouseId", "number")
                    .contains(entry.getKey())) {
                  request.set(entry.getKey(), entry.getValue());
                }
              });
    }
    return request;
  }

  private ObjectNode freezeRequest(
      InventorySession session, InventoryFinding finding, SaveInspectionRequest request) {
    PlanSelection selection = request.planSelection();
    ObjectNode body = mapper.createObjectNode();
    body.put("warehouseId", session.getWarehouseId().toString());
    body.put("inventoryId", session.getId().toString());
    body.put("findingId", finding.getId().toString());
    body.put("sourceRevision", Math.addExact(finding.getRevision(), 1));
    body.put("mode", selection.mode());
    body.put("priority", selection.priority());
    body.put("movementToRepair", selection.movementToRepair());
    body.put("forceCapitalRepair", selection.forceCapitalRepair());
    if (selection.logisticsPlanningMode() == null) {
      body.putNull("logisticsPlanningMode");
    } else {
      body.put("logisticsPlanningMode", selection.logisticsPlanningMode().name());
    }
    if (selection.logisticsScheduledDate() == null) {
      body.putNull("logisticsScheduledDate");
    } else {
      body.put(
          "logisticsScheduledDate", selection.logisticsScheduledDate().toString());
    }
    if (selection.coverMediaId() == null) body.putNull("coverMediaId");
    else body.put("coverMediaId", selection.coverMediaId().toString());
    body.set("lines", mapper.valueToTree(selection.lines()));
    body.set("plan", mapper.valueToTree(selection.stages()));
    ArrayNode media = body.putArray("mediaReferences");
    for (MediaReference reference : request.media()) {
      ObjectNode item = media.addObject();
      item.put("mediaId", reference.mediaId().toString());
      item.put("generation", reference.generation());
    }
    return body;
  }

  private void validateObservation(Observation observation, boolean equipment) {
    if (observation == null || observation.presence() == null) {
      throw new IllegalArgumentException("Observation presence is required");
    }
    JsonNode value = observation.value();
    switch (observation.presence()) {
      case ABSENT -> {
        if (value != null && !value.isNull()) {
          throw new IllegalArgumentException("ABSENT observation value must be null");
        }
      }
      case EXPLICIT_EMPTY -> {
        if (value == null
            || (equipment ? !value.isArray() || !value.isEmpty() : !value.isObject() || !value.isEmpty())) {
          throw new IllegalArgumentException("EXPLICIT_EMPTY observation has the wrong JSON shape");
        }
      }
      case PRESENT -> {
        if (value == null
            || (equipment ? !value.isArray() || value.isEmpty() : !value.isObject() || value.isEmpty())) {
          throw new IllegalArgumentException("PRESENT observation has the wrong JSON shape");
        }
        if (equipment) {
          for (JsonNode item : value) {
            if (!item.isObject()) {
              throw new IllegalArgumentException("Equipment observation items must be objects");
            }
          }
        }
      }
    }
  }

  private void validatePlanSelection(InspectionState inspection, PlanSelection selection) {
    if (inspection == InspectionState.READY) {
      if (selection != null) throw new IllegalArgumentException("READY inspection rejects a plan");
      return;
    }
    if (inspection != InspectionState.WORK_STAGED || selection == null) {
      throw new IllegalArgumentException("WORK_STAGED inspection requires a plan");
    }
    if (selection.lines().isEmpty()) {
      throw new IllegalArgumentException("Inventory plan requires at least one line");
    }
    if (selection.priority() == null || selection.priority() < 1 || selection.priority() > 5) {
      throw new IllegalArgumentException("Inventory plan priority must be between 1 and 5");
    }
    if (!selection.isLogisticsPlanningValid()) {
      throw new IllegalArgumentException("Inventory logistics planning is invalid");
    }
    if (!selection.isRepairDestinationChoiceValid()) {
      throw new IllegalArgumentException(
          "Capital repair and movement to repair are mutually exclusive");
    }
    boolean manualMode = "MANUAL".equals(selection.mode());
    for (PlanLineInput line : selection.lines()) {
      BigDecimal quantity = new BigDecimal(line.quantity());
      if (quantity.signum() <= 0) throw new IllegalArgumentException("Plan quantity must be positive");
      if ("CATALOG".equals(line.aggregationKind())) {
        if (line.catalogNodeId() == null
            || line.routingCatalogNodeId() != null
            || line.description() != null
            || line.type() != null
            || line.unit() != null
            || line.unitPriceMinor() != null
            || line.normativeMinutes() != null) {
          throw new IllegalArgumentException("CATALOG plan line evidence is invalid");
        }
      } else if (!manualMode
          || line.catalogNodeId() != null
          || line.routingCatalogNodeId() == null
          || line.description() == null
          || line.description().isBlank()
          || line.type() == null
          || line.unit() == null
          || line.unit().isBlank()
          || line.unitPriceMinor() == null
          || line.normativeMinutes() == null) {
        throw new IllegalArgumentException("MANUAL plan line evidence requires MANUAL mode");
      }
      if (line.groupComment() != null && line.groupComment().isBlank()) {
        throw new IllegalArgumentException("Plan group comment cannot be blank");
      }
    }
    if (manualMode && selection.stages().isEmpty()) {
      throw new IllegalArgumentException("MANUAL plan requires ordered stages");
    }
    for (int index = 0; index < selection.stages().size(); index++) {
      PlanStageSelection stage = selection.stages().get(index);
      if (!"REPAIR_WORK".equals(stage.kind())) {
        throw new IllegalArgumentException("Inventory plan stages can contain repair work only");
      }
      if (stage.order() != index) {
        throw new IllegalArgumentException("Plan stage order must be contiguous");
      }
    }
  }

  /**
   * A repeat inspection starts from an immutable historical snapshot. Media rotation keeps the
   * logical media ID but advances its READY generation, so a retained historical reference must
   * be rebased before a new finding and maintenance-source revision is frozen. New references
   * still have to name the exact current generation; only a reference proven to belong to the
   * active previous revision may advance implicitly.
   */
  private SaveInspectionRequest currentMediaRequest(
      InventorySession session, InventoryFinding finding, SaveInspectionRequest request) {
    Map<UUID, Long> retained = retainedMediaGenerations(finding);
    List<MediaReference> currentMedia =
        currentReadyMediaReferences(
            finding.getId(), session.getWarehouseId(), request.media(), retained);
    PlanSelection selection = request.planSelection();
    PlanSelection currentSelection = null;
    if (selection != null) {
      List<PlanLineInput> currentLines =
          selection.lines().stream()
              .map(
                  line ->
                      new PlanLineInput(
                          line.aggregationKind(),
                          line.catalogNodeId(),
                          line.routingCatalogNodeId(),
                          line.description(),
                          line.type(),
                          line.unit(),
                          line.quantity(),
                          line.unitPriceMinor(),
                          line.normativeMinutes(),
                          line.groupComment(),
                          currentReadyMediaReferences(
                              finding.getId(),
                              session.getWarehouseId(),
                              line.mediaReferences(),
                              retained)))
              .toList();
      currentSelection =
          new PlanSelection(
              selection.mode(),
              selection.priority(),
              selection.coverMediaId(),
              selection.movementToRepair(),
              selection.logisticsPlanningMode(),
              selection.logisticsScheduledDate(),
              currentLines,
              selection.stages(),
              selection.forceCapitalRepair());
    }
    return new SaveInspectionRequest(
        request.expectedSessionRevision(),
        request.expectedFindingRevision(),
        request.inspection(),
        request.comment(),
        request.passportObservation(),
        request.equipmentObservation(),
        currentMedia,
        request.coverMediaId(),
        currentSelection);
  }

  private Map<UUID, Long> retainedMediaGenerations(InventoryFinding finding) {
    Map<UUID, Long> retained = new LinkedHashMap<>();
    for (FindingMediaReference reference :
        findingPersistence.findingMediaReferences(finding.getId(), finding.getRevision())) {
      putRetainedMediaGeneration(
          retained, reference.getMediaId(), reference.getGeneration());
    }
    findingPersistence
        .findPlanSnapshot(finding.getId(), finding.getRevision())
        .ifPresent(
            plan -> {
              JsonNode snapshot = read(plan.getSourceSnapshot());
              collectRetainedMediaGenerations(
                  snapshot.path("mediaReferences"), retained, "frozen plan media");
              JsonNode lines = snapshot.path("lines");
              if (!lines.isArray()) {
                throw new IllegalStateException("Persisted frozen plan lines are invalid");
              }
              for (JsonNode line : lines) {
                collectRetainedMediaGenerations(
                    line.path("mediaReferences"), retained, "frozen plan line media");
              }
            });
    return Map.copyOf(retained);
  }

  private void collectRetainedMediaGenerations(
      JsonNode references, Map<UUID, Long> retained, String name) {
    if (references.isMissingNode() || references.isNull()) return;
    if (!references.isArray() || references.size() > 100) {
      throw new IllegalStateException("Persisted " + name + " are invalid");
    }
    for (JsonNode reference : references) {
      if (!reference.isObject()) {
        throw new IllegalStateException("Persisted " + name + " are invalid");
      }
      UUID mediaId = requiredUuid(reference, "mediaId", name + " id");
      long generation = reference.path("generation").asLong(-1);
      if (generation < 0) {
        throw new IllegalStateException("Persisted " + name + " are invalid");
      }
      putRetainedMediaGeneration(retained, mediaId, generation);
    }
  }

  private void putRetainedMediaGeneration(
      Map<UUID, Long> retained, UUID mediaId, long generation) {
    Long previous = retained.putIfAbsent(mediaId, generation);
    if (previous != null && previous != generation) {
      throw new IllegalStateException(
          "Persisted inspection references multiple generations of one media object");
    }
  }

  private List<MediaReference> currentReadyMediaReferences(
      UUID findingId,
      UUID warehouseId,
      List<MediaReference> requested,
      Map<UUID, Long> retained) {
    Set<UUID> unique = new HashSet<>();
    List<MediaReference> current = new ArrayList<>(requested.size());
    for (MediaReference reference : requested) {
      if (!unique.add(reference.mediaId())) {
        throw new IllegalArgumentException(
            "Media references must contain only one generation per media object");
      }
      var fact =
          findingPersistence
              .findLatestFindingMediaFact(reference.mediaId(), findingId, warehouseId)
              .orElse(null);
      if (fact == null || !"READY".equals(fact.getMediaStatus())) {
        throw mediaNotReady();
      }
      if (reference.generation() != fact.getGeneration()) {
        Long retainedGeneration = retained.get(reference.mediaId());
        if (retainedGeneration == null || retainedGeneration != reference.generation()) {
          throw mediaNotReady();
        }
      }
      current.add(new MediaReference(reference.mediaId(), fact.getGeneration()));
    }
    return List.copyOf(current);
  }

  private InventoryException mediaNotReady() {
    return new InventoryException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVENTORY_MEDIA_NOT_READY",
        "Referenced media generation is not READY for this finding");
  }

  private void validateCoverMedia(SaveInspectionRequest request) {
    UUID coverMediaId = request.coverMediaId();
    List<MediaReference> media = request.media();
    if (media.isEmpty()) {
      if (coverMediaId != null) {
        throw validationFailure("Титульное фото нельзя выбрать без фотографий");
      }
    } else {
      if (coverMediaId == null) {
        throw validationFailure("Выберите титульную фотографию");
      }
      if (media.stream().noneMatch(reference -> coverMediaId.equals(reference.mediaId()))) {
        throw validationFailure("Титульная фотография отсутствует среди фотографий проверки");
      }
    }
    PlanSelection selection = request.planSelection();
    if (selection != null && !java.util.Objects.equals(selection.coverMediaId(), coverMediaId)) {
      throw validationFailure(
          "Титульная фотография рабочего плана не совпадает с фотографией проверки");
    }
  }

  private InventoryException validationFailure(String message) {
    return new InventoryException(
        HttpStatus.UNPROCESSABLE_ENTITY, "INVENTORY_VALIDATION_FAILED", message);
  }

  private void validateReadyMedia(
      UUID findingId,
      UUID warehouseId,
      List<MediaReference> media,
      UUID coverMediaId) {
    Set<UUID> unique = new HashSet<>();
    for (MediaReference reference : media) {
      if (!unique.add(reference.mediaId())) {
        throw new IllegalArgumentException(
            "Media references must contain only one generation per media object");
      }
      var fact =
          findingPersistence
              .findReadyFindingMediaFact(
                  reference.mediaId(), reference.generation(), findingId, warehouseId)
              .orElse(null);
      if (fact == null) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_MEDIA_NOT_READY",
            "Referenced media generation is not READY for this finding");
      }
      if (reference.mediaId().equals(coverMediaId) && !"IMAGE".equals(fact.getMediaKind())) {
        throw validationFailure("Титульным медиа может быть только фотография");
      }
    }
  }

  private void persistMedia(InventoryFinding finding, List<MediaReference> media) {
    for (MediaReference reference : media) {
      var fact =
          findingPersistence
              .findReadyFindingMediaFact(
                  reference.mediaId(),
                  reference.generation(),
                  finding.getId(),
                  requireSession(finding.getInventoryId()).getWarehouseId())
              .orElseThrow(
                  () ->
                      new InventoryException(
                          HttpStatus.UNPROCESSABLE_ENTITY,
                          "INVENTORY_MEDIA_NOT_READY",
                          "Media readiness changed before inspection commit"));
      findingPersistence.saveMediaReference(
          new FindingMediaReference(
              finding.getId(),
              finding.getRevision(),
              reference.mediaId(),
              reference.generation(),
              fact.getMediaKind()));
    }
  }

  private void persistPlan(
      InventoryFinding finding,
      PlanSelection selection,
      InventoryDependencyGateway.FrozenPlan frozen) {
    JsonNode snapshot = frozen.snapshot();
    UUID catalogVersionId = UUID.fromString(snapshot.path("catalogVersionId").asText());
    boolean movementToRepair =
        requiredBoolean(snapshot, "movementToRepair", "frozen movement to repair");
    boolean forceCapitalRepair =
        booleanOrDefault(
            snapshot, "forceCapitalRepair", "frozen capital-repair choice", false);
    LogisticsPlanningMode logisticsPlanningMode;
    LocalDate logisticsScheduledDate;
    try {
      logisticsPlanningMode =
          nullableLogisticsPlanningMode(
              snapshot, "logisticsPlanningMode", "frozen logistics planning mode");
      logisticsScheduledDate =
          nullableLocalDate(
              snapshot, "logisticsScheduledDate", "frozen logistics scheduled date");
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Persisted frozen logistics planning is invalid", exception);
    }
    if (!LogisticsPlanningMode.validInboundPlanning(
        movementToRepair, logisticsPlanningMode, logisticsScheduledDate)) {
      throw new IllegalStateException("Persisted frozen logistics planning is invalid");
    }
    if (movementToRepair != selection.movementToRepair()
        || forceCapitalRepair != selection.forceCapitalRepair()
        || logisticsPlanningMode != selection.logisticsPlanningMode()
        || !java.util.Objects.equals(
            logisticsScheduledDate, selection.logisticsScheduledDate())) {
      throw InventoryException.dependency(
          "Maintenance-service returned mismatched logistics planning");
    }
    findingPersistence.savePlanSnapshot(
        new FindingPlanSnapshot(
            finding.getId(),
            finding.getRevision(),
            finding.getInventoryId(),
            selection.mode(),
            movementToRepair,
            logisticsPlanningMode,
            logisticsScheduledDate,
            catalogVersionId,
            frozen.fingerprint(),
            write(snapshot),
            forceCapitalRepair,
            2));
    int lineNo = 0;
    for (JsonNode line : snapshot.path("lines")) {
      String unit = line.path("unit").isNull() ? null : line.path("unit").asText();
      if (unit == null || unit.isBlank()) {
        throw new IllegalArgumentException("Frozen inventory line unit is required");
      }
      BigDecimal quantity = new BigDecimal(line.path("quantity").asText());
      long priceMinor = line.path("unitPriceMinor").longValue();
      BigDecimal normative = new BigDecimal(line.path("normativeMinutes").asText());
      String sourceKind = line.path("aggregationKind").asText();
      UUID lineCatalogVersionId =
          line.path("catalogVersionId").isNull()
              ? null
              : UUID.fromString(line.path("catalogVersionId").asText());
      UUID catalogNodeId =
          line.path("catalogNodeId").isNull()
              ? null
              : UUID.fromString(line.path("catalogNodeId").asText());
      String normalizedDescription =
          line.path("normalizedDescription").isNull()
              ? null
              : line.path("normalizedDescription").asText();
      findingPersistence.savePlanLine(
          new FindingPlanLine(
              finding.getId(),
              finding.getRevision(),
              lineNo++,
              sourceKind,
              line.path("type").asText(),
              lineCatalogVersionId,
              catalogNodeId,
              line.path("description").asText(),
              normalizedDescription,
              unit,
              quantity,
              priceMinor,
              normative));
    }
    int stageNo = 0;
    for (JsonNode stage : snapshot.path("stages")) {
      if (!"REPAIR_WORK".equals(requiredText(stage, "kind", "frozen plan stage kind"))) {
        throw new IllegalStateException("Frozen inventory plan contains a legacy movement stage");
      }
      JsonNode routing = stage.path("routing");
      findingPersistence.savePlanStage(
          new FindingPlanStage(
              finding.getId(),
              finding.getRevision(),
              stageNo++,
              stage.path("kind").asText(),
              UUID.fromString(stage.path("catalogNodeId").asText()),
              stage.path("catalogNodeName").asText(),
              UUID.fromString(routing.path("queueId").asText()),
              routing.path("queueName").asText(),
              routing.path("queueType").asText(),
              false,
              write(stage)));
    }
  }
}
