package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReadiness;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliation;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReturnShortageSnapshot;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.maintenance.api.MaintenanceReturnArrivalResponse;
import dev.buhanzaz.rwms.logistics.maintenance.service.MaintenanceReturnArrivalService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReturnShortageSnapshotRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Durable local state for the two terminal return flows. Network operations
 * run in {@link ReturnCompletionProcessor}; this class records every result
 * in a short transaction before the next durable operation becomes eligible.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ReturnCompletionWorkflowStore {
  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsExternalAttemptRepository attemptRepository;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsMediaReferenceRepository mediaReferenceRepository;
  private final LogisticsReturnShortageSnapshotRepository shortageSnapshotRepository;
  private final LogisticsReconciliationRepository reconciliationRepository;
  private final LogisticsEventStore eventStore;
  private final LogisticsDocumentService documents;
  private final MaintenanceReturnArrivalService returnArrivals;

  /**
   * Builds local completion work for exactly one current claim. A transition that has already
   * moved to a different local state is deferred by the processor without a dependency request.
   */
  @Transactional
  public Optional<Work> workForClaim(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    LogisticsDocument document = attempt.getDocument();
    if (!isCompletionState(document.getState())) return Optional.empty();
    LogisticsDocumentLine line = attempt.getLine();
    if (line == null) return Optional.empty();
    if (document.getState() == LogisticsDocumentState.ACCEPTING) {
      return acceptanceWork(attempt, document, line);
    }
    if (document.getState() == LogisticsDocumentState.ESTIMATE_PENDING) {
      return estimateWork(attempt, document, line);
    }
    return Optional.empty();
  }

  /** Records media validation only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmMedia(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.MediaValidation validation) {
    LogisticsExternalAttempt attempt =
        attempt(claim, LogisticsDocumentService.RETURN_MEDIA_VALIDATE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (!isCompletionState(document.getState())) return;

    List<LogisticsMediaReference> references = mediaReferences(line);
    requireValidatedMedia(document, line, references, validation);
    OffsetDateTime completedAt = now();
    for (LogisticsMediaReference reference : references) reference.ready(completedAt);
    attempt.confirm(mediaDigest("RETURN_MEDIA_VALIDATE_RESPONSE", document, line, references), completedAt);
    boolean estimate = document.getState() == LogisticsDocumentState.ESTIMATE_PENDING;
    String settlementOperation =
        estimate
            ? LogisticsDocumentService.RETURN_ASSET_SETTLE_ESTIMATE
            : LogisticsDocumentService.RETURN_ASSET_SETTLE_FREE;
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        settlementOperation,
        assetEffectDigest(
            settlementOperation, document, line, activeGuard(line)),
        completedAt);
  }

  /** Records settlement only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmSettlement(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot,
      boolean estimate) {
    String operationType =
        estimate
            ? LogisticsDocumentService.RETURN_ASSET_SETTLE_ESTIMATE
            : LogisticsDocumentService.RETURN_ASSET_SETTLE_FREE;
    LogisticsExternalAttempt attempt = attempt(claim, operationType);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (!isExpectedState(document, estimate)) return;

    LogisticsGuard guard = activeGuard(line);
    requireSettlementSnapshot(document, line, guard, snapshot, estimate);
    OffsetDateTime completedAt = now();
    attempt.confirm(snapshotDigest(operationType + "_RESPONSE", snapshot), completedAt);
    guard.recordObservedAssetVersion(snapshot.version());
    if (estimate) {
      LogisticsReturnShortageSnapshot source =
          shortageSnapshotRepository
              .findByLine_Id(line.getId())
              .orElseThrow(() -> malformed("Return estimate settlement has no source snapshot"));
      MaintenanceReturnArrivalResponse arrival = returnArrival(document, line);
      createAttemptIfMissing(
          document,
          line,
          LogisticsTargetService.MAINTENANCE,
          LogisticsDocumentService.RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT,
          estimateSourceDigest(document, line, source, arrival.arrivedAt()),
          completedAt);
    } else {
      createAdditionalEquipmentAttemptOrRelease(document, line, guard, completedAt);
    }
  }

  /** Records maintenance completion only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmMaintenance(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.ReturnEstimateSource source) {
    LogisticsExternalAttempt attempt =
        attempt(claim, LogisticsDocumentService.RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.ESTIMATE_PENDING) return;
    LogisticsReturnShortageSnapshot expected =
        shortageSnapshotRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> malformed("Maintenance completion has no return estimate source snapshot"));
    requireMaintenanceSource(expected, source);

    OffsetDateTime completedAt = now();
    attempt.confirm(maintenanceDigest(source), completedAt);
    createAdditionalEquipmentAttemptOrRelease(document, line, activeGuard(line), completedAt);
  }

  /** Records additional-equipment receipt only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmAdditionalEquipmentReceipt(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.ReturnEquipmentReceipt receipt) {
    LogisticsExternalAttempt attempt =
        attempt(claim, LogisticsDocumentService.RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (!isCompletionState(document.getState())) return;
    List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> expected =
        additionalEquipment(line);
    requireAdditionalEquipmentReceipt(document, line, expected, receipt);

    OffsetDateTime completedAt = now();
    attempt.confirm(additionalEquipmentReceiptDigest(receipt), completedAt);
    createReleaseAttempt(document, line, activeGuard(line), completedAt);
  }

  /** Records asset lease release only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmLeaseRelease(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.OperationLease releasedLease) {
    LogisticsExternalAttempt attempt =
        attempt(claim, LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (!isCompletionState(document.getState())) return;
    LogisticsGuard guard = activeGuard(line);
    requireReleasedLease(line, guard, releasedLease);

    OffsetDateTime completedAt = now();
    attempt.confirm(releaseDigest(releasedLease), completedAt);
    guard.release();
    finishIfEveryLeaseReleased(document);
  }

  /** Records a dependency failure only if the supplied lease is still exact and current. */
  @Transactional
  public void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    if (!isCompletionState(document.getState())) return;
    LogisticsDocumentLine line = requiredLine(attempt);
    OffsetDateTime completedAt = now();
    String responseDigest =
        LogisticsCommandChecksum.sha256(
            "RETURN_COMPLETION_FAILURE",
            List.of(attempt.getOperationType(), exception.kind().name()));

    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      attempt.reject(responseDigest, completedAt);
      markConflict(line, attempt.getOperationType());
      moveToConflict(document);
      documentRepository.saveAndFlush(document);
      eventStore.append(
          document,
          lineCount(document),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.RETURN_CONFLICT,
          "DEPENDENCY_REJECTED");
      return;
    }

    if (exception.kind() == LogisticsDependencyException.FailureKind.CONFIGURATION
        || attempt.getRetryCount() >= 3) {
      attempt.requireReconciliation(responseDigest, completedAt);
      markReconciliationRequired(line, attempt.getOperationType());
      moveToReconciliation(document);
      documentRepository.saveAndFlush(document);
      if (reconciliationRepository
          .findByDocument_IdAndLine_IdAndState(
              document.getId(), line.getId(), LogisticsReconciliationState.OPEN)
          .isEmpty()) {
        reconciliationRepository.save(
            LogisticsReconciliation.open(
                document, line, "RETURN_COMPLETION_DEPENDENCY_UNKNOWN", completedAt));
      }
      eventStore.append(
          document,
          lineCount(document),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.RETURN_RECONCILIATION_REQUIRED,
          "DEPENDENCY_UNKNOWN");
      return;
    }
    attempt.retry(completedAt.plusSeconds(retryDelaySeconds(attempt.getRetryCount())));
  }

  private Optional<Work> acceptanceWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    if (LogisticsDocumentService.RETURN_MEDIA_VALIDATE.equals(attempt.getOperationType())) {
      return Optional.of(
          Work.media(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              document.getWarehouseId(),
              mediaReferences(line)));
    }
    if (LogisticsDocumentService.RETURN_ASSET_SETTLE_FREE.equals(attempt.getOperationType())) {
      return Optional.of(settlementWork(attempt, document, line, false));
    }
    if (LogisticsDocumentService.RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE.equals(
        attempt.getOperationType())) {
      return Optional.of(returnEquipmentWork(attempt, document, line));
    }
    if (LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE.equals(attempt.getOperationType())) {
      return Optional.of(releaseWork(attempt, document, line));
    }
    return Optional.empty();
  }

  private Optional<Work> estimateWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    if (LogisticsDocumentService.RETURN_MEDIA_VALIDATE.equals(attempt.getOperationType())) {
      return Optional.of(
          Work.media(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              document.getWarehouseId(),
              mediaReferences(line)));
    }
    if (LogisticsDocumentService.RETURN_ASSET_SETTLE_ESTIMATE.equals(attempt.getOperationType())) {
      return Optional.of(settlementWork(attempt, document, line, true));
    }
    if (LogisticsDocumentService.RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT.equals(attempt.getOperationType())) {
      LogisticsReturnShortageSnapshot snapshot =
          shortageSnapshotRepository
              .findByLine_Id(line.getId())
              .orElseThrow(() -> malformed("Return maintenance attempt has no source snapshot"));
      MaintenanceReturnArrivalResponse arrival = returnArrival(document, line);
      return Optional.of(
          Work.maintenance(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              snapshot.getWarehouseId(),
              snapshot.getRentalItemId(),
              snapshot.getRentalItemVersion(),
              document.getScheduledDate(),
              arrival.arrivedAt(),
              mediaReferences(line)));
    }
    if (LogisticsDocumentService.RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE.equals(
        attempt.getOperationType())) {
      return Optional.of(returnEquipmentWork(attempt, document, line));
    }
    if (LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE.equals(attempt.getOperationType())) {
      return Optional.of(releaseWork(attempt, document, line));
    }
    return Optional.empty();
  }

  private Work settlementWork(
      LogisticsExternalAttempt attempt,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      boolean estimate) {
    LogisticsGuard guard = activeGuard(line);
    return Work.settlement(
        attempt.getOperationId(),
        document.getId(),
        line.getId(),
        line.getAssetId(),
        guard.getObservedAssetVersion(),
        guard.getLeaseId(),
        guard.getFenceToken(),
        estimate);
  }

  private Work releaseWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    LogisticsGuard guard = activeGuard(line);
    return Work.release(
        attempt.getOperationId(),
        document.getId(),
        line.getId(),
        line.getAssetId(),
        guard.getLeaseId(),
        guard.getLeaseVersion(),
        guard.getFenceToken());
  }

  private Work returnEquipmentWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    return Work.returnEquipment(
        attempt.getOperationId(),
        document.getId(),
        line.getId(),
        document.getWarehouseId(),
        additionalEquipment(line));
  }

  private void createAdditionalEquipmentAttemptOrRelease(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      OffsetDateTime createdAt) {
    List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> additional =
        additionalEquipment(line);
    if (additional.isEmpty()) {
      createReleaseAttempt(document, line, guard, createdAt);
      return;
    }
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE,
        additionalEquipmentRequestDigest(document, line, additional),
        createdAt);
  }

  private void createReleaseAttempt(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      OffsetDateTime createdAt) {
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE,
            List.of(
                guard.getLeaseId().toString(),
                Long.toString(guard.getLeaseVersion()),
                Long.toString(guard.getFenceToken()),
                document.getId().toString(),
                line.getId().toString())),
        createdAt);
  }

  private void createAttemptIfMissing(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsTargetService targetService,
      String operationType,
      String requestDigest,
      OffsetDateTime createdAt) {
    if (attemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(document.getId(), line.getId(), operationType)
        .isPresent()) {
      return;
    }
    attemptRepository.save(
        LogisticsExternalAttempt.create(
            document,
            line,
            targetService,
            operationType,
            requestDigest,
            document.getCorrelationId(),
            null,
            createdAt));
  }

  private void finishIfEveryLeaseReleased(LogisticsDocument document) {
    List<LogisticsDocumentLine> lines = lines(document.getId());
    if (lines.isEmpty()) throw malformed("Return completion has no lines");
    for (LogisticsDocumentLine line : lines) {
      Optional<LogisticsExternalAttempt> release =
          attemptRepository.findByDocument_IdAndLine_IdAndOperationType(
              document.getId(), line.getId(), LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE);
      Optional<LogisticsGuard> guard = guardRepository.findByLine_Id(line.getId());
      List<LogisticsMediaReference> media =
          mediaReferenceRepository.findAllByLine_IdAndPurposeOrderByCreatedAtAsc(
              line.getId(), LogisticsMediaPurpose.RETURN_INSPECTION);
      if (release.isEmpty()
          || release.get().getResult() != LogisticsExternalAttemptResult.CONFIRMED
          || guard.isEmpty()
          || guard.get().getGuardState() != LogisticsGuardState.RELEASED
          || media.isEmpty()
          || media.stream()
              .anyMatch(
                  reference ->
                      reference.getReadiness() != LogisticsMediaReadiness.READY)) {
        return;
      }
    }

    LogisticsEventType eventType;
    if (document.getState() == LogisticsDocumentState.ACCEPTING) {
      document.acceptReturn();
      eventType = LogisticsEventType.RETURN_ACCEPTED;
    } else if (document.getState() == LogisticsDocumentState.ESTIMATE_PENDING) {
      document.requestReturnEstimate();
      eventType = LogisticsEventType.RETURN_ESTIMATE_REQUESTED;
    } else {
      return;
    }
    documentRepository.saveAndFlush(document);
    documents.closeRentalOrderReturn(document);
    eventStore.append(
        document,
        lines.size(),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        eventType,
        null);
  }

  private void markConflict(LogisticsDocumentLine line, String operationType) {
    if (LogisticsDocumentService.RETURN_MEDIA_VALIDATE.equals(operationType)) {
      for (LogisticsMediaReference reference : mediaReferences(line)) {
        if (reference.getReadiness() == LogisticsMediaReadiness.PENDING) reference.reject();
      }
    }
    guardRepository.findByLine_Id(line.getId()).ifPresent(ReturnCompletionWorkflowStore::conflictGuard);
  }

  private void markReconciliationRequired(LogisticsDocumentLine line, String operationType) {
    if (LogisticsDocumentService.RETURN_MEDIA_VALIDATE.equals(operationType)) {
      for (LogisticsMediaReference reference : mediaReferences(line)) {
        if (reference.getReadiness() == LogisticsMediaReadiness.PENDING) {
          reference.requireReconciliation();
        }
      }
    }
    guardRepository
        .findByLine_Id(line.getId())
        .ifPresent(ReturnCompletionWorkflowStore::requireGuardReconciliation);
  }

  private static void conflictGuard(LogisticsGuard guard) {
    if (guard.getGuardState() == LogisticsGuardState.ACTIVE) guard.conflict();
  }

  private static void requireGuardReconciliation(LogisticsGuard guard) {
    if (guard.getGuardState() == LogisticsGuardState.ACTIVE) guard.requireReconciliation();
  }

  private void moveToConflict(LogisticsDocument document) {
    if (document.getState() == LogisticsDocumentState.ACCEPTING) {
      document.returnAcceptanceConflict();
    } else if (document.getState() == LogisticsDocumentState.ESTIMATE_PENDING) {
      document.returnEstimateConflict();
    } else {
      throw malformed("Return completion is not in a conflictable state");
    }
  }

  private void moveToReconciliation(LogisticsDocument document) {
    if (document.getState() == LogisticsDocumentState.ACCEPTING) {
      document.returnAcceptanceRequiresReconciliation();
    } else if (document.getState() == LogisticsDocumentState.ESTIMATE_PENDING) {
      document.returnEstimateRequiresReconciliation();
    } else {
      throw malformed("Return completion is not in a reconcilable state");
    }
  }

  private LogisticsExternalAttempt attempt(
      LogisticsExternalAttemptClaimService.Claim claim, String operationType) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!operationType.equals(attempt.getOperationType())) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
  }

  private LogisticsGuard activeGuard(LogisticsDocumentLine line) {
    LogisticsGuard guard =
        guardRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> malformed("Return completion has no asset guard"));
    if (guard.getGuardState() != LogisticsGuardState.ACTIVE
        || guard.getLeaseId() == null
        || guard.getLeaseVersion() == null
        || guard.getFenceToken() == null
        || guard.getObservedAssetVersion() == null) {
      throw malformed("Return completion asset guard is inactive or incomplete");
    }
    return guard;
  }

  private List<LogisticsMediaReference> mediaReferences(LogisticsDocumentLine line) {
    List<LogisticsMediaReference> references =
        mediaReferenceRepository.findAllByLine_IdAndPurposeOrderByCreatedAtAsc(
            line.getId(), LogisticsMediaPurpose.RETURN_INSPECTION);
    if (references.isEmpty()) throw malformed("Return completion has no media references");
    return references;
  }

  private static LogisticsDocumentLine requiredLine(LogisticsExternalAttempt attempt) {
    if (attempt.getLine() == null) throw malformed("Return completion attempt has no line");
    return attempt.getLine();
  }

  private static boolean isCompletionState(LogisticsDocumentState state) {
    return state == LogisticsDocumentState.ACCEPTING || state == LogisticsDocumentState.ESTIMATE_PENDING;
  }

  private static boolean isExpectedState(LogisticsDocument document, boolean estimate) {
    return estimate
        ? document.getState() == LogisticsDocumentState.ESTIMATE_PENDING
        : document.getState() == LogisticsDocumentState.ACCEPTING;
  }

  private static void requireValidatedMedia(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsMediaReference> references,
      LogisticsDependencyGateway.MediaValidation validation) {
    if (validation == null
        || validation.ownerType() != LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN
        || !document.getId().equals(validation.documentId())
        || !line.getId().equals(validation.lineId())
        || !document.getWarehouseId().equals(validation.warehouseId())
        || validation.references() == null
        || !mediaSet(references).equals(Set.copyOf(validation.references()))) {
      throw malformed("Media-service returned malformed return ownership truth");
    }
  }

  private static Set<LogisticsDependencyGateway.MediaReference> mediaSet(
      List<LogisticsMediaReference> references) {
    HashSet<LogisticsDependencyGateway.MediaReference> values = new HashSet<>();
    for (LogisticsMediaReference reference : references) {
      if (reference.getMediaId() == null || reference.getGeneration() == null || reference.getGeneration() < 1) {
        throw malformed("Return media reference is incomplete");
      }
      values.add(new LogisticsDependencyGateway.MediaReference(reference.getMediaId(), reference.getGeneration()));
    }
    return Set.copyOf(values);
  }

  private static void requireSettlementSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot,
      boolean estimate) {
    String requiredStatus = estimate ? "WAITING_ESTIMATE_CONFIRMATION" : "FREE";
    if (snapshot == null
        || !line.getAssetId().equals(snapshot.assetId())
        || !document.getWarehouseId().equals(snapshot.warehouseId())
        || snapshot.version() <= guard.getObservedAssetVersion()
        || !requiredStatus.equals(snapshot.status())
        || snapshot.contents() == null) {
      throw malformed("Asset-service returned malformed return-settlement truth");
    }
  }

  private static void requireMaintenanceSource(
      LogisticsReturnShortageSnapshot expected,
      LogisticsDependencyGateway.ReturnEstimateSource actual) {
    if (actual == null
        || !expected.getDocument().getId().equals(actual.returnId())
        || !expected.getLine().getId().equals(actual.lineId())
        || actual.sourceVersion() < 0
        || !expected.getWarehouseId().equals(actual.warehouseId())
        || !expected.getRentalItemId().equals(actual.rentalItemId())
        || actual.rentalItemVersion() != expected.getRentalItemVersion()
        || actual.estimateId() == null
        || actual.snapshotSha256() == null
        || !actual.snapshotSha256().matches("[0-9a-f]{64}")
        || actual.receivedAt() == null) {
      throw malformed("Maintenance-service returned malformed return estimate source truth");
    }
  }

  private static void requireReleasedLease(
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.OperationLease releasedLease) {
    if (releasedLease == null
        || !guard.getLeaseId().equals(releasedLease.leaseId())
        || !line.getAssetId().equals(releasedLease.rentalItemId())
        || releasedLease.version() < guard.getLeaseVersion()
        || releasedLease.fencingToken() != guard.getFenceToken()
        || !"RELEASED".equals(releasedLease.state())
        || releasedLease.expiresAt() == null) {
      throw malformed("Asset-service returned malformed released operation lease");
    }
  }

  private static List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> additionalEquipment(
      LogisticsDocumentLine line) {
    JsonNode snapshot = line.getReturnAdditionalContentsSnapshot();
    if (snapshot == null) return List.of();
    JsonNode values = snapshot.get("additionalEquipment");
    if (values == null || !values.isArray()) {
      throw malformed("Return additional equipment snapshot is malformed");
    }
    List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> additional = new ArrayList<>();
    HashSet<UUID> equipmentIds = new HashSet<>();
    for (JsonNode value : values) {
      JsonNode equipmentId = value.get("equipmentId");
      JsonNode quantity = value.get("quantity");
      if (equipmentId == null
          || !equipmentId.isTextual()
          || quantity == null
          || !quantity.isIntegralNumber()
          || quantity.longValue() < 1) {
        throw malformed("Return additional equipment snapshot is malformed");
      }
      try {
        UUID id = UUID.fromString(equipmentId.textValue());
        if (!equipmentIds.add(id)) {
          throw malformed("Return additional equipment snapshot has duplicate equipment");
        }
        additional.add(
            new LogisticsDependencyGateway.ReturnEquipmentReceiptLine(
                null, id, quantity.longValue(), null, -1, -1));
      } catch (IllegalArgumentException exception) {
        throw malformed("Return additional equipment snapshot has an invalid equipment identifier");
      }
    }
    additional.sort(Comparator.comparing(value -> value.equipmentId().toString()));
    return List.copyOf(additional);
  }

  private static void requireAdditionalEquipmentReceipt(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> expected,
      LogisticsDependencyGateway.ReturnEquipmentReceipt actual) {
    if (actual == null
        || !document.getId().equals(actual.returnId())
        || !line.getId().equals(actual.returnLineId())
        || !document.getWarehouseId().equals(actual.warehouseId())
        || actual.lines() == null
        || actual.lines().size() != expected.size()) {
      throw malformed("Asset-service returned malformed return equipment receipt truth");
    }
    java.util.Map<UUID, Long> expectedQuantities = new java.util.HashMap<>();
    for (LogisticsDependencyGateway.ReturnEquipmentReceiptLine value : expected) {
      expectedQuantities.put(value.equipmentId(), value.quantity());
    }
    java.util.Map<UUID, Long> actualQuantities = new java.util.HashMap<>();
    for (LogisticsDependencyGateway.ReturnEquipmentReceiptLine value : actual.lines()) {
      if (value == null
          || value.receiptId() == null
          || value.equipmentId() == null
          || value.quantity() < 1
          || value.stockBalanceId() == null
          || value.stockBalanceVersion() < 0
          || value.stockQuantity() < value.quantity()
          || actualQuantities.put(value.equipmentId(), value.quantity()) != null) {
        throw malformed("Asset-service returned malformed return equipment receipt truth");
      }
    }
    if (!expectedQuantities.equals(actualQuantities)) {
      throw malformed("Asset-service returned mismatched return equipment receipt truth");
    }
  }

  private static String mediaDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsMediaReference> references) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(document.getWarehouseId().toString());
    references.stream()
        .sorted(Comparator.comparing(LogisticsMediaReference::getMediaId))
        .forEach(
            reference -> {
              values.add(reference.getMediaId().toString());
              values.add(Long.toString(reference.getGeneration()));
            });
    return LogisticsCommandChecksum.sha256(operation, values);
  }

  private static String assetEffectDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            line.getAssetId().toString(),
            Long.toString(guard.getObservedAssetVersion()),
            guard.getLeaseId().toString(),
            Long.toString(guard.getFenceToken()),
            document.getId().toString(),
            line.getId().toString()));
  }

  private static String additionalEquipmentRequestDigest(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> equipment) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(document.getWarehouseId().toString());
    equipment.stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            value -> {
              values.add(value.equipmentId().toString());
              values.add(Long.toString(value.quantity()));
            });
    return LogisticsCommandChecksum.sha256(
        LogisticsDocumentService.RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE, values);
  }

  private static String additionalEquipmentReceiptDigest(
      LogisticsDependencyGateway.ReturnEquipmentReceipt receipt) {
    List<String> values = new ArrayList<>();
    values.add(receipt.returnId().toString());
    values.add(receipt.returnLineId().toString());
    values.add(receipt.warehouseId().toString());
    receipt.lines().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            value -> {
              values.add(value.receiptId().toString());
              values.add(value.equipmentId().toString());
              values.add(Long.toString(value.quantity()));
              values.add(value.stockBalanceId().toString());
              values.add(Long.toString(value.stockBalanceVersion()));
              values.add(Long.toString(value.stockQuantity()));
            });
    return LogisticsCommandChecksum.sha256(
        "RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE_RESPONSE", values);
  }

  private String estimateSourceDigest(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsReturnShortageSnapshot snapshot,
      OffsetDateTime arrivedAt) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(snapshot.getWarehouseId().toString());
    values.add(snapshot.getRentalItemId().toString());
    values.add(Long.toString(snapshot.getRentalItemVersion()));
    values.add(document.getScheduledDate().toString());
    values.add(arrivedAt.toString());
    mediaSet(mediaReferences(line)).stream()
        .sorted(Comparator.comparing(value -> value.mediaId().toString()))
        .forEach(
            reference -> {
              values.add(reference.mediaId().toString());
              values.add(Long.toString(reference.generation()));
            });
    return LogisticsCommandChecksum.sha256(
        LogisticsDocumentService.RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT, values);
  }

  private static String snapshotDigest(
      String operation, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    if (snapshot == null || snapshot.contents() == null) {
      throw malformed("Asset-service returned an empty rental-item snapshot");
    }
    List<String> values = new ArrayList<>();
    values.add(snapshot.assetId().toString());
    values.add(Long.toString(snapshot.version()));
    values.add(snapshot.warehouseId().toString());
    values.add(snapshot.status());
    for (LogisticsDependencyGateway.EquipmentContent content : snapshot.contents()) {
      if (content == null || content.equipmentId() == null || content.quantity() < 0) {
        throw malformed("Asset-service returned malformed equipment contents");
      }
      values.add(content.equipmentId().toString());
      values.add(Long.toString(content.quantity()));
    }
    return LogisticsCommandChecksum.sha256(operation, values);
  }

  private static String maintenanceDigest(LogisticsDependencyGateway.ReturnEstimateSource source) {
    List<String> values = new ArrayList<>();
    values.add(source.returnId().toString());
    values.add(source.lineId().toString());
    values.add(Long.toString(source.sourceVersion()));
    values.add(source.warehouseId().toString());
    values.add(source.rentalItemId().toString());
    values.add(Long.toString(source.rentalItemVersion()));
    values.add(source.estimateId().toString());
    values.add(source.snapshotSha256());
    values.add(source.receivedAt().toInstant().toString());
    return LogisticsCommandChecksum.sha256("RETURN_MAINTENANCE_ESTIMATE_SOURCE_RESPONSE", values);
  }

  private static String releaseDigest(LogisticsDependencyGateway.OperationLease lease) {
    return LogisticsCommandChecksum.sha256(
        "RETURN_ASSET_LEASE_RELEASE_RESPONSE",
        List.of(
            lease.leaseId().toString(),
            Long.toString(lease.version()),
            lease.rentalItemId().toString(),
            Long.toString(lease.fencingToken()),
            lease.state(),
            lease.expiresAt().toInstant().toString()));
  }

  private List<LogisticsDocumentLine> lines(UUID documentId) {
    return lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
  }

  private int lineCount(LogisticsDocument document) {
    return lines(document.getId()).size();
  }

  private static int retryDelaySeconds(int currentRetryCount) {
    return switch (currentRetryCount) {
      case 0 -> 1;
      case 1 -> 2;
      default -> 4;
    };
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private MaintenanceReturnArrivalResponse returnArrival(
      LogisticsDocument document, LogisticsDocumentLine line) {
    return returnArrivals
        .forReturn(document.getId(), document.getWarehouseId(), line.getAssetId())
        .orElseThrow(() -> malformed("Return maintenance attempt has no physical arrival event"));
  }

  private static LogisticsDependencyException malformed(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

  record Work(
      WorkType type,
      UUID operationId,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      boolean estimate,
      List<LogisticsDependencyGateway.MediaReference> references,
      List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> returnEquipment,
      LocalDate dispatchDate,
      OffsetDateTime arrivedAt) {
    static Work media(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID warehouseId,
        List<LogisticsMediaReference> references) {
      return new Work(
          WorkType.MEDIA,
          operationId,
          documentId,
          lineId,
          warehouseId,
          null,
          -1,
          null,
          -1,
          -1,
          false,
          mediaSet(references).stream().sorted(Comparator.comparing(value -> value.mediaId().toString())).toList(),
          List.of(),
          null,
          null);
    }

    static Work settlement(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID leaseId,
        long fencingToken,
        boolean estimate) {
      return new Work(
          WorkType.SETTLEMENT,
          operationId,
          documentId,
          lineId,
          null,
          assetId,
          expectedAssetVersion,
          leaseId,
          -1,
          fencingToken,
          estimate,
          List.of(),
          List.of(),
          null,
          null);
    }

    static Work maintenance(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID warehouseId,
        UUID assetId,
        long assetVersion,
        LocalDate dispatchDate,
        OffsetDateTime arrivedAt,
        List<LogisticsMediaReference> references) {
      return new Work(
          WorkType.MAINTENANCE,
          operationId,
          documentId,
          lineId,
          warehouseId,
          assetId,
          assetVersion,
          null,
          -1,
          -1,
          true,
          mediaSet(references).stream()
              .sorted(Comparator.comparing(value -> value.mediaId().toString()))
              .toList(),
          List.of(),
          dispatchDate,
          arrivedAt);
    }

    static Work returnEquipment(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID warehouseId,
        List<LogisticsDependencyGateway.ReturnEquipmentReceiptLine> equipment) {
      return new Work(
          WorkType.RETURN_EQUIPMENT,
          operationId,
          documentId,
          lineId,
          warehouseId,
          null,
          -1,
          null,
          -1,
          -1,
          false,
          List.of(),
          List.copyOf(equipment),
          null,
          null);
    }

    static Work release(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        UUID leaseId,
        long expectedLeaseVersion,
        long fencingToken) {
      return new Work(
          WorkType.LEASE_RELEASE,
          operationId,
          documentId,
          lineId,
          null,
          assetId,
          -1,
          leaseId,
          expectedLeaseVersion,
          fencingToken,
          false,
          List.of(),
          List.of(),
          null,
          null);
    }
  }

  enum WorkType {
    MEDIA,
    SETTLEMENT,
    MAINTENANCE,
    RETURN_EQUIPMENT,
    LEASE_RELEASE
  }
}
