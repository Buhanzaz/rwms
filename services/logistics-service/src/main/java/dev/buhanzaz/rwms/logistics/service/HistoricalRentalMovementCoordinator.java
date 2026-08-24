package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateHistoricalRentalMovementRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.HistoricalRentalMovementKind;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns creation of user-entered past rental facts. It deliberately creates normal logistics
 * documents, lines, events and recoverable dependency attempts rather than mutating cabin state
 * in the browser or fabricating a driver task.
 */
@Service
@RequiredArgsConstructor
class HistoricalRentalMovementCoordinator {
  private static final String CREATE_HISTORICAL_RENTAL_MOVEMENT =
      "CREATE_HISTORICAL_RENTAL_MOVEMENT";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsEventStore eventStore;
  private final LogisticsDocumentWarehouseAdmission warehouseAdmission;
  private final LogisticsDocumentIdempotency idempotency;
  private final LogisticsDocumentReadProjection readProjection;
  private final LogisticsDocumentAttemptWriter attemptWriter;

  LogisticsDocumentCommandResult create(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateHistoricalRentalMovementRequest request,
      String clientSnapshot,
      AdmissionTicket admission) {
    require(request, subjectId, idempotencyKey, correlationId, clientSnapshot, admission);
    String checksum = checksum(request);
    idempotency.acquireLock(subjectId, CREATE_HISTORICAL_RENTAL_MOVEMENT, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(
            subjectId, idempotencyKey, CREATE_HISTORICAL_RENTAL_MOVEMENT, checksum);
    if (replay != null) return result(replay, true);

    WarehouseOperationDirection direction = direction(request.kind());
    warehouseAdmission.requireAdmission(
        admission, List.of(new AdmissionRequirement(request.warehouseId(), direction)));
    LocalDate warehouseToday = admission.localDate(request.warehouseId());
    if (request.occurredOn().isAfter(warehouseToday)) {
      throw new LogisticsConflictException("Historical rental operation date cannot be in the future");
    }

    LogisticsDocument document =
        request.kind() == HistoricalRentalMovementKind.SHIPMENT
            ? LogisticsDocument.createHistoricalRentalShipment(
                request.warehouseId(),
                request.clientId(),
                clientSnapshot,
                request.occurredOn(),
                subjectId,
                correlationId)
            : LogisticsDocument.createHistoricalRentalReturn(
                request.warehouseId(),
                request.clientId(),
                clientSnapshot,
                request.occurredOn(),
                subjectId,
                correlationId);
    document = documentRepository.saveAndFlush(document);
    LogisticsDocumentLine line =
        lineRepository.saveAndFlush(
            LogisticsDocumentLine.create(
                document,
                1,
                request.rentalItemId(),
                request.expectedRentalItemVersion(),
                clientSnapshot));
    eventStore.initialize(document, 1, correlationId, subjectId);

    if (request.kind() == HistoricalRentalMovementKind.SHIPMENT) {
      beginHistoricalShipment(document, line);
    } else {
      beginHistoricalReturn(document, line);
    }
    warehouseAdmission.enqueue(document, document.getWarehouseId(), admission);
    idempotency.remember(
        subjectId,
        idempotencyKey,
        CREATE_HISTORICAL_RENTAL_MOVEMENT,
        checksum,
        document);
    return result(document, false);
  }

  private void beginHistoricalShipment(LogisticsDocument document, LogisticsDocumentLine line) {
    OffsetDateTime startedAt = now();
    document.beginShipmentPreparation();
    documentRepository.saveAndFlush(document);
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.MAINTENANCE,
        LogisticsDocumentEffectOperations.SHIPMENT_HISTORICAL_MAINTENANCE_CLOSE,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentEffectOperations.SHIPMENT_HISTORICAL_MAINTENANCE_CLOSE,
            List.of(
                document.getId().toString(),
                document.getWarehouseId().toString(),
                line.getAssetId().toString(),
                Long.toString(line.getAssetVersion()))),
        startedAt);
    eventStore.append(
        document,
        1,
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.SHIPMENT_PREPARATION_STARTED,
        "HISTORICAL_RENTAL_IMPORT");
  }

  private void beginHistoricalReturn(LogisticsDocument document, LogisticsDocumentLine line) {
    OffsetDateTime startedAt = now();
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.MEDIA,
        LogisticsDocumentEffectOperations.RETURN_MEDIA_OWNER_PROOF_REGISTER,
        attemptWriter.ownerProofDigest(
            LogisticsDocumentEffectOperations.RETURN_MEDIA_OWNER_PROOF_REGISTER,
            document,
            line,
            document.getWarehouseId(),
            0,
            0,
            true),
        startedAt);
    document.beginReturnRegistration();
    documentRepository.saveAndFlush(document);
    attemptWriter.createDocumentAttempt(
        document,
        LogisticsTargetService.WAREHOUSE,
        LogisticsDocumentEffectOperations.RETURN_WAREHOUSE_IDENTITY,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentEffectOperations.RETURN_WAREHOUSE_IDENTITY,
            List.of(document.getWarehouseId().toString())),
        startedAt);
    eventStore.append(
        document,
        1,
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.RETURN_REGISTRATION_STARTED,
        "HISTORICAL_RENTAL_IMPORT");
  }

  private LogisticsDocumentCommandResult result(LogisticsDocument document, boolean replayed) {
    return new LogisticsDocumentCommandResult(readProjection.view(document), replayed);
  }

  private static WarehouseOperationDirection direction(HistoricalRentalMovementKind kind) {
    if (kind == null) throw new IllegalArgumentException("Historical rental operation kind is required");
    return kind == HistoricalRentalMovementKind.SHIPMENT
        ? WarehouseOperationDirection.OUTGOING
        : WarehouseOperationDirection.INCOMING;
  }

  private static String checksum(CreateHistoricalRentalMovementRequest request) {
    return LogisticsCommandChecksum.sha256(
        CREATE_HISTORICAL_RENTAL_MOVEMENT,
        List.of(
            request.warehouseId().toString(),
            request.rentalItemId().toString(),
            Long.toString(request.expectedRentalItemVersion()),
            request.clientId().toString(),
            request.kind().name(),
            request.occurredOn().toString()));
  }

  private static void require(
      CreateHistoricalRentalMovementRequest request,
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      String clientSnapshot,
      AdmissionTicket admission) {
    if (request == null
        || request.warehouseId() == null
        || request.rentalItemId() == null
        || request.expectedRentalItemVersion() == null
        || request.expectedRentalItemVersion() < 0
        || request.clientId() == null
        || request.kind() == null
        || request.occurredOn() == null
        || subjectId == null
        || idempotencyKey == null
        || correlationId == null
        || admission == null
        || clientSnapshot == null
        || clientSnapshot.isBlank()
        || clientSnapshot.trim().length() > 512) {
      throw new IllegalArgumentException("Historical rental movement request is incomplete");
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
