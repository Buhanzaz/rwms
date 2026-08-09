package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReconcileRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationRequest;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRequestRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Records an explicit, idempotent operator reconciliation request without changing physical or
 * source-owned state. The request is later handled by the existing recovery workflow.
 */
@Service
@RequiredArgsConstructor
class LogisticsDocumentReconciliationCoordinator {
  private static final String RECONCILE_DOCUMENT = "RECONCILE_DOCUMENT";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsReconciliationRequestRepository reconciliationRequestRepository;
  private final LogisticsDocumentIdempotency idempotency;
  private final LogisticsDocumentReadProjection readProjection;

  LogisticsDocumentCommandResult reconcile(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      LogisticsDocumentType documentType,
      long expectedDocumentVersion,
      ReconcileRequest request) {
    requireReconciliationCommand(
        documentId, documentType, correlationId, expectedDocumentVersion, request);
    String reason = request.reason().trim();
    String checksum =
        LogisticsCommandChecksum.sha256(
            RECONCILE_DOCUMENT,
            List.of(
                documentId.toString(),
                documentType.name(),
                Long.toString(expectedDocumentVersion),
                reason));
    idempotency.acquireLock(subjectId, RECONCILE_DOCUMENT, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, RECONCILE_DOCUMENT, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document = readProjection.document(documentId, documentType);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Logistics document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.CONFLICT
        && document.getState() != LogisticsDocumentState.RECONCILIATION_REQUIRED) {
      throw new LogisticsConflictException("Only a visible conflict can be reconciled");
    }
    reconciliationRequestRepository.save(
        LogisticsReconciliationRequest.create(document, reason, subjectId, correlationId, now()));
    idempotency.remember(subjectId, idempotencyKey, RECONCILE_DOCUMENT, checksum, document);
    return result(document, false);
  }

  private LogisticsDocumentCommandResult result(LogisticsDocument document, boolean replayed) {
    return new LogisticsDocumentCommandResult(readProjection.view(document), replayed);
  }

  private static void requireReconciliationCommand(
      UUID documentId,
      LogisticsDocumentType documentType,
      UUID correlationId,
      long expectedDocumentVersion,
      ReconcileRequest request) {
    if (documentId == null
        || documentType == null
        || correlationId == null
        || expectedDocumentVersion < 0) {
      throw new IllegalArgumentException("Reconciliation identifiers and version are required");
    }
    requireRequest(request);
    if (request.reason() == null
        || request.reason().trim().isEmpty()
        || request.reason().trim().length() > 500) {
      throw new IllegalArgumentException("Reconciliation reason is invalid");
    }
  }

  private static void requireExpectedVersion(
      LogisticsDocument document, long expectedVersion, String message) {
    if (document.getVersion() != expectedVersion) {
      throw new LogisticsConflictException(message);
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static void requireRequest(Object request) {
    if (request == null) throw new IllegalArgumentException("Request is required");
  }
}
