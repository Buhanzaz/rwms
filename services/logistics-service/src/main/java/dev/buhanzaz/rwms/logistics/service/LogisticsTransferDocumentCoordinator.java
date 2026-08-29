package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferArrivalPreflightView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.UpdateTransferPlanRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.domain.TransferPlan;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanSnapshot;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns the transfer document state machine, including independently fenced departure and arrival
 * lines. It preserves the durable relay hand-off before any private dependency call is possible.
 */
@Service
@RequiredArgsConstructor
class LogisticsTransferDocumentCoordinator {
  private static final String CREATE_TRANSFER = "CREATE_TRANSFER";
  private static final String UPDATE_TRANSFER_PLAN = "UPDATE_TRANSFER_PLAN";
  private static final String CONFIRM_TRANSFER_PLAN = "CONFIRM_TRANSFER_PLAN";
  private static final String DEPART_TRANSFER_LINE = "DEPART_TRANSFER_LINE";
  private static final String ARRIVE_TRANSFER_LINE = "ARRIVE_TRANSFER_LINE";
  private static final String CANCEL_TRANSFER = "CANCEL_TRANSFER";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsMediaReferenceRepository mediaReferenceRepository;
  private final EquipmentMovementTaskService equipmentMovementTasks;
  private final TransferFurnitureTaskService transferFurnitureTasks;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsEventStore eventStore;
  private final LogisticsDocumentWarehouseAdmission warehouseAdmission;
  private final LogisticsDocumentIdempotency idempotency;
  private final LogisticsDocumentReadProjection readProjection;
  private final LogisticsDocumentAttemptWriter attemptWriter;
  private final DocumentDriverTaskPlanner driverTaskPlanner;
  private final TransferPlanService transferPlanning;
  private final TransferPlanWorkflowStore transferPlanWorkflow;

  LogisticsDocumentCommandResult createTransfer(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateTransferRequest request) {
    requireRequest(request);
    warehouseAdmission.requireLegacyAdmissionDisabled();
    return createTransfer(
        subjectId,
        idempotencyKey,
        correlationId,
        request,
        warehouseAdmission.testTicket(
            subjectId, CREATE_TRANSFER, idempotencyKey, transferAdmission(request)));
  }

  LogisticsDocumentCommandResult createTransfer(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateTransferRequest request,
      AdmissionTicket admission) {
    requireRequest(request);
    String checksum =
        LogisticsCommandChecksum.sha256(CREATE_TRANSFER, transferFingerprintValues(request));
    idempotency.acquireLock(subjectId, CREATE_TRANSFER, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, CREATE_TRANSFER, checksum);
    if (replay != null) return result(replay, true);

    List<AdmissionRequirement> requirements = transferAdmission(request);
    warehouseAdmission.requireAdmission(admission, requirements);
    validateTransferSchedule(request, admission.localDate(request.warehouseId()));
    validateTransferCreateShape(request);

    LogisticsDocument document =
        documentRepository.saveAndFlush(
            LogisticsDocument.createTransfer(
                request.warehouseId(),
                request.destinationWarehouseId(),
                request.scheduledDate(),
                subjectId,
                correlationId));
    List<LogisticsDocumentLine> lines = List.of();
    int eventLineCount;
    if (request.plan() == null) {
      lines = lineRepository.saveAllAndFlush(transferLines(document, request.lines()));
      driverTaskPlanner.plan(document, lines);
      createMediaOwnerProofAttempts(document, lines);
      transferFurnitureTasks.createForTransfer(
          subjectId, document, request.scheduledDate(), request.furnitureReplacements(), admission);
      eventLineCount = lines.size();
    } else {
      TransferPlan plan =
          transferPlanning.createDraft(document, request.scheduledDate(), request.plan());
      eventLineCount = transferPlanning.auditLineCount(plan);
    }
    eventStore.initialize(document, eventLineCount, correlationId, subjectId);
    warehouseAdmission.enqueue(document, document.getWarehouseId(), admission);
    warehouseAdmission.enqueue(document, document.getDestinationWarehouseId(), admission);
    idempotency.remember(subjectId, idempotencyKey, CREATE_TRANSFER, checksum, document);
    return result(document, false);
  }

