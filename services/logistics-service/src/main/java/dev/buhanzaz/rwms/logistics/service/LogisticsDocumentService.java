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
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
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
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsDocumentResponseMapper;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderAuditService;
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
import java.nio.charset.StandardCharsets;
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
  static final String RETURN_MEDIA_OWNER_PROOF_REGISTER = "RETURN_MEDIA_OWNER_PROOF_REGISTER";
  static final String RETURN_MEDIA_VALIDATE = "RETURN_MEDIA_VALIDATE";
  static final String RETURN_ASSET_SETTLE_FREE = "RETURN_ASSET_SETTLE_FREE";
  static final String RETURN_ASSET_SETTLE_SHORTAGE = "RETURN_ASSET_SETTLE_SHORTAGE";
  static final String RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE =
      "RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE";
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
  static final String TRANSFER_TASK_REGISTER = "TRANSFER_TASK_REGISTER";
  static final String TRANSFER_TASK_CANCEL = "TRANSFER_TASK_CANCEL";
  static final String TRANSFER_MEDIA_OWNER_PROOF_REGISTER =
      "TRANSFER_MEDIA_OWNER_PROOF_REGISTER";
  static final String TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE =
      "TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE";

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
  private final RentalOrderRepository rentalOrders;
  private final OrderAuditService orderAudit;
  private final EquipmentMovementTaskService equipmentMovementTasks;
  private final LogisticsDocumentResponseMapper responseMapper;
  private final LogisticsIdempotencyProperties idempotencyProperties;
  private final LogisticsEventStore eventStore;

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

    RentalOrder returnOrder = validateReturnRentalBinding(request);
    LogisticsDocument document =
        documentRepository.saveAndFlush(
            LogisticsDocument.createReturn(
                request.warehouseId(),
                request.clientId(),
                returnOrder == null ? null : returnOrder.getClient().getDisplayName(),
                request.driverSnapshot(),
                subjectId,
                correlationId));
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(returnLines(document, request.lines()));
    OffsetDateTime proofCreatedAt = now();
    for (LogisticsDocumentLine line : lines) {
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          RETURN_MEDIA_OWNER_PROOF_REGISTER,
          ownerProofDigest(
              RETURN_MEDIA_OWNER_PROOF_REGISTER,
              document,
              line,
              document.getWarehouseId(),
              0,
              0,
              true),
          proofCreatedAt);
    }
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

    RentalOrder shipmentOrder = validateShipmentRentalBinding(request);
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            request.warehouseId(),
            request.clientId(),
            shipmentOrder == null
                ? request.partySnapshot()
                : shipmentOrder.getClient().getDisplayName(),
            request.driverSnapshot(),
            subjectId,
            correlationId);
    document.scheduleShipment(request.driverSnapshot(), now());
    document = documentRepository.saveAndFlush(document);
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(
            shipmentLines(document, request.lines(), shipmentOrder == null ? null : shipmentOrder.getId()));
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    startShipmentPreparation(document, lines, correlationId, subjectId, now());
    remember(subjectId, idempotencyKey, CREATE_SHIPMENT, checksum, document);
    return new CreateResult(toView(document), false);
  }

  @Transactional
  public CreateResult createTransfer(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateTransferRequest request) {
    requireRequest(request);
    validateTransferEquipment(request);
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
                request.warehouseId(),
                request.destinationWarehouseId(),
                request.driverSnapshot(),
                subjectId,
                correlationId));
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(transferLines(document, request.lines()));
    OffsetDateTime proofCreatedAt = now();
    for (LogisticsDocumentLine line : lines) {
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          TRANSFER_MEDIA_OWNER_PROOF_REGISTER,
          ownerProofDigest(
              TRANSFER_MEDIA_OWNER_PROOF_REGISTER,
              document,
              line,
              transferDestination(document),
              0,
              0,
              true),
          proofCreatedAt);
      LogisticsTaskReference taskReference =
          taskReferenceRepository.save(
              LogisticsTaskReference.pending(
                  document,
                  line,
                  transferTaskExternalId(document, line),
                  document.getWarehouseId(),
                  proofCreatedAt));
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.TASK_BOARD,
          TRANSFER_TASK_REGISTER,
          LogisticsCommandChecksum.sha256(
              TRANSFER_TASK_REGISTER,
              List.of(
                  document.getWarehouseId().toString(),
                  taskReference.getExternalTaskId().toString(),
                  "0",
                  "<null>")),
          proofCreatedAt);
    }
    if (!request.equipment().isEmpty()) {
      EquipmentMovementTaskService.CreateResult task =
          equipmentMovementTasks.create(
              subjectId,
              transferEquipmentTaskIdempotencyKey(document.getId()),
              new CreateEquipmentMovementTaskRequest(
                  document.getWarehouseId(),
                  document.getDestinationWarehouseId(),
                  null,
                  null,
                  request.equipmentDeadlineAt(),
                  request.equipment().stream()
                      .map(
                          equipment ->
                              new EquipmentMovementLineRequest(
                                  equipment.equipmentId(),
                                  null,
                                  EquipmentMovementLocationKind.STOCK,
                                  equipment.expectedSourceBalanceVersion(),
                                  null,
                                  EquipmentMovementLocationKind.STOCK,
                                  equipment.quantity()))
                      .toList()));
      document.linkEquipmentMovementTask(task.response().id());
      documentRepository.saveAndFlush(document);
    }
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    remember(subjectId, idempotencyKey, CREATE_TRANSFER, checksum, document);
    return new CreateResult(toView(document), false);
  }

  /** Creates the date-less shipment that becomes visible as soon as an order is saved. */
  @Transactional
  public LogisticsDocumentView createRentalOrderShipmentDraft(
      UUID subjectId,
      UUID correlationId,
      RentalOrder order,
      List<LogisticsDependencyGateway.OrderUnitReservation> reservations) {
    requireRequest(order);
    if (subjectId == null || correlationId == null) {
      throw new IllegalArgumentException("Shipment actor and correlation identifiers are required");
    }
    if (order.getStatus() != RentalOrderStatus.SAVED
        || order.getWarehouseId() == null
        || order.getClient() == null) {
      throw new LogisticsConflictException("Saved rental order is required for shipment creation");
    }
    LogisticsDocument existing =
        documentRepository
            .findByDocumentTypeAndRentalOrderId(LogisticsDocumentType.SHIPMENT, order.getId())
            .orElse(null);
    if (existing != null) return toView(existing);
    if (reservations == null || reservations.isEmpty() || reservations.size() > 100) {
      throw new LogisticsConflictException("Заказ должен содержать от 1 до 100 бытовок");
    }

    LogisticsDocument document =
        documentRepository.saveAndFlush(
            LogisticsDocument.createRentalOrderShipment(
                order.getWarehouseId(),
                order.getClient().getId(),
                order.getId(),
                order.getClient().getDisplayName(),
                subjectId,
                correlationId));
    List<LogisticsDocumentLine> lines = new ArrayList<>(reservations.size());
    HashSet<UUID> unitIds = new HashSet<>();
    for (int index = 0; index < reservations.size(); index++) {
      LogisticsDependencyGateway.OrderUnitReservation reservation = reservations.get(index);
      if (reservation == null
          || reservation.unit() == null
          || reservation.unitId() == null
          || !reservation.unitId().equals(reservation.unit().id())
          || !order.getId().equals(reservation.orderId())
          || !order.getWarehouseId().equals(reservation.warehouseId())
          || !"ACTIVE".equals(reservation.state())
          || !unitIds.add(reservation.unitId())) {
        throw new LogisticsConflictException("Order unit reservation is invalid");
      }
      LogisticsDocumentLine line =
          LogisticsDocumentLine.create(
              document,
              index + 1,
              reservation.unitId(),
              reservation.unit().version(),
              null,
              order.getId());
      line.captureSourceAllocations(emptyAllocationSnapshot());
      lines.add(line);
    }
    lines = lineRepository.saveAllAndFlush(lines);
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    return toView(document);
  }

  /** Advances the order and creates its date-less return after every cabin is shipped. */
  @Transactional
  public void completeRentalOrderShipment(LogisticsDocument shipment) {
    if (shipment == null
        || shipment.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || shipment.getState() != LogisticsDocumentState.SHIPPED
        || shipment.getRentalOrderId() == null) {
      return;
    }
    RentalOrder order =
        rentalOrders
            .findForUpdate(shipment.getRentalOrderId())
            .orElseThrow(() -> new LogisticsConflictException("Rental order was not found"));
    boolean fulfilled = order.fulfill();
    if (fulfilled) {
      rentalOrders.saveAndFlush(order);
      orderAudit.appendForActor(
          order.getId(),
          OrderAuditEventType.ORDER_FULFILLED,
          shipment.getRequestedBySubjectId(),
          order.getCreatedByRole(),
          "ORDER",
          order.getId().toString(),
          Map.of("status", RentalOrderStatus.SAVED.name()),
          Map.of("status", RentalOrderStatus.FULFILLED.name()));
    }
    if (documentRepository
        .findByDocumentTypeAndRentalOrderId(LogisticsDocumentType.RETURN, order.getId())
        .isPresent()) {
      return;
    }

    LogisticsDocument returnDocument =
        documentRepository.saveAndFlush(
            LogisticsDocument.createRentalOrderReturn(
                order.getWarehouseId(),
                order.getClient().getId(),
                order.getId(),
                order.getClient().getDisplayName(),
                shipment.getRequestedBySubjectId(),
                shipment.getCorrelationId()));
    List<LogisticsDocumentLine> shipmentLines = linesRequired(shipment.getId());
    List<LogisticsDocumentLine> returnLines = new ArrayList<>(shipmentLines.size());
    for (LogisticsDocumentLine shipmentLine : shipmentLines) {
      LogisticsGuard guard =
          guardRepository
              .findByLine_Id(shipmentLine.getId())
              .orElseThrow(
                  () -> new LogisticsConflictException("Shipped order line has no asset guard"));
      if (guard.getObservedAssetVersion() == null) {
        throw new LogisticsConflictException("Shipped order line has no observed asset version");
      }
      returnLines.add(
          LogisticsDocumentLine.create(
              returnDocument,
              shipmentLine.getLineNumber(),
              shipmentLine.getAssetId(),
              guard.getObservedAssetVersion(),
              order.getClient().getDisplayName(),
              order.getId()));
    }
    returnLines = lineRepository.saveAllAndFlush(returnLines);
    OffsetDateTime createdAt = now();
    for (LogisticsDocumentLine line : returnLines) {
      createLineAttempt(
          returnDocument,
          line,
          LogisticsTargetService.MEDIA,
          RETURN_MEDIA_OWNER_PROOF_REGISTER,
          ownerProofDigest(
              RETURN_MEDIA_OWNER_PROOF_REGISTER,
              returnDocument,
              line,
              returnDocument.getWarehouseId(),
              0,
              0,
              true),
          createdAt);
    }
    eventStore.initialize(
        returnDocument,
        returnLines.size(),
        shipment.getCorrelationId(),
        shipment.getRequestedBySubjectId());
  }

  /** Closes a linked order after its return has reached either terminal inspection outcome. */
  @Transactional
  public void closeRentalOrderReturn(LogisticsDocument returnDocument) {
    if (returnDocument == null
        || returnDocument.getDocumentType() != LogisticsDocumentType.RETURN
        || returnDocument.getRentalOrderId() == null
        || (returnDocument.getState() != LogisticsDocumentState.ACCEPTED
            && returnDocument.getState() != LogisticsDocumentState.ESTIMATE_REQUESTED)) {
      return;
    }
    RentalOrder order =
        rentalOrders
            .findForUpdate(returnDocument.getRentalOrderId())
            .orElseThrow(() -> new LogisticsConflictException("Rental order was not found"));
    if (!order.close()) return;
    rentalOrders.saveAndFlush(order);
    orderAudit.appendForActor(
        order.getId(),
        OrderAuditEventType.ORDER_CLOSED,
        returnDocument.getRequestedBySubjectId(),
        order.getCreatedByRole(),
        "ORDER",
        order.getId().toString(),
        Map.of("status", RentalOrderStatus.FULFILLED.name()),
        Map.of("status", RentalOrderStatus.CLOSED.name()));
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
      long expectedDocumentVersion,
      ReturnPickupRequest request) {
    requireReturnCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            REGISTER_RETURN,
            List.of(
                documentId.toString(),
                Long.toString(expectedDocumentVersion),
                request.driverSnapshot().trim(),
                request.scheduledAt().toString()));
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

    document.scheduleReturn(request.driverSnapshot(), request.scheduledAt());
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
      line.captureReturnAdditionalContents(additionalContentsSnapshot(input));
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
   * Retains the explicit command only for pre-existing DRAFT documents. New
   * documents start their durable preparation workflow atomically with their
   * creation, so the panel never has to coordinate create-and-plan commands.
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
    boolean startPreparation = document.getState() == LogisticsDocumentState.DRAFT;
    List<LogisticsDocumentLine> lines = linesRequired(documentId);
    try {
      document.scheduleShipment(request.driverSnapshot(), request.scheduledAt());
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException(
          "Shipment plan cannot be changed in its current lifecycle state");
    }
    if (startPreparation) {
      startShipmentPreparation(document, lines, correlationId, subjectId, now());
    } else {
      documentRepository.saveAndFlush(document);
      eventStore.append(
          document,
          lines.size(),
          correlationId,
          subjectId,
          LogisticsEventType.SHIPMENT_PLANNED,
          null);
    }
    remember(subjectId, idempotencyKey, PLAN_SHIPMENT, checksum, document);
    return new CreateResult(toView(document), false);
  }

  private void startShipmentPreparation(
      LogisticsDocument document,
      List<LogisticsDocumentLine> lines,
      UUID correlationId,
      UUID subjectId,
      OffsetDateTime startedAt) {
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
          startedAt);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.SHIPMENT_PREPARATION_STARTED,
        null);
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
    try {
      document.requireShipmentDepartureAllowed(now());
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException(
          "Дата отгрузки ещё не наступила. Измените дату и повторите действие");
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

    cancelTransferEquipmentTask(subjectId, document);
    document.beginTransferCancellation();
    documentRepository.saveAndFlush(document);
    List<LogisticsTaskReference> taskReferences =
        taskReferenceRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId);
    OffsetDateTime cancellationStartedAt = now();
    for (LogisticsTaskReference reference : taskReferences) {
      if (reference.getTaskState() == LogisticsTaskReferenceState.PENDING
          || reference.getTaskState() == LogisticsTaskReferenceState.DONE) {
        reference.cancel(reference.getTaskVersion() == null ? 0 : reference.getTaskVersion());
        continue;
      }
      if (reference.getTaskState() == LogisticsTaskReferenceState.REGISTERED) {
        createLineAttempt(
            document,
            reference.getLine(),
            LogisticsTargetService.TASK_BOARD,
            TRANSFER_TASK_CANCEL,
            LogisticsCommandChecksum.sha256(
                TRANSFER_TASK_CANCEL,
                List.of(
                    reference.getExternalTaskId().toString(),
                    Long.toString(reference.getTaskVersion()))),
            cancellationStartedAt);
        continue;
      }
      if (reference.getTaskState() != LogisticsTaskReferenceState.CANCELLED) {
        throw new LogisticsConflictException("Transfer preparation task cannot be cancelled");
      }
    }
    taskReferenceRepository.saveAllAndFlush(taskReferences);
    OffsetDateTime proofUpdatedAt = now();
    for (LogisticsDocumentLine line : lines) {
      createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE,
          ownerProofDigest(
              TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE,
              document,
              line,
              transferDestination(document),
              1,
              1,
              false),
          proofUpdatedAt);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.TRANSFER_CANCELLATION_STARTED,
        null);
    if (taskReferences.stream()
        .allMatch(reference -> reference.getTaskState() == LogisticsTaskReferenceState.CANCELLED)) {
      document.cancelTransfer();
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
    }
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
    idempotencyRepository.acquireTransactionLock(lockKey);
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
        summary.clientId(),
        summary.equipmentMovementTaskId(),
        summary.scheduledAt(),
        summary.rentalOrderId(),
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
          || input.additionalEquipment() == null
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
      if (input.additionalEquipment().size() > 100) {
        throw new LogisticsConflictException("Return additional equipment is invalid");
      }
      HashSet<UUID> additionalEquipmentIds = new HashSet<>();
      for (var additional : input.additionalEquipment()) {
        if (additional == null
            || additional.equipmentId() == null
            || additional.quantity() == null
            || additional.quantity() < 1
            || !additionalEquipmentIds.add(additional.equipmentId())) {
          throw new LogisticsConflictException("Return additional equipment is invalid");
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

  private static String ownerProofDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      boolean active) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            document.getDocumentType().name(),
            document.getId().toString(),
            line.getId().toString(),
            warehouseId.toString(),
            Long.toString(ownerRevision),
            Long.toString(aggregateVersion),
            Boolean.toString(active)));
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

  private static ObjectNode additionalContentsSnapshot(
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnMediaLineRequest input) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    ArrayNode values = root.putArray("additionalEquipment");
    input.additionalEquipment().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            value -> {
              ObjectNode additional = values.addObject();
              additional.put("equipmentId", value.equipmentId().toString());
              additional.put("quantity", value.quantity());
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
    return List.of(
        documentId.toString(),
        Long.toString(expectedVersion),
        request.driverSnapshot().trim(),
        request.scheduledAt().toString());
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
              line.additionalEquipment().stream()
                  .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
                  .forEach(
                      additional -> {
                        values.add(additional.equipmentId().toString());
                        values.add(Long.toString(additional.quantity()));
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
              document,
              index + 1,
              input.assetId(),
              input.assetVersion(),
              input.tenantSnapshot(),
              input.rentalOrderId()));
    }
    return lines;
  }

  private static List<LogisticsDocumentLine> shipmentLines(
      LogisticsDocument document,
      List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest> inputs,
      UUID rentalOrderId) {
    validateLineInputs(inputs);
    List<LogisticsDocumentLine> lines = new ArrayList<>(inputs.size());
    for (int index = 0; index < inputs.size(); index++) {
      var input = inputs.get(index);
      validateShipmentAllocations(input);
      LogisticsDocumentLine line =
          LogisticsDocumentLine.create(
              document, index + 1, input.assetId(), input.assetVersion(), null, rentalOrderId);
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
    values.add(request.clientId() == null ? null : request.clientId().toString());
    values.add(optionalTrimSnapshot(request.driverSnapshot()));
    for (var line : request.lines()) {
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
      values.add(line.tenantSnapshot());
      values.add(line.rentalOrderId() == null ? null : line.rentalOrderId().toString());
    }
    return values;
  }

  private static String optionalTrimSnapshot(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static List<String> shipmentFingerprintValues(CreateShipmentRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(request.clientId() == null ? null : request.clientId().toString());
    values.add(request.rentalOrderId() == null ? null : request.rentalOrderId().toString());
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
    values.add(request.driverSnapshot());
    values.add(
        request.equipmentDeadlineAt() == null ? null : request.equipmentDeadlineAt().toString());
    for (var line : request.lines()) {
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
    }
    request.equipment().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            equipment -> {
              values.add(equipment.equipmentId().toString());
              values.add(Long.toString(equipment.expectedSourceBalanceVersion()));
              values.add(Long.toString(equipment.quantity()));
            });
    return values;
  }

  private static void validateTransferEquipment(CreateTransferRequest request) {
    if (request.equipment() == null || request.equipment().size() > 100) {
      throw new IllegalArgumentException("Transfer equipment lines are invalid");
    }
    if (request.equipment().isEmpty()) return;
    if (request.equipmentDeadlineAt() == null || !request.equipmentDeadlineAt().isAfter(now())) {
      throw new IllegalArgumentException(
          "A future equipment task deadline is required for transfer furniture");
    }
    HashSet<UUID> equipmentIds = new HashSet<>();
    for (var equipment : request.equipment()) {
      if (equipment == null
          || equipment.equipmentId() == null
          || equipment.expectedSourceBalanceVersion() == null
          || equipment.expectedSourceBalanceVersion() < 0
          || equipment.quantity() == null
          || equipment.quantity() < 1
          || !equipmentIds.add(equipment.equipmentId())) {
        throw new IllegalArgumentException("Transfer equipment line is invalid");
      }
    }
  }

  private static UUID transferTaskExternalId(
      LogisticsDocument document, LogisticsDocumentLine line) {
    return UUID.nameUUIDFromBytes(
        ("rwms:transfer:" + document.getId() + ":" + line.getId())
            .getBytes(StandardCharsets.UTF_8));
  }

  private static UUID transferEquipmentTaskIdempotencyKey(UUID documentId) {
    return UUID.nameUUIDFromBytes(
        ("rwms:transfer-equipment:" + documentId).getBytes(StandardCharsets.UTF_8));
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

  /**
   * The panel may present several active rentals for a client, but each return
   * line must still name the rental order that owns its active reservation.
   * This checks service-local order truth before the fenced asset workflow
   * verifies the physical reservation again.
   */
  private RentalOrder validateReturnRentalBinding(CreateReturnRequest request) {
    boolean hasBinding =
        request.clientId() != null
            || request.lines().stream().anyMatch(line -> line.rentalOrderId() != null);
    if (!hasBinding) return null;
    if (request.clientId() == null) {
      throw new IllegalArgumentException("clientId is required for a bound rental return");
    }

    RentalOrder first = null;
    for (var line : request.lines()) {
      if (line.rentalOrderId() == null) {
        throw new IllegalArgumentException("rentalOrderId is required for every bound return line");
      }
      RentalOrder order = requireActiveRentalOrder(line.rentalOrderId(), request.clientId(), request.warehouseId());
      if (first == null) first = order;
    }
    return first;
  }

  private RentalOrder validateShipmentRentalBinding(CreateShipmentRequest request) {
    boolean hasBinding = request.clientId() != null || request.rentalOrderId() != null;
    if (!hasBinding) return null;
    if (request.clientId() == null || request.rentalOrderId() == null) {
      throw new IllegalArgumentException(
          "clientId and rentalOrderId are required for a bound rental shipment");
    }
    return requireActiveRentalOrder(
        request.rentalOrderId(), request.clientId(), request.warehouseId());
  }

  private RentalOrder requireActiveRentalOrder(
      UUID rentalOrderId, UUID clientId, UUID warehouseId) {
    RentalOrder order =
        rentalOrders
            .findWithClientById(rentalOrderId)
            .orElseThrow(() -> new LogisticsConflictException("Rental order was not found"));
    if (order.getStatus() != RentalOrderStatus.DRAFT
        || !clientId.equals(order.getClient().getId())
        || !warehouseId.equals(order.getWarehouseId())) {
      throw new LogisticsConflictException(
          "Rental order does not belong to the selected client and warehouse");
    }
    return order;
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

  private static ObjectNode emptyAllocationSnapshot() {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.putArray("allocations");
    return root;
  }

  public record CreateResult(LogisticsDocumentView response, boolean replayed) {}
}
