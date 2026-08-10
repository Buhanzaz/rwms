package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentPlanRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsEquipmentHoldReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsEquipmentHoldState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsEquipmentHoldReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the shipment state machine from creation and preparation through planning, confirmation,
 * and cancellation. It records each asset/hold effect before the existing relay processes it.
 */
@Service
@RequiredArgsConstructor
class LogisticsShipmentDocumentCoordinator {
  private static final String CREATE_SHIPMENT = "CREATE_SHIPMENT";
  private static final String PLAN_SHIPMENT = "PLAN_SHIPMENT";
  private static final String CONFIRM_SHIPMENT = "CONFIRM_SHIPMENT";
  private static final String CANCEL_SHIPMENT = "CANCEL_SHIPMENT";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsExternalAttemptRepository externalAttemptRepository;
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsEquipmentHoldReferenceRepository holdReferenceRepository;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final ShipmentFurnitureTaskService shipmentFurnitureTasks;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsEventStore eventStore;
  private final LogisticsDocumentWarehouseAdmission warehouseAdmission;
  private final LogisticsDocumentIdempotency idempotency;
  private final LogisticsDocumentReadProjection readProjection;
  private final LogisticsDocumentAttemptWriter attemptWriter;
  private final LogisticsRentalOrderBindingPolicy rentalOrderBinding;
  private final DocumentDriverTaskPlanner driverTaskPlanner;
  private final ShipmentTaskSettingsService shipmentTaskSettings;

  LogisticsDocumentCommandResult createShipment(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateShipmentRequest request) {
    requireRequest(request);
    warehouseAdmission.requireLegacyAdmissionDisabled();
    return createShipment(
        subjectId,
        idempotencyKey,
        correlationId,
        request,
        warehouseAdmission.testTicket(
            subjectId,
            CREATE_SHIPMENT,
            idempotencyKey,
            List.of(
                new AdmissionRequirement(
                    request.warehouseId(), WarehouseOperationDirection.OUTGOING))));
  }

