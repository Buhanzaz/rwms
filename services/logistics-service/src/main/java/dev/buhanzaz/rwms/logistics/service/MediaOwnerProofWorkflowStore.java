package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliation;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
          LogisticsDocumentService.SHIPMENT_MEDIA_OWNER_PROOF_REGISTER,
          LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_REGISTER,
          LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE);

  private final LogisticsExternalAttemptRepository attemptRepository;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsReconciliationRepository reconciliationRepository;
  private final CustomerRentalSessionRepository customerSessions;

  /**
   * Builds an owner-proof request only for the exact leased operation. Transfer deactivation stays
   * deferred until the corresponding registration proof is confirmed, without scanning other rows.
   */
  @Transactional
  public Optional<Work> workForClaim(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!OPERATIONS.contains(attempt.getOperationType())) return Optional.empty();
    LogisticsDocumentLine line = requiredLine(attempt);
    if (LogisticsDocumentService.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE.equals(
            attempt.getOperationType())
        && !transferRegistrationConfirmed(attempt.getDocument(), line)) {
      return Optional.empty();
    }
    return Optional.of(work(attempt));
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

  /** Records the proof response only if the supplied lease is still exact and current. */
  @Transactional
  public void confirm(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.MediaOwnerProof proof) {
    LogisticsExternalAttempt attempt = attempt(claim);
    if (attempt.getResult() == LogisticsExternalAttemptResult.CONFIRMED) return;
    Work expected = work(attempt);
    requireExactProof(expected, proof);
    attempt.confirm(responseDigest(attempt.getOperationType(), proof), now());
  }

  /** Records proof failure only if the supplied lease is still exact and current. */
  @Transactional
  public void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt = attempt(claim);
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

  private LogisticsExternalAttempt attempt(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!OPERATIONS.contains(attempt.getOperationType())) {
      throw new IllegalArgumentException("External attempt type is invalid");
    }
    return attempt;
  }

  private Work work(LogisticsExternalAttempt attempt) {
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
            null,
            true);
      }
      case LogisticsDocumentService.SHIPMENT_MEDIA_OWNER_PROOF_REGISTER -> {
        requireType(document, LogisticsDocumentType.SHIPMENT);
        if (document.getRentalOrderId() == null) {
          throw new IllegalStateException("Customer shipment media proof has no rental order");
        }
        UUID authorizedSubjectId =
            customerSessions
                .findFirstByOrderIdOrderByCreatedAtAscIdAsc(document.getRentalOrderId())
                .filter(session -> document.getWarehouseId().equals(session.getWarehouseId()))
                .map(session -> session.getCustomerSubjectId())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Customer shipment media proof has no customer session"));
        yield new Work(
            attempt.getOperationId(),
            LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT,
            document.getId(),
            line.getId(),
            document.getWarehouseId(),
            0,
            0,
            authorizedSubjectId,
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
            null,
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
            null,
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
        || !java.util.Objects.equals(
            expected.authorizedSubjectId(), actual.authorizedSubjectId())
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
            proof.authorizedSubjectId() == null ? "" : proof.authorizedSubjectId().toString(),
            Boolean.toString(proof.active())));
  }

  private static long retryDelaySeconds(int retryCount) {
    return 1L << Math.min(retryCount, 6);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** One leased owner-proof command with its optional customer-subject restriction. */
  record Work(
      UUID operationId,
      LogisticsDependencyGateway.LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID authorizedSubjectId,
      boolean active) {}
}
