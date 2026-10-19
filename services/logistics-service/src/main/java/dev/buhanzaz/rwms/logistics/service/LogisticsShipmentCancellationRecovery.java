package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns the durable evidence checks and audit recovery required before shipment cancellation may
 * compensate asset-side preparation effects.
 */
@Service
@RequiredArgsConstructor
class LogisticsShipmentCancellationRecovery {
  private static final String HISTORICAL_CANCELLATION_RESOLUTION =
      "HISTORICAL_SHIPMENT_CANCELLED_BY_OPERATOR";

  private final LogisticsExternalAttemptRepository externalAttemptRepository;
  private final LogisticsReconciliationRepository reconciliationRepository;

  /**
   * Rejects cancellation unless every recorded preparation effect has an outcome that the selected
   * ordinary or failed-historical workflow can safely compensate.
   */
  void requireSafe(UUID documentId, boolean failedHistoricalImport) {
    List<LogisticsExternalAttempt> attempts = attempts(documentId);
    if (failedHistoricalImport) {
      requireFailedHistoricalCancellationSafe(attempts);
    } else {
      requireOrdinaryCancellationSafe(attempts);
    }
  }

  /** Resolves only open reconciliation audit rows after the historical document enters cancellation. */
  void resolveHistoricalReconciliations(
      UUID documentId, UUID subjectId, OffsetDateTime resolvedAt) {
    for (var reconciliation :
        reconciliationRepository.findAllByDocument_IdAndStateOrderByOpenedAtAscIdAsc(
            documentId, LogisticsReconciliationState.OPEN)) {
      reconciliation.resolve(HISTORICAL_CANCELLATION_RESOLUTION, subjectId, resolvedAt);
    }
  }

  /**
   * Reopens only a lease acquisition whose original operation identity can recover a lost response;
   * the relay then replays that exact attempt before releasing the returned lease capability.
   */
  boolean reopenHistoricalLeaseAcquisitionIfRequired(
      UUID documentId, OffsetDateTime retryAt) {
    boolean recoveryRequired = false;
    for (LogisticsExternalAttempt attempt : attempts(documentId)) {
      if (!LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_ACQUIRE.equals(
          attempt.getOperationType())) {
        continue;
      }
      if (attempt.getResult() == LogisticsExternalAttemptResult.RECONCILIATION_REQUIRED) {
        attempt.reopenForRecovery(retryAt);
        recoveryRequired = true;
      } else if (attempt.getResult() == LogisticsExternalAttemptResult.PENDING
          || attempt.getResult() == LogisticsExternalAttemptResult.RETRY) {
        recoveryRequired = true;
      }
    }
    return recoveryRequired;
  }

  private List<LogisticsExternalAttempt> attempts(UUID documentId) {
    return externalAttemptRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId);
  }

  private static void requireOrdinaryCancellationSafe(List<LogisticsExternalAttempt> attempts) {
    for (LogisticsExternalAttempt attempt : attempts) {
      String operation = attempt.getOperationType();
      boolean mutatingPreparation =
          LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_ACQUIRE.equals(operation)
              || operation.startsWith(
                  LogisticsDocumentEffectOperations.SHIPMENT_HOLD_ACQUIRE_PREFIX);
      if (mutatingPreparation && attempt.getResult() != LogisticsExternalAttemptResult.CONFIRMED) {
        throw new LogisticsConflictException(
            "A shipment preparation effect has an unknown outcome");
      }
    }
  }

  private static void requireFailedHistoricalCancellationSafe(
      List<LogisticsExternalAttempt> attempts) {
    for (LogisticsExternalAttempt attempt : attempts) {
      String operation = attempt.getOperationType();
      LogisticsExternalAttemptResult result = attempt.getResult();
      if (LogisticsDocumentEffectOperations.SHIPMENT_ASSET_CONFIRM.equals(operation)
          && result != LogisticsExternalAttemptResult.PERMANENT_REJECTION) {
        throw new LogisticsConflictException(
            "Historical shipment asset effect is complete or has an unknown outcome");
      }
      boolean reversibleAssetPreparation =
          LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_ACQUIRE.equals(operation)
              || operation.startsWith(
                  LogisticsDocumentEffectOperations.SHIPMENT_HOLD_ACQUIRE_PREFIX)
              || operation.startsWith(
                  LogisticsDocumentEffectOperations.SHIPMENT_HOLD_COMMIT_PREFIX);
      boolean recoverableLeaseAcquisition =
          LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_ACQUIRE.equals(operation)
              && (result == LogisticsExternalAttemptResult.PENDING
                  || result == LogisticsExternalAttemptResult.RETRY
                  || result == LogisticsExternalAttemptResult.RECONCILIATION_REQUIRED);
      if (reversibleAssetPreparation
          && result != LogisticsExternalAttemptResult.CONFIRMED
          && result != LogisticsExternalAttemptResult.PERMANENT_REJECTION
          && !recoverableLeaseAcquisition) {
        throw new LogisticsConflictException(
            "Historical shipment preparation effect has an unknown outcome");
      }
    }
  }
}
