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
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReturnShortageSnapshotRepository;
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
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsMediaReferenceRepository mediaReferenceRepository;
  private final LogisticsReturnShortageSnapshotRepository shortageSnapshotRepository;
  private final LogisticsReconciliationRepository reconciliationRepository;
  private final LogisticsEventStore eventStore;

  Optional<Work> nextWork(UUID documentId) {
    if (documentId == null) return Optional.empty();
    OffsetDateTime now = now();
    for (LogisticsExternalAttempt attempt :
        attemptRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      LogisticsDocument document = attempt.getDocument();
      if (!isCompletionState(document.getState()) || !attempt.isDue(now)) continue;
      LogisticsDocumentLine line = attempt.getLine();
      if (line == null) throw malformed("Return completion attempt has no line");

      if (document.getState() == LogisticsDocumentState.ACCEPTING) {
        Optional<Work> acceptance = acceptanceWork(attempt, document, line);
        if (acceptance.isPresent()) return acceptance;
      }
      if (document.getState() == LogisticsDocumentState.ESTIMATE_PENDING) {
        Optional<Work> estimate = estimateWork(attempt, document, line);
        if (estimate.isPresent()) return estimate;
      }
    }
    return Optional.empty();
  }

  @Transactional
  public void confirmMedia(UUID operationId, LogisticsDependencyGateway.MediaValidation validation) {
    LogisticsExternalAttempt attempt = attempt(operationId, LogisticsDocumentService.RETURN_MEDIA_VALIDATE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.ACCEPTING) return;

    List<LogisticsMediaReference> references = mediaReferences(line);
    requireValidatedMedia(document, line, references, validation);
    OffsetDateTime completedAt = now();
    for (LogisticsMediaReference reference : references) reference.ready(completedAt);
    attempt.confirm(mediaDigest("RETURN_MEDIA_VALIDATE_RESPONSE", document, line, references), completedAt);
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.RETURN_ASSET_SETTLE_FREE,
        assetEffectDigest(
            LogisticsDocumentService.RETURN_ASSET_SETTLE_FREE, document, line, activeGuard(line)),
        completedAt);
  }

  @Transactional
  public void confirmSettlement(
      UUID operationId,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot,
      boolean shortage) {
    String operationType =
        shortage
            ? LogisticsDocumentService.RETURN_ASSET_SETTLE_SHORTAGE
            : LogisticsDocumentService.RETURN_ASSET_SETTLE_FREE;
    LogisticsExternalAttempt attempt = attempt(operationId, operationType);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (!isExpectedState(document, shortage)) return;

    LogisticsGuard guard = activeGuard(line);
    requireSettlementSnapshot(document, line, guard, snapshot, shortage);
    OffsetDateTime completedAt = now();
    attempt.confirm(snapshotDigest(operationType + "_RESPONSE", snapshot), completedAt);
    guard.recordObservedAssetVersion(snapshot.version());
    if (shortage) {
      LogisticsReturnShortageSnapshot source =
          shortageSnapshotRepository
              .findByLine_Id(line.getId())
              .orElseThrow(() -> malformed("Return shortage settlement has no source snapshot"));
      createAttemptIfMissing(
          document,
          line,
          LogisticsTargetService.MAINTENANCE,
          LogisticsDocumentService.RETURN_MAINTENANCE_SHORTAGE_UPSERT,
          shortageDigest(document, line, source),
          completedAt);
    } else {
      createReleaseAttempt(document, line, guard, completedAt);
    }
  }

  @Transactional
  public void confirmMaintenance(
      UUID operationId, LogisticsDependencyGateway.ReturnShortageSource source) {
    LogisticsExternalAttempt attempt =
        attempt(operationId, LogisticsDocumentService.RETURN_MAINTENANCE_SHORTAGE_UPSERT);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.ESTIMATE_PENDING) return;
    LogisticsReturnShortageSnapshot expected =
        shortageSnapshotRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> malformed("Maintenance completion has no shortage source snapshot"));
    requireMaintenanceSource(expected, source);

    OffsetDateTime completedAt = now();
    attempt.confirm(maintenanceDigest(source), completedAt);
    createReleaseAttempt(document, line, activeGuard(line), completedAt);
  }

  @Transactional
  public void confirmLeaseRelease(
      UUID operationId, LogisticsDependencyGateway.OperationLease releasedLease) {
    LogisticsExternalAttempt attempt =
        attempt(operationId, LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE);
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

  @Transactional
  public void recordFailure(UUID operationId, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt =
        attemptRepository
            .findByOperationId(operationId)
            .orElseThrow(() -> new IllegalArgumentException("External attempt is missing"));
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
    if (LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE.equals(attempt.getOperationType())) {
      return Optional.of(releaseWork(attempt, document, line));
    }
    return Optional.empty();
  }

  private Optional<Work> estimateWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    if (LogisticsDocumentService.RETURN_ASSET_SETTLE_SHORTAGE.equals(attempt.getOperationType())) {
      return Optional.of(settlementWork(attempt, document, line, true));
    }
    if (LogisticsDocumentService.RETURN_MAINTENANCE_SHORTAGE_UPSERT.equals(attempt.getOperationType())) {
      LogisticsReturnShortageSnapshot snapshot =
          shortageSnapshotRepository
              .findByLine_Id(line.getId())
              .orElseThrow(() -> malformed("Return maintenance attempt has no source snapshot"));
      return Optional.of(
          Work.maintenance(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              snapshot.getWarehouseId(),
              snapshot.getRentalItemId(),
              snapshot.getRentalItemVersion(),
              shortages(snapshot)));
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
      boolean shortage) {
    LogisticsGuard guard = activeGuard(line);
    return Work.settlement(
        attempt.getOperationId(),
        document.getId(),
        line.getId(),
        line.getAssetId(),
        guard.getObservedAssetVersion(),
        guard.getLeaseId(),
        guard.getFenceToken(),
        shortage);
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
      if (release.isEmpty()
          || release.get().getResult() != LogisticsExternalAttemptResult.CONFIRMED
          || guard.isEmpty()
          || guard.get().getGuardState() != LogisticsGuardState.RELEASED) {
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

  private LogisticsExternalAttempt attempt(UUID operationId, String operationType) {
    LogisticsExternalAttempt attempt =
        attemptRepository
            .findByOperationId(operationId)
            .orElseThrow(() -> new IllegalArgumentException("External attempt is missing"));
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
    if (references.isEmpty()) throw malformed("Return acceptance has no media references");
    return references;
  }

  private static LogisticsDocumentLine requiredLine(LogisticsExternalAttempt attempt) {
    if (attempt.getLine() == null) throw malformed("Return completion attempt has no line");
    return attempt.getLine();
  }

  private static boolean isCompletionState(LogisticsDocumentState state) {
    return state == LogisticsDocumentState.ACCEPTING || state == LogisticsDocumentState.ESTIMATE_PENDING;
  }

  private static boolean isExpectedState(LogisticsDocument document, boolean shortage) {
    return shortage
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
      boolean shortage) {
    String requiredStatus = shortage ? "WAITING_ESTIMATE_CONFIRMATION" : "FREE";
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
      LogisticsDependencyGateway.ReturnShortageSource actual) {
    if (actual == null
        || !expected.getDocument().getId().equals(actual.returnId())
        || !expected.getLine().getId().equals(actual.lineId())
        || actual.sourceVersion() < 0
        || !expected.getWarehouseId().equals(actual.warehouseId())
        || !expected.getRentalItemId().equals(actual.rentalItemId())
        || actual.rentalItemVersion() != expected.getRentalItemVersion()
        || actual.snapshotSha256() == null
        || !actual.snapshotSha256().matches("[0-9a-f]{64}")
        || actual.receivedAt() == null
        || !shortageSet(expected).equals(Set.copyOf(actual.shortages()))) {
      throw malformed("Maintenance-service returned malformed shortage source truth");
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

  private static List<LogisticsDependencyGateway.EquipmentShortage> shortages(
      LogisticsReturnShortageSnapshot snapshot) {
    JsonNode values = snapshot.getShortages().get("shortages");
    if (values == null || !values.isArray() || values.isEmpty()) {
      throw malformed("Return shortage snapshot is malformed");
    }
    List<LogisticsDependencyGateway.EquipmentShortage> shortages = new ArrayList<>();
    HashSet<UUID> ids = new HashSet<>();
    for (JsonNode value : values) {
      JsonNode equipmentId = value.get("equipmentId");
      JsonNode missingQuantity = value.get("missingQuantity");
      if (equipmentId == null
          || !equipmentId.isTextual()
          || missingQuantity == null
          || !missingQuantity.isIntegralNumber()
          || missingQuantity.longValue() < 1) {
        throw malformed("Return shortage snapshot is malformed");
      }
      try {
        UUID id = UUID.fromString(equipmentId.textValue());
        if (!ids.add(id)) throw malformed("Return shortage snapshot has duplicate equipment");
        shortages.add(new LogisticsDependencyGateway.EquipmentShortage(id, missingQuantity.longValue()));
      } catch (IllegalArgumentException exception) {
        throw malformed("Return shortage snapshot has an invalid equipment identifier");
      }
    }
    return shortages;
  }

  private static Set<LogisticsDependencyGateway.EquipmentShortage> shortageSet(
      LogisticsReturnShortageSnapshot snapshot) {
    return Set.copyOf(shortages(snapshot));
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

  private static String shortageDigest(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsReturnShortageSnapshot snapshot) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(snapshot.getWarehouseId().toString());
    values.add(snapshot.getRentalItemId().toString());
    values.add(Long.toString(snapshot.getRentalItemVersion()));
    for (LogisticsDependencyGateway.EquipmentShortage shortage : shortages(snapshot)) {
      values.add(shortage.equipmentId().toString());
      values.add(Long.toString(shortage.missingQuantity()));
    }
    return LogisticsCommandChecksum.sha256(
        LogisticsDocumentService.RETURN_MAINTENANCE_SHORTAGE_UPSERT, values);
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

  private static String maintenanceDigest(LogisticsDependencyGateway.ReturnShortageSource source) {
    List<String> values = new ArrayList<>();
    values.add(source.returnId().toString());
    values.add(source.lineId().toString());
    values.add(Long.toString(source.sourceVersion()));
    values.add(source.warehouseId().toString());
    values.add(source.rentalItemId().toString());
    values.add(Long.toString(source.rentalItemVersion()));
    source.shortages().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            shortage -> {
              values.add(shortage.equipmentId().toString());
              values.add(Long.toString(shortage.missingQuantity()));
            });
    values.add(source.snapshotSha256());
    values.add(source.receivedAt().toInstant().toString());
    return LogisticsCommandChecksum.sha256("RETURN_MAINTENANCE_SHORTAGE_RESPONSE", values);
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
      boolean shortage,
      List<LogisticsDependencyGateway.MediaReference> references,
      List<LogisticsDependencyGateway.EquipmentShortage> shortages) {
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
          List.of());
    }

    static Work settlement(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID leaseId,
        long fencingToken,
        boolean shortage) {
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
          shortage,
          List.of(),
          List.of());
    }

    static Work maintenance(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID warehouseId,
        UUID assetId,
        long assetVersion,
        List<LogisticsDependencyGateway.EquipmentShortage> shortages) {
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
          List.of(),
          List.copyOf(shortages));
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
          List.of());
    }
  }

  enum WorkType {
    MEDIA,
    SETTLEMENT,
    MAINTENANCE,
    LEASE_RELEASE
  }
}