  /** Replaces one complete transfer plan under document and idempotency fences. */
  TransferPlanCommandResult updateTransferPlan(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      UpdateTransferPlanRequest request,
      LocalDate warehouseToday) {
    requirePlanCommand(
        subjectId,
        idempotencyKey,
        correlationId,
        documentId,
        expectedDocumentVersion,
        request,
        warehouseToday);
    String checksum =
        LogisticsCommandChecksum.sha256(
            UPDATE_TRANSFER_PLAN,
            transferPlanFingerprintValues(
                documentId,
                expectedDocumentVersion,
                request.scheduledDate(),
                request.plan()));
    idempotency.acquireLock(subjectId, UPDATE_TRANSFER_PLAN, idempotencyKey);
    TransferPlanView replay =
        idempotency.replayResponse(
            subjectId,
            idempotencyKey,
            UPDATE_TRANSFER_PLAN,
            checksum,
            TransferPlanView.class);
    if (replay != null) return new TransferPlanCommandResult(replay, true);

    LogisticsDocument document = requiredTransferForUpdate(documentId);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Transfer document version changed concurrently");
    validateTransferSchedule(request.scheduledDate(), warehouseToday);
    document.updateTransferDraftSchedule(request.scheduledDate());
    TransferPlan plan =
        transferPlanning.replaceDraft(document, request.scheduledDate(), request.plan());
    documentRepository.saveAndFlush(document);
    eventStore.append(
        document,
        transferPlanning.auditLineCount(plan),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_PLAN_UPDATED,
        "PLAN_REPLACED");
    TransferPlanView response = transferPlanning.view(document, 0);
    idempotency.remember(
        subjectId, idempotencyKey, UPDATE_TRANSFER_PLAN, checksum, document, response);
    return new TransferPlanCommandResult(response, false);
  }

