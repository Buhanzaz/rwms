package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.AcceptReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentSummary;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.RequestReturnEstimateRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReconcileRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentPlanRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsEquipmentHoldReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsEquipmentHoldState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsIdempotencyRecord;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReturnShortageSnapshot;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsDocumentResponseMapper;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsEquipmentHoldReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsIdempotencyRecordRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReturnShortageSnapshotRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReconciliationRequestRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTaskReferenceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LogisticsDocumentService {
  private static final String CREATE_RETURN = "CREATE_RETURN";
  private static final String CREATE_SHIPMENT = "CREATE_SHIPMENT";
  private static final String CREATE_TRANSFER = "CREATE_TRANSFER";
  private static final String REGISTER_RETURN = "REGISTER_RETURN";
  private static final String ACCEPT_RETURN = "ACCEPT_RETURN";
  private static final String REQUEST_RETURN_ESTIMATE = "REQUEST_RETURN_ESTIMATE";
  private static final String PLAN_SHIPMENT = "PLAN_SHIPMENT";
  private static final String CONFIRM_SHIPMENT = "CONFIRM_SHIPMENT";
  private static final String CANCEL_SHIPMENT = "CANCEL_SHIPMENT";
  private static final String DEPART_TRANSFER_LINE = "DEPART_TRANSFER_LINE";
  private static final String ARRIVE_TRANSFER_LINE = "ARRIVE_TRANSFER_LINE";
  private static final String CANCEL_TRANSFER = "CANCEL_TRANSFER";
  private static final String RECONCILE_DOCUMENT = "RECONCILE_DOCUMENT";
  static final String RETURN_WAREHOUSE_IDENTITY = "RETURN_WAREHOUSE_IDENTITY";
  static final String RETURN_MEDIA_VALIDATE = "RETURN_MEDIA_VALIDATE";
  static final String RETURN_ASSET_SETTLE_FREE = "RETURN_ASSET_SETTLE_FREE";
  static final String RETURN_ASSET_SETTLE_SHORTAGE = "RETURN_ASSET_SETTLE_SHORTAGE";
  static final String RETURN_MAINTENANCE_SHORTAGE_UPSERT = "RETURN_MAINTENANCE_SHORTAGE_UPSERT";
  static final String RETURN_ASSET_LEASE_RELEASE = "RETURN_ASSET_LEASE_RELEASE";
  static final String SHIPMENT_ASSET_SNAPSHOT = "SHIPMENT_ASSET_SNAPSHOT";
  static final String SHIPMENT_ASSET_LEASE_ACQUIRE = "SHIPMENT_ASSET_LEASE_ACQUIRE";
  static final String SHIPMENT_TASK_REGISTER = "SHIPMENT_TASK_REGISTER";
  static final String SHIPMENT_TASK_STATUS = "SHIPMENT_TASK_STATUS";
  static final String SHIPMENT_ASSET_CONFIRM = "SHIPMENT_ASSET_CONFIRM";
  static final String SHIPMENT_ASSET_LEASE_RELEASE = "SHIPMENT_ASSET_LEASE_RELEASE";
  static final String SHIPMENT_TASK_CANCEL = "SHIPMENT_TASK_CANCEL";
  static final String SHIPMENT_HOLD_ACQUIRE_PREFIX = "SHIPMENT_HOLD_ACQUIRE:";
  static final String SHIPMENT_HOLD_COMMIT_PREFIX = "SHIPMENT_HOLD_COMMIT:";
  static final String SHIPMENT_HOLD_RELEASE_PREFIX = "SHIPMENT_HOLD_RELEASE:";
  static final String TRANSFER_ORIGIN_WAREHOUSE_IDENTITY = "TRANSFER_ORIGIN_WAREHOUSE_IDENTITY";
  static final String TRANSFER_DESTINATION_WAREHOUSE_IDENTITY =
      "TRANSFER_DESTINATION_WAREHOUSE_IDENTITY";
  static final String TRANSFER_ASSET_SNAPSHOT = "TRANSFER_ASSET_SNAPSHOT";
  static final String TRANSFER_ASSET_LEASE_ACQUIRE = "TRANSFER_ASSET_LEASE_ACQUIRE";
  static final String TRANSFER_ASSET_DEPART = "TRANSFER_ASSET_DEPART";
  static final String TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY =
      "TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY";
  static final String TRANSFER_MEDIA_VALIDATE = "TRANSFER_MEDIA_VALIDATE";
  static final String TRANSFER_ASSET_ARRIVAL_SNAPSHOT = "TRANSFER_ASSET_ARRIVAL_SNAPSHOT";
  static final String TRANSFER_ASSET_ARRIVE = "TRANSFER_ASSET_ARRIVE";
  static final String TRANSFER_ASSET_LEASE_RELEASE = "TRANSFER_ASSET_LEASE_RELEASE";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsExternalAttemptRepository externalAttemptRepository;
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsEquipmentHoldReferenceRepository holdReferenceRepository;
  private final LogisticsIdempotencyRecordRepository idempotencyRepository;
  private final LogisticsMediaReferenceRepository mediaReferenceRepository;
  private final LogisticsReturnShortageSnapshotRepository shortageSnapshotRepository;
  private final LogisticsTaskReferenceRepository taskReferenceRepository;
  private final LogisticsReconciliationRequestRepository reconciliationRequestRepository;
  private final LogisticsDocumentResponseMapper responseMapper;
  private final LogisticsIdempotencyProperties idempotencyProperties;
  private final LogisticsEventStore eventStore;
  private final JdbcTemplate jdbc;

  @Transactional
  public CreateResult createReturn(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateReturnRequest request) {
    requireRequest(request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            CREATE_RETURN,
            returnFingerprintValues(request));
    acquireIdempotencyLock(subjectId, CREATE_RETURN, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, CREATE_RETURN, checksum);
    if (replay != null) return replay;

    LogisticsDocument document =
        documentRepository.saveAndFlush(
            LogisticsDocument.createReturn(request.warehouseId(), subjectId, correlationId));
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(returnLines(document, request.lines()));
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    remember(subjectId, idempotencyKey, CREATE_RETURN, checksum, document);
    return new CreateResult(toView(document), false);
  }

  @Transactional
  public CreateResult createShipment(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateShipmentRequest request) {
    requireRequest(request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            CREATE_SHIPMENT,
            shipmentFingerprintValues(request));
    acquireIdempotencyLock(subjectId, CREATE_SHIPMENT, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, CREATE_SHIPMENT, checksum);
    if (replay != null) return replay;

    LogisticsDocument document =
        documentRepository.saveAndFlush(
            LogisticsDocument.createShipment(
                request.warehouseId(),
                request.partySnapshot(),
                request.driverSnapshot(),
                subjectId,
                correlationId));
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(shipmentLines(document, request.lines()));
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    remember(subjectId, idempotencyKey, CREATE_SHIPMENT, checksum, document);
    return new CreateResult(toView(document), false);
  }

  @Transactional
  public CreateResult createTransfer(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateTransferRequest request) {
    requireRequest(request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            CREATE_TRANSFER,
            transferFingerprintValues(request));
    acquireIdempotencyLock(subjectId, CREATE_TRANSFER, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, CREATE_TRANSFER, checksum);
    if (replay != null) return replay;

    LogisticsDocument document =
        documentRepository.saveAndFlush(
            LogisticsDocument.createTransfer(
                request.warehouseId(), request.destinationWarehouseId(), subjectId, correlationId));
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(transferLines(document, request.lines()));
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    remember(subjectId, idempotencyKey, CREATE_TRANSFER, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /**
   * Commits the first durable return-registration attempt before any private
   * service call. The relay can therefore replay an uncertain outcome through
   * the same stable operation identifier instead of guessing whether asset
   * state changed.
   */
  @Transactional
  public CreateResult registerReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    if (documentId == null || correlationId == null || expectedDocumentVersion < 0) {
      throw new IllegalArgumentException("Return registration identifiers and version are required");
    }
    String checksum =
        LogisticsCommandChecksum.sha256(
            REGISTER_RETURN, List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    acquireIdempotencyLock(subjectId, REGISTER_RETURN, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, REGISTER_RETURN, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.RETURN);
    if (document.getVersion() != expectedDocumentVersion) {
      throw new LogisticsConflictException("Return document version changed concurrently");
    }
    List<LogisticsDocumentLine> lines =
        lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
    if (lines.isEmpty()) throw new IllegalStateException("Return document has no lines");

    document.beginReturnRegistration();
    documentRepository.saveAndFlush(document);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    externalAttemptRepository.save(
        LogisticsExternalAttempt.create(
            document,
            null,
            LogisticsTargetService.WAREHOUSE,
            RETURN_WAREHOUSE_IDENTITY,
            LogisticsCommandChecksum.sha256(
                RETURN_WAREHOUSE_IDENTITY, List.of(document.getWarehouseId().toString())),
            correlationId,
            null,
            now));
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.RETURN_REGISTRATION_STARTED,
        null);
    remember(subjectId, idempotencyKey, REGISTER_RETURN, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /**
   * Starts the undamaged-return completion saga after freezing the exact
   * per-line media generation references. The private media receiver is the
   * source of truth for ownership and readiness; this service persists only
   * opaque references and its own durable attempt ledger.
   */
  @Transactional
  public CreateResult acceptUndamagedReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      AcceptReturnRequest request) {
    requireReturnCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            ACCEPT_RETURN,
            acceptanceFingerprintValues(documentId, expectedDocumentVersion, request));
    acquireIdempotencyLock(subjectId, ACCEPT_RETURN, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, ACCEPT_RETURN, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.RETURN);
    requireExpectedVersion(document, expectedDocumentVersion, "Return document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.INSPECTION_REQUIRED) {
      throw new LogisticsConflictException("Return cannot be accepted in its current lifecycle state");
    }
    List<LogisticsDocumentLine> lines = linesRequired(documentId);
    Map<UUID, LogisticsDocumentLine> byId = linesById(lines);
    validateAcceptanceLines(request, byId.keySet());

    OffsetDateTime now = now();
    List<LogisticsMediaReference> references = new ArrayList<>();
    for (var input : request.lines()) {
      LogisticsDocumentLine line = byId.get(input.lineId());
      for (var reference : input.references()) {
        references.add(
            LogisticsMediaReference.pending(
                document,
                line,
                reference.mediaId(),
                reference.generation(),
                LogisticsMediaPurpose.RETURN_INSPECTION,
                now));
      }
    }
    document.beginReturnAcceptance();
    documentRepository.saveAndFlush(document);
    mediaReferenceRepository.saveAllAndFlush(references);
    for (LogisticsDocumentLine line : lines) {
      List<LogisticsMediaReference> lineReferences =
          references.stream().filter(reference -> line.equals(reference.getLine())).toList();
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          RETURN_MEDIA_VALIDATE,
          mediaAttemptDigest(document, line, lineReferences),
          now);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.RETURN_ACCEPTANCE_STARTED,
        null);
    remember(subjectId, idempotencyKey, ACCEPT_RETURN, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /**
   * Starts a shortage settlement saga. The shortage vectors are immutable
   * logistics evidence; maintenance remains the owner of every estimate and
   * repair decision.
   */
  @Transactional
  public CreateResult requestReturnEstimate(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      RequestReturnEstimateRequest request) {
    requireReturnCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            REQUEST_RETURN_ESTIMATE,
            estimateFingerprintValues(documentId, expectedDocumentVersion, request));
    acquireIdempotencyLock(subjectId, REQUEST_RETURN_ESTIMATE, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, REQUEST_RETURN_ESTIMATE, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.RETURN);
    requireExpectedVersion(document, expectedDocumentVersion, "Return document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.INSPECTION_REQUIRED) {
      throw new LogisticsConflictException("Return estimate cannot be requested in its current lifecycle state");
    }
    List<LogisticsDocumentLine> lines = linesRequired(documentId);
    Map<UUID, LogisticsDocumentLine> byId = linesById(lines);
    validateEstimateLines(request, byId.keySet());

    OffsetDateTime now = now();
    List<LogisticsReturnShortageSnapshot> snapshots = new ArrayList<>();
    for (var input : request.lines()) {
      LogisticsDocumentLine line = byId.get(input.lineId());
      LogisticsGuard guard = activeGuard(line);
      ObjectNode shortages = shortagesSnapshot(input);
      snapshots.add(
          LogisticsReturnShortageSnapshot.create(
              document,
              line,
              document.getWarehouseId(),
              line.getAssetId(),
              guard.getObservedAssetVersion(),
              shortages,
              shortageSnapshotDigest(document, line, guard, input),
              now));
    }
    document.beginReturnEstimate();
    documentRepository.saveAndFlush(document);
    shortageSnapshotRepository.saveAllAndFlush(snapshots);
    for (LogisticsReturnShortageSnapshot snapshot : snapshots) {
      LogisticsDocumentLine line = snapshot.getLine();
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.ASSET,
          RETURN_ASSET_SETTLE_SHORTAGE,
          LogisticsCommandChecksum.sha256(
              RETURN_ASSET_SETTLE_SHORTAGE,
              List.of(
                  line.getAssetId().toString(),
                  Long.toString(activeGuard(line).getObservedAssetVersion()),
                  activeGuard(line).getLeaseId().toString(),
                  Long.toString(activeGuard(line).getFenceToken()),
                  document.getId().toString(),
                  line.getId().toString())),
          now);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.RETURN_ESTIMATE_STARTED,
        null);
    remember(subjectId, idempotencyKey, REQUEST_RETURN_ESTIMATE, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /**
   * The creation payload is the immutable shipment plan. Planning starts its
   * durable dependency saga only when the operator repeats that exact plan at
   * the current aggregate version; a late plan replacement is deliberately
   * rejected rather than mutating a task/hold already in flight.
   */
  @Transactional
  public CreateResult planShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      ShipmentPlanRequest request) {
    requireShipmentCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            PLAN_SHIPMENT,
            shipmentPlanFingerprintValues(documentId, expectedDocumentVersion, request));
    acquireIdempotencyLock(subjectId, PLAN_SHIPMENT, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, PLAN_SHIPMENT, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.SHIPMENT);
    requireExpectedVersion(document, expectedDocumentVersion, "Shipment document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.DRAFT) {
      throw new LogisticsConflictException("Shipment plan cannot be started in its current lifecycle state");
    }
    List<LogisticsDocumentLine> lines = linesRequired(documentId);
    requireImmutableShipmentPlan(document, lines, request);

    OffsetDateTime now = now();
    document.beginShipmentPreparation();
    documentRepository.saveAndFlush(document);
    for (LogisticsDocumentLine line : lines) {
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.ASSET,
          SHIPMENT_ASSET_SNAPSHOT,
          LogisticsCommandChecksum.sha256(
              SHIPMENT_ASSET_SNAPSHOT,
              List.of(line.getAssetId().toString(), Long.toString(line.getAssetVersion()))),
          now);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.SHIPMENT_PREPARATION_STARTED,
        null);
    remember(subjectId, idempotencyKey, PLAN_SHIPMENT, checksum, document);
    return new CreateResult(toView(document), false);
  }

  @Transactional
  public CreateResult confirmShipmentPreparation(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    requireShipmentCommand(documentId, correlationId, expectedDocumentVersion, new Object());
    String checksum =
        LogisticsCommandChecksum.sha256(
            CONFIRM_SHIPMENT, List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    acquireIdempotencyLock(subjectId, CONFIRM_SHIPMENT, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, CONFIRM_SHIPMENT, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.SHIPMENT);
    requireExpectedVersion(document, expectedDocumentVersion, "Shipment document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.AWAITING_CONFIRMATION) {
      throw new LogisticsConflictException("Shipment preparation is not awaiting confirmation");
    }
    List<LogisticsDocumentLine> lines = linesRequired(documentId);
    OffsetDateTime now = now();
    document.beginShipmentConfirmation();
    documentRepository.saveAndFlush(document);
    for (LogisticsDocumentLine line : lines) {
      LogisticsTaskReference task =
          taskReferenceRepository
              .findByLine_Id(line.getId())
              .orElseThrow(() -> new LogisticsConflictException("Shipment line has no preparation task"));
      if (task.getTaskState() != LogisticsTaskReferenceState.REGISTERED) {
        throw new LogisticsConflictException("Shipment preparation task is not registered");
      }
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.TASK_BOARD,
          SHIPMENT_TASK_STATUS,
          LogisticsCommandChecksum.sha256(
              SHIPMENT_TASK_STATUS, List.of(task.getExternalTaskId().toString())),
          now);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.SHIPMENT_CONFIRMATION_STARTED,
        null);
    remember(subjectId, idempotencyKey, CONFIRM_SHIPMENT, checksum, document);
    return new CreateResult(toView(document), false);
  }

  @Transactional
  public CreateResult cancelShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    requireShipmentCommand(documentId, correlationId, expectedDocumentVersion, new Object());
    String checksum =
        LogisticsCommandChecksum.sha256(
            CANCEL_SHIPMENT, List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    acquireIdempotencyLock(subjectId, CANCEL_SHIPMENT, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, CANCEL_SHIPMENT, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.SHIPMENT);
    requireExpectedVersion(document, expectedDocumentVersion, "Shipment document version changed concurrently");
    boolean cancellingDraft = document.getState() == LogisticsDocumentState.DRAFT;
    List<LogisticsDocumentLine> lines = linesRequired(documentId);
    for (LogisticsTaskReference task : taskReferenceRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      if (task.getTaskState() == LogisticsTaskReferenceState.PENDING) {
        throw new LogisticsConflictException("A shipment preparation-task outcome is still unknown");
      }
    }
    for (LogisticsExternalAttempt attempt :
        externalAttemptRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      String operation = attempt.getOperationType();
      boolean mutatingPreparation =
          SHIPMENT_ASSET_LEASE_ACQUIRE.equals(operation)
              || operation.startsWith(SHIPMENT_HOLD_ACQUIRE_PREFIX)
              || SHIPMENT_TASK_REGISTER.equals(operation);
      if (mutatingPreparation
          && attempt.getResult() != dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult.CONFIRMED) {
        throw new LogisticsConflictException("A shipment preparation effect has an unknown outcome");
      }
    }

    OffsetDateTime now = now();
    document.beginShipmentCancellation();
    documentRepository.saveAndFlush(document);
    for (LogisticsTaskReference task : taskReferenceRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      if (task.getTaskState() == LogisticsTaskReferenceState.REGISTERED) {
        createLineAttempt(
            document,
            task.getLine(),
            LogisticsTargetService.TASK_BOARD,
            SHIPMENT_TASK_CANCEL,
            LogisticsCommandChecksum.sha256(
                SHIPMENT_TASK_CANCEL,
                List.of(task.getExternalTaskId().toString(), Long.toString(task.getTaskVersion()))),
            now);
      }
    }
    for (LogisticsEquipmentHoldReference hold :
        holdReferenceRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      if (hold.getHoldState() == LogisticsEquipmentHoldState.ACTIVE
          || hold.getHoldState() == LogisticsEquipmentHoldState.COMMITTED) {
        createLineAttempt(
            document,
            hold.getLine(),
            LogisticsTargetService.ASSET,
            holdReleaseOperation(hold.getHoldId()),
            LogisticsCommandChecksum.sha256(
                holdReleaseOperation(hold.getHoldId()),
                List.of(
                    hold.getHoldId().toString(),
                    Long.toString(hold.getHoldVersion()),
                    documentId.toString(),
                    hold.getLine().getId().toString())),
            now);
      }
    }
    for (LogisticsDocumentLine line : lines) {
      guardRepository
          .findByLine_Id(line.getId())
          .filter(guard -> guard.getGuardState() == LogisticsGuardState.ACTIVE)
          .ifPresent(
              guard ->
                  createLineAttempt(
                      document,
                      line,
                      LogisticsTargetService.ASSET,
                      SHIPMENT_ASSET_LEASE_RELEASE,
                      LogisticsCommandChecksum.sha256(
                          SHIPMENT_ASSET_LEASE_RELEASE,
                          List.of(
                              guard.getLeaseId().toString(),
                              Long.toString(guard.getLeaseVersion()),
                              Long.toString(guard.getFenceToken()),
                              documentId.toString(),
                              line.getId().toString())),
                      now));
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.SHIPMENT_CANCELLATION_STARTED,
        null);
    if (cancellingDraft) {
      document.cancelShipment();
      documentRepository.saveAndFlush(document);
      eventStore.append(
          document,
          lines.size(),
          correlationId,
          subjectId,
          LogisticsEventType.SHIPMENT_CANCELLED,
          null);
    }
    remember(subjectId, idempotencyKey, CANCEL_SHIPMENT, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /**
   * Begins one independently versioned transfer departure. The asset lease and
   * canonical effect remain asynchronous, but every required dependency
   * attempt is committed before the relay is allowed to make a network call.
   */
  @Transactional
  public CreateResult departTransferLine(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      long expectedLineVersion) {
    requireTransferCommand(documentId, lineId, correlationId, expectedDocumentVersion, expectedLineVersion);
    String checksum =
        LogisticsCommandChecksum.sha256(
            DEPART_TRANSFER_LINE,
            List.of(
                documentId.toString(),
                lineId.toString(),
                Long.toString(expectedDocumentVersion),
                Long.toString(expectedLineVersion)));
    acquireIdempotencyLock(subjectId, DEPART_TRANSFER_LINE, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, DEPART_TRANSFER_LINE, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.TRANSFER);
    requireExpectedVersion(document, expectedDocumentVersion, "Transfer document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.DRAFT
        && document.getState() != LogisticsDocumentState.DEPARTING) {
      throw new LogisticsConflictException("Transfer departure is not allowed in its current lifecycle state");
    }
    LogisticsDocumentLine line = transferLine(document, lineId);
    requireLineVersion(line, expectedLineVersion, "Transfer line version changed concurrently");
    if (line.getState() != LogisticsLineState.PENDING) {
      throw new LogisticsConflictException("Transfer line is not pending departure");
    }

    UUID destinationWarehouseId = transferDestination(document);
    OffsetDateTime now = now();
    document.beginTransferDeparture();
    documentRepository.saveAndFlush(document);
    line.beginDeparture();
    lineRepository.saveAndFlush(line);
    createLineAttempt(
        document,
        line,
        LogisticsTargetService.WAREHOUSE,
        TRANSFER_ORIGIN_WAREHOUSE_IDENTITY,
        transferWarehouseDigest(TRANSFER_ORIGIN_WAREHOUSE_IDENTITY, document, line, document.getWarehouseId()),
        now);
    createLineAttempt(
        document,
        line,
        LogisticsTargetService.WAREHOUSE,
        TRANSFER_DESTINATION_WAREHOUSE_IDENTITY,
        transferWarehouseDigest(
            TRANSFER_DESTINATION_WAREHOUSE_IDENTITY, document, line, destinationWarehouseId),
        now);
    createLineAttempt(
        document,
        line,
        LogisticsTargetService.ASSET,
        TRANSFER_ASSET_SNAPSHOT,
        LogisticsCommandChecksum.sha256(
            TRANSFER_ASSET_SNAPSHOT,
            List.of(
                line.getAssetId().toString(),
                Long.toString(line.getAssetVersion()),
                document.getWarehouseId().toString(),
                document.getId().toString(),
                line.getId().toString())),
        now);
    eventStore.append(
        document,
        linesRequired(documentId).size(),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_DEPARTURE_STARTED,
        null);
    remember(subjectId, idempotencyKey, DEPART_TRANSFER_LINE, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /**
   * Freezes exact media generations before asking media-service for ownership
   * truth, then lets the durable relay verify the in-transit snapshot and
   * invoke the one permitted destination assignment effect.
   */
  @Transactional
  public CreateResult arriveTransferLine(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      long expectedLineVersion,
      ArriveTransferLineRequest request) {
    requireTransferCommand(documentId, lineId, correlationId, expectedDocumentVersion, expectedLineVersion);
    requireRequest(request);
    validateTransferMediaReferences(request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            ARRIVE_TRANSFER_LINE,
            transferArrivalFingerprintValues(
                documentId, lineId, expectedDocumentVersion, expectedLineVersion, request));
    acquireIdempotencyLock(subjectId, ARRIVE_TRANSFER_LINE, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, ARRIVE_TRANSFER_LINE, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.TRANSFER);
    requireExpectedVersion(document, expectedDocumentVersion, "Transfer document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.IN_TRANSIT
        && document.getState() != LogisticsDocumentState.ARRIVING) {
      throw new LogisticsConflictException("Transfer arrival is not allowed in its current lifecycle state");
    }
    LogisticsDocumentLine line = transferLine(document, lineId);
    requireLineVersion(line, expectedLineVersion, "Transfer line version changed concurrently");
    if (line.getState() != LogisticsLineState.DEPARTED) {
      throw new LogisticsConflictException("Transfer line is not awaiting arrival");
    }

    UUID destinationWarehouseId = transferDestination(document);
    OffsetDateTime now = now();
    document.beginTransferArrival();
    documentRepository.saveAndFlush(document);
    line.beginArrival();
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
    createLineAttempt(
        document,
        line,
        LogisticsTargetService.WAREHOUSE,
        TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY,
        transferWarehouseDigest(
            TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY, document, line, destinationWarehouseId),
        now);
    createLineAttempt(
        document,
        line,
        LogisticsTargetService.MEDIA,
        TRANSFER_MEDIA_VALIDATE,
        transferMediaDigest(document, line, references),
        now);
    eventStore.append(
        document,
        linesRequired(documentId).size(),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_ARRIVAL_STARTED,
        null);
    remember(subjectId, idempotencyKey, ARRIVE_TRANSFER_LINE, checksum, document);
    return new CreateResult(toView(document), false);
  }

  @Transactional
  public CreateResult cancelTransfer(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    requireTransferCancellationCommand(documentId, correlationId, expectedDocumentVersion);
    String checksum =
        LogisticsCommandChecksum.sha256(
            CANCEL_TRANSFER, List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    acquireIdempotencyLock(subjectId, CANCEL_TRANSFER, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, CANCEL_TRANSFER, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, LogisticsDocumentType.TRANSFER);
    requireExpectedVersion(document, expectedDocumentVersion, "Transfer document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.DRAFT) {
      throw new LogisticsConflictException("A transfer cannot be cancelled after departure begins");
    }
    List<LogisticsDocumentLine> lines = linesRequired(documentId);
    if (lines.stream().anyMatch(line -> line.getState() != LogisticsLineState.PENDING)) {
      throw new LogisticsConflictException("A transfer can be cancelled only while every line is pending");
    }

    document.cancel();
    documentRepository.saveAndFlush(document);
    for (LogisticsDocumentLine line : lines) line.cancel();
    lineRepository.saveAllAndFlush(lines);
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_CANCELLED,
        null);
    remember(subjectId, idempotencyKey, CANCEL_TRANSFER, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /**
   * Records an operator-reviewed reconciliation request without inventing a
   * reverse physical movement or silently changing source-owned state.
   */
  @Transactional
  public CreateResult reconcile(
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
    acquireIdempotencyLock(subjectId, RECONCILE_DOCUMENT, idempotencyKey);
    CreateResult replay = replay(subjectId, idempotencyKey, RECONCILE_DOCUMENT, checksum);
    if (replay != null) return replay;

    LogisticsDocument document = document(documentId, documentType);
    requireExpectedVersion(document, expectedDocumentVersion, "Logistics document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.CONFLICT
        && document.getState() != LogisticsDocumentState.RECONCILIATION_REQUIRED) {
      throw new LogisticsConflictException("Only a visible conflict can be reconciled");
    }
    reconciliationRequestRepository.save(
        LogisticsReconciliationRequest.create(document, reason, subjectId, correlationId, now()));
    remember(subjectId, idempotencyKey, RECONCILE_DOCUMENT, checksum, document);
    return new CreateResult(toView(document), false);
  }

  public LogisticsDocumentView get(UUID id, LogisticsDocumentType type) {
    return toView(document(id, type));
  }

  public List<LogisticsDocumentView> list(LogisticsDocumentType type, UUID warehouseId) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    return documentRepository
        .findAllByDocumentTypeAndWarehouseIdOrderByCreatedAtDescIdDesc(type, warehouseId)
        .stream()
        .map(this::toView)
        .toList();
  }

  public LogisticsDocument document(UUID id, LogisticsDocumentType type) {
    return documentRepository.findByIdAndDocumentType(id, type).orElseThrow(LogisticsNotFoundException::new);
  }

  private CreateResult replay(
      UUID subjectId, UUID idempotencyKey, String operation, String checksum) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("subjectId and Idempotency-Key are required");
    }
    return idempotencyRepository
        .findBySubjectIdAndOperationNameAndIdempotencyKey(subjectId, operation, idempotencyKey)
        .map(
            record -> {
              if (!record.matches(checksum)) {
                throw new LogisticsConflictException("Idempotency key was reused with a different command");
              }
              return new CreateResult(toView(record.getDocument()), true);
            })
        .orElse(null);
  }

  /**
   * Serializes only one subject/operation/key tuple inside PostgreSQL. A retry
   * that arrives concurrently waits for the first transaction, then reads its
   * durable idempotency response instead of creating a second aggregate.
   */
  private void acquireIdempotencyLock(UUID subjectId, String operation, UUID idempotencyKey) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("subjectId and Idempotency-Key are required");
    }
    String lockKey = subjectId + "\u001f" + operation + "\u001f" + idempotencyKey;
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?::text, 0))",
        (ResultSetExtractor<Void>) resultSet -> null,
        lockKey);
  }

  private void remember(
      UUID subjectId,
      UUID idempotencyKey,
      String operation,
      String checksum,
      LogisticsDocument document) {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    idempotencyRepository.save(
        LogisticsIdempotencyRecord.create(
            subjectId,
            operation,
            idempotencyKey,
            checksum,
            document,
            createdAt,
            createdAt.plus(idempotencyProperties.retention())));
  }

  private LogisticsDocumentView toView(LogisticsDocument document) {
    LogisticsDocumentSummary summary = responseMapper.toSummary(document);
    return new LogisticsDocumentView(
        summary.id(),
        summary.version(),
        summary.documentType(),
        summary.state(),
        summary.warehouseId(),
        summary.destinationWarehouseId(),
        summary.partySnapshot(),
        summary.driverSnapshot(),
        responseMapper.toLineViews(lineRepository.findAllByDocument_IdOrderByLineNumber(summary.id())),
        summary.createdAt(),
        summary.updatedAt());
  }

  private static void requireReturnCommand(
      UUID documentId, UUID correlationId, long expectedDocumentVersion, Object request) {
    if (documentId == null || correlationId == null || expectedDocumentVersion < 0) {
      throw new IllegalArgumentException("Return command identifiers and version are required");
    }
    requireRequest(request);
  }

  private static void requireShipmentCommand(
      UUID documentId, UUID correlationId, long expectedDocumentVersion, Object request) {
    if (documentId == null || correlationId == null || expectedDocumentVersion < 0) {
      throw new IllegalArgumentException("Shipment command identifiers and version are required");
    }
    requireRequest(request);
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
      throw new IllegalArgumentException("Transfer cancellation identifiers and version are required");
    }
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

  private List<LogisticsDocumentLine> linesRequired(UUID documentId) {
    List<LogisticsDocumentLine> lines =
        lineRepository.findAllByDocument_IdOrderByLineNumber(documentId);
    if (lines.isEmpty()) throw new IllegalStateException("Logistics document has no lines");
    return lines;
  }

  private LogisticsDocumentLine transferLine(LogisticsDocument document, UUID lineId) {
    LogisticsDocumentLine line =
        lineRepository.findById(lineId).orElseThrow(LogisticsNotFoundException::new);
    if (!document.getId().equals(line.getDocument().getId())) {
      throw new LogisticsNotFoundException();
    }
    return line;
  }

  private static UUID transferDestination(LogisticsDocument document) {
    UUID destinationWarehouseId = document.getDestinationWarehouseId();
    if (destinationWarehouseId == null || destinationWarehouseId.equals(document.getWarehouseId())) {
      throw new IllegalStateException("Transfer destination warehouse is invalid");
    }
    return destinationWarehouseId;
  }

  private static void requireLineVersion(
      LogisticsDocumentLine line, long expectedVersion, String message) {
    if (line.getVersion() != expectedVersion) throw new LogisticsConflictException(message);
  }

  private static Map<UUID, LogisticsDocumentLine> linesById(List<LogisticsDocumentLine> lines) {
    return lines.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(LogisticsDocumentLine::getId, line -> line));
  }

  private static void requireImmutableShipmentPlan(
      LogisticsDocument document, List<LogisticsDocumentLine> lines, ShipmentPlanRequest request) {
    if (!document.getPartySnapshot().equals(trimSnapshot(request.partySnapshot()))
        || !document.getDriverSnapshot().equals(trimSnapshot(request.driverSnapshot()))
        || request.lines() == null
        || request.lines().size() != lines.size()) {
      throw new LogisticsConflictException("Shipment plan differs from the immutable draft");
    }
    for (int index = 0; index < lines.size(); index++) {
      LogisticsDocumentLine persisted = lines.get(index);
      var input = request.lines().get(index);
      validateShipmentAllocations(input);
      if (input.assetId() == null
          || !persisted.getAssetId().equals(input.assetId())
          || persisted.getAssetVersion() != input.assetVersion()
          || persisted.getSourceAllocationSnapshot() == null
          || !sameAllocationSnapshot(persisted.getSourceAllocationSnapshot(), input)) {
        throw new LogisticsConflictException("Shipment plan differs from the immutable draft");
      }
    }
  }

  private static String trimSnapshot(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Shipment snapshot is required");
    }
    return value.trim();
  }

  private static boolean sameAllocationSnapshot(
      tools.jackson.databind.JsonNode snapshot,
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest input) {
    tools.jackson.databind.JsonNode values = snapshot == null ? null : snapshot.get("allocations");
    if (values == null || !values.isArray() || values.size() != input.allocations().size()) return false;
    java.util.HashMap<UUID, String> persisted = new java.util.HashMap<>();
    for (tools.jackson.databind.JsonNode value : values) {
      tools.jackson.databind.JsonNode equipmentId = value.get("equipmentId");
      tools.jackson.databind.JsonNode quantity = value.get("quantity");
      tools.jackson.databind.JsonNode expectedVersion = value.get("expectedStockVersion");
      if (equipmentId == null
          || !equipmentId.isTextual()
          || quantity == null
          || !quantity.isIntegralNumber()
          || expectedVersion == null
          || !expectedVersion.isIntegralNumber()) return false;
      try {
        UUID id = UUID.fromString(equipmentId.textValue());
        if (persisted.put(id, quantity.longValue() + ":" + expectedVersion.longValue()) != null) return false;
      } catch (IllegalArgumentException exception) {
        return false;
      }
    }
    for (var allocation : input.allocations()) {
      if (!Long.toString(allocation.quantity())
          .concat(":" + allocation.expectedStockVersion())
          .equals(persisted.get(allocation.equipmentId()))) return false;
    }
    return true;
  }

  private static void validateAcceptanceLines(AcceptReturnRequest request, Set<UUID> requiredLineIds) {
    if (request.lines() == null || request.lines().size() != requiredLineIds.size()) {
      throw new LogisticsConflictException("Return acceptance must contain media for every return line");
    }
    HashSet<UUID> lineIds = new HashSet<>();
    HashSet<UUID> mediaIds = new HashSet<>();
    for (var input : request.lines()) {
      if (input == null
          || input.lineId() == null
          || input.references() == null
          || input.references().isEmpty()
          || input.references().size() > 20
          || !lineIds.add(input.lineId())
          || !requiredLineIds.contains(input.lineId())) {
        throw new LogisticsConflictException("Return acceptance lines are invalid");
      }
      HashSet<UUID> lineMediaIds = new HashSet<>();
      for (var reference : input.references()) {
        if (reference == null
            || reference.mediaId() == null
            || reference.generation() < 1
            || !lineMediaIds.add(reference.mediaId())
            || !mediaIds.add(reference.mediaId())) {
          throw new LogisticsConflictException("Return acceptance media references are invalid");
        }
      }
    }
    if (!lineIds.equals(requiredLineIds)) {
      throw new LogisticsConflictException("Return acceptance must contain exactly the return lines");
    }
  }

  private static void validateEstimateLines(
      RequestReturnEstimateRequest request, Set<UUID> requiredLineIds) {
    if (request.lines() == null || request.lines().size() != requiredLineIds.size()) {
      throw new LogisticsConflictException("Return estimate must contain shortages for every return line");
    }
    HashSet<UUID> lineIds = new HashSet<>();
    for (var input : request.lines()) {
      if (input == null
          || input.lineId() == null
          || input.shortages() == null
          || input.shortages().isEmpty()
          || input.shortages().size() > 100
          || !lineIds.add(input.lineId())
          || !requiredLineIds.contains(input.lineId())) {
        throw new LogisticsConflictException("Return estimate lines are invalid");
      }
      HashSet<UUID> equipmentIds = new HashSet<>();
      for (var shortage : input.shortages()) {
        if (shortage == null
            || shortage.equipmentId() == null
            || shortage.missingQuantity() < 1
            || !equipmentIds.add(shortage.equipmentId())) {
          throw new LogisticsConflictException("Return shortage values are invalid");
        }
      }
    }
    if (!lineIds.equals(requiredLineIds)) {
      throw new LogisticsConflictException("Return estimate must contain exactly the return lines");
    }
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

  private LogisticsGuard activeGuard(LogisticsDocumentLine line) {
    LogisticsGuard guard =
        guardRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> new LogisticsConflictException("Return line has no active asset lease"));
    if (guard.getGuardState() != LogisticsGuardState.ACTIVE
        || guard.getLeaseId() == null
        || guard.getLeaseVersion() == null
        || guard.getFenceToken() == null
        || guard.getObservedAssetVersion() == null) {
      throw new LogisticsConflictException("Return line does not have an active asset lease");
    }
    return guard;
  }

  private void createLineAttempt(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsTargetService target,
      String operationType,
      String requestDigest,
      OffsetDateTime createdAt) {
    if (externalAttemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(document.getId(), line.getId(), operationType)
        .isPresent()) {
      throw new LogisticsConflictException("Return external attempt already exists");
    }
    externalAttemptRepository.save(
        LogisticsExternalAttempt.create(
            document,
            line,
            target,
            operationType,
            requestDigest,
            document.getCorrelationId(),
            null,
            createdAt));
  }

  private static String mediaAttemptDigest(
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
    return LogisticsCommandChecksum.sha256(RETURN_MEDIA_VALIDATE, values);
  }

  private static String transferWarehouseDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID warehouseId) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            document.getId().toString(),
            line.getId().toString(),
            warehouseId.toString()));
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
    return LogisticsCommandChecksum.sha256(TRANSFER_MEDIA_VALIDATE, values);
  }

  private static ObjectNode shortagesSnapshot(
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnShortageLineRequest input) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    ArrayNode values = root.putArray("shortages");
    input.shortages().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            value -> {
              ObjectNode shortage = values.addObject();
              shortage.put("equipmentId", value.equipmentId().toString());
              shortage.put("missingQuantity", value.missingQuantity());
            });
    return root;
  }

  private static String shortageSnapshotDigest(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnShortageLineRequest input) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(document.getWarehouseId().toString());
    values.add(line.getAssetId().toString());
    values.add(Long.toString(guard.getObservedAssetVersion()));
    input.shortages().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            value -> {
              values.add(value.equipmentId().toString());
              values.add(Long.toString(value.missingQuantity()));
            });
    return LogisticsCommandChecksum.sha256("RETURN_SHORTAGE_SNAPSHOT", values);
  }

  private static List<String> shipmentPlanFingerprintValues(
      UUID documentId, long expectedVersion, ShipmentPlanRequest request) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(Long.toString(expectedVersion));
    values.add(trimSnapshot(request.partySnapshot()));
    values.add(trimSnapshot(request.driverSnapshot()));
    if (request.lines() == null) throw new IllegalArgumentException("Shipment lines are required");
    for (var line : request.lines()) {
      if (line == null || line.assetId() == null) {
        throw new IllegalArgumentException("Shipment line is required");
      }
      validateShipmentAllocations(line);
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
      line.allocations().stream()
          .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
          .forEach(
              allocation -> {
                values.add(allocation.equipmentId().toString());
                values.add(Long.toString(allocation.quantity()));
                values.add(Long.toString(allocation.expectedStockVersion()));
              });
    }
    return values;
  }

  static String holdAcquireOperation(UUID equipmentId) {
    return SHIPMENT_HOLD_ACQUIRE_PREFIX + equipmentId;
  }

  static String holdCommitOperation(UUID holdId) {
    return SHIPMENT_HOLD_COMMIT_PREFIX + holdId;
  }

  static String holdReleaseOperation(UUID holdId) {
    return SHIPMENT_HOLD_RELEASE_PREFIX + holdId;
  }

  private static List<String> acceptanceFingerprintValues(
      UUID documentId, long expectedVersion, AcceptReturnRequest request) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(Long.toString(expectedVersion));
    request.lines().stream()
        .sorted(Comparator.comparing(value -> value.lineId().toString()))
        .forEach(
            line -> {
              values.add(line.lineId().toString());
              line.references().stream()
                  .sorted(Comparator.comparing(value -> value.mediaId().toString()))
                  .forEach(
                      reference -> {
                        values.add(reference.mediaId().toString());
                        values.add(Long.toString(reference.generation()));
                      });
            });
    return values;
  }

  private static List<String> estimateFingerprintValues(
      UUID documentId, long expectedVersion, RequestReturnEstimateRequest request) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(Long.toString(expectedVersion));
    request.lines().stream()
        .sorted(Comparator.comparing(value -> value.lineId().toString()))
        .forEach(
            line -> {
              values.add(line.lineId().toString());
              line.shortages().stream()
                  .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
                  .forEach(
                      shortage -> {
                        values.add(shortage.equipmentId().toString());
                        values.add(Long.toString(shortage.missingQuantity()));
                      });
            });
    return values;
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
    return values;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static List<LogisticsDocumentLine> returnLines(
      LogisticsDocument document, List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest> inputs) {
    validateLineInputs(inputs);
    List<LogisticsDocumentLine> lines = new ArrayList<>(inputs.size());
    for (int index = 0; index < inputs.size(); index++) {
      var input = inputs.get(index);
      if (input.tenantSnapshot() == null || input.tenantSnapshot().isBlank()) {
        throw new IllegalArgumentException("tenantSnapshot is required for a return line");
      }
      lines.add(
          LogisticsDocumentLine.create(
              document, index + 1, input.assetId(), input.assetVersion(), input.tenantSnapshot()));
    }
    return lines;
  }

  private static List<LogisticsDocumentLine> shipmentLines(
      LogisticsDocument document, List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest> inputs) {
    validateLineInputs(inputs);
    List<LogisticsDocumentLine> lines = new ArrayList<>(inputs.size());
    for (int index = 0; index < inputs.size(); index++) {
      var input = inputs.get(index);
      validateShipmentAllocations(input);
      LogisticsDocumentLine line =
          LogisticsDocumentLine.create(document, index + 1, input.assetId(), input.assetVersion(), null);
      line.captureSourceAllocations(allocationSnapshot(input));
      lines.add(line);
    }
    return lines;
  }

  private static List<LogisticsDocumentLine> transferLines(
      LogisticsDocument document, List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest> inputs) {
    validateLineInputs(inputs);
    List<LogisticsDocumentLine> lines = new ArrayList<>(inputs.size());
    for (int index = 0; index < inputs.size(); index++) {
      var input = inputs.get(index);
      lines.add(LogisticsDocumentLine.create(document, index + 1, input.assetId(), input.assetVersion(), null));
    }
    return lines;
  }

  private static void validateLineInputs(List<?> inputs) {
    if (inputs == null || inputs.isEmpty() || inputs.size() > 100) {
      throw new IllegalArgumentException("Document must contain 1 to 100 lines");
    }
    HashSet<UUID> assetIds = new HashSet<>();
    for (Object input : inputs) {
      UUID assetId =
          switch (input) {
            case dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest line -> line.assetId();
            case dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest line -> line.assetId();
            case dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest line -> line.assetId();
            default -> throw new IllegalArgumentException("Unsupported logistics line input");
          };
      if (assetId == null || !assetIds.add(assetId)) {
        throw new IllegalArgumentException("Document line asset IDs must be distinct");
      }
    }
  }

  private static List<String> returnFingerprintValues(CreateReturnRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    for (var line : request.lines()) {
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
      values.add(line.tenantSnapshot());
    }
    return values;
  }

  private static List<String> shipmentFingerprintValues(CreateShipmentRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(request.partySnapshot());
    values.add(request.driverSnapshot());
    for (var line : request.lines()) {
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
      line.allocations().stream()
          .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
          .forEach(
              allocation -> {
                values.add(allocation.equipmentId().toString());
                values.add(Long.toString(allocation.quantity()));
                values.add(Long.toString(allocation.expectedStockVersion()));
              });
    }
    return values;
  }

  private static List<String> transferFingerprintValues(CreateTransferRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(request.destinationWarehouseId().toString());
    for (var line : request.lines()) {
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
    }
    return values;
  }

  private static void requireRequest(Object request) {
    if (request == null) throw new IllegalArgumentException("Request is required");
  }

  private static void validateShipmentAllocations(
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest input) {
    if (input.allocations() == null || input.allocations().size() > 100) {
      throw new IllegalArgumentException("Shipment allocation count is invalid");
    }
    HashSet<UUID> equipmentIds = new HashSet<>();
    for (var allocation : input.allocations()) {
      if (allocation == null
          || allocation.equipmentId() == null
          || allocation.quantity() < 1
          || allocation.expectedStockVersion() < 0
          || !equipmentIds.add(allocation.equipmentId())) {
        throw new IllegalArgumentException("Shipment allocation is invalid");
      }
    }
  }

  private static ObjectNode allocationSnapshot(
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest input) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    ArrayNode values = root.putArray("allocations");
    input.allocations().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            allocation -> {
              ObjectNode value = values.addObject();
              value.put("equipmentId", allocation.equipmentId().toString());
              value.put("quantity", allocation.quantity());
              value.put("expectedStockVersion", allocation.expectedStockVersion());
            });
    return root;
  }

  public record CreateResult(LogisticsDocumentView response, boolean replayed) {}
}
