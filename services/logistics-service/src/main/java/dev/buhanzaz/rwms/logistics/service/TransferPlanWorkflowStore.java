package dev.buhanzaz.rwms.logistics.service;

import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.DRIVER_TASK_PLAN;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.FURNITURE_EXECUTE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_ACTIVE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_ASSIGN;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_CANCEL;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_TRANSIT;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_ASSIGN;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_CANCEL;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_COMPLETE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_TRANSIT;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.UNIT_RELEASE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.UNIT_RESERVE;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.domain.TransferLooseFurnitureState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlan;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanSnapshot;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanWorkflowState;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.driver.service.TransferDriverTaskContentService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.TransferPlanRepository;
import dev.buhanzaz.rwms.logistics.vehicle.service.VehicleOperationalAssignmentService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the durable orchestration checkpoints between a confirmed transfer plan and asset/task-board
 * effects. Remote calls are never issued here: every command commits an external-attempt intent,
 * and the relay later returns an exact fenced receipt to one of the confirmation methods.
 */
@Service
@RequiredArgsConstructor
class TransferPlanWorkflowStore {
  private static final int MAX_TRANSIENT_RETRIES = 3;

  private final TransferPlanRepository plans;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository lines;
  private final LogisticsExternalAttemptRepository attempts;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsDocumentAttemptWriter attemptWriter;
  private final LogisticsEventStore eventStore;
  private final TransferPlanWorkflowProperties properties;
  private final DocumentDriverTaskPlanner driverTasks;
  private final TransferDriverTaskContentService driverTaskContent;
  private final VehicleOperationalAssignmentService vehicleAssignments;

