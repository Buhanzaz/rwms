package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliation;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional local half of the logistics media-owner proof delivery. The
 * exact proof is derived from immutable document and line identity, while the
 * network call is made outside the transaction by {@link MediaOwnerProofProcessor}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MediaOwnerProofWorkflowStore {
  static final List<String> OPERATIONS =
      List.of(
          LogisticsDocumentService.RETURN_MEDIA_OWNER_PROOF_REGISTER,
          LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_REGISTER,
          LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE);

  private final LogisticsExternalAttemptRepository attemptRepository;
  private final LogisticsReconciliationRepository reconciliationRepository;

  Optional<Work> nextWork(UUID documentId) {
    if (documentId == null) return Optional.empty();
    OffsetDateTime now = now();
    Set<UUID> blockedLines = new HashSet<>();
    for (LogisticsExternalAttempt attempt :
        attemptRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      if (!OPERATIONS.contains(attempt.getOperationType())) continue;
      LogisticsDocumentLine line = requiredLine(attempt);
      if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) continue;
      if (blockedLines.contains(line.getId())) continue;
      if (LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE.equals(
              attempt.getOperationType())
          && !transferRegistrationConfirmed(attempt.getDocument(), line)) {
        continue;
      }
      if (attempt.getResult() != LogisticsExternalAttemptResult.PENDING
          && attempt.getResult() != LogisticsExternalAttemptResult.RETRY) {
        blockedLines.add(line.getId());
        continue;
      }
      if (!attempt.isDue(now)) {
        blockedLines.add(line.getId());
        continue;
      }
      return Optional.of(work(attempt));
    }
    return Optional.empty();
  }

  private boolean transferRegistrationConfirmed(
      LogisticsDocument document, LogisticsDocumentLine line) {
    return attemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(
            document.getId(),
            line.getId(),
            LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_REGISTER)
        .map(value -> value.getResult() == LogisticsExternalAttemptResult.CONFIRMED)
        .orElse(false);
  }

  @Transactional
  public void confirm(UUID operationId, LogisticsDependencyGateway.MediaOwnerProof proof) {
    LogisticsExternalAttempt attempt = attempt(operationId);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    Work expected = work(attempt);
    requireExactProof(expected, proof);
    attempt.confirm(responseDigest(attempt.getOperationType(), proof), now());
  }

  @Transactional
  public void recordFailure(UUID operationId, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt = attempt(operationId);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    OffsetDateTime failedAt = now();
    if (exception.kind() == LogisticsDependencyException.FailureKind.TRANSIENT) {
      attempt.retry(failedAt.plusSeconds(retryDelaySeconds(attempt.getRetryCount())));
      return;
    }

    attempt.requireReconciliation(
        LogisticsCommandChecksum.sha256(
            "MEDIA_OWNER_PROOF_FAILURE",
            List.of(attempt.getOperationType(), exception.kind().name())),
        failedAt);
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (reconciliationRepository
        .findByDocument_IdAndLine_IdAndState(
            document.getId(), line.getId(), LogisticsReconciliationState.OPEN)
        .isEmpty()) {
      reconciliationRepository.save(
          LogisticsReconciliation.open(document, line, "MEDIA_OWNER_PROOF_REJECTED", failedAt));
    }
  }

  private LogisticsExternalAttempt attempt(UUID operationId) {
    LogisticsExternalAttempt attempt =
        attemptRepository
            .findByOperationId(operationId)
            .orElseThrow(() -> new IllegalArgumentException("External attempt is missing"));
    if (!OPERATIONS.contains(attempt.getOperationType())) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
  }

  private static Work work(LogisticsExternalAttempt attempt) {
    LogisticsDocument document = attempt.getDocument();
    LogisticsDocumentLine line = requiredLine(attempt);
    return switch (attempt.getOperationType()) {
      case LogisticsDocumentService.RETURN_MEDIA_OWNER_PROOF_REGISTER -> {
        requireType(document, LogisticsDocumentType.RETURN);
        yield new Work(
            attempt.getOperationId(),
            LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
            document.getId(),
            line.getId(),
            document.getWarehouseId(),
            0,
            0,
            true);
      }
      case LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_REGISTER -> {
        requireType(document, LogisticsDocumentType.TRANSFER);
        yield new Work(
            attempt.getOperationId(),
            LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
            document.getId(),
            line.getId(),
            destination(document),
            0,
            0,
            true);
      }
      case LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE -> {
        requireType(document, LogisticsDocumentType.TRANSFER);
        yield new Work(
            attempt.getOperationId(),
            LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
            document.getId(),
            line.getId(),
            destination(document),
            1,
            1,
            false);
      }
      default -> throw new IllegalArgumentException("External attempt type is invalid");
    };
  }

  private static void requireExactProof(
      Work expected, LogisticsDependencyGateway.MediaOwnerProof actual) {
    if (actual == null
        || expected.ownerType() != actual.ownerType()
        || !expected.documentId().equals(actual.documentId())
        || !expected.lineId().equals(actual.lineId())
        || !expected.warehouseId().equals(actual.warehouseId())
        || expected.ownerRevision() != actual.ownerRevision()
        || expected.aggregateVersion() != actual.aggregateVersion()
        || !expected.operationId().equals(actual.proofEventId())
        || expected.active() != actual.active()) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.CONFIGURATION,
          "Media-service returned a mismatched logistics owner proof");
    }
  }

  private static LogisticsDocumentLine requiredLine(LogisticsExternalAttempt attempt) {
    if (attempt.getLine() == null) {
      throw new IllegalStateException("Media owner proof has no logistics line");
    }
    return attempt.getLine();
  }

  private static void requireType(LogisticsDocument document, LogisticsDocumentType expected) {
    if (document.getDocumentType() != expected) {
      throw new IllegalStateException("Media owner proof document type is invalid");
    }
  }

  private static UUID destination(LogisticsDocument document) {
    if (document.getDestinationWarehouseId() == null) {
      throw new IllegalStateException("Transfer media owner proof has no destination warehouse");
    }
    return document.getDestinationWarehouseId();
  }

  private static String responseDigest(
      String operation, LogisticsDependencyGateway.MediaOwnerProof proof) {
    return LogisticsCommandChecksum.sha256(
        operation + "_RESPONSE",
        List.of(
            proof.ownerType().name(),
            proof.documentId().toString(),
            proof.lineId().toString(),
            proof.warehouseId().toString(),
            Long.toString(proof.ownerRevision()),
            Long.toString(proof.aggregateVersion()),
            proof.proofEventId().toString(),
            Boolean.toString(proof.active())));
  }

  private static long retryDelaySeconds(int retryCount) {
    return 1L << Math.min(retryCount, 6);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  record Work(
      UUID operationId,
      LogisticsDependencyGateway.LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      boolean active) {}
}