  /**
   * Freezes a complete plan and materializes its physical cabin lines. Asset reservation readiness
   * remains explicit NOT_RESERVED; planned transfers therefore cannot depart yet.
   */
  TransferPlanCommandResult confirmTransferPlan(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      AdmissionTicket admission) {
    if (subjectId == null
        || idempotencyKey == null
        || correlationId == null
        || documentId == null
        || expectedDocumentVersion < 0
        || admission == null) {
      throw new IllegalArgumentException("Transfer confirmation identity and version are required");
    }
    String checksum =
        LogisticsCommandChecksum.sha256(
            CONFIRM_TRANSFER_PLAN,
            List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    idempotency.acquireLock(subjectId, CONFIRM_TRANSFER_PLAN, idempotencyKey);
    TransferPlanView replay =
        idempotency.replayResponse(
            subjectId,
            idempotencyKey,
            CONFIRM_TRANSFER_PLAN,
            checksum,
            TransferPlanView.class);
    if (replay != null) return new TransferPlanCommandResult(replay, true);

    LogisticsDocument document = requiredTransferForUpdate(documentId);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Transfer document version changed concurrently");
    List<AdmissionRequirement> requirements = transferAdmission(document);
    warehouseAdmission.requireAdmission(admission, requirements);
    validateTransferSchedule(
        document.getScheduledDate(), admission.localDate(document.getWarehouseId()));
    if (!readProjection.lines(documentId).isEmpty()) {
      throw new LogisticsConflictException("Planned transfer already has physical cabin lines");
    }

    TransferPlan plan = transferPlanning.confirm(document);
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(
            plannedTransferLines(document, transferPlanning.allocatedCabins(plan)));
    createMediaOwnerProofAttempts(document, lines);
    transferFurnitureTasks.createForTransfer(
        subjectId,
        document,
        document.getScheduledDate(),
        transferPlanning.furnitureReplacements(plan),
        admission);
    document.touchTransferPlanConfirmation();
    documentRepository.saveAndFlush(document);
    transferPlanWorkflow.enqueueConfirmation(document, plan, lines);
    eventStore.append(
        document,
        transferPlanning.auditLineCount(plan),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_CONFIRMED,
        "RESERVING");
    TransferPlanView response = transferPlanning.view(document, lines.size());
    idempotency.remember(
        subjectId, idempotencyKey, CONFIRM_TRANSFER_PLAN, checksum, document, response);
    return new TransferPlanCommandResult(response, false);
  }

  LogisticsDocumentCommandResult departTransferLine(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      long expectedLineVersion) {
    requireTransferCommand(
        documentId, lineId, correlationId, expectedDocumentVersion, expectedLineVersion);
    String checksum =
        LogisticsCommandChecksum.sha256(
            DEPART_TRANSFER_LINE,
            List.of(
                documentId.toString(),
                lineId.toString(),
                Long.toString(expectedDocumentVersion),
                Long.toString(expectedLineVersion)));
    idempotency.acquireLock(subjectId, DEPART_TRANSFER_LINE, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, DEPART_TRANSFER_LINE, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document =
        readProjection.document(documentId, LogisticsDocumentType.TRANSFER);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Transfer document version changed concurrently");
    transferPlanning.requireDepartureAllowed(document);
    if (document.getState() != LogisticsDocumentState.DRAFT
        && document.getState() != LogisticsDocumentState.DEPARTING) {
      throw new LogisticsConflictException(
          "Transfer departure is not allowed in its current lifecycle state");
    }
    LogisticsDocumentLine line = readProjection.transferLine(document, lineId);
    requireLineVersion(line, expectedLineVersion, "Transfer line version changed concurrently");
    if (line.getState() != LogisticsLineState.PENDING) {
      throw new LogisticsConflictException("Transfer line is not pending departure");
    }
    transferFurnitureTasks.requireReadyForDeparture(document.getId(), line.getAssetId());

    UUID destinationWarehouseId = transferDestination(document);
    OffsetDateTime now = now();
    document.beginTransferDeparture();
    documentRepository.saveAndFlush(document);
    line.beginDeparture();
    lineRepository.saveAndFlush(line);
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.WAREHOUSE,
        LogisticsDocumentEffectOperations.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY,
        transferWarehouseDigest(
            LogisticsDocumentEffectOperations.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY,
            document,
            line,
            document.getWarehouseId()),
        now);
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.WAREHOUSE,
        LogisticsDocumentEffectOperations.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY,
        transferWarehouseDigest(
            LogisticsDocumentEffectOperations.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY,
            document,
            line,
            destinationWarehouseId),
        now);
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.MAINTENANCE,
        LogisticsDocumentEffectOperations.TRANSFER_MAINTENANCE_PREPARE_DEPARTURE,
        transferMaintenanceDigest(
            LogisticsDocumentEffectOperations.TRANSFER_MAINTENANCE_PREPARE_DEPARTURE,
            document,
            line,
            null,
            null),
        now);
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.ASSET,
        LogisticsDocumentEffectOperations.TRANSFER_ASSET_SNAPSHOT,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentEffectOperations.TRANSFER_ASSET_SNAPSHOT,
            List.of(
                line.getAssetId().toString(),
                Long.toString(line.getAssetVersion()),
                document.getWarehouseId().toString(),
                document.getId().toString(),
                line.getId().toString())),
        now);
    eventStore.append(
        document,
        readProjection.linesRequired(documentId).size(),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_DEPARTURE_STARTED,
        null);
    idempotency.remember(subjectId, idempotencyKey, DEPART_TRANSFER_LINE, checksum, document);
    return result(document, false);
  }

  LogisticsDocumentCommandResult arriveTransferLine(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      long expectedLineVersion,
      ArriveTransferLineRequest request) {
    requireTransferCommand(
        documentId, lineId, correlationId, expectedDocumentVersion, expectedLineVersion);
    requireRequest(request);
    validateTransferMediaReferences(request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            ARRIVE_TRANSFER_LINE,
            transferArrivalFingerprintValues(
                documentId, lineId, expectedDocumentVersion, expectedLineVersion, request));
    idempotency.acquireLock(subjectId, ARRIVE_TRANSFER_LINE, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, ARRIVE_TRANSFER_LINE, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document =
        readProjection.document(documentId, LogisticsDocumentType.TRANSFER);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Transfer document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.IN_TRANSIT
        && document.getState() != LogisticsDocumentState.ARRIVING) {
      throw new LogisticsConflictException(
          "Transfer arrival is not allowed in its current lifecycle state");
    }
    LogisticsDocumentLine line = readProjection.transferLine(document, lineId);
    requireLineVersion(line, expectedLineVersion, "Transfer line version changed concurrently");
    if (line.getState() != LogisticsLineState.DEPARTED) {
      throw new LogisticsConflictException("Transfer line is not awaiting arrival");
    }
    LogisticsDependencyGateway.TransferRepairArrivalPreflight preflight =
        requiredTransferArrivalPreflight(document, line);
    validateTransferArrivalContinuation(request, preflight);

    UUID destinationWarehouseId = transferDestination(document);
    OffsetDateTime now = now();
    document.beginTransferArrival();
    documentRepository.saveAndFlush(document);
    line.beginArrival();
    line.configureRepairContinuation(request.priority());
    lineRepository.saveAndFlush(line);
    List<LogisticsMediaReference> references =
        request.references().stream()
            .map(
                reference ->
                    LogisticsMediaReference.pending(
                        document,
                        line,
                        reference.mediaId(),
                        reference.generation(),
                        LogisticsMediaPurpose.TRANSFER_ARRIVAL,
                        now))
            .toList();
    mediaReferenceRepository.saveAllAndFlush(references);
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.WAREHOUSE,
        LogisticsDocumentEffectOperations.TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY,
        transferWarehouseDigest(
            LogisticsDocumentEffectOperations.TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY,
            document,
            line,
            destinationWarehouseId),
        now);
    attemptWriter.createLineAttempt(
        document,
        line,
        LogisticsTargetService.MEDIA,
        LogisticsDocumentEffectOperations.TRANSFER_MEDIA_VALIDATE,
        transferMediaDigest(document, line, references),
        now);
    eventStore.append(
        document,
        readProjection.linesRequired(documentId).size(),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_ARRIVAL_STARTED,
        null);
    idempotency.remember(subjectId, idempotencyKey, ARRIVE_TRANSFER_LINE, checksum, document);
    return result(document, false);
  }

  TransferArrivalPreflightView transferArrivalPreflight(
      UUID documentId, UUID lineId, long expectedDocumentVersion, long expectedLineVersion) {
    if (documentId == null
        || lineId == null
        || expectedDocumentVersion < 0
        || expectedLineVersion < 0) {
      throw new IllegalArgumentException(
          "Transfer preflight identifiers and versions are required");
    }
    LogisticsDocument document =
        readProjection.document(documentId, LogisticsDocumentType.TRANSFER);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Transfer document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.IN_TRANSIT
        && document.getState() != LogisticsDocumentState.ARRIVING) {
      throw new LogisticsConflictException(
          "Transfer arrival is not allowed in its current lifecycle state");
    }
    LogisticsDocumentLine line = readProjection.transferLine(document, lineId);
    requireLineVersion(line, expectedLineVersion, "Transfer line version changed concurrently");
    if (line.getState() != LogisticsLineState.DEPARTED) {
      throw new LogisticsConflictException("Transfer line is not awaiting arrival");
    }
    LogisticsDependencyGateway.TransferRepairArrivalPreflight preflight =
        requiredTransferArrivalPreflight(document, line);
    return new TransferArrivalPreflightView(
        document.getId(),
        line.getId(),
        preflight.activeRepairId(),
        preflight.priorityRequired(),
        preflight.missingQueueDefinitionIds());
  }

  LogisticsDocumentCommandResult cancelTransfer(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    requireTransferCancellationCommand(documentId, correlationId, expectedDocumentVersion);
    String checksum =
        LogisticsCommandChecksum.sha256(
            CANCEL_TRANSFER,
            List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    idempotency.acquireLock(subjectId, CANCEL_TRANSFER, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, CANCEL_TRANSFER, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document =
        readProjection.document(documentId, LogisticsDocumentType.TRANSFER);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Transfer document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.DRAFT) {
      throw new LogisticsConflictException("A transfer cannot be cancelled after departure begins");
    }
    List<LogisticsDocumentLine> lines = readProjection.lines(documentId);
    if (lines.stream().anyMatch(line -> line.getState() != LogisticsLineState.PENDING)) {
      throw new LogisticsConflictException(
          "A transfer can be cancelled only while every line is pending");
    }

    driverTaskPlanner.cancelBeforeStart(document, lines);

    cancelTransferEquipmentTask(subjectId, document);
    transferFurnitureTasks.cancelForTransfer(subjectId, document.getId());
    document.beginTransferCancellation();
    documentRepository.saveAndFlush(document);
    OffsetDateTime proofUpdatedAt = now();
    for (LogisticsDocumentLine line : lines) {
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          LogisticsDocumentEffectOperations.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE,
          attemptWriter.ownerProofDigest(
              LogisticsDocumentEffectOperations.TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE,
              document,
              line,
              transferDestination(document),
              1,
              1,
              false),
          proofUpdatedAt);
    }
    int eventLineCount =
        lines.isEmpty()
            ? transferPlanning.auditLineCount(transferPlanning.required(documentId))
            : lines.size();
    eventStore.append(
        document,
        eventLineCount,
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_CANCELLATION_STARTED,
        null);
    transferPlanWorkflow.requestCancellation(document);
    idempotency.remember(subjectId, idempotencyKey, CANCEL_TRANSFER, checksum, document);
    return result(document, false);
  }

  /**
   * Stable payload digest for maintenance transfer effects. The package-level facade wrapper is
   * retained because the untouched relay uses it as an existing recovery seam.
   */
  static String transferMaintenanceDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      Integer priority,
      Long rentalItemVersion) {
    if (LogisticsDocumentEffectOperations.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL.equals(operation)
        && (rentalItemVersion == null || rentalItemVersion < 0)) {
      throw new IllegalArgumentException(
          "Transfer maintenance completion requires a non-negative rental-item version");
    }
    if (rentalItemVersion != null && rentalItemVersion < 0) {
      throw new IllegalArgumentException("Transfer maintenance rental-item version is invalid");
    }
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(line.getAssetId().toString());
    if (rentalItemVersion != null) values.add(rentalItemVersion.toString());
    values.add(document.getWarehouseId().toString());
    values.add(transferDestination(document).toString());
    values.add(priority == null ? null : priority.toString());
    return LogisticsCommandChecksum.sha256(operation, values);
  }

  private LogisticsDependencyGateway.TransferRepairArrivalPreflight
      requiredTransferArrivalPreflight(LogisticsDocument document, LogisticsDocumentLine line) {
    if (line.getMaintenancePreparedAt() == null || line.getTransferAssetStatus() == null) {
      throw new LogisticsConflictException(
          "Transfer line has no durable maintenance departure truth");
    }
    LogisticsDependencyGateway.TransferRepairArrivalPreflight preflight =
        dependencies.preflightTransferArrival(
            document.getId(),
            line.getId(),
            line.getAssetId(),
            document.getWarehouseId(),
            transferDestination(document));
    if (preflight == null
        || preflight.missingQueueDefinitionIds() == null
        || preflight.missingQueueDefinitionIds().stream().anyMatch(java.util.Objects::isNull)
        || preflight.missingQueueDefinitionIds().size()
            != Set.copyOf(preflight.missingQueueDefinitionIds()).size()
        || !java.util.Objects.equals(line.getActiveRepairId(), preflight.activeRepairId())
        || (line.hasActiveRepair() && !preflight.priorityRequired())
        || (!line.hasActiveRepair()
            && (preflight.priorityRequired()
                || !preflight.missingQueueDefinitionIds().isEmpty()))) {
      throw new LogisticsConflictException(
          "Maintenance repair truth changed while the cabin was in transfer");
    }
    return preflight;
  }

  private static void validateTransferArrivalContinuation(
      ArriveTransferLineRequest request,
      LogisticsDependencyGateway.TransferRepairArrivalPreflight preflight) {
    if (!preflight.missingQueueDefinitionIds().isEmpty()) {
      throw new LogisticsConflictException(
          "Target warehouse is missing queues required by the active repair");
    }
    if (preflight.activeRepairId() == null) {
      if (request.priority() != null) {
        throw new LogisticsConflictException(
            "A transfer without an active repair has no repair continuation settings");
      }
      return;
    }
    if (request.priority() == null || request.priority() < 1 || request.priority() > 5) {
      throw new LogisticsConflictException("The accepting employee must select a repair priority");
    }
  }

  private LogisticsDocumentCommandResult result(LogisticsDocument document, boolean replayed) {
    return new LogisticsDocumentCommandResult(readProjection.view(document), replayed);
  }

  private static void requireTransferCommand(
      UUID documentId,
      UUID lineId,
      UUID correlationId,
      long expectedDocumentVersion,
      long expectedLineVersion) {
    if (documentId == null
        || lineId == null
        || correlationId == null
        || expectedDocumentVersion < 0
        || expectedLineVersion < 0) {
      throw new IllegalArgumentException("Transfer command identifiers and versions are required");
    }
  }

  private static void requireTransferCancellationCommand(
      UUID documentId, UUID correlationId, long expectedDocumentVersion) {
    if (documentId == null || correlationId == null || expectedDocumentVersion < 0) {
      throw new IllegalArgumentException(
          "Transfer cancellation identifiers and version are required");
    }
  }

  private static void requireExpectedVersion(
      LogisticsDocument document, long expectedVersion, String message) {
    if (document.getVersion() != expectedVersion) {
      throw new LogisticsConflictException(message);
    }
  }

  private static UUID transferDestination(LogisticsDocument document) {
    UUID destinationWarehouseId = document.getDestinationWarehouseId();
    if (destinationWarehouseId == null
        || destinationWarehouseId.equals(document.getWarehouseId())) {
      throw new IllegalStateException("Transfer destination warehouse is invalid");
    }
    return destinationWarehouseId;
  }

  private static void requireLineVersion(
      LogisticsDocumentLine line, long expectedVersion, String message) {
    if (line.getVersion() != expectedVersion) throw new LogisticsConflictException(message);
  }

  private static void validateTransferMediaReferences(ArriveTransferLineRequest request) {
    if (request.references() == null
        || request.references().isEmpty()
        || request.references().size() > 20) {
      throw new LogisticsConflictException("Transfer arrival media references are required");
    }
    HashSet<UUID> mediaIds = new HashSet<>();
    for (var reference : request.references()) {
      if (reference == null
          || reference.mediaId() == null
          || reference.generation() < 1
          || !mediaIds.add(reference.mediaId())) {
        throw new LogisticsConflictException("Transfer arrival media references are invalid");
      }
    }
  }

  private static String transferWarehouseDigest(
      String operation, LogisticsDocument document, LogisticsDocumentLine line, UUID warehouseId) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(document.getId().toString(), line.getId().toString(), warehouseId.toString()));
  }

  private static String transferMediaDigest(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsMediaReference> references) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(transferDestination(document).toString());
    references.stream()
        .sorted(Comparator.comparing(LogisticsMediaReference::getMediaId))
        .forEach(
            reference -> {
              values.add(reference.getMediaId().toString());
              values.add(Long.toString(reference.getGeneration()));
            });
    return LogisticsCommandChecksum.sha256(
        LogisticsDocumentEffectOperations.TRANSFER_MEDIA_VALIDATE, values);
  }

  private static List<String> transferArrivalFingerprintValues(
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      long expectedLineVersion,
      ArriveTransferLineRequest request) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(lineId.toString());
    values.add(Long.toString(expectedDocumentVersion));
    values.add(Long.toString(expectedLineVersion));
    request.references().stream()
        .sorted(Comparator.comparing(value -> value.mediaId().toString()))
        .forEach(
            reference -> {
              values.add(reference.mediaId().toString());
              values.add(Long.toString(reference.generation()));
            });
    values.add(request.priority() == null ? null : request.priority().toString());
    return values;
  }

  private static List<AdmissionRequirement> transferAdmission(CreateTransferRequest request) {
    return List.of(
        new AdmissionRequirement(request.warehouseId(), WarehouseOperationDirection.OUTGOING),
        new AdmissionRequirement(
            request.destinationWarehouseId(), WarehouseOperationDirection.INCOMING));
  }

  private static List<AdmissionRequirement> transferAdmission(LogisticsDocument document) {
    return List.of(
        new AdmissionRequirement(document.getWarehouseId(), WarehouseOperationDirection.OUTGOING),
        new AdmissionRequirement(
            transferDestination(document), WarehouseOperationDirection.INCOMING));
  }

  private static List<LogisticsDocumentLine> transferLines(
      LogisticsDocument document,
      List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest> inputs) {
    validateTransferLineInputs(inputs);
    List<LogisticsDocumentLine> lines = new ArrayList<>(inputs.size());
    for (int index = 0; index < inputs.size(); index++) {
      var input = inputs.get(index);
      lines.add(
          LogisticsDocumentLine.create(
              document, index + 1, input.assetId(), input.assetVersion(), null));
    }
    return lines;
  }

  private static List<LogisticsDocumentLine> plannedTransferLines(
      LogisticsDocument document, List<TransferPlanSnapshot.Allocation> allocations) {
    List<LogisticsDocumentLine> lines = new ArrayList<>(allocations.size());
    for (int index = 0; index < allocations.size(); index++) {
      TransferPlanSnapshot.Allocation allocation = allocations.get(index);
      lines.add(
          LogisticsDocumentLine.create(
              document,
              index + 1,
              allocation.assetId(),
              allocation.assetVersion(),
              null));
    }
    return lines;
  }

  private static void validateTransferLineInputs(
      List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest> inputs) {
    if (inputs == null || inputs.isEmpty() || inputs.size() > 100) {
      throw new IllegalArgumentException("Document must contain 1 to 100 lines");
    }
    HashSet<UUID> assetIds = new HashSet<>();
    for (var input : inputs) {
      UUID assetId = input.assetId();
      if (assetId == null || !assetIds.add(assetId)) {
        throw new IllegalArgumentException("Document line asset IDs must be distinct");
      }
    }
  }

  private static List<String> transferFingerprintValues(CreateTransferRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(request.destinationWarehouseId().toString());
    values.add(request.scheduledDate().toString());
    for (var line : request.lines()) {
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
    }
    request.furnitureReplacements().stream()
        .sorted(Comparator.comparing(value -> value.assetId().toString()))
        .forEach(
            replacement -> {
              values.add(replacement.assetId().toString());
              replacement.contents().stream()
                  .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
                  .forEach(
                      furniture -> {
                        values.add(furniture.equipmentId().toString());
                        values.add(Long.toString(furniture.quantity()));
                      });
            });
    // A concrete-line request must retain its pre-V67 digest so an idempotent retry that crosses
    // the deployment boundary still replays the existing durable command receipt.
    if (request.plan() != null) {
      values.add("TRANSFER_PLAN_V1");
      appendPlanFingerprint(values, request.plan());
    }
    return values;
  }

  private static void validateTransferSchedule(CreateTransferRequest request, LocalDate today) {
    validateTransferSchedule(request.scheduledDate(), today);
  }

  private static void validateTransferSchedule(LocalDate scheduledDate, LocalDate today) {
    if (scheduledDate == null || today == null || scheduledDate.isBefore(today)) {
      throw new IllegalArgumentException("Transfer task date cannot be in the past");
    }
  }

  private static void validateTransferCreateShape(CreateTransferRequest request) {
    if (request.plan() == null) {
      validateTransferFurnitureReplacements(request);
      return;
    }
    if (!request.lines().isEmpty() || !request.furnitureReplacements().isEmpty()) {
      throw new IllegalArgumentException(
          "A planned transfer cannot mix legacy concrete lines with planning detail");
    }
  }

  private static void validateTransferFurnitureReplacements(CreateTransferRequest request) {
    if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 100) {
      throw new IllegalArgumentException("Transfer cabin lines are invalid");
    }
    Set<UUID> transferCabins = new HashSet<>();
    for (var line : request.lines()) {
      if (line == null || line.assetId() == null || !transferCabins.add(line.assetId())) {
        throw new IllegalArgumentException("Transfer cabin line is invalid");
      }
    }
    if (request.furnitureReplacements() == null || request.furnitureReplacements().size() > 100) {
      throw new IllegalArgumentException("Transfer furniture replacements are invalid");
    }
    Set<UUID> replacementCabins = new HashSet<>();
    for (var replacement : request.furnitureReplacements()) {
      if (replacement == null
          || replacement.assetId() == null
          || !transferCabins.contains(replacement.assetId())
          || !replacementCabins.add(replacement.assetId())
          || replacement.contents() == null
          || replacement.contents().size() > 100) {
        throw new IllegalArgumentException("Transfer furniture replacement is invalid");
      }
      Set<UUID> equipmentIds = new HashSet<>();
      for (var furniture : replacement.contents()) {
        if (furniture == null
            || furniture.equipmentId() == null
            || furniture.quantity() == null
            || furniture.quantity() < 1
            || !equipmentIds.add(furniture.equipmentId())) {
          throw new IllegalArgumentException("Transfer furniture requirement is invalid");
        }
      }
    }
  }

  private void createMediaOwnerProofAttempts(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {
    OffsetDateTime proofCreatedAt = now();
    for (LogisticsDocumentLine line : lines) {
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          LogisticsDocumentEffectOperations.TRANSFER_MEDIA_OWNER_PROOF_REGISTER,
          attemptWriter.ownerProofDigest(
              LogisticsDocumentEffectOperations.TRANSFER_MEDIA_OWNER_PROOF_REGISTER,
              document,
              line,
              transferDestination(document),
              0,
              0,
              true),
          proofCreatedAt);
    }
  }

  private LogisticsDocument requiredTransferForUpdate(UUID documentId) {
    LogisticsDocument document =
        documentRepository.findForUpdate(documentId).orElseThrow(LogisticsNotFoundException::new);
    if (document.getDocumentType() != LogisticsDocumentType.TRANSFER) {
      throw new LogisticsNotFoundException();
    }
    return document;
  }

  private static void requirePlanCommand(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      UpdateTransferPlanRequest request,
      LocalDate warehouseToday) {
    if (subjectId == null
        || idempotencyKey == null
        || correlationId == null
        || documentId == null
        || expectedDocumentVersion < 0
        || request == null
        || request.plan() == null
        || request.scheduledDate() == null
        || warehouseToday == null) {
      throw new IllegalArgumentException("Transfer plan command identity and version are required");
    }
  }

  private static List<String> transferPlanFingerprintValues(
      UUID documentId,
      long expectedDocumentVersion,
      LocalDate scheduledDate,
      TransferPlanRequest plan) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(Long.toString(expectedDocumentVersion));
    values.add(scheduledDate.toString());
    appendPlanFingerprint(values, plan);
    return values;
  }

  private static void appendPlanFingerprint(List<String> values, TransferPlanRequest plan) {
    values.add(nullable(plan.plannedDepartureAt()));
    values.add(nullable(plan.plannedArrivalAt()));
    values.add(normalized(plan.logisticsComment()));
    values.add(nullable(plan.tripDriverId()));
    values.add(nullable(plan.tripVehicleId()));
    appendResourceFingerprint(values, plan.driverReposition());
    appendResourceFingerprint(values, plan.vehicleReposition());
    for (var group : plan.cabinGroups()) {
      values.add(group.rentalTypeId().toString());
      values.add(nullable(group.dimensionId()));
      values.add(nullable(group.finishingId()));
      values.add(nullable(group.linoleum()));
      values.add(Integer.toString(group.quantity()));
      group.characteristicIds().stream()
          .sorted(Comparator.comparing(UUID::toString))
          .forEach(characteristicId -> values.add(characteristicId.toString()));
      values.add("FURNITURE");
      for (var furniture : group.furniturePerCabin()) {
        values.add(furniture.furnitureCatalogItemId().toString());
        values.add(Long.toString(furniture.quantityPerCabin()));
      }
      values.add("ALLOCATIONS");
      for (var allocation : group.allocatedCabins()) {
        values.add(allocation.assetId().toString());
        values.add(Long.toString(allocation.assetVersion()));
      }
      values.add("GROUP_END");
    }
    values.add("LOOSE_FURNITURE");
    for (var furniture : plan.looseFurniture()) {
      values.add(furniture.furnitureCatalogItemId().toString());
      values.add(Long.toString(furniture.quantity()));
    }
  }

  private static void appendResourceFingerprint(
      List<String> values,
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferResourceRepositionRequest
          resource) {
    if (resource == null) {
      values.add("NONE");
      values.add(null);
      values.add(null);
      return;
    }
    values.add(resource.mode() == null ? null : resource.mode().name());
    values.add(nullable(resource.resourceId()));
    values.add(nullable(resource.until()));
  }

  private static String normalized(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  private static String nullable(Object value) {
    return value == null ? null : value.toString();
  }

  private static UUID transferEquipmentCancellationIdempotencyKey(UUID documentId) {
    return UUID.nameUUIDFromBytes(
        ("rwms:transfer-equipment-cancel:" + documentId).getBytes(StandardCharsets.UTF_8));
  }

  private void cancelTransferEquipmentTask(UUID subjectId, LogisticsDocument document) {
    if (document.getEquipmentMovementTaskId() == null) return;
    var task = equipmentMovementTasks.required(document.getEquipmentMovementTaskId());
    if (task.getState() == EquipmentMovementTaskState.CANCELLING
        || task.getState() == EquipmentMovementTaskState.CANCELLED) {
      return;
    }
    if (task.getState() != EquipmentMovementTaskState.RESERVING
        && task.getState() != EquipmentMovementTaskState.REGISTERING_TASK
        && task.getState() != EquipmentMovementTaskState.AWAITING_WORKER) {
      throw new LogisticsConflictException("Transfer furniture task can no longer be cancelled");
    }
    equipmentMovementTasks.cancel(
        subjectId,
        task.getId(),
        transferEquipmentCancellationIdempotencyKey(document.getId()),
        new CancelEquipmentMovementTaskRequest(task.getVersion()));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static void requireRequest(Object request) {
    if (request == null) throw new IllegalArgumentException("Request is required");
  }
}