  /** Creates all initial reservation and resource intents in the confirmation transaction. */
  @Transactional
  void enqueueConfirmation(
      LogisticsDocument document, TransferPlan plan, List<LogisticsDocumentLine> physicalLines) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(plan, "plan");
    List<LogisticsDocumentLine> ordered = List.copyOf(physicalLines);
    TransferPlanSnapshot snapshot = plan.snapshot();
    vehicleAssignments.createForConfirmedTransfer(
        document, snapshot, properties.resourceArrivalBuffer());
    OffsetDateTime createdAt = now();
    if (!ordered.isEmpty()) {
      createAttempt(
          document,
          LogisticsTargetService.ASSET,
          UNIT_RESERVE,
          unitReservationDigest(document, snapshot, ordered),
          createdAt);
    }
    for (TransferPlanSnapshot.LooseFurniture item : snapshot.looseFurniture()) {
      String operation = TransferPlanWorkflowOperations.furnitureReserve(item.position());
      createAttempt(
          document,
          LogisticsTargetService.ASSET,
          operation,
          furnitureReservationDigest(document, snapshot, item, operation),
          createdAt);
    }
    if (requiresTripOnlyAssignment(snapshot)) {
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          TRIP_DRIVER_ASSIGN,
          assignmentDigest(document, snapshot, AssignmentRole.TRIP, TRIP_DRIVER_ASSIGN),
          createdAt);
    }
    if (requiresRepositionAssignment(snapshot)) {
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          REPOSITION_DRIVER_ASSIGN,
          assignmentDigest(
              document, snapshot, AssignmentRole.REPOSITION, REPOSITION_DRIVER_ASSIGN),
          createdAt);
    }
    createAttempt(
        document,
        LogisticsTargetService.TASK_BOARD,
        DRIVER_TASK_PLAN,
        driverTaskDigest(document, snapshot, ordered),
        createdAt);
  }

  /**
   * Materializes one immutable work item from the exact claimed attempt. Missing prerequisites are
   * represented as an empty result so the shared claim service can defer it without remote I/O.
   */
  @Transactional
  Optional<Work> workForClaim(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    LogisticsDocument document = attempt.getDocument();
    TransferPlan plan = requiredPlan(document.getId());
    TransferPlanSnapshot snapshot = plan.snapshot();
    String operation = attempt.getOperationType();
    if (DRIVER_TASK_PLAN.equals(operation)
        && (snapshot.workflowState() == TransferPlanWorkflowState.RELEASING
            || snapshot.workflowState() == TransferPlanWorkflowState.RELEASED)) {
      attempt.confirm(
          LogisticsCommandChecksum.sha256(
              "XFER_PLAN_DRIVER_TASK_CANCELLED", List.of(document.getId().toString())),
          now());
      return Optional.empty();
    }
    if (UNIT_RESERVE.equals(operation)) {
      return Optional.of(unitReservationWork(attempt, document, snapshot));
    }
    if (operation.startsWith(TransferPlanWorkflowOperations.FURNITURE_RESERVE_PREFIX)) {
      return Optional.of(furnitureReservationWork(attempt, document, snapshot, operation));
    }
    if (TRIP_DRIVER_ASSIGN.equals(operation)) {
      return Optional.of(
          assignmentCreateWork(attempt, document, snapshot, AssignmentRole.TRIP));
    }
    if (REPOSITION_DRIVER_ASSIGN.equals(operation)) {
      return Optional.of(
          assignmentCreateWork(attempt, document, snapshot, AssignmentRole.REPOSITION));
    }
    if (DRIVER_TASK_PLAN.equals(operation)) {
      if (!reservationPrerequisitesConfirmed(document, snapshot)) return Optional.empty();
      return Optional.of(
          new DriverTaskPlanWork(
              attempt.getOperationId(),
              document.getId(),
              document.getWarehouseId(),
              snapshot.tripDriverId(),
              snapshot.plannedDepartureAt()));
    }
    if (UNIT_RELEASE.equals(operation)) {
      return Optional.of(unitReleaseWork(attempt, document));
    }
    if (operation.startsWith(TransferPlanWorkflowOperations.FURNITURE_RELEASE_PREFIX)) {
      return Optional.of(furnitureReleaseWork(attempt, document, snapshot, operation));
    }
    if (FURNITURE_EXECUTE.equals(operation)) {
      return Optional.of(furnitureExecuteWork(attempt, document, snapshot));
    }
    if (isAssignmentTransition(operation)) {
      return Optional.of(assignmentTransitionWork(attempt, snapshot, operation));
    }
    throw new IllegalArgumentException("Unsupported transfer-plan workflow operation");
  }

  /** Persists an exact stock source before the first mutating furniture reservation call. */
  @Transactional
  void freezeFurnitureSource(
      LogisticsExternalAttemptClaimService.Claim claim,
      int position,
      LogisticsDependencyGateway.EquipmentBalanceAvailability balance) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!attempt.getOperationType().equals(
        TransferPlanWorkflowOperations.furnitureReserve(position))) {
      throw new IllegalArgumentException("Furniture source claim does not match its line");
    }
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    TransferPlanSnapshot.LooseFurniture item = loose(plan.snapshot(), position);
    if (balance == null
        || balance.balanceId() == null
        || balance.version() < 0
        || !item.furnitureCatalogItemId().equals(balance.equipmentId())
        || !attempt.getDocument().getWarehouseId().equals(balance.warehouseId())
        || balance.rentalItemId() != null
        || !"STOCK".equals(balance.locationKind())
        || !balance.allocatable()
        || balance.availableStock() < item.quantity()) {
      throw new LogisticsConflictException("Недостаточно свободной мебели на складе отправления");
    }
    if (item.sourceBalanceId() == null) {
      plan.freezeLooseFurnitureSource(position, balance.balanceId(), balance.version());
      plans.saveAndFlush(plan);
      return;
    }
    if (!item.sourceBalanceId().equals(balance.balanceId())
        || !Objects.equals(item.expectedSourceBalanceVersion(), balance.version())) {
      throw new LogisticsConflictException("Источник мебели изменился во время резервирования");
    }
  }

  /** Confirms the all-or-nothing asset-owned cabin reservation batch. */
  @Transactional
  void confirmUnitReservation(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.TransferUnitReservationReceipt receipt) {
    LogisticsExternalAttempt attempt = requireAttempt(claim, UNIT_RESERVE);
    LogisticsDocument document = attempt.getDocument();
    List<LogisticsDocumentLine> documentLines = lockedLines(document.getId());
    if (receipt == null
        || !document.getId().equals(receipt.transferId())
        || receipt.lines() == null
        || receipt.lines().size() != documentLines.size()) {
      throw malformed("Asset-service returned an incomplete cabin reservation batch");
    }
    Map<UUID, LogisticsDependencyGateway.TransferUnitReservationLineReceipt> byLine =
        new HashMap<>();
    for (var value : receipt.lines()) {
      if (value == null
          || value.lineId() == null
          || value.rentalItemId() == null
          || value.reservationId() == null
          || value.version() < 0
          || value.currentRentalItemVersion() < 0
          || !"ACTIVE".equals(value.state())
          || byLine.put(value.lineId(), value) != null) {
        throw malformed("Asset-service returned an invalid cabin reservation row");
      }
    }
    for (LogisticsDocumentLine line : documentLines) {
      var value = byLine.get(line.getId());
      if (value == null || !line.getAssetId().equals(value.rentalItemId())) {
        throw malformed("Asset-service cabin reservation does not match the transfer line");
      }
      line.attachTransferUnitReservation(
          value.reservationId(), value.version(), value.currentRentalItemVersion());
    }
    lines.saveAllAndFlush(documentLines);
    attempt.confirm(unitReceiptDigest(receipt), now());
    afterReservationProgress(document);
  }

  /** Confirms one loose-furniture hold while preserving its exact asset-owned identity. */
  @Transactional
  void confirmFurnitureReservation(
      LogisticsExternalAttemptClaimService.Claim claim,
      int position,
      LogisticsDependencyGateway.EquipmentMovementReservation receipt) {
    String operation = TransferPlanWorkflowOperations.furnitureReserve(position);
    LogisticsExternalAttempt attempt = requireAttempt(claim, operation);
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    TransferPlanSnapshot.LooseFurniture item = loose(plan.snapshot(), position);
    if (receipt == null
        || receipt.reservationId() == null
        || receipt.version() < 0
        || !attempt.getDocument().getId().equals(receipt.movementId())
        || !item.lineId().equals(receipt.lineId())
        || !item.furnitureCatalogItemId().equals(receipt.equipmentId())
        || !attempt.getDocument().getWarehouseId().equals(receipt.sourceWarehouseId())
        || !item.sourceBalanceId().equals(receipt.sourceBalanceId())
        || item.quantity() != receipt.quantity()
        || !"STOCK".equals(receipt.sourceLocationKind())
        || !"ACTIVE".equals(receipt.state())) {
      throw malformed("Asset-service returned a mismatched furniture reservation");
    }
    plan.attachLooseFurnitureReservation(
        position, receipt.reservationId(), receipt.version(), receipt.sourceBalanceId());
    plans.saveAndFlush(plan);
    attempt.confirm(furnitureReceiptDigest(receipt), now());
    afterReservationProgress(attempt.getDocument());
  }

  /** Confirms one task-board assignment without conflating trip execution and repositioning. */
  @Transactional
  void confirmAssignment(
      LogisticsExternalAttemptClaimService.Claim claim,
      AssignmentRole role,
      LogisticsDependencyGateway.WorkerOperationalAssignment receipt) {
    String operation =
        role == AssignmentRole.TRIP ? TRIP_DRIVER_ASSIGN : REPOSITION_DRIVER_ASSIGN;
    LogisticsExternalAttempt attempt = requireAttempt(claim, operation);
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    TransferPlanSnapshot snapshot = plan.snapshot();
    UUID expectedWorker = assignmentWorker(snapshot, role);
    if (receipt == null
        || receipt.assignmentId() == null
        || receipt.version() < 0
        || !attempt.getDocument().getId().equals(receipt.transferId())
        || !expectedWorker.equals(receipt.workerId())
        || !attempt.getDocument().getWarehouseId().equals(receipt.sourceWarehouseId())
        || !attempt
            .getDocument()
            .getDestinationWarehouseId()
            .equals(receipt.destinationWarehouseId())
        || !"PLANNED".equals(receipt.status())) {
      throw malformed("Task-board returned a mismatched operational assignment");
    }
    if (role == AssignmentRole.TRIP) {
      plan.attachTripDriverAssignment(
          receipt.assignmentId(), receipt.version(), receipt.status());
      if (sameAssignment(snapshot.tripDriverAssignment(), snapshot.repositionedDriverAssignment())) {
        plan.attachRepositionedDriverAssignment(
            receipt.assignmentId(), receipt.version(), receipt.status());
      }
    } else {
      plan.attachRepositionedDriverAssignment(
          receipt.assignmentId(), receipt.version(), receipt.status());
      if (Objects.equals(snapshot.tripDriverId(), snapshot.driverReposition().resourceId())) {
        plan.attachTripDriverAssignment(
            receipt.assignmentId(), receipt.version(), receipt.status());
      }
    }
    plans.saveAndFlush(plan);
    attempt.confirm(assignmentReceiptDigest(receipt), now());
    afterReservationProgress(attempt.getDocument());
  }

  /** Freezes the trip-driver display snapshot before the existing driver-task planner runs. */
  @Transactional
  void assignTripDriver(UUID documentId, LogisticsDependencyGateway.WarehouseDriverIdentity driver) {
    LogisticsDocument document = requiredDocument(documentId);
    TransferPlanSnapshot snapshot = requiredPlan(documentId).snapshot();
    if (snapshot.tripDriverId() == null) return;
    if (driver == null
        || !snapshot.tripDriverId().equals(driver.workerId())
        || driver.displayName() == null
        || driver.displayName().isBlank()) {
      throw new LogisticsConflictException("Назначенный водитель недоступен на складе отправления");
    }
    document.assignTransferTripDriver(driver.workerId(), driver.displayName());
    documents.saveAndFlush(document);
    eventStore.append(
        document,
        Math.max(1, requiredPlan(documentId).auditLineCount()),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.TRANSFER_PLAN_UPDATED,
        "TRIP_DRIVER_RESOLVED");
  }

  /** Invokes the existing grouped route-task planner from the durable workflow attempt. */
  @Transactional
  void planDriverTask(UUID documentId) {
    LogisticsDocument document = requiredDocument(documentId);
    List<LogisticsDocumentLine> documentLines = lockedLines(documentId);
    TransferPlanSnapshot snapshot = requiredPlan(documentId).snapshot();
    var workerContent = driverTaskContent.build(document, snapshot);
    if (documentLines.isEmpty()) {
      long totalQuantity =
          snapshot.looseFurniture().stream()
              .mapToLong(TransferPlanSnapshot.LooseFurniture::quantity)
              .reduce(0, Math::addExact);
      if (!snapshot.looseFurniture().isEmpty() && totalQuantity < 1) {
        throw new LogisticsConflictException("В перемещении отсутствует груз для водителя");
      }
      String cargoSummary =
          snapshot.looseFurniture().isEmpty()
              ? resourceOnlySummary(snapshot)
              : "Мебель: %d поз., %d ед."
                  .formatted(snapshot.looseFurniture().size(), totalQuantity);
      driverTasks.planTransferCargo(document, cargoSummary, workerContent);
      return;
    }
    driverTasks.plan(document, documentLines, workerContent);
  }

  /** Confirms the existing grouped driver-task planner after every reservation is ready. */
  @Transactional
  void confirmDriverTaskPlan(LogisticsExternalAttemptClaimService.Claim claim) {
    LogisticsExternalAttempt attempt = requireAttempt(claim, DRIVER_TASK_PLAN);
    LogisticsDocument document = attempt.getDocument();
    TransferPlan plan = requiredPlan(document.getId());
    if (!reservationPrerequisitesConfirmed(document, plan.snapshot())) {
      throw new LogisticsConflictException("Transfer reservations are not ready for driver work");
    }
    attempt.confirm(
        LogisticsCommandChecksum.sha256(
            "XFER_PLAN_DRIVER_TASK_RESPONSE", List.of(document.getId().toString())),
        now());
    plan.markReserved();
    plans.saveAndFlush(plan);
  }

  /** Enqueues in-transit assignment transitions after every cabin has physically departed. */
  @Transactional
  void beginTransit(LogisticsDocument document) {
    TransferPlan plan = plans.findForUpdateByDocumentId(document.getId()).orElse(null);
    if (plan == null) return;
    TransferPlanSnapshot snapshot = plan.snapshot();
    plan.markInTransit();
    vehicleAssignments.beginTransit(document.getId());
    plans.saveAndFlush(plan);
    OffsetDateTime createdAt = now();
    if (hasAssignment(snapshot.tripDriverAssignment())) {
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          TRIP_DRIVER_TRANSIT,
          transitionDigest(snapshot.tripDriverAssignment(), "IN_TRANSIT", TRIP_DRIVER_TRANSIT),
          createdAt);
    }
    if (hasDistinctRepositionAssignment(snapshot)) {
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          REPOSITION_DRIVER_TRANSIT,
          transitionDigest(
              snapshot.repositionedDriverAssignment(),
              "IN_TRANSIT",
              REPOSITION_DRIVER_TRANSIT),
          createdAt);
    }
  }

  /** Starts post-unloading furniture and driver effects, completing immediately when none exist. */
  @Transactional
  void requestCompletion(LogisticsDocument document) {
    TransferPlan plan = plans.findForUpdateByDocumentId(document.getId()).orElse(null);
    if (plan == null) {
      completeDocument(document, lockedLines(document.getId()));
      return;
    }
    plan.beginCompletion();
    vehicleAssignments.arrive(document.getId());
    plans.saveAndFlush(plan);
    TransferPlanSnapshot snapshot = plan.snapshot();
    OffsetDateTime createdAt = now();
    if (snapshot.looseFurniture().stream()
        .anyMatch(item -> item.state() == TransferLooseFurnitureState.IN_TRANSIT)) {
      createAttempt(
          document,
          LogisticsTargetService.ASSET,
          FURNITURE_EXECUTE,
          furnitureExecutionDigest(document, snapshot),
          createdAt);
    }
    if (hasAssignment(snapshot.tripDriverAssignment())) {
      String target =
          sameAssignment(snapshot.tripDriverAssignment(), snapshot.repositionedDriverAssignment())
              ? "ACTIVE"
              : "COMPLETED";
      String operation =
          "ACTIVE".equals(target) ? REPOSITION_DRIVER_ACTIVE : TRIP_DRIVER_COMPLETE;
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          operation,
          transitionDigest(snapshot.tripDriverAssignment(), target, operation),
          createdAt);
    }
    if (hasDistinctRepositionAssignment(snapshot)) {
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          REPOSITION_DRIVER_ACTIVE,
          transitionDigest(
              snapshot.repositionedDriverAssignment(),
              "ACTIVE",
              REPOSITION_DRIVER_ACTIVE),
          createdAt);
    }
    maybeFinishCompletion(document, plan);
  }

  /** Begins pre-start compensation; late reservation receipts enqueue their own release attempts. */
  @Transactional
  void requestCancellation(LogisticsDocument document) {
    TransferPlan plan = plans.findForUpdateByDocumentId(document.getId()).orElse(null);
    if (plan == null) {
      finishCancellation(document, lockedLines(document.getId()), null);
      return;
    }
    plan.beginRelease();
    vehicleAssignments.cancelBeforeStart(document.getId());
    plans.saveAndFlush(plan);
    enqueueKnownReleases(document, plan.snapshot());
    maybeFinishCancellation(document, plan);
  }

  /** Confirms a batch release of every still-active cabin reservation. */
  @Transactional
  void confirmUnitRelease(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.TransferUnitReservationReceipt receipt) {
    LogisticsExternalAttempt attempt = requireAttempt(claim, UNIT_RELEASE);
    List<LogisticsDocumentLine> documentLines = lockedLines(attempt.getDocument().getId());
    Map<UUID, LogisticsDependencyGateway.TransferUnitReservationLineReceipt> byLine =
        receipt == null || receipt.lines() == null
            ? Map.of()
            : receipt.lines().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        LogisticsDependencyGateway.TransferUnitReservationLineReceipt::lineId,
                        value -> value));
    for (LogisticsDocumentLine line : documentLines) {
      if (!"ACTIVE".equals(line.getTransferUnitReservationState())) continue;
      var value = byLine.get(line.getId());
      if (value == null
          || !line.getAssetId().equals(value.rentalItemId())
          || !"RELEASED".equals(value.state())) {
        throw malformed("Asset-service returned an incomplete cabin release batch");
      }
      line.releaseTransferUnitReservation(
          value.reservationId(), value.version(), value.currentRentalItemVersion());
    }
    lines.saveAllAndFlush(documentLines);
    attempt.confirm(unitReceiptDigest(receipt), now());
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    maybeFinishCancellation(attempt.getDocument(), plan);
  }

  /** Confirms one loose-furniture release receipt. */
  @Transactional
  void confirmFurnitureRelease(
      LogisticsExternalAttemptClaimService.Claim claim,
      int position,
      LogisticsDependencyGateway.EquipmentMovementReservation receipt) {
    String operation = TransferPlanWorkflowOperations.furnitureRelease(position);
    LogisticsExternalAttempt attempt = requireAttempt(claim, operation);
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    TransferPlanSnapshot.LooseFurniture item = loose(plan.snapshot(), position);
    if (receipt == null
        || !item.reservationId().equals(receipt.reservationId())
        || !"RELEASED".equals(receipt.state())) {
      throw malformed("Asset-service returned a mismatched furniture release");
    }
    plan.releaseLooseFurniture(position, receipt.version());
    plans.saveAndFlush(plan);
    attempt.confirm(furnitureReceiptDigest(receipt), now());
    maybeFinishCancellation(attempt.getDocument(), plan);
  }

  /** Confirms the atomic destination-stock execution of independent furniture cargo. */
  @Transactional
  void confirmFurnitureExecution(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.EquipmentMovementExecution receipt) {
    LogisticsExternalAttempt attempt = requireAttempt(claim, FURNITURE_EXECUTE);
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    TransferPlanSnapshot snapshot = plan.snapshot();
    if (receipt == null
        || !attempt.getDocument().getId().equals(receipt.movementId())
        || receipt.lines() == null) {
      throw malformed("Asset-service returned an invalid furniture execution batch");
    }
    Map<UUID, LogisticsDependencyGateway.EquipmentMovementExecutionLine> byLine =
        receipt.lines().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    LogisticsDependencyGateway.EquipmentMovementExecutionLine::lineId,
                    value -> value));
    for (TransferPlanSnapshot.LooseFurniture item : snapshot.looseFurniture()) {
      if (item.state() != TransferLooseFurnitureState.IN_TRANSIT) continue;
      var value = byLine.get(item.lineId());
      if (value == null || !item.reservationId().equals(value.reservationId())) {
        throw malformed("Asset-service omitted a furniture execution row");
      }
      plan.executeLooseFurniture(item.position(), value.reservationVersion());
    }
    plans.saveAndFlush(plan);
    attempt.confirm(furnitureExecutionReceiptDigest(receipt), now());
    maybeFinishCompletion(attempt.getDocument(), plan);
  }

  /** Confirms a version-fenced assignment lifecycle transition. */
  @Transactional
  void confirmAssignmentTransition(
      LogisticsExternalAttemptClaimService.Claim claim,
      LogisticsDependencyGateway.WorkerOperationalAssignment receipt) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!isAssignmentTransition(attempt.getOperationType())) {
      throw new IllegalArgumentException("Assignment transition claim is invalid");
    }
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    TransferPlanSnapshot snapshot = plan.snapshot();
    AssignmentRole role = transitionRole(attempt.getOperationType());
    TransferPlanSnapshot.Assignment expected = assignment(snapshot, role);
    String target = transitionTarget(attempt.getOperationType());
    if (receipt == null
        || !expected.assignmentId().equals(receipt.assignmentId())
        || receipt.version() < Objects.requireNonNull(expected.version())
        || !target.equals(receipt.status())) {
      throw malformed("Task-board returned a mismatched assignment transition");
    }
    if (role == AssignmentRole.TRIP) {
      plan.attachTripDriverAssignment(
          receipt.assignmentId(), receipt.version(), receipt.status());
    } else {
      plan.attachRepositionedDriverAssignment(
          receipt.assignmentId(), receipt.version(), receipt.status());
      if (sameAssignment(snapshot.tripDriverAssignment(), snapshot.repositionedDriverAssignment())) {
        plan.attachTripDriverAssignment(
            receipt.assignmentId(), receipt.version(), receipt.status());
      }
    }
    plans.saveAndFlush(plan);
    attempt.confirm(assignmentReceiptDigest(receipt), now());
    if (plan.snapshot().workflowState() == TransferPlanWorkflowState.RELEASING) {
      maybeFinishCancellation(attempt.getDocument(), plan);
    } else if (plan.snapshot().workflowState() == TransferPlanWorkflowState.COMPLETING) {
      maybeFinishCompletion(attempt.getDocument(), plan);
    }
  }

  /** Applies the shared bounded retry/rejection policy to a still-current transfer-plan attempt. */
  @Transactional
  void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    TransferPlan plan = requiredPlan(attempt.getDocument().getId());
    OffsetDateTime failedAt = now();
    String code = "TRANSFER_PLAN_" + exception.kind().name();
    String digest =
        LogisticsCommandChecksum.sha256(
            "TRANSFER_PLAN_FAILURE",
            List.of(attempt.getOperationType(), exception.kind().name()));
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      attempt.reject(digest, failedAt);
      plan.conflict(code);
      plans.saveAndFlush(plan);
      return;
    }
    if (exception.kind() == LogisticsDependencyException.FailureKind.CONFIGURATION
        || attempt.getRetryCount() >= MAX_TRANSIENT_RETRIES) {
      attempt.requireReconciliation(digest, failedAt);
      plan.requireReconciliation(code);
      plans.saveAndFlush(plan);
      return;
    }
    attempt.retry(failedAt.plusSeconds(retryDelaySeconds(attempt.getRetryCount())));
  }

  private UnitReservationWork unitReservationWork(
      LogisticsExternalAttempt attempt,
      LogisticsDocument document,
      TransferPlanSnapshot snapshot) {
    Map<UUID, TransferPlanSnapshot.CargoGroup> byAsset = allocationGroups(snapshot);
    List<LogisticsDependencyGateway.TransferUnitReservationRequestLine> requests =
        lockedLines(document.getId()).stream()
            .map(
                line -> {
                  TransferPlanSnapshot.CargoGroup group = byAsset.get(line.getAssetId());
                  if (group == null) {
                    throw malformed("Transfer line has no confirmed cargo requirement");
                  }
                  return new LogisticsDependencyGateway.TransferUnitReservationRequestLine(
                      line.getId(),
                      line.getAssetId(),
                      line.getAssetVersion(),
                      group.rentalTypeId(),
                      group.dimensionId(),
                      group.finishingId(),
                      group.characteristicIds(),
                      group.linoleum());
                })
            .toList();
    return new UnitReservationWork(
        attempt.getOperationId(), document.getId(), document.getWarehouseId(), requests);
  }

  private Work furnitureReservationWork(
      LogisticsExternalAttempt attempt,
      LogisticsDocument document,
      TransferPlanSnapshot snapshot,
      String operation) {
    int position = suffixPosition(operation, TransferPlanWorkflowOperations.FURNITURE_RESERVE_PREFIX);
    TransferPlanSnapshot.LooseFurniture item = loose(snapshot, position);
    if (item.sourceBalanceId() == null || item.expectedSourceBalanceVersion() == null) {
      return new PrepareFurnitureReservationWork(
          attempt.getOperationId(),
          document.getId(),
          document.getWarehouseId(),
          position,
          item.furnitureCatalogItemId(),
          item.quantity());
    }
    return new FurnitureReservationWork(
        attempt.getOperationId(),
        document.getId(),
        document.getWarehouseId(),
        position,
        item.lineId(),
        item.furnitureCatalogItemId(),
        item.quantity(),
        item.sourceBalanceId(),
        item.expectedSourceBalanceVersion(),
        snapshot.plannedArrivalAt().plus(properties.reservationGrace()));
  }

  private AssignmentCreateWork assignmentCreateWork(
      LogisticsExternalAttempt attempt,
      LogisticsDocument document,
      TransferPlanSnapshot snapshot,
      AssignmentRole role) {
    OffsetDateTime effectiveFrom =
        snapshot.plannedArrivalAt().plus(properties.resourceArrivalBuffer());
    if (role == AssignmentRole.TRIP) {
      return new AssignmentCreateWork(
          attempt.getOperationId(),
          role,
          document.getId(),
          snapshot.tripDriverId(),
          document.getWarehouseId(),
          document.getDestinationWarehouseId(),
          "TRIP_ONLY",
          snapshot.plannedDepartureAt(),
          effectiveFrom,
          effectiveFrom);
    }
    return new AssignmentCreateWork(
        attempt.getOperationId(),
        role,
        document.getId(),
        snapshot.driverReposition().resourceId(),
        document.getWarehouseId(),
        document.getDestinationWarehouseId(),
        snapshot.driverReposition().mode().name(),
        snapshot.plannedDepartureAt(),
        effectiveFrom,
        snapshot.driverReposition().until());
  }

  private UnitReleaseWork unitReleaseWork(
      LogisticsExternalAttempt attempt, LogisticsDocument document) {
    List<LogisticsDependencyGateway.TransferUnitReservationReleaseLine> requests =
        lockedLines(document.getId()).stream()
            .filter(line -> "ACTIVE".equals(line.getTransferUnitReservationState()))
            .map(
                line ->
                    new LogisticsDependencyGateway.TransferUnitReservationReleaseLine(
                        line.getTransferUnitReservationId(),
                        line.getId(),
                        line.getAssetId(),
                        Objects.requireNonNull(line.getTransferUnitReservationVersion())))
            .toList();
    return new UnitReleaseWork(attempt.getOperationId(), document.getId(), requests);
  }

  private FurnitureReleaseWork furnitureReleaseWork(
      LogisticsExternalAttempt attempt,
      LogisticsDocument document,
      TransferPlanSnapshot snapshot,
      String operation) {
    int position = suffixPosition(operation, TransferPlanWorkflowOperations.FURNITURE_RELEASE_PREFIX);
    TransferPlanSnapshot.LooseFurniture item = loose(snapshot, position);
    return new FurnitureReleaseWork(
        attempt.getOperationId(),
        document.getId(),
        position,
        Objects.requireNonNull(item.lineId()),
        Objects.requireNonNull(item.reservationId()),
        Objects.requireNonNull(item.reservationVersion()));
  }

  private FurnitureExecuteWork furnitureExecuteWork(
      LogisticsExternalAttempt attempt,
      LogisticsDocument document,
      TransferPlanSnapshot snapshot) {
    List<LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine> requests =
        snapshot.looseFurniture().stream()
            .filter(item -> item.state() == TransferLooseFurnitureState.IN_TRANSIT)
            .map(
                item ->
                    new LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine(
                        item.reservationId(),
                        Objects.requireNonNull(item.reservationVersion()),
                        item.lineId(),
                        document.getDestinationWarehouseId(),
                        null,
                        "STOCK"))
            .toList();
    return new FurnitureExecuteWork(attempt.getOperationId(), document.getId(), requests);
  }

  private AssignmentTransitionWork assignmentTransitionWork(
      LogisticsExternalAttempt attempt,
      TransferPlanSnapshot snapshot,
      String operation) {
    AssignmentRole role = transitionRole(operation);
    TransferPlanSnapshot.Assignment assignment = assignment(snapshot, role);
    return new AssignmentTransitionWork(
        attempt.getOperationId(),
        role,
        assignment.assignmentId(),
        Objects.requireNonNull(assignment.version()),
        transitionTarget(operation));
  }

  private void afterReservationProgress(LogisticsDocument document) {
    TransferPlan plan = requiredPlan(document.getId());
    if (plan.snapshot().workflowState() == TransferPlanWorkflowState.RELEASING) {
      enqueueKnownReleases(document, plan.snapshot());
      maybeFinishCancellation(document, plan);
    }
  }

  private void enqueueKnownReleases(LogisticsDocument document, TransferPlanSnapshot snapshot) {
    OffsetDateTime createdAt = now();
    if (lockedLines(document.getId()).stream()
        .anyMatch(line -> "ACTIVE".equals(line.getTransferUnitReservationState()))) {
      createAttempt(
          document,
          LogisticsTargetService.ASSET,
          UNIT_RELEASE,
          unitReleaseDigest(document),
          createdAt);
    }
    for (TransferPlanSnapshot.LooseFurniture item : snapshot.looseFurniture()) {
      if (item.state() != TransferLooseFurnitureState.RESERVED) continue;
      String operation = TransferPlanWorkflowOperations.furnitureRelease(item.position());
      createAttempt(
          document,
          LogisticsTargetService.ASSET,
          operation,
          furnitureReleaseDigest(document, item, operation),
          createdAt);
    }
    if (hasAssignment(snapshot.tripDriverAssignment())
        && "PLANNED".equals(snapshot.tripDriverAssignment().status())) {
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          TRIP_DRIVER_CANCEL,
          transitionDigest(snapshot.tripDriverAssignment(), "CANCELLED", TRIP_DRIVER_CANCEL),
          createdAt);
    }
    if (hasDistinctRepositionAssignment(snapshot)
        && "PLANNED".equals(snapshot.repositionedDriverAssignment().status())) {
      createAttempt(
          document,
          LogisticsTargetService.TASK_BOARD,
          REPOSITION_DRIVER_CANCEL,
          transitionDigest(
              snapshot.repositionedDriverAssignment(),
              "CANCELLED",
              REPOSITION_DRIVER_CANCEL),
          createdAt);
    }
  }

  private boolean reservationPrerequisitesConfirmed(
      LogisticsDocument document, TransferPlanSnapshot snapshot) {
    List<LogisticsDocumentLine> documentLines = lockedLines(document.getId());
    if (!documentLines.isEmpty()
        && documentLines.stream()
            .anyMatch(line -> !"ACTIVE".equals(line.getTransferUnitReservationState()))) {
      return false;
    }
    if (snapshot.looseFurniture().stream()
        .anyMatch(item -> item.state() != TransferLooseFurnitureState.RESERVED)) {
      return false;
    }
    if (requiresTripOnlyAssignment(snapshot)
        && !planned(snapshot.tripDriverAssignment())) return false;
    if (requiresRepositionAssignment(snapshot)
        && !planned(snapshot.repositionedDriverAssignment())) return false;
    return vehicleAssignments.confirmationReady(
        document, snapshot, properties.resourceArrivalBuffer());
  }

  private void maybeFinishCompletion(LogisticsDocument document, TransferPlan plan) {
    TransferPlanSnapshot snapshot = plan.snapshot();
    if (snapshot.workflowState() != TransferPlanWorkflowState.COMPLETING) return;
    if (snapshot.looseFurniture().stream()
        .anyMatch(item -> item.state() != TransferLooseFurnitureState.EXECUTED)) return;
    if (hasAssignment(snapshot.tripDriverAssignment())) {
      String expected =
          sameAssignment(snapshot.tripDriverAssignment(), snapshot.repositionedDriverAssignment())
              ? "ACTIVE"
              : "COMPLETED";
      if (!expected.equals(snapshot.tripDriverAssignment().status())) return;
    }
    if (hasDistinctRepositionAssignment(snapshot)
        && !"ACTIVE".equals(snapshot.repositionedDriverAssignment().status())) return;
    if (!vehicleAssignments.arrivalApplied(document.getId())) return;
    plan.completeWorkflow();
    plans.saveAndFlush(plan);
    completeDocument(document, lockedLines(document.getId()));
  }

  private void completeDocument(
      LogisticsDocument document, List<LogisticsDocumentLine> documentLines) {
    if (document.getState() == LogisticsDocumentState.COMPLETED) return;
    document.completeTransfer();
    documents.saveAndFlush(document);
    eventStore.append(
        document,
        eventLineCount(document, documentLines),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.TRANSFER_COMPLETED,
        null);
  }

  private void maybeFinishCancellation(LogisticsDocument document, TransferPlan plan) {
    TransferPlanSnapshot snapshot = plan.snapshot();
    if (snapshot.workflowState() != TransferPlanWorkflowState.RELEASING) return;
    if (!acquisitionAttemptsTerminal(document.getId(), snapshot)) return;
    if (lockedLines(document.getId()).stream()
        .anyMatch(line -> "ACTIVE".equals(line.getTransferUnitReservationState()))) return;
    if (snapshot.looseFurniture().stream()
        .anyMatch(item -> item.state() == TransferLooseFurnitureState.RESERVED)) return;
    if (hasAssignment(snapshot.tripDriverAssignment())
        && !"CANCELLED".equals(snapshot.tripDriverAssignment().status())) return;
    if (hasDistinctRepositionAssignment(snapshot)
        && !"CANCELLED".equals(snapshot.repositionedDriverAssignment().status())) return;
    if (!vehicleAssignments.cancellationApplied(document.getId())) return;
    plan.finishRelease();
    plans.saveAndFlush(plan);
    finishCancellation(document, lockedLines(document.getId()), plan);
  }

  private void finishCancellation(
      LogisticsDocument document,
      List<LogisticsDocumentLine> documentLines,
      TransferPlan ignoredPlan) {
    if (document.getState() == LogisticsDocumentState.CANCELLED) return;
    document.cancelTransfer();
    for (LogisticsDocumentLine line : documentLines) {
      if (line.getState() == LogisticsLineState.PENDING) line.cancel();
    }
    lines.saveAllAndFlush(documentLines);
    documents.saveAndFlush(document);
    eventStore.append(
        document,
        eventLineCount(document, documentLines),
        document.getCorrelationId(),
        document.getRequestedBySubjectId(),
        LogisticsEventType.TRANSFER_CANCELLED,
        null);
  }

  private boolean acquisitionAttemptsTerminal(
      UUID documentId, TransferPlanSnapshot snapshot) {
    if (snapshot.state()
        == dev.buhanzaz.rwms.logistics.domain.TransferPlanState.DRAFT) return true;
    List<String> operations = new ArrayList<>();
    if (snapshot.totalCabinCount() > 0) operations.add(UNIT_RESERVE);
    snapshot.looseFurniture().forEach(
        item -> operations.add(TransferPlanWorkflowOperations.furnitureReserve(item.position())));
    if (requiresTripOnlyAssignment(snapshot)) operations.add(TRIP_DRIVER_ASSIGN);
    if (requiresRepositionAssignment(snapshot)) operations.add(REPOSITION_DRIVER_ASSIGN);
    return operations.stream().allMatch(operation -> terminal(documentId, operation));
  }

  private boolean terminal(UUID documentId, String operation) {
    return attempts
        .findByDocument_IdAndLineIsNullAndOperationType(documentId, operation)
        .map(value -> value.getResult() != LogisticsExternalAttemptResult.PENDING
            && value.getResult() != LogisticsExternalAttemptResult.RETRY)
        .orElse(false);
  }

  /**
   * Preserves the non-zero event-envelope cardinality for a transfer whose only concrete facts are
   * driver or vehicle resources; it does not fabricate a physical cargo line.
   */
  private int eventLineCount(
      LogisticsDocument document, List<LogisticsDocumentLine> documentLines) {
    int count =
        plans
            .findByDocument_Id(document.getId())
            .map(TransferPlan::auditLineCount)
            .orElse(documentLines.size());
    return Math.max(1, count);
  }

  private void createAttempt(
      LogisticsDocument document,
      LogisticsTargetService target,
      String operation,
      String digest,
      OffsetDateTime createdAt) {
    if (attempts
        .findByDocument_IdAndLineIsNullAndOperationType(document.getId(), operation)
        .isPresent()) return;
    attemptWriter.createDocumentAttempt(document, target, operation, digest, createdAt);
  }

  private LogisticsExternalAttempt requireAttempt(
      LogisticsExternalAttemptClaimService.Claim claim, String operation) {
    LogisticsExternalAttempt attempt = claims.requireCurrentAttempt(claim);
    if (!operation.equals(attempt.getOperationType())) {
      throw new IllegalArgumentException("Transfer-plan external attempt type is invalid");
    }
    return attempt;
  }

  private TransferPlan requiredPlan(UUID documentId) {
    return plans
        .findForUpdateByDocumentId(documentId)
        .orElseThrow(LogisticsNotFoundException::new);
  }

  private LogisticsDocument requiredDocument(UUID documentId) {
    return documents.findForUpdate(documentId).orElseThrow(LogisticsNotFoundException::new);
  }

  private List<LogisticsDocumentLine> lockedLines(UUID documentId) {
    return lines.findAllForUpdateByDocumentIdIn(List.of(documentId));
  }

  private static Map<UUID, TransferPlanSnapshot.CargoGroup> allocationGroups(
      TransferPlanSnapshot snapshot) {
    Map<UUID, TransferPlanSnapshot.CargoGroup> result = new HashMap<>();
    for (TransferPlanSnapshot.CargoGroup group : snapshot.cabinGroups()) {
      for (TransferPlanSnapshot.Allocation allocation : group.allocatedCabins()) {
        if (result.put(allocation.assetId(), group) != null) {
          throw new IllegalStateException("Transfer cabin allocation is duplicated");
        }
      }
    }
    return Map.copyOf(result);
  }

  private static TransferPlanSnapshot.LooseFurniture loose(
      TransferPlanSnapshot snapshot, int position) {
    return snapshot.looseFurniture().stream()
        .filter(item -> item.position() == position)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Loose furniture position is unknown"));
  }

  private static int suffixPosition(String operation, String prefix) {
    try {
      int value = Integer.parseInt(operation.substring(prefix.length()));
      if (value < 1 || value > 100) throw new NumberFormatException();
      return value;
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Furniture workflow operation is malformed", exception);
    }
  }

  private static String resourceOnlySummary(TransferPlanSnapshot snapshot) {
    List<String> resources = new ArrayList<>();
    if (snapshot.tripDriverId() != null) resources.add("водитель рейса");
    if (snapshot.tripVehicleId() != null) resources.add("автомобиль рейса");
    if (snapshot.driverReposition().mode() != TransferResourceRepositionMode.NONE) {
      resources.add("перемещение водителя");
    }
    if (snapshot.vehicleReposition().mode() != TransferResourceRepositionMode.NONE) {
      resources.add("перемещение автомобиля");
    }
    if (resources.isEmpty()) {
      throw new LogisticsConflictException("В перемещении отсутствует груз или ресурс");
    }
    return "Ресурсное перемещение: " + String.join(", ", resources);
  }

  private static boolean requiresTripOnlyAssignment(TransferPlanSnapshot snapshot) {
    return snapshot.tripDriverId() != null
        && !Objects.equals(snapshot.tripDriverId(), snapshot.driverReposition().resourceId());
  }

  private static boolean requiresRepositionAssignment(TransferPlanSnapshot snapshot) {
    return snapshot.driverReposition().mode() != TransferResourceRepositionMode.NONE;
  }

  private static UUID assignmentWorker(
      TransferPlanSnapshot snapshot, AssignmentRole role) {
    UUID result =
        role == AssignmentRole.TRIP
            ? snapshot.tripDriverId()
            : snapshot.driverReposition().resourceId();
    return Objects.requireNonNull(result, "assignment worker");
  }

  private static TransferPlanSnapshot.Assignment assignment(
      TransferPlanSnapshot snapshot, AssignmentRole role) {
    return role == AssignmentRole.TRIP
        ? snapshot.tripDriverAssignment()
        : snapshot.repositionedDriverAssignment();
  }

  private static boolean hasAssignment(TransferPlanSnapshot.Assignment assignment) {
    return assignment != null && assignment.assignmentId() != null;
  }

  private static boolean planned(TransferPlanSnapshot.Assignment assignment) {
    return hasAssignment(assignment) && "PLANNED".equals(assignment.status());
  }

  private static boolean hasDistinctRepositionAssignment(TransferPlanSnapshot snapshot) {
    return hasAssignment(snapshot.repositionedDriverAssignment())
        && !sameAssignment(
            snapshot.tripDriverAssignment(), snapshot.repositionedDriverAssignment());
  }

  private static boolean sameAssignment(
      TransferPlanSnapshot.Assignment first, TransferPlanSnapshot.Assignment second) {
    return hasAssignment(first)
        && hasAssignment(second)
        && first.assignmentId().equals(second.assignmentId());
  }

  private static boolean isAssignmentTransition(String operation) {
    return Set.of(
            TRIP_DRIVER_TRANSIT,
            REPOSITION_DRIVER_TRANSIT,
            TRIP_DRIVER_COMPLETE,
            REPOSITION_DRIVER_ACTIVE,
            TRIP_DRIVER_CANCEL,
            REPOSITION_DRIVER_CANCEL)
        .contains(operation);
  }

  private static AssignmentRole transitionRole(String operation) {
    return operation.startsWith("XFER_PLAN_TRIP_")
        ? AssignmentRole.TRIP
        : AssignmentRole.REPOSITION;
  }

  private static String transitionTarget(String operation) {
    if (operation.endsWith("TRANSIT")) return "IN_TRANSIT";
    if (operation.endsWith("COMPLETE")) return "COMPLETED";
    if (operation.endsWith("ACTIVE")) return "ACTIVE";
    if (operation.endsWith("CANCEL")) return "CANCELLED";
    throw new IllegalArgumentException("Operational assignment transition is unknown");
  }

  private static long retryDelaySeconds(int retryCount) {
    return Math.min(1L << Math.min(Math.max(retryCount, 0), 6), 60L);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static LogisticsDependencyException malformed(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

  private static String unitReservationDigest(
      LogisticsDocument document,
      TransferPlanSnapshot snapshot,
      List<LogisticsDocumentLine> documentLines) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(document.getWarehouseId().toString());
    values.add(snapshot.planId().toString());
    documentLines.stream()
        .sorted(Comparator.comparingInt(LogisticsDocumentLine::getLineNumber))
        .forEach(
            line -> {
              values.add(line.getId().toString());
              values.add(line.getAssetId().toString());
              values.add(Long.toString(line.getAssetVersion()));
            });
    return LogisticsCommandChecksum.sha256(UNIT_RESERVE, values);
  }

  private static String furnitureReservationDigest(
      LogisticsDocument document,
      TransferPlanSnapshot snapshot,
      TransferPlanSnapshot.LooseFurniture item,
      String operation) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            document.getId().toString(),
            document.getWarehouseId().toString(),
            snapshot.planId().toString(),
            item.lineId().toString(),
            item.furnitureCatalogItemId().toString(),
            Long.toString(item.quantity()),
            snapshot.plannedArrivalAt().toInstant().toString()));
  }

  private static String assignmentDigest(
      LogisticsDocument document,
      TransferPlanSnapshot snapshot,
      AssignmentRole role,
      String operation) {
    UUID worker = assignmentWorker(snapshot, role);
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            document.getId().toString(),
            worker.toString(),
            document.getWarehouseId().toString(),
            document.getDestinationWarehouseId().toString(),
            snapshot.plannedDepartureAt().toInstant().toString(),
            snapshot.plannedArrivalAt().toInstant().toString()));
  }

  private static String driverTaskDigest(
      LogisticsDocument document,
      TransferPlanSnapshot snapshot,
      List<LogisticsDocumentLine> documentLines) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(snapshot.tripDriverId() == null ? "" : snapshot.tripDriverId().toString());
    values.add(Integer.toString(documentLines.size()));
    documentLines.forEach(line -> values.add(line.getId().toString()));
    return LogisticsCommandChecksum.sha256(DRIVER_TASK_PLAN, values);
  }

  private static String unitReleaseDigest(LogisticsDocument document) {
    return LogisticsCommandChecksum.sha256(
        UNIT_RELEASE, List.of(document.getId().toString(), document.getWarehouseId().toString()));
  }

  private static String furnitureReleaseDigest(
      LogisticsDocument document,
      TransferPlanSnapshot.LooseFurniture item,
      String operation) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            document.getId().toString(),
            item.lineId().toString(),
            item.reservationId().toString(),
            Long.toString(item.reservationVersion())));
  }

  private static String furnitureExecutionDigest(
      LogisticsDocument document, TransferPlanSnapshot snapshot) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(document.getDestinationWarehouseId().toString());
    snapshot.looseFurniture().forEach(
        item -> {
          values.add(item.lineId().toString());
          values.add(item.reservationId().toString());
          values.add(Long.toString(item.reservationVersion()));
        });
    return LogisticsCommandChecksum.sha256(FURNITURE_EXECUTE, values);
  }

  private static String transitionDigest(
      TransferPlanSnapshot.Assignment assignment, String target, String operation) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            assignment.assignmentId().toString(),
            Long.toString(assignment.version()),
            target));
  }

  private static String unitReceiptDigest(
      LogisticsDependencyGateway.TransferUnitReservationReceipt receipt) {
    List<String> values = new ArrayList<>();
    values.add(receipt.transferId().toString());
    receipt.lines().stream()
        .sorted(Comparator.comparing(value -> value.lineId().toString()))
        .forEach(
            value -> {
              values.add(value.reservationId().toString());
              values.add(Long.toString(value.version()));
              values.add(value.lineId().toString());
              values.add(value.rentalItemId().toString());
              values.add(Long.toString(value.currentRentalItemVersion()));
              values.add(value.state());
            });
    return LogisticsCommandChecksum.sha256("XFER_PLAN_UNITS_RESPONSE", values);
  }

  private static String furnitureReceiptDigest(
      LogisticsDependencyGateway.EquipmentMovementReservation receipt) {
    return LogisticsCommandChecksum.sha256(
        "XFER_PLAN_FURN_RESPONSE",
        List.of(
            receipt.reservationId().toString(),
            Long.toString(receipt.version()),
            receipt.movementId().toString(),
            receipt.lineId().toString(),
            receipt.equipmentId().toString(),
            receipt.state()));
  }

  private static String furnitureExecutionReceiptDigest(
      LogisticsDependencyGateway.EquipmentMovementExecution receipt) {
    List<String> values = new ArrayList<>();
    values.add(receipt.movementId().toString());
    receipt.lines().stream()
        .sorted(Comparator.comparing(value -> value.lineId().toString()))
        .forEach(
            value -> {
              values.add(value.reservationId().toString());
              values.add(Long.toString(value.reservationVersion()));
              values.add(value.lineId().toString());
            });
    return LogisticsCommandChecksum.sha256("XFER_PLAN_FURN_EXEC_RESPONSE", values);
  }

  private static String assignmentReceiptDigest(
      LogisticsDependencyGateway.WorkerOperationalAssignment receipt) {
    return LogisticsCommandChecksum.sha256(
        "XFER_PLAN_ASSIGNMENT_RESPONSE",
        List.of(
            receipt.assignmentId().toString(),
            Long.toString(receipt.version()),
            receipt.transferId().toString(),
            receipt.workerId().toString(),
            receipt.mode(),
            receipt.status()));
  }

  /** Separates the route executor reservation from destination operational placement. */
  enum AssignmentRole {
    TRIP,
    REPOSITION
  }

  /** Marker interface for one exact remote effect derived from a fenced attempt. */
  sealed interface Work
      permits UnitReservationWork,
          PrepareFurnitureReservationWork,
          FurnitureReservationWork,
          AssignmentCreateWork,
          DriverTaskPlanWork,
          UnitReleaseWork,
          FurnitureReleaseWork,
          FurnitureExecuteWork,
          AssignmentTransitionWork {
    /** Target-service idempotency identity owned by the persisted external attempt. */
    UUID operationId();
  }

  /** All selected cabins in the asset-owned atomic reservation boundary. */
  record UnitReservationWork(
      UUID operationId,
      UUID transferId,
      UUID sourceWarehouseId,
      List<LogisticsDependencyGateway.TransferUnitReservationRequestLine> requests)
      implements Work {}

  /** Read-only availability lookup required before freezing a furniture balance. */
  record PrepareFurnitureReservationWork(
      UUID operationId,
      UUID transferId,
      UUID sourceWarehouseId,
      int position,
      UUID equipmentId,
      long quantity)
      implements Work {}

  /** One exact asset-owned loose-furniture reservation effect. */
  record FurnitureReservationWork(
      UUID operationId,
      UUID transferId,
      UUID sourceWarehouseId,
      int position,
      UUID lineId,
      UUID equipmentId,
      long quantity,
      UUID sourceBalanceId,
      long expectedSourceBalanceVersion,
      OffsetDateTime reservedUntil)
      implements Work {}

  /** One trip-only or repositioning driver commitment. */
  record AssignmentCreateWork(
      UUID operationId,
      AssignmentRole role,
      UUID transferId,
      UUID workerId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil)
      implements Work {}

  /** Existing driver-task planner work delayed until cargo/resource reservations succeed. */
  record DriverTaskPlanWork(
      UUID operationId,
      UUID transferId,
      UUID sourceWarehouseId,
      UUID tripDriverId,
      OffsetDateTime plannedDepartureAt)
      implements Work {}

  /** Atomic release of every selected cabin reservation before physical departure. */
  record UnitReleaseWork(
      UUID operationId,
      UUID transferId,
      List<LogisticsDependencyGateway.TransferUnitReservationReleaseLine> requests)
      implements Work {}

  /** One exact loose-furniture release effect. */
  record FurnitureReleaseWork(
      UUID operationId,
      UUID transferId,
      int position,
      UUID lineId,
      UUID reservationId,
      long expectedReservationVersion)
      implements Work {}

  /** Atomic destination-stock execution for every independent furniture line. */
  record FurnitureExecuteWork(
      UUID operationId,
      UUID transferId,
      List<LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine> requests)
      implements Work {}

  /** One expected-version driver-assignment transition. */
  record AssignmentTransitionWork(
      UUID operationId,
      AssignmentRole role,
      UUID assignmentId,
      long expectedVersion,
      String targetStatus)
      implements Work {}
}