  LogisticsDocumentCommandResult createShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateShipmentRequest request,
      AdmissionTicket admission) {
    requireRequest(request);
    String checksum =
        LogisticsCommandChecksum.sha256(CREATE_SHIPMENT, shipmentFingerprintValues(request));
    idempotency.acquireLock(subjectId, CREATE_SHIPMENT, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, CREATE_SHIPMENT, checksum);
    if (replay != null) return result(replay, true);

    validateShipmentLineInputs(request.lines());
    shipmentTaskSettings.requireWithinLimit(
        request.warehouseId(), request.lines().size(), subjectId);

    warehouseAdmission.requireAdmission(
        admission,
        List.of(
            new AdmissionRequirement(
                request.warehouseId(), WarehouseOperationDirection.OUTGOING)));

    RentalOrder shipmentOrder = rentalOrderBinding.validateShipmentBinding(request);
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            request.warehouseId(),
            request.clientId(),
            shipmentOrder == null ? request.partySnapshot() : shipmentOrder.getClient().getDisplayName(),
            request.driverSnapshot(),
            request.driverWorkerId(),
            subjectId,
            correlationId);
    document.scheduleShipment(
        request.driverSnapshot(),
        request.driverWorkerId(),
        admission.localDate(request.warehouseId()));
    document = documentRepository.saveAndFlush(document);
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(
            shipmentLines(
                document,
                request.lines(),
                shipmentOrder == null ? null : shipmentOrder.getId()));
    driverTaskPlanner.plan(document, lines);
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    startShipmentPreparation(document, lines, correlationId, subjectId, now());
    warehouseAdmission.enqueue(document, document.getWarehouseId(), admission);
    idempotency.remember(subjectId, idempotencyKey, CREATE_SHIPMENT, checksum, document);
    return result(document, false);
  }

  LogisticsDocumentCommandResult planShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      ShipmentPlanRequest request) {
    requireShipmentCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            PLAN_SHIPMENT, shipmentPlanFingerprintValues(documentId, expectedDocumentVersion, request));
    idempotency.acquireLock(subjectId, PLAN_SHIPMENT, idempotencyKey);
    LogisticsDocument replay = idempotency.replay(subjectId, idempotencyKey, PLAN_SHIPMENT, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document = readProjection.document(documentId, LogisticsDocumentType.SHIPMENT);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Shipment document version changed concurrently");
    boolean startPreparation = document.getState() == LogisticsDocumentState.DRAFT;
    if (startPreparation) {
      shipmentFurnitureTasks.requireShipmentFurnitureReady(documentId);
    }
    List<LogisticsDocumentLine> lines = readProjection.linesRequired(documentId);
    // Reconcile the order terms before dirtying the document. The term query/save can trigger an
    // automatic JPA flush; doing it after scheduleShipment would advance the document version
    // before the event-store compare-and-set and make a valid plan look concurrent.
    synchronizeRentalShipmentTerms(document, request.scheduledDate());
    try {
      document.scheduleShipment(
          request.driverSnapshot(), request.driverWorkerId(), request.scheduledDate());
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException("Shipment plan cannot be changed in its current lifecycle state");
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
    driverTaskPlanner.plan(document, lines);
    idempotency.remember(subjectId, idempotencyKey, PLAN_SHIPMENT, checksum, document);
    return result(document, false);
  }

  void synchronizeRentalShipmentTerms(LogisticsDocument shipment, LocalDate shipmentDate) {
    if (shipment == null || shipment.getRentalOrderId() == null) return;
    List<LogisticsDocumentLine> lines = readProjection.linesRequired(shipment.getId());
    List<UUID> unitIds = lines.stream().map(LogisticsDocumentLine::getAssetId).toList();
    List<RentalOrderUnitTerm> terms =
        rentalTerms.findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(
            shipment.getRentalOrderId(), unitIds);
    if (terms.size() != unitIds.size()) {
      throw new LogisticsConflictException(
          "Для каждой бытовки отгрузки должен быть задан срок аренды");
    }
    Map<UUID, RentalOrderUnitTerm> byUnit =
        terms.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    RentalOrderUnitTerm::getRentalItemId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    for (UUID unitId : unitIds) {
      RentalOrderUnitTerm term = byUnit.get(unitId);
      if (term == null) {
        throw new LogisticsConflictException("Срок аренды бытовки не найден");
      }
      if (term.getRentalShipmentId() == null) {
        term.assignShipment(shipment.getId(), shipmentDate);
      } else {
        term.rescheduleShipment(shipment.getId(), shipmentDate);
      }
    }
    rentalTerms.saveAllAndFlush(terms);
  }

  void clearRentalShipmentTerms(LogisticsDocument shipment) {
    if (shipment == null || shipment.getId() == null) return;
    List<RentalOrderUnitTerm> terms =
        rentalTerms.findAllByRentalShipmentIdOrderByRentalItemIdAsc(shipment.getId());
    if (terms.isEmpty()) return;
    for (RentalOrderUnitTerm term : terms) {
      term.clearShipment(shipment.getId());
    }
    rentalTerms.saveAllAndFlush(terms);
  }

  LogisticsDocumentCommandResult confirmShipmentPreparation(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    return confirmShipmentPreparation(
        subjectId, idempotencyKey, correlationId, documentId, expectedDocumentVersion, false);
  }

  LogisticsDocumentCommandResult confirmShipmentPreparation(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      boolean keepScheduledDate) {
    if (dependencies.productionReady()) {
      throw new IllegalStateException(
          "A production shipment confirmation requires an effective warehouse date");
    }
    return confirmShipmentPreparation(
        subjectId,
        idempotencyKey,
        correlationId,
        documentId,
        expectedDocumentVersion,
        keepScheduledDate,
        now().toLocalDate());
  }

  LogisticsDocumentCommandResult confirmShipmentPreparation(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      boolean keepScheduledDate,
      LocalDate warehouseToday) {
    if (warehouseToday == null) {
      throw new IllegalArgumentException("Effective warehouse date is required");
    }
    requireShipmentCommand(documentId, correlationId, expectedDocumentVersion, new Object());
    String checksum =
        LogisticsCommandChecksum.sha256(
            CONFIRM_SHIPMENT,
            keepScheduledDate
                ? List.of(
                    documentId.toString(),
                    Long.toString(expectedDocumentVersion),
                    Boolean.toString(true))
                : List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    idempotency.acquireLock(subjectId, CONFIRM_SHIPMENT, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, CONFIRM_SHIPMENT, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document = readProjection.document(documentId, LogisticsDocumentType.SHIPMENT);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Shipment document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.AWAITING_CONFIRMATION) {
      throw new LogisticsConflictException("Shipment preparation is not awaiting confirmation");
    }
    shipmentFurnitureTasks.requireShipmentFurnitureReady(documentId);
    if (!keepScheduledDate) {
      try {
        document.requireShipmentDepartureAllowed(warehouseToday);
      } catch (IllegalStateException exception) {
        throw new LogisticsConflictException(
            "Дата отгрузки ещё не наступила. Измените дату и повторите действие");
      }
    }
    List<LogisticsDocumentLine> lines = readProjection.linesRequired(documentId);
    OffsetDateTime now = now();
    document.beginShipmentConfirmation();
    documentRepository.saveAndFlush(document);
    for (LogisticsDocumentLine line : lines) {
      createShipmentConfirmationEffects(document, line, now);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.SHIPMENT_CONFIRMATION_STARTED,
        null);
    idempotency.remember(subjectId, idempotencyKey, CONFIRM_SHIPMENT, checksum, document);
    return result(document, false);
  }

  LogisticsDocumentCommandResult cancelShipment(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion) {
    requireShipmentCommand(documentId, correlationId, expectedDocumentVersion, new Object());
    String checksum =
        LogisticsCommandChecksum.sha256(
            CANCEL_SHIPMENT, List.of(documentId.toString(), Long.toString(expectedDocumentVersion)));
    idempotency.acquireLock(subjectId, CANCEL_SHIPMENT, idempotencyKey);
    LogisticsDocument replay = idempotency.replay(subjectId, idempotencyKey, CANCEL_SHIPMENT, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document = readProjection.document(documentId, LogisticsDocumentType.SHIPMENT);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Shipment document version changed concurrently");
    boolean cancellingDraft = document.getState() == LogisticsDocumentState.DRAFT;
    List<LogisticsDocumentLine> lines = readProjection.linesRequired(documentId);
    for (LogisticsExternalAttempt attempt :
        externalAttemptRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      String operation = attempt.getOperationType();
      boolean mutatingPreparation =
          LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_ACQUIRE.equals(operation)
              || operation.startsWith(LogisticsDocumentEffectOperations.SHIPMENT_HOLD_ACQUIRE_PREFIX);
      if (mutatingPreparation
          && attempt.getResult()
              != dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult.CONFIRMED) {
        throw new LogisticsConflictException("A shipment preparation effect has an unknown outcome");
      }
    }
    driverTaskPlanner.cancelBeforeStart(document, lines);

    OffsetDateTime now = now();
    document.beginShipmentCancellation();
    documentRepository.saveAndFlush(document);
    for (LogisticsEquipmentHoldReference hold :
        holdReferenceRepository.findAllByDocument_IdOrderByCreatedAtAsc(documentId)) {
      if (hold.getHoldState() == LogisticsEquipmentHoldState.ACTIVE
          || hold.getHoldState() == LogisticsEquipmentHoldState.COMMITTED) {
        String operation = LogisticsDocumentEffectOperations.holdReleaseOperation(hold.getHoldId());
        attemptWriter.createLineAttempt(
            document,
            hold.getLine(),
            LogisticsTargetService.ASSET,
            operation,
            LogisticsCommandChecksum.sha256(
                operation,
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
                  attemptWriter.createLineAttempt(
                      document,
                      line,
                      LogisticsTargetService.ASSET,
                      LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_RELEASE,
                      LogisticsCommandChecksum.sha256(
                          LogisticsDocumentEffectOperations.SHIPMENT_ASSET_LEASE_RELEASE,
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
      clearRentalShipmentTerms(document);
      eventStore.append(
          document,
          lines.size(),
          correlationId,
          subjectId,
          LogisticsEventType.SHIPMENT_CANCELLED,
          null);
    }
    idempotency.remember(subjectId, idempotencyKey, CANCEL_SHIPMENT, checksum, document);
    return result(document, false);
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
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.ASSET,
          LogisticsDocumentEffectOperations.SHIPMENT_ASSET_SNAPSHOT,
          LogisticsCommandChecksum.sha256(
              LogisticsDocumentEffectOperations.SHIPMENT_ASSET_SNAPSHOT,
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

  private void createShipmentConfirmationEffects(
      LogisticsDocument document, LogisticsDocumentLine line, OffsetDateTime createdAt) {
    List<LogisticsEquipmentHoldReference> holds =
        holdReferenceRepository.findAllByLine_IdOrderByCreatedAtAsc(line.getId());
    if (holds.isEmpty()) {
      LogisticsGuard guard = activeGuard(line);
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.ASSET,
          LogisticsDocumentEffectOperations.SHIPMENT_ASSET_CONFIRM,
          LogisticsCommandChecksum.sha256(
              LogisticsDocumentEffectOperations.SHIPMENT_ASSET_CONFIRM,
              List.of(
                  line.getAssetId().toString(),
                  Long.toString(guard.getObservedAssetVersion()),
                  guard.getLeaseId().toString(),
                  Long.toString(guard.getFenceToken()),
                  document.getId().toString(),
                  line.getId().toString())),
          createdAt);
      return;
    }
    for (LogisticsEquipmentHoldReference hold : holds) {
      String operation = LogisticsDocumentEffectOperations.holdCommitOperation(hold.getHoldId());
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.ASSET,
          operation,
          LogisticsCommandChecksum.sha256(
              operation,
              List.of(
                  hold.getHoldId().toString(),
                  Long.toString(hold.getHoldVersion()),
                  document.getId().toString(),
                  line.getId().toString())),
          createdAt);
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

  private LogisticsDocumentCommandResult result(LogisticsDocument document, boolean replayed) {
    return new LogisticsDocumentCommandResult(readProjection.view(document), replayed);
  }

  private static void requireShipmentCommand(
      UUID documentId, UUID correlationId, long expectedDocumentVersion, Object request) {
    if (documentId == null || correlationId == null || expectedDocumentVersion < 0) {
      throw new IllegalArgumentException("Shipment command identifiers and version are required");
    }
    requireRequest(request);
  }

  private static void requireExpectedVersion(
      LogisticsDocument document, long expectedVersion, String message) {
    if (document.getVersion() != expectedVersion) {
      throw new LogisticsConflictException(message);
    }
  }

  private static List<String> shipmentPlanFingerprintValues(
      UUID documentId, long expectedVersion, ShipmentPlanRequest request) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(Long.toString(expectedVersion));
    values.add(request.driverSnapshot().trim());
    if (request.driverWorkerId() != null) {
      values.add(request.driverWorkerId().toString());
    }
    values.add(request.scheduledDate().toString());
    return values;
  }

  private static List<LogisticsDocumentLine> shipmentLines(
      LogisticsDocument document,
      List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest> inputs,
      UUID rentalOrderId) {
    validateShipmentLineInputs(inputs);
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

  private static void validateShipmentLineInputs(
      List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest> inputs) {
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

  private static List<String> shipmentFingerprintValues(CreateShipmentRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(request.clientId() == null ? null : request.clientId().toString());
    values.add(request.rentalOrderId() == null ? null : request.rentalOrderId().toString());
    values.add(request.partySnapshot());
    values.add(request.driverSnapshot());
    if (request.driverWorkerId() != null) {
      values.add(request.driverWorkerId().toString());
    }
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

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static void requireRequest(Object request) {
    if (request == null) throw new IllegalArgumentException("Request is required");
  }
}
