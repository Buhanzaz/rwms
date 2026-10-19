package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReadiness;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliation;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
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
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Durable local half of the irreversible inter-warehouse transfer workflow.
 * It writes and validates all local evidence in transactions; network calls
 * are performed only by {@link TransferProcessor} after this store has
 * committed an attempt with a stable operation ID.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TransferWorkflowStore {
  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsExternalAttemptRepository attemptRepository;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsMediaReferenceRepository mediaReferenceRepository;
  private final LogisticsReconciliationRepository reconciliationRepository;
  private final LogisticsEventStore eventStore;
  private final TransferPlanWorkflowStore transferPlanWorkflow;

  /**
   * Builds local transfer request data for one exact lease. A claimed operation that is not yet the
   * current workflow transition is deferred by the processor without scanning unrelated attempts.
   */
  @Transactional
  public Optional<Work> workForClaim(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    LogisticsDocument document = attempt.getDocument();
    if (!isTransferWorkState(document.getState())) return Optional.empty();
    LogisticsDocumentLine line = attempt.getLine();
    if (line == null) return Optional.empty();
    return switch (document.getState()) {
      case DEPARTING -> departureWork(attempt, document, line);
      case ARRIVING -> arrivalWork(attempt, document, line);
      default -> Optional.empty();
    };
  }

  /** Records warehouse validation only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmWarehouse(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.WarehouseIdentity identity) {
    LogisticsExternalAttempt attempt = identityAttempt(claim);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    String operation = attempt.getOperationType();
    UUID expectedWarehouseId = expectedWarehouseId(document, operation);
    if (!isCurrentIdentityState(document, operation)) return;
    requireWarehouseIdentity(expectedWarehouseId, identity);

    attempt.confirm(identityDigest(operation + "_RESPONSE", identity), now());
  }

  /** Records departure maintenance completion only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmMaintenanceDeparture(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.TransferRepairDeparture preparation) {
    LogisticsExternalAttempt attempt =
        attempt(
            claim,
            LogisticsDocumentService.TRANSFER_MAINTENANCE_PREPARE_DEPARTURE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.DEPARTING) return;
    requireMaintenanceDeparture(line, preparation);

    OffsetDateTime completedAt = now();
    line.captureMaintenanceDeparture(
        preparation.activeRepairId(),
        preparation.activeRepairVersion(),
        preparation.assetStatus(),
        completedAt);
    lineRepository.saveAndFlush(line);
    attempt.confirm(
        maintenanceDepartureDigest(
            "TRANSFER_MAINTENANCE_PREPARE_DEPARTURE_RESPONSE", preparation),
        completedAt);
  }

  /** Records arrival maintenance completion only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmMaintenanceArrival(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.TransferRepairArrivalCompletion completion) {
    LogisticsExternalAttempt attempt =
        attempt(
            claim,
            LogisticsDocumentService.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.ARRIVING) return;
    requireMaintenanceArrival(document, line, completion);

    OffsetDateTime completedAt = now();
    line.completeMaintenanceArrival(completion.repairVersion(), completedAt);
    attempt.confirm(
        maintenanceArrivalDigest(
            "TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL_RESPONSE", completion),
        completedAt);
    finishLineArrival(document, line);
  }

  /** Records an asset snapshot only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmSnapshot(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    LogisticsExternalAttempt attempt = snapshotAttempt(claim);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    OffsetDateTime completedAt = now();

    if (LogisticsDocumentService.TRANSFER_ASSET_SNAPSHOT.equals(attempt.getOperationType())) {
      if (document.getState() != LogisticsDocumentState.DEPARTING) return;
      if (!departurePreconditionsConfirmed(document, line)) return;
      requireDepartureSourceSnapshot(document, line, snapshot);
      line.captureExpectedContents(contentsSnapshot(snapshot.contents()));
      attempt.confirm(snapshotDigest("TRANSFER_ASSET_SNAPSHOT_RESPONSE", snapshot), completedAt);
      createAttemptIfMissing(
          document,
          line,
          LogisticsTargetService.ASSET,
          LogisticsDocumentService.TRANSFER_ASSET_LEASE_ACQUIRE,
          LogisticsCommandChecksum.sha256(
              LogisticsDocumentService.TRANSFER_ASSET_LEASE_ACQUIRE,
              List.of(
                  line.getAssetId().toString(),
                  Long.toString(line.getAssetVersion()),
                  document.getId().toString(),
                  line.getId().toString())),
          completedAt);
      return;
    }

    if (document.getState() != LogisticsDocumentState.ARRIVING) return;
    if (!arrivalPreconditionsConfirmed(document, line)) return;
    LogisticsGuard guard = activeGuard(line);
    requireArrivalSourceSnapshot(document, line, guard, snapshot);
    attempt.confirm(snapshotDigest("TRANSFER_ASSET_ARRIVAL_SNAPSHOT_RESPONSE", snapshot), completedAt);
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.TRANSFER_ASSET_ARRIVE,
        assetEffectDigest(LogisticsDocumentService.TRANSFER_ASSET_ARRIVE, document, line, guard),
        completedAt);
  }

  /** Records asset lease acquisition only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmLease(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.OperationLease lease) {
    LogisticsExternalAttempt attempt =
        attempt(claim, LogisticsDocumentService.TRANSFER_ASSET_LEASE_ACQUIRE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.DEPARTING) return;
    requireActiveLease(line, lease);

    OffsetDateTime completedAt = now();
    attempt.confirm(leaseDigest("TRANSFER_ASSET_LEASE_RESPONSE", lease), completedAt);
    Optional<LogisticsGuard> existing = guardRepository.findByLine_Id(line.getId());
    if (existing.isPresent()) {
      LogisticsGuard guard = existing.get();
      if (guard.getGuardState() != LogisticsGuardState.ACTIVE
          || !lease.leaseId().equals(guard.getLeaseId())
          || lease.version() != guard.getLeaseVersion()
          || lease.fencingToken() != guard.getFenceToken()) {
        throw malformed("Transfer line has a conflicting asset guard");
      }
    } else {
      guardRepository.save(
          LogisticsGuard.active(
              document,
              line,
              lease.leaseId(),
              lease.version(),
              lease.fencingToken(),
              line.getAssetVersion(),
              completedAt));
    }
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.TRANSFER_ASSET_DEPART,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.TRANSFER_ASSET_DEPART,
            List.of(
                line.getAssetId().toString(),
                Long.toString(line.getAssetVersion()),
                lease.leaseId().toString(),
                Long.toString(lease.fencingToken()),
                document.getId().toString(),
                line.getId().toString())),
        completedAt);
  }

  /** Records media validation only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmMedia(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.MediaValidation validation) {
    LogisticsExternalAttempt attempt =
        attempt(claim, LogisticsDocumentService.TRANSFER_MEDIA_VALIDATE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.ARRIVING) return;
    if (!arrivalWarehouseConfirmed(document, line)) return;
    List<LogisticsMediaReference> references = mediaReferences(line);
    requireValidatedMedia(document, line, references, validation);

    OffsetDateTime completedAt = now();
    for (LogisticsMediaReference reference : references) reference.ready(completedAt);
    attempt.confirm(mediaDigest("TRANSFER_MEDIA_VALIDATE_RESPONSE", document, line, references), completedAt);
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.TRANSFER_ASSET_ARRIVAL_SNAPSHOT,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.TRANSFER_ASSET_ARRIVAL_SNAPSHOT,
            List.of(
                line.getAssetId().toString(),
                document.getWarehouseId().toString(),
                document.getId().toString(),
                line.getId().toString())),
        completedAt);
  }

  /** Records a fenced asset effect only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmFencedEffect(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    LogisticsExternalAttempt attempt = effectAttempt(claim);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    LogisticsGuard guard = activeGuard(line);
    OffsetDateTime completedAt = now();

    if (LogisticsDocumentService.TRANSFER_ASSET_DEPART.equals(attempt.getOperationType())) {
      if (document.getState() != LogisticsDocumentState.DEPARTING) return;
      requireDepartedSnapshot(document, line, guard, snapshot);
      attempt.confirm(snapshotDigest("TRANSFER_ASSET_DEPART_RESPONSE", snapshot), completedAt);
      guard.recordObservedAssetVersion(snapshot.version());
      line.consumeTransferUnitReservation();
      line.markDeparted();
      lineRepository.saveAndFlush(line);
      if (everyLineDeparted(document)) {
        document.markTransferInTransit();
        documentRepository.saveAndFlush(document);
        eventStore.append(
            document,
            lineCount(document),
            document.getCorrelationId(),
            document.getRequestedBySubjectId(),
            LogisticsEventType.TRANSFER_DEPARTED,
            null);
        transferPlanWorkflow.beginTransit(document);
      }
      return;
    }

    if (document.getState() != LogisticsDocumentState.ARRIVING) return;
    requireArrivedSnapshot(document, line, guard, snapshot);
    line.captureFactualContents(contentsSnapshot(snapshot.contents()));
    attempt.confirm(snapshotDigest("TRANSFER_ASSET_ARRIVE_RESPONSE", snapshot), completedAt);
    guard.recordObservedAssetVersion(snapshot.version());
    createLeaseReleaseAttempt(document, line, guard, completedAt);
  }

  /** Records asset lease release only if the supplied lease is still exact and current. */
  @Transactional
  public void confirmLeaseRelease(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.OperationLease lease) {
    LogisticsExternalAttempt attempt =
        attempt(claim, LogisticsDocumentService.TRANSFER_ASSET_LEASE_RELEASE);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (document.getState() != LogisticsDocumentState.ARRIVING) return;
    LogisticsGuard guard = activeGuard(line);
    requireReleasedLease(line, guard, lease);

    OffsetDateTime completedAt = now();
    attempt.confirm(leaseDigest("TRANSFER_ASSET_LEASE_RELEASE_RESPONSE", lease), completedAt);
    guard.release();
    if (line.hasActiveRepair()) {
      LogisticsGuard releasedGuard = releasedGuard(document, line);
      createAttemptIfMissing(
          document,
          line,
          LogisticsTargetService.MAINTENANCE,
          LogisticsDocumentService.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL,
          LogisticsDocumentService.transferMaintenanceDigest(
              LogisticsDocumentService.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL,
              document,
              line,
              line.getRepairContinuationPriority(),
              releasedGuard.getObservedAssetVersion()),
          completedAt);
      return;
    }
    finishLineArrival(document, line);
  }

  private void finishLineArrival(
      LogisticsDocument document, LogisticsDocumentLine line) {
    line.markArrived();
    lineRepository.saveAndFlush(line);
    document.beginTransferArrival();
    documentRepository.saveAndFlush(document);
    eventStore.append(
        document,
        lineCount(document),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.TRANSFER_LINE_ARRIVED,
        null);
    if (everyLineArrived(document)) {
      transferPlanWorkflow.requestCompletion(document);
    }
  }

  /** Records a dependency failure only if the supplied lease is still exact and current. */
  @Transactional
  public void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    LogisticsDocument document = attempt.getDocument();
    if (!isTransferWorkState(document.getState())) return;
    LogisticsDocumentLine line = requiredLine(attempt);
    OffsetDateTime completedAt = now();
    String responseDigest =
        LogisticsCommandChecksum.sha256(
            "TRANSFER_FAILURE", List.of(attempt.getOperationType(), exception.kind().name()));

    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      attempt.reject(responseDigest, completedAt);
      markConflict(line, attempt.getOperationType());
      document.transferConflict();
      documentRepository.saveAndFlush(document);
      eventStore.append(
          document,
          lineCount(document),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.TRANSFER_CONFLICT,
          "DEPENDENCY_REJECTED");
      return;
    }

    if (exception.kind() == LogisticsDependencyException.FailureKind.CONFIGURATION
        || attempt.getRetryCount() >= 3) {
      attempt.requireReconciliation(responseDigest, completedAt);
      markReconciliationRequired(line, attempt.getOperationType());
      document.transferRequiresReconciliation();
      documentRepository.saveAndFlush(document);
      if (reconciliationRepository
          .findByDocument_IdAndLine_IdAndState(
              document.getId(), line.getId(), LogisticsReconciliationState.OPEN)
          .isEmpty()) {
        reconciliationRepository.save(
            LogisticsReconciliation.open(
                document, line, "TRANSFER_DEPENDENCY_UNKNOWN", completedAt));
      }
      eventStore.append(
          document,
          lineCount(document),
          document.getCorrelationId(),
          document.getRequestedBySubjectId(),
          LogisticsEventType.TRANSFER_RECONCILIATION_REQUIRED,
          "DEPENDENCY_UNKNOWN");
      return;
    }

    attempt.retry(completedAt.plusSeconds(retryDelaySeconds(attempt.getRetryCount())));
  }

  private Optional<Work> departureWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    String operation = attempt.getOperationType();
    if (LogisticsDocumentService.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY.equals(operation)) {
      return Optional.of(Work.warehouse(attempt.getOperationId(), document.getId(), line.getId(), document.getWarehouseId()));
    }
    if (LogisticsDocumentService.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY.equals(operation)) {
      return Optional.of(
          Work.warehouse(
              attempt.getOperationId(), document.getId(), line.getId(), destinationWarehouseId(document)));
    }
    if (LogisticsDocumentService.TRANSFER_MAINTENANCE_PREPARE_DEPARTURE.equals(operation)) {
      return Optional.of(
          Work.maintenancePrepare(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              line.getAssetId(),
              document.getWarehouseId(),
              destinationWarehouseId(document)));
    }
    if (LogisticsDocumentService.TRANSFER_ASSET_SNAPSHOT.equals(operation)) {
      if (!departurePreconditionsConfirmed(document, line)) return Optional.empty();
      return Optional.of(Work.snapshot(attempt.getOperationId(), line.getAssetId()));
    }
    if (LogisticsDocumentService.TRANSFER_ASSET_LEASE_ACQUIRE.equals(operation)) {
      return Optional.of(
          Work.lease(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              line.getAssetId(),
              line.getAssetVersion()));
    }
    if (LogisticsDocumentService.TRANSFER_ASSET_DEPART.equals(operation)) {
      LogisticsGuard guard = activeGuard(line);
      return Optional.of(
          Work.effect(
              attempt.getOperationId(),
              LogisticsDependencyGateway.AssetEffect.TRANSFER_DEPART,
              document.getId(),
              line.getId(),
              line.getAssetId(),
              guard.getObservedAssetVersion(),
              guard.getLeaseId(),
              guard.getFenceToken(),
              null,
              line.getTransferAssetStatus()));
    }
    return Optional.empty();
  }

  private Optional<Work> arrivalWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document, LogisticsDocumentLine line) {
    String operation = attempt.getOperationType();
    if (LogisticsDocumentService.TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY.equals(operation)) {
      return Optional.of(
          Work.warehouse(
              attempt.getOperationId(), document.getId(), line.getId(), destinationWarehouseId(document)));
    }
    if (LogisticsDocumentService.TRANSFER_MEDIA_VALIDATE.equals(operation)) {
      if (!arrivalWarehouseConfirmed(document, line)) return Optional.empty();
      return Optional.of(
          Work.media(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              destinationWarehouseId(document),
              mediaReferences(line)));
    }
    if (LogisticsDocumentService.TRANSFER_ASSET_ARRIVAL_SNAPSHOT.equals(operation)) {
      if (!arrivalPreconditionsConfirmed(document, line)) return Optional.empty();
      return Optional.of(Work.snapshot(attempt.getOperationId(), line.getAssetId()));
    }
    if (LogisticsDocumentService.TRANSFER_ASSET_ARRIVE.equals(operation)) {
      LogisticsGuard guard = activeGuard(line);
      return Optional.of(
          Work.effect(
              attempt.getOperationId(),
              LogisticsDependencyGateway.AssetEffect.TRANSFER_ARRIVE,
              document.getId(),
              line.getId(),
              line.getAssetId(),
              guard.getObservedAssetVersion(),
              guard.getLeaseId(),
              guard.getFenceToken(),
              destinationWarehouseId(document),
              line.getTransferAssetStatus()));
    }
    if (LogisticsDocumentService.TRANSFER_ASSET_LEASE_RELEASE.equals(operation)) {
      LogisticsGuard guard = activeGuard(line);
      return Optional.of(
          Work.release(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              guard.getLeaseId(),
              guard.getLeaseVersion(),
              guard.getFenceToken()));
    }
    if (LogisticsDocumentService.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL.equals(operation)) {
      LogisticsGuard releasedGuard = releasedGuard(document, line);
      long rentalItemVersion = releasedGuard.getObservedAssetVersion();
      requireMaintenanceArrivalFingerprint(attempt, document, line, rentalItemVersion);
      return Optional.of(
          Work.maintenanceComplete(
              attempt.getOperationId(),
              document.getId(),
              line.getId(),
              line.getAssetId(),
              document.getWarehouseId(),
              destinationWarehouseId(document),
              line.getRepairContinuationPriority(),
              rentalItemVersion));
    }
    return Optional.empty();
  }

  private boolean departureWarehousesConfirmed(
      LogisticsDocument document, LogisticsDocumentLine line) {
    return confirmed(document, line, LogisticsDocumentService.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY)
        && confirmed(document, line, LogisticsDocumentService.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY);
  }

  private boolean departurePreconditionsConfirmed(
      LogisticsDocument document, LogisticsDocumentLine line) {
    return departureWarehousesConfirmed(document, line)
        && confirmed(
            document,
            line,
            LogisticsDocumentService.TRANSFER_MAINTENANCE_PREPARE_DEPARTURE);
  }

  private boolean arrivalWarehouseConfirmed(LogisticsDocument document, LogisticsDocumentLine line) {
    return confirmed(
        document, line, LogisticsDocumentService.TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY);
  }

  private boolean arrivalPreconditionsConfirmed(
      LogisticsDocument document, LogisticsDocumentLine line) {
    return arrivalWarehouseConfirmed(document, line)
        && confirmed(document, line, LogisticsDocumentService.TRANSFER_MEDIA_VALIDATE);
  }

  private boolean confirmed(LogisticsDocument document, LogisticsDocumentLine line, String operation) {
    return attemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(document.getId(), line.getId(), operation)
        .map(attempt -> attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED)
        .orElse(false);
  }

  private void createLeaseReleaseAttempt(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      OffsetDateTime createdAt) {
    createAttemptIfMissing(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentService.TRANSFER_ASSET_LEASE_RELEASE,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentService.TRANSFER_ASSET_LEASE_RELEASE,
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
      LogisticsTargetService target,
      String operation,
      String digest,
      OffsetDateTime createdAt) {
    if (attemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(document.getId(), line.getId(), operation)
        .isPresent()) return;
    attemptRepository.save(
        LogisticsExternalAttempt.create(
            document, line, target, operation, digest, document.getCorrelationId(), null, createdAt));
  }

  private boolean everyLineDeparted(LogisticsDocument document) {
    List<LogisticsDocumentLine> lines = lines(document.getId());
    if (lines.isEmpty()) return false;
    for (LogisticsDocumentLine line : lines) {
      if (line.getState() != LogisticsLineState.DEPARTED
          || !confirmed(document, line, LogisticsDocumentService.TRANSFER_ASSET_DEPART)) {
        return false;
      }
    }
    return true;
  }

  private boolean everyLineArrived(LogisticsDocument document) {
    List<LogisticsDocumentLine> lines = lines(document.getId());
    if (lines.isEmpty()) return false;
    for (LogisticsDocumentLine line : lines) {
      if (line.getState() != LogisticsLineState.ARRIVED
          || !confirmed(document, line, LogisticsDocumentService.TRANSFER_ASSET_LEASE_RELEASE)) {
        return false;
      }
    }
    return true;
  }

  private LogisticsExternalAttempt identityAttempt(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!isIdentityOperation(attempt.getOperationType())) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
  }

  private LogisticsExternalAttempt snapshotAttempt(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!LogisticsDocumentService.TRANSFER_ASSET_SNAPSHOT.equals(attempt.getOperationType())
        && !LogisticsDocumentService.TRANSFER_ASSET_ARRIVAL_SNAPSHOT.equals(attempt.getOperationType())) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
  }

  private LogisticsExternalAttempt effectAttempt(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!LogisticsDocumentService.TRANSFER_ASSET_DEPART.equals(attempt.getOperationType())
        && !LogisticsDocumentService.TRANSFER_ASSET_ARRIVE.equals(attempt.getOperationType())) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
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
        guardRepository.findByLine_Id(line.getId()).orElseThrow(() -> malformed("Transfer line has no asset guard"));
    if (guard.getGuardState() != LogisticsGuardState.ACTIVE
        || guard.getLeaseId() == null
        || guard.getLeaseVersion() == null
        || guard.getFenceToken() == null
        || guard.getObservedAssetVersion() == null) {
      throw malformed("Transfer asset guard is inactive or incomplete");
    }
    return guard;
  }

  private LogisticsGuard releasedGuard(
      LogisticsDocument document, LogisticsDocumentLine line) {
    LogisticsGuard guard =
        guardRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> malformed("Transfer line has no released asset guard"));
    if (guard.getGuardState() != LogisticsGuardState.RELEASED
        || guard.getDocument() == null
        || !document.getId().equals(guard.getDocument().getId())
        || guard.getLine() == null
        || !line.getId().equals(guard.getLine().getId())
        || !line.getAssetId().equals(guard.getAssetId())
        || guard.getObservedAssetVersion() == null
        || guard.getObservedAssetVersion() < 0) {
      throw malformed("Transfer released asset guard is incomplete or mismatched");
    }
    return guard;
  }

  private List<LogisticsMediaReference> mediaReferences(LogisticsDocumentLine line) {
    List<LogisticsMediaReference> references =
        mediaReferenceRepository.findAllByLine_IdAndPurposeOrderByCreatedAtAsc(
            line.getId(), LogisticsMediaPurpose.TRANSFER_ARRIVAL);
    if (references.isEmpty()) throw malformed("Transfer arrival has no media references");
    return references;
  }

  private static LogisticsDocumentLine requiredLine(LogisticsExternalAttempt attempt) {
    if (attempt.getLine() == null) throw malformed("Transfer attempt has no line");
    return attempt.getLine();
  }

  private static boolean isTransferWorkState(LogisticsDocumentState state) {
    return state == LogisticsDocumentState.DEPARTING
        || state == LogisticsDocumentState.ARRIVING;
  }

  private static boolean isIdentityOperation(String operation) {
    return LogisticsDocumentService.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY.equals(operation)
        || LogisticsDocumentService.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY.equals(operation)
        || LogisticsDocumentService.TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY.equals(operation);
  }

  private static boolean isCurrentIdentityState(LogisticsDocument document, String operation) {
    return (LogisticsDocumentService.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY.equals(operation)
            || LogisticsDocumentService.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY.equals(operation))
        ? document.getState() == LogisticsDocumentState.DEPARTING
        : document.getState() == LogisticsDocumentState.ARRIVING;
  }

  private static UUID expectedWarehouseId(LogisticsDocument document, String operation) {
    return LogisticsDocumentService.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY.equals(operation)
        ? document.getWarehouseId()
        : destinationWarehouseId(document);
  }

  private static UUID destinationWarehouseId(LogisticsDocument document) {
    UUID destination = document.getDestinationWarehouseId();
    if (destination == null || destination.equals(document.getWarehouseId())) {
      throw malformed("Transfer destination warehouse is invalid");
    }
    return destination;
  }

  private static void requireWarehouseIdentity(
      UUID expectedWarehouseId, LogisticsDependencyGateway.WarehouseIdentity identity) {
    if (identity == null
        || !expectedWarehouseId.equals(identity.id())
        || identity.version() < 0
        || identity.timeZone() == null
        || identity.timeZone().isBlank()
        || identity.timeZone().length() > 64) {
      throw rejected("Warehouse-service rejected a transfer warehouse");
    }
    try {
      ZoneId.of(identity.timeZone());
    } catch (DateTimeException exception) {
      throw malformed("Warehouse-service returned an invalid IANA timezone");
    }
  }

  private static void requireDepartureSourceSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())
        || snapshot.version() != line.getAssetVersion()
        || !document.getWarehouseId().equals(snapshot.warehouseId())
        || !matchesTransferSourceStatus(
            line.getTransferAssetStatus(), snapshot.status())) {
      throw rejected("Asset-service rejected transfer departure preconditions");
    }
  }

  private static void requireArrivalSourceSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())
        || snapshot.version() != guard.getObservedAssetVersion()
        || !document.getWarehouseId().equals(snapshot.warehouseId())
        || !"IN_TRANSFER".equals(snapshot.status())
        || !sameExpectedContents(line, snapshot.contents())) {
      throw rejected("Transfer arrival preconditions no longer match canonical asset truth");
    }
  }

  private static void requireDepartedSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())) {
      throw malformed("Asset-service returned a different transfer rental item");
    }
    if (!document.getWarehouseId().equals(snapshot.warehouseId())) {
      throw malformed("Asset-service changed the transfer warehouse at departure");
    }
    if (snapshot.version() <= guard.getObservedAssetVersion()) {
      throw malformed("Asset-service did not advance the transfer departure version");
    }
    if (!"IN_TRANSFER".equals(snapshot.status())) {
      throw malformed("Asset-service did not confirm the in-transfer status");
    }
    if (!sameExpectedContents(line, snapshot.contents())) {
      throw malformed("Asset-service changed transfer contents at departure");
    }
  }

  private static void requireArrivedSnapshot(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    if (!line.getAssetId().equals(snapshot.assetId())
        || !destinationWarehouseId(document).equals(snapshot.warehouseId())
        || snapshot.version() <= guard.getObservedAssetVersion()
        || !java.util.Objects.equals(line.getTransferAssetStatus(), snapshot.status())
        || !sameExpectedContents(line, snapshot.contents())) {
      throw malformed("Asset-service returned malformed transfer-arrival truth");
    }
  }

  private static void requireMaintenanceDeparture(
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.TransferRepairDeparture preparation) {
    if (preparation == null
        || (!"FREE".equals(preparation.assetStatus())
            && !"REPAIR".equals(preparation.assetStatus()))
        || ("FREE".equals(preparation.assetStatus())
            && (preparation.activeRepairId() != null
                || preparation.activeRepairVersion() != null))
        || ("REPAIR".equals(preparation.assetStatus())
            && (preparation.activeRepairId() == null
                || preparation.activeRepairVersion() == null
                || preparation.activeRepairVersion() < 0))) {
      throw malformed("Maintenance-service returned malformed transfer departure truth");
    }
    if (line.getMaintenancePreparedAt() != null
        && (!java.util.Objects.equals(
                line.getActiveRepairId(), preparation.activeRepairId())
            || !java.util.Objects.equals(
                line.getActiveRepairVersion(), preparation.activeRepairVersion())
            || !java.util.Objects.equals(
                line.getTransferAssetStatus(), preparation.assetStatus()))) {
      throw malformed("Maintenance-service changed durable transfer departure truth");
    }
  }

  private static void requireMaintenanceArrival(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsDependencyGateway.TransferRepairArrivalCompletion completion) {
    if (completion == null
        || line.getActiveRepairId() == null
        || !line.getActiveRepairId().equals(completion.activeRepairId())
        || completion.repairVersion() == null
        || completion.repairVersion() < 0
        || (line.getActiveRepairVersion() != null
            && completion.repairVersion() < line.getActiveRepairVersion())
        || !destinationWarehouseId(document).equals(completion.warehouseId())) {
      throw malformed("Maintenance-service returned malformed transfer arrival completion");
    }
  }

  private static void requireMaintenanceArrivalFingerprint(
      LogisticsExternalAttempt attempt,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      long rentalItemVersion) {
    String expected =
        LogisticsDocumentService.transferMaintenanceDigest(
            LogisticsDocumentService.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL,
            document,
            line,
            line.getRepairContinuationPriority(),
            rentalItemVersion);
    if (!expected.equals(attempt.getRequestSha256())) {
      throw malformed("Transfer maintenance arrival request no longer matches released asset truth");
    }
  }

  private static void requireSnapshotShape(LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    if (snapshot == null
        || snapshot.assetId() == null
        || snapshot.warehouseId() == null
        || snapshot.version() < 0
        || snapshot.status() == null
        || snapshot.contents() == null) {
      throw malformed("Asset-service returned malformed rental-item truth");
    }
  }

  private static boolean matchesTransferSourceStatus(
      String restorationClass, String actualStatus) {
    if ("FREE".equals(restorationClass)) return "FREE".equals(actualStatus);
    return "REPAIR".equals(restorationClass)
        && ("REPAIR".equals(actualStatus) || "CAPITAL_REPAIR".equals(actualStatus));
  }

  private static void requireActiveLease(
      LogisticsDocumentLine line, LogisticsDependencyGateway.OperationLease lease) {
    if (lease == null
        || lease.leaseId() == null
        || !line.getAssetId().equals(lease.rentalItemId())
        || lease.version() < 0
        || lease.fencingToken() < 1
        || !"ACTIVE".equals(lease.state())
        || lease.expiresAt() == null) {
      throw malformed("Asset-service returned malformed transfer operation lease");
    }
  }

  private static void requireReleasedLease(
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      LogisticsDependencyGateway.OperationLease lease) {
    if (lease == null
        || !guard.getLeaseId().equals(lease.leaseId())
        || !line.getAssetId().equals(lease.rentalItemId())
        || lease.version() < guard.getLeaseVersion()
        || lease.fencingToken() != guard.getFenceToken()
        || !"RELEASED".equals(lease.state())
        || lease.expiresAt() == null) {
      throw malformed("Asset-service returned malformed released transfer lease");
    }
  }

  private static void requireValidatedMedia(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsMediaReference> references,
      LogisticsDependencyGateway.MediaValidation validation) {
    if (validation == null
        || validation.ownerType() != LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER
        || !document.getId().equals(validation.documentId())
        || !line.getId().equals(validation.lineId())
        || !destinationWarehouseId(document).equals(validation.warehouseId())
        || validation.references() == null
        || validation.references().size() != references.size()) {
      throw malformed("Media-service returned malformed transfer ownership truth");
    }
    Set<LogisticsDependencyGateway.MediaReference> actual = new HashSet<>();
    for (LogisticsDependencyGateway.MediaReference reference : validation.references()) {
      if (reference == null
          || reference.mediaId() == null
          || reference.generation() < 1
          || !actual.add(reference)) {
        throw malformed("Media-service returned invalid transfer media references");
      }
    }
    if (!mediaSet(references).equals(Set.copyOf(actual))) {
      throw malformed("Media-service returned mismatched transfer media references");
    }
  }

  private static Set<LogisticsDependencyGateway.MediaReference> mediaSet(
      List<LogisticsMediaReference> references) {
    HashSet<LogisticsDependencyGateway.MediaReference> values = new HashSet<>();
    for (LogisticsMediaReference reference : references) {
      if (reference.getMediaId() == null
          || reference.getGeneration() == null
          || reference.getGeneration() < 1
          || !values.add(
              new LogisticsDependencyGateway.MediaReference(
                  reference.getMediaId(), reference.getGeneration()))) {
        throw malformed("Transfer media reference is incomplete");
      }
    }
    return Set.copyOf(values);
  }

  private static boolean sameExpectedContents(
      LogisticsDocumentLine line, List<LogisticsDependencyGateway.EquipmentContent> contents) {
    JsonNode expected = line.getExpectedContentsSnapshot();
    if (expected == null) return false;
    JsonNode values = expected.get("contents");
    if (values == null || !values.isArray()) return false;
    HashMap<UUID, Long> expectedValues = new HashMap<>();
    for (JsonNode value : values) {
      JsonNode equipmentId = value.get("equipmentId");
      JsonNode quantity = value.get("quantity");
      if (equipmentId == null
          || !equipmentId.isTextual()
          || quantity == null
          || !quantity.isIntegralNumber()
          || quantity.longValue() < 0) {
        return false;
      }
      try {
        if (expectedValues.put(UUID.fromString(equipmentId.textValue()), quantity.longValue()) != null) {
          return false;
        }
      } catch (IllegalArgumentException exception) {
        return false;
      }
    }
    HashMap<UUID, Long> actualValues = new HashMap<>();
    if (contents == null) return false;
    for (LogisticsDependencyGateway.EquipmentContent content : contents) {
      if (content == null
          || content.equipmentId() == null
          || content.quantity() < 0
          || actualValues.put(content.equipmentId(), content.quantity()) != null) {
        return false;
      }
    }
    return expectedValues.equals(actualValues);
  }

  private static ObjectNode contentsSnapshot(List<LogisticsDependencyGateway.EquipmentContent> contents) {
    if (contents == null) throw malformed("Asset-service returned missing contents");
    List<LogisticsDependencyGateway.EquipmentContent> sorted = new ArrayList<>(contents);
    sorted.sort(
        Comparator.comparing(
            value -> value == null || value.equipmentId() == null ? "" : value.equipmentId().toString()));
    HashSet<UUID> equipmentIds = new HashSet<>();
    ArrayNode values = JsonNodeFactory.instance.arrayNode();
    for (LogisticsDependencyGateway.EquipmentContent content : sorted) {
      if (content == null
          || content.equipmentId() == null
          || content.quantity() < 0
          || !equipmentIds.add(content.equipmentId())) {
        throw malformed("Asset-service returned malformed equipment contents");
      }
      ObjectNode value = values.addObject();
      value.put("equipmentId", content.equipmentId().toString());
      value.put("quantity", content.quantity());
    }
    ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
    snapshot.set("contents", values);
    return snapshot;
  }

  private void markConflict(LogisticsDocumentLine line, String operation) {
    if (line.getState() != LogisticsLineState.ARRIVED && line.getState() != LogisticsLineState.CANCELLED) {
      line.conflict();
    }
    if (LogisticsDocumentService.TRANSFER_MEDIA_VALIDATE.equals(operation)) {
      for (LogisticsMediaReference reference : mediaReferences(line)) {
        if (reference.getReadiness() == LogisticsMediaReadiness.PENDING) reference.reject();
      }
    }
    guardRepository.findByLine_Id(line.getId()).ifPresent(TransferWorkflowStore::conflictGuard);
  }

  private void markReconciliationRequired(LogisticsDocumentLine line, String operation) {
    if (LogisticsDocumentService.TRANSFER_MEDIA_VALIDATE.equals(operation)) {
      for (LogisticsMediaReference reference : mediaReferences(line)) {
        if (reference.getReadiness() == LogisticsMediaReadiness.PENDING) {
          reference.requireReconciliation();
        }
      }
    }
    guardRepository.findByLine_Id(line.getId()).ifPresent(TransferWorkflowStore::reconcileGuard);
  }

  private static void conflictGuard(LogisticsGuard guard) {
    if (guard.getGuardState() == LogisticsGuardState.ACTIVE) guard.conflict();
  }

  private static void reconcileGuard(LogisticsGuard guard) {
    if (guard.getGuardState() == LogisticsGuardState.ACTIVE) guard.requireReconciliation();
  }

  private static String identityDigest(
      String operation, LogisticsDependencyGateway.WarehouseIdentity identity) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            identity.id().toString(),
            Long.toString(identity.version()),
            Boolean.toString(identity.active()),
            identity.timeZone()));
  }

  private static String snapshotDigest(
      String operation, LogisticsDependencyGateway.RentalItemSnapshot snapshot) {
    requireSnapshotShape(snapshot);
    ObjectNode contents = contentsSnapshot(snapshot.contents());
    List<String> values = new ArrayList<>();
    values.add(snapshot.assetId().toString());
    values.add(Long.toString(snapshot.version()));
    values.add(snapshot.warehouseId().toString());
    values.add(snapshot.status());
    for (JsonNode content : contents.get("contents")) {
      values.add(content.get("equipmentId").textValue());
      values.add(Long.toString(content.get("quantity").longValue()));
    }
    return LogisticsCommandChecksum.sha256(operation, values);
  }

  private static String leaseDigest(
      String operation, LogisticsDependencyGateway.OperationLease lease) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            lease.leaseId().toString(),
            Long.toString(lease.version()),
            lease.rentalItemId().toString(),
            Long.toString(lease.fencingToken()),
            lease.state(),
            lease.expiresAt().toInstant().toString()));
  }

  private static String mediaDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsMediaReference> references) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(destinationWarehouseId(document).toString());
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

  private static String maintenanceDepartureDigest(
      String operation,
      LogisticsDependencyGateway.TransferRepairDeparture preparation) {
    List<String> values = new ArrayList<>();
    values.add(
        preparation.activeRepairId() == null
            ? null
            : preparation.activeRepairId().toString());
    values.add(
        preparation.activeRepairVersion() == null
            ? null
            : preparation.activeRepairVersion().toString());
    values.add(preparation.assetStatus());
    return LogisticsCommandChecksum.sha256(operation, values);
  }

  private static String maintenanceArrivalDigest(
      String operation,
      LogisticsDependencyGateway.TransferRepairArrivalCompletion completion) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            completion.activeRepairId().toString(),
            completion.repairVersion().toString(),
            completion.warehouseId().toString()));
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

  private static LogisticsDependencyException rejected(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.PERMANENT_REJECTION, message);
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
      LogisticsDependencyGateway.AssetEffect assetEffect,
      List<LogisticsDependencyGateway.MediaReference> references,
      UUID sourceWarehouseId,
      String transferAssetStatus,
      Integer priority,
      long rentalItemVersion) {
    static Work warehouse(UUID operationId, UUID documentId, UUID lineId, UUID warehouseId) {
      return new Work(
          WorkType.WAREHOUSE,
          operationId,
          documentId,
          lineId,
          warehouseId,
          null,
          -1,
          null,
          -1,
          -1,
          null,
          List.of(),
          null,
          null,
          null,
          -1);
    }

    static Work snapshot(UUID operationId, UUID assetId) {
      return new Work(
          WorkType.SNAPSHOT,
          operationId,
          null,
          null,
          null,
          assetId,
          -1,
          null,
          -1,
          -1,
          null,
          List.of(),
          null,
          null,
          null,
          -1);
    }

    static Work lease(
        UUID operationId, UUID documentId, UUID lineId, UUID assetId, long expectedAssetVersion) {
      return new Work(
          WorkType.LEASE,
          operationId,
          documentId,
          lineId,
          null,
          assetId,
          expectedAssetVersion,
          null,
          -1,
          -1,
          null,
          List.of(),
          null,
          null,
          null,
          -1);
    }

    static Work media(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID warehouseId,
        List<LogisticsMediaReference> references) {
      List<LogisticsDependencyGateway.MediaReference> values =
          mediaSet(references).stream()
              .sorted(Comparator.comparing(value -> value.mediaId().toString()))
              .toList();
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
          null,
          values,
          null,
          null,
          null,
          -1);
    }

    static Work effect(
        UUID operationId,
        LogisticsDependencyGateway.AssetEffect effect,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        long expectedAssetVersion,
        UUID leaseId,
        long fencingToken,
        UUID destinationWarehouseId,
        String transferAssetStatus) {
      return new Work(
          WorkType.EFFECT,
          operationId,
          documentId,
          lineId,
          destinationWarehouseId,
          assetId,
          expectedAssetVersion,
          leaseId,
          -1,
          fencingToken,
          effect,
          List.of(),
          null,
          transferAssetStatus,
          null,
          -1);
    }

    static Work release(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID leaseId,
        long expectedLeaseVersion,
        long fencingToken) {
      return new Work(
          WorkType.LEASE_RELEASE,
          operationId,
          documentId,
          lineId,
          null,
          null,
          -1,
          leaseId,
          expectedLeaseVersion,
          fencingToken,
          null,
          List.of(),
          null,
          null,
          null,
          -1);
    }

    static Work maintenancePrepare(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        UUID sourceWarehouseId,
        UUID destinationWarehouseId) {
      return new Work(
          WorkType.MAINTENANCE_PREPARE,
          operationId,
          documentId,
          lineId,
          destinationWarehouseId,
          assetId,
          -1,
          null,
          -1,
          -1,
          null,
          List.of(),
          sourceWarehouseId,
          null,
          null,
          -1);
    }

    static Work maintenanceComplete(
        UUID operationId,
        UUID documentId,
        UUID lineId,
        UUID assetId,
        UUID sourceWarehouseId,
        UUID destinationWarehouseId,
        Integer priority,
        long rentalItemVersion) {
      return new Work(
          WorkType.MAINTENANCE_COMPLETE,
          operationId,
          documentId,
          lineId,
          destinationWarehouseId,
          assetId,
          -1,
          null,
          -1,
          -1,
          null,
          List.of(),
          sourceWarehouseId,
          null,
          priority,
          rentalItemVersion);
    }
  }

  enum WorkType {
    WAREHOUSE,
    SNAPSHOT,
    LEASE,
    MEDIA,
    EFFECT,
    LEASE_RELEASE,
    MAINTENANCE_PREPARE,
    MAINTENANCE_COMPLETE
  }
}
