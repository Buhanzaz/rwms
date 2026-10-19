package dev.buhanzaz.rwms.logistics.order.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.RescheduleCustomerBookingRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.*;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleService.PublishedReschedulePreparation;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingRescheduleReceipt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverBoardTask;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanCommitResult;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplanPrepareSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.PlanningReplacementTaskResult;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRecoveryOperation;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSaga;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSagaState;
import dev.buhanzaz.rwms.logistics.planning.repository.PlanningPublishedRescheduleSagaRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Coordinates the durable cross-owner withdrawal protocol for already published pre-start work.
 * Remote calls are idempotent and outside local transaction boundaries; after OWNER_COMMITTED
 * every retry moves forward to task-board and local tombstones.
 */
@Service
public class PlanningPublishedRescheduleSagaService {
  private static final Set<PlanningPublishedRescheduleSagaState> RECOVERABLE =
      Set.of(
          PlanningPublishedRescheduleSagaState.PENDING,
          PlanningPublishedRescheduleSagaState.PREPARED,
          PlanningPublishedRescheduleSagaState.OWNER_COMMITTED,
          PlanningPublishedRescheduleSagaState.BOARD_COMMITTED,
          PlanningPublishedRescheduleSagaState.RELEASE_PENDING);
  private static final Set<PlanningPublishedRescheduleSagaState> TERMINAL_ADMISSION_STATES =
      Set.of(
          PlanningPublishedRescheduleSagaState.COMPLETE,
          PlanningPublishedRescheduleSagaState.RELEASED);
  private static final int MAX_ATTEMPTS = 8;

  private final PlanningPublishedRescheduleSagaRepository sagas;
  private final LogisticsTransactionLock transactionLock;
  private final RentalOrderRepository orders;
  private final CustomerRentalSessionRepository sessions;
  private final CustomerDeliverySlotRepository slots;
  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDocumentRepository documents;
  private final CustomerBookingLifecycleService bookingLifecycle;
  private final RentalOrderPlanningIntegrationService planning;
  private final LogisticsDocumentService documentService;
  private final LogisticsDependencyGateway dependencies;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final TransactionTemplate transactions;

  /** Builds the saga owner with a bounded local transaction coordinator and replaceable UTC clock. */
  public PlanningPublishedRescheduleSagaService(
      PlanningPublishedRescheduleSagaRepository sagas,
      LogisticsTransactionLock transactionLock,
      RentalOrderRepository orders,
      CustomerRentalSessionRepository sessions,
      CustomerDeliverySlotRepository slots,
      DriverLogisticsTaskRepository tasks,
      LogisticsDocumentRepository documents,
      CustomerBookingLifecycleService bookingLifecycle,
      RentalOrderPlanningIntegrationService planning,
      LogisticsDocumentService documentService,
      LogisticsDependencyGateway dependencies,
      ObjectMapper objectMapper,
      ObjectProvider<Clock> clocks,
      PlatformTransactionManager transactionManager) {
    this.sagas = sagas;
    this.transactionLock = transactionLock;
    this.orders = orders;
    this.sessions = sessions;
    this.slots = slots;
    this.tasks = tasks;
    this.documents = documents;
    this.bookingLifecycle = bookingLifecycle;
    this.planning = planning;
    this.documentService = documentService;
    this.dependencies = dependencies;
    this.objectMapper = objectMapper;
    this.clock = clocks.getIfAvailable(Clock::systemUTC);
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Runs or exactly resumes one published reschedule and returns only after full convergence. */
  public PlanningOrderRescheduleResponse reschedule(
      UUID orderId, UUID idempotencyKey, PlanningOrderRescheduleRequest request) {
    PlanningPublishedAssignmentWithdrawalRequest withdrawal =
        Objects.requireNonNull(
            request.publishedPlanWithdrawal(), "Published plan withdrawal is required");
    String requestJson = encode(request);
    String requestHash = fingerprint(orderId, request);
    PlanningPublishedRescheduleSaga saga =
        transactions.execute(
            ignored -> prepareIntent(orderId, idempotencyKey, request, requestHash, requestJson));
    if (saga == null) throw new IllegalStateException("Reschedule intent was not persisted");
    if (saga.getState() == PlanningPublishedRescheduleSagaState.COMPLETE) {
      return decodeResponse(saga.getResponseJson());
    }
    if (saga.getState() == PlanningPublishedRescheduleSagaState.RELEASED
        || saga.getState() == PlanningPublishedRescheduleSagaState.QUARANTINED) {
      throw terminalFailure(saga);
    }

    for (int step = 0; step < 5; step++) {
      PlanningPublishedRescheduleSagaState state = state(idempotencyKey);
      if (state == PlanningPublishedRescheduleSagaState.COMPLETE) {
        return completedResponse(idempotencyKey);
      }
      if (state == PlanningPublishedRescheduleSagaState.RELEASED
          || state == PlanningPublishedRescheduleSagaState.QUARANTINED) {
        throw terminalFailure(required(idempotencyKey));
      }
      advance(idempotencyKey, withdrawal, request);
    }
    throw pending();
  }

  /** Removes one already owner-cancelled assignment and atomically revises its published siblings. */
  public PlanningPublishedAssignmentWithdrawalResult withdrawCancelled(
      UUID sourcePlanId,
      UUID idempotencyKey,
      PlanningPublishedAssignmentWithdrawalRequest withdrawal) {
    if (sourcePlanId == null
        || idempotencyKey == null
        || withdrawal == null
        || !sourcePlanId.equals(withdrawal.sourcePlanId())) {
      throw new IllegalArgumentException("Published cancellation withdrawal identity is required");
    }
    String requestJson = encode(withdrawal);
    String requestHash = fingerprint(sourcePlanId, withdrawal);
    PlanningPublishedRescheduleSaga saga =
        transactions.execute(
            ignored ->
                prepareCancellationIntent(
                    sourcePlanId,
                    idempotencyKey,
                    withdrawal,
                    requestHash,
                    requestJson));
    if (saga == null) throw new IllegalStateException("Cancellation withdrawal was not persisted");
    if (saga.getState() == PlanningPublishedRescheduleSagaState.COMPLETE) {
      return decodeWithdrawalResponse(saga.getResponseJson());
    }
    if (saga.getState() == PlanningPublishedRescheduleSagaState.RELEASED
        || saga.getState() == PlanningPublishedRescheduleSagaState.QUARANTINED) {
      throw terminalFailure(saga);
    }

    for (int step = 0; step < 5; step++) {
      PlanningPublishedRescheduleSagaState state = state(idempotencyKey);
      if (state == PlanningPublishedRescheduleSagaState.COMPLETE) {
        return decodeWithdrawalResponse(required(idempotencyKey).getResponseJson());
      }
      if (state == PlanningPublishedRescheduleSagaState.RELEASED
          || state == PlanningPublishedRescheduleSagaState.QUARANTINED) {
        throw terminalFailure(required(idempotencyKey));
      }
      advance(idempotencyKey);
    }
    throw pending();
  }

  /** Reconciles a bounded batch; every step is exact-replay safe across service instances. */
  @Scheduled(
      fixedDelayString = "${rwms.logistics.planning.published-reschedule-recovery-delay:3s}",
      initialDelayString = "${rwms.logistics.planning.published-reschedule-recovery-initial-delay:5s}")
  public void recover() {
    OffsetDateTime now = now();
    List<UUID> due =
        sagas.findDueIds(RECOVERABLE, now, PageRequest.of(0, 25));
    for (UUID id : due) {
      try {
        advance(id);
      } catch (RuntimeException ignored) {
        // The exact safe code is already retained on the saga; the next bounded pass resumes it.
      }
    }
  }

  private void advance(
      UUID sagaId,
      PlanningPublishedAssignmentWithdrawalRequest withdrawal,
      PlanningOrderRescheduleRequest request) {
    advance(sagaId, withdrawal, request, PlanningPublishedRecoveryOperation.RESCHEDULE);
  }

  private void advance(UUID sagaId) {
    PlanningPublishedRescheduleSaga saga = required(sagaId);
    PlanningPublishedRecoveryOperation operation = saga.getOperation();
    PlanningOrderRescheduleRequest request =
        operation == PlanningPublishedRecoveryOperation.RESCHEDULE
            ? decodeRequest(saga.getRequestJson())
            : null;
    PlanningPublishedAssignmentWithdrawalRequest withdrawal =
        operation == PlanningPublishedRecoveryOperation.RESCHEDULE
            ? Objects.requireNonNull(request).publishedPlanWithdrawal()
            : decodeWithdrawal(saga.getRequestJson());
    advance(sagaId, withdrawal, request, operation);
  }

  private void advance(
      UUID sagaId,
      PlanningPublishedAssignmentWithdrawalRequest withdrawal,
      PlanningOrderRescheduleRequest request,
      PlanningPublishedRecoveryOperation operation) {
    PlanningPublishedRescheduleSaga saga = required(sagaId);
    try {
      switch (saga.getState()) {
        case PENDING -> prepareRemote(sagaId, withdrawal);
        case PREPARED -> {
          if (operation == PlanningPublishedRecoveryOperation.RESCHEDULE) {
            commitOwner(sagaId, Objects.requireNonNull(request));
          } else {
            commitCancellationOwner(sagaId, withdrawal);
          }
        }
        case OWNER_COMMITTED -> commitBoard(sagaId);
        case BOARD_COMMITTED -> finalizeLocal(sagaId, withdrawal, operation);
        case RELEASE_PENDING -> releaseRemote(sagaId);
        case COMPLETE, RELEASED, QUARANTINED -> {
          return;
        }
      }
    } catch (OrderProblemException problem) {
      if (state(sagaId) == PlanningPublishedRescheduleSagaState.PREPARED) {
        transactions.executeWithoutResult(
            ignored -> {
              PlanningPublishedRescheduleSaga locked = locked(sagaId);
              locked.releasePending(problem.code(), problem.getMessage(), now());
              sagas.saveAndFlush(locked);
            });
        try {
          releaseRemote(sagaId);
        } catch (RuntimeException releaseFailure) {
          scheduleRetry(sagaId, releaseFailure);
          throw pending();
        }
      }
      throw problem;
    } catch (RuntimeException failure) {
      if (failure instanceof LogisticsDependencyException dependency
          && dependency.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        quarantine(sagaId, "PUBLISHED_RESCHEDULE_REJECTED", safeMessage(failure));
        throw conflict("PUBLISHED_RESCHEDULE_REJECTED", "Опубликованный рейс уже изменён или начат");
      }
      scheduleRetry(sagaId, failure);
      throw pending();
    }
  }

  private void prepareRemote(
      UUID sagaId, PlanningPublishedAssignmentWithdrawalRequest withdrawal) {
    List<LogisticsDependencyGateway.PlanningReplacementShift> shiftSnapshots =
        planning.validateAndSnapshotReplacement(
            withdrawal.sourcePlanId(),
            new ReplacePlanningAssignmentsRequest(
                withdrawal.warehouseId(),
                withdrawal.date(),
                withdrawal.expectedSourcePlanVersion(),
                withdrawal.replacementPlanVersion(),
                withdrawal.remainingAssignments(),
                withdrawal.driverShiftPlans()));
    Map<UUID, DriverBoardTask> boardTasks = new LinkedHashMap<>();
    java.util.stream.Stream.concat(
            java.util.stream.Stream.of(withdrawal.removedAssignment().externalTaskId()),
            withdrawal.remainingAssignments().stream()
                .map(PlanningAssignmentReplacementRequest::externalTaskId))
        .distinct()
        .forEach(externalTaskId -> boardTasks.put(externalTaskId, dependencies.readDriverTask(externalTaskId)));
    PreparedOwnerSnapshot prepared =
        transactions.execute(
            ignored ->
                preparedOwnerSnapshot(sagaId, withdrawal, shiftSnapshots, boardTasks));
    if (prepared == null) throw new IllegalStateException("Published plan snapshot is absent");
    var remote =
        dependencies.preparePlanningReschedule(
            prepared.sourcePlanId(), sagaId, prepared.remoteSnapshot());
    if (!sagaId.equals(remote.holdId())
        || !prepared.sourcePlanId().equals(remote.sourcePlanId())
        || remote.sourcePlanVersion() != withdrawal.expectedSourcePlanVersion()
        || !withdrawal
            .removedAssignment()
            .externalTaskId()
            .equals(remote.removedExternalTaskId())) {
      throw new LogisticsConflictException("Task-board returned another published plan hold");
    }
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga locked = locked(sagaId);
          if (locked.getState() == PlanningPublishedRescheduleSagaState.PENDING) {
            locked.prepared(remote.holdId(), now());
            sagas.saveAndFlush(locked);
          }
        });
  }

  private void commitOwner(UUID sagaId, PlanningOrderRescheduleRequest request) {
    PlanningPublishedRescheduleSaga current = required(sagaId);
    if (current.getState() != PlanningPublishedRescheduleSagaState.PREPARED) return;
    CustomerRentalSession currentSession =
        sessions
            .findFirstByOrderIdOrderByCreatedAtAscIdAsc(current.getOrderId())
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    PublishedReschedulePreparation preparation =
        bookingLifecycle.preparePublishedForDispatcher(
            identity(currentSession),
            currentSession.getBookingId(),
            current.getOrderId(),
            sagaId,
            request.expectedOrderVersion(),
            request.decisionCode().name(),
            request.decisionActorSubjectId(),
            request.decisionReason(),
            new RescheduleCustomerBookingRequest(
                request.expectedSessionVersion(), request.slotId(), request.slotVersion()));
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga saga = locked(sagaId);
          if (saga.getState() != PlanningPublishedRescheduleSagaState.PREPARED) return;
          PlanningPublishedAssignmentWithdrawalRequest withdrawal =
              request.publishedPlanWithdrawal();
          requireLocalMembership(saga, request, withdrawal);
          CustomerBookingRescheduleReceipt ownerReceipt =
              bookingLifecycle.commitPreparedPublishedForDispatcher(preparation);
          saga.ownerCommitted(encode(response(ownerReceipt)), now());
          sagas.saveAndFlush(saga);
        });
  }

  private void commitCancellationOwner(
      UUID sagaId, PlanningPublishedAssignmentWithdrawalRequest withdrawal) {
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga saga = locked(sagaId);
          if (saga.getState() != PlanningPublishedRescheduleSagaState.PREPARED) return;
          if (saga.getOperation() != PlanningPublishedRecoveryOperation.CANCELLATION) {
            throw new IllegalStateException("Published recovery operation changed");
          }
          requireLocalMembership(saga, null, withdrawal);
          saga.ownerCommitted("{}", now());
          sagas.saveAndFlush(saga);
        });
  }

  private void commitBoard(UUID sagaId) {
    PlanningPublishedRescheduleSaga saga = required(sagaId);
    PlanningReplanCommitResult remote =
        dependencies.commitPlanningReschedule(saga.getTaskBoardHoldId(), sagaId);
    if (!sagaId.equals(remote.holdId())
        || !saga.getSourcePlanId().equals(remote.sourcePlanId())
        || remote.sourcePlanVersion() != saga.getReplacementPlanVersion()
        || remote.removedAssignment() == null
        || !saga.getRemovedExternalTaskId().equals(remote.removedAssignment().externalTaskId())
        || !"CANCELLED".equals(remote.removedAssignment().status())) {
      throw new LogisticsConflictException("Task-board returned another reschedule commit");
    }
    String responseJson = encode(remote);
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga locked = locked(sagaId);
          if (locked.getState() == PlanningPublishedRescheduleSagaState.OWNER_COMMITTED) {
            locked.boardCommitted(responseJson, now());
            sagas.saveAndFlush(locked);
          }
        });
  }

  private void finalizeLocal(
      UUID sagaId,
      PlanningPublishedAssignmentWithdrawalRequest withdrawal,
      PlanningPublishedRecoveryOperation operation) {
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga saga = locked(sagaId);
          if (saga.getState() != PlanningPublishedRescheduleSagaState.BOARD_COMMITTED) return;
          PlanningReplanCommitResult remote = decodeCommit(saga.getTaskBoardCommitJson());
          Map<UUID, PlanningReplacementTaskResult> remoteRemaining =
              remote.remainingAssignments().stream()
                  .collect(
                      Collectors.toMap(
                          PlanningReplacementTaskResult::externalTaskId, Function.identity()));
          Set<UUID> requestedRemaining =
              withdrawal.remainingAssignments().stream()
                  .map(PlanningAssignmentReplacementRequest::externalTaskId)
                  .collect(Collectors.toUnmodifiableSet());
          if (!requestedRemaining.equals(remoteRemaining.keySet())) {
            throw new LogisticsConflictException("Task-board reschedule remainder is incomplete");
          }

          if (operation == PlanningPublishedRecoveryOperation.RESCHEDULE) {
            LogisticsDocument document =
                documents
                    .findForUpdate(withdrawal.removedAssignment().documentId())
                    .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
            documentService.cancelShipment(
                RentalOrderPlanningIntegrationService.plannerSubjectId(),
                deterministic("published-reschedule-document:" + sagaId),
                deterministic("published-reschedule-correlation:" + sagaId),
                document.getId(),
                document.getVersion());
          }

          List<DriverLogisticsTask> membership =
              tasks.findAllForUpdateBySourcePlanId(saga.getSourcePlanId());
          Map<UUID, DriverLogisticsTask> tasksByExternal =
              membership.stream()
                  .collect(
                      Collectors.toMap(DriverLogisticsTask::getExternalTaskId, Function.identity()));
          for (PlanningAssignmentReplacementRequest item : withdrawal.remainingAssignments()) {
            DriverLogisticsTask task = tasksByExternal.get(item.externalTaskId());
            PlanningReplacementTaskResult board = remoteRemaining.get(item.externalTaskId());
            if (task == null || !task.getTaskBoardEntryId().equals(board.entryId())) {
              throw new LogisticsConflictException("Local published task remainder diverged");
            }
            task.applyPlannerReplacement(
                item.expectedTaskVersion(),
                saga.getSourcePlanId(),
                saga.getExpectedSourcePlanVersion(),
                saga.getReplacementPlanVersion(),
                domainAudience(item.driverAudienceMode()),
                item.driverWorkerId(),
                item.driverName(),
                item.provisionalEta(),
                board.taskVersion(),
                board.entryId());
          }
          DriverLogisticsTask removed =
              tasksByExternal.get(withdrawal.removedAssignment().externalTaskId());
          if (removed == null || removed.getState() != DriverTaskState.CANCELLED) {
            throw new LogisticsConflictException("Removed local driver task did not cancel");
          }
          removed.removeFromPlannerLineage(
              saga.getSourcePlanId(),
              saga.getExpectedSourcePlanVersion(),
              saga.getReplacementPlanVersion(),
              now());
          tasks.saveAllAndFlush(membership);

          PlanningPublishedAssignmentWithdrawalResult withdrawalResult =
              new PlanningPublishedAssignmentWithdrawalResult(
                  saga.getSourcePlanId(),
                  saga.getReplacementPlanVersion(),
                  removed.getExternalTaskId(),
                  removed.getVersion(),
                  "COMPLETE");
          if (operation == PlanningPublishedRecoveryOperation.RESCHEDULE) {
            PlanningOrderRescheduleResponse ownerResponse = decodeResponse(saga.getResponseJson());
            PlanningOrderRescheduleResponse response =
                new PlanningOrderRescheduleResponse(
                    ownerResponse.orderId(),
                    ownerResponse.orderVersion(),
                    ownerResponse.sessionId(),
                    ownerResponse.sessionVersion(),
                    ownerResponse.bookingId(),
                    ownerResponse.warehouseId(),
                    ownerResponse.confirmedSlot(),
                    withdrawalResult);
            saga.complete(encode(response), now());
          } else {
            saga.complete(encode(withdrawalResult), now());
          }
          sagas.saveAndFlush(saga);
        });
  }

  private void releaseRemote(UUID sagaId) {
    PlanningPublishedRescheduleSaga saga = required(sagaId);
    var released =
        dependencies.releasePlanningReschedule(saga.getTaskBoardHoldId(), sagaId);
    if (!saga.getTaskBoardHoldId().equals(released.holdId())
        || !saga.getSourcePlanId().equals(released.sourcePlanId())) {
      throw new LogisticsConflictException("Task-board released another planning hold");
    }
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga locked = locked(sagaId);
          if (locked.getState() == PlanningPublishedRescheduleSagaState.RELEASE_PENDING) {
            locked.released(now());
            sagas.saveAndFlush(locked);
          }
        });
  }

  private PlanningPublishedRescheduleSaga prepareIntent(
      UUID orderId,
      UUID idempotencyKey,
      PlanningOrderRescheduleRequest request,
      String requestHash,
      String requestJson) {
    if (orderId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Published reschedule identity is required");
    }
    transactionLock.acquire("planning-published-reschedule:" + idempotencyKey);
    PlanningPublishedAssignmentWithdrawalRequest withdrawal = request.publishedPlanWithdrawal();
    if (withdrawal == null) {
      throw new IllegalArgumentException("Published plan withdrawal is required");
    }
    transactionLock.acquire("planning-published-recovery-source:" + withdrawal.sourcePlanId());
    PlanningPublishedRescheduleSaga existing = sagas.findById(idempotencyKey).orElse(null);
    if (existing != null) {
      if (!orderId.equals(existing.getOrderId())
          || existing.getOperation() != PlanningPublishedRecoveryOperation.RESCHEDULE
          || !requestHash.equals(existing.getRequestSha256())) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другого переноса");
      }
      return existing;
    }
    Context context =
        requiredContextForUpdate(
            orderId, request.expectedOrderVersion(), request.expectedSessionVersion());
    requireNoActivePublishedChange(context.session());
    CustomerDeliverySlot selected =
        slots
            .findById(withdrawal == null ? null : request.slotId())
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (withdrawal == null
        || withdrawal.replacementPlanVersion() <= withdrawal.expectedSourcePlanVersion()
        || !context.slot().getDeliveryDate().equals(withdrawal.date())
        || !withdrawal.date().equals(withdrawal.removedAssignment().scheduledDate())
        || context.slot().getDeliveryDate().equals(selected.getDeliveryDate())
        || !context.session().getWarehouseId().equals(selected.getWarehouseId())) {
      throw conflict(
          "PUBLISHED_RESCHEDULE_INVALID",
          "Опубликованный рейс и выбранная новая дата не соответствуют друг другу");
    }
    PlanningPublishedRescheduleSaga created =
        PlanningPublishedRescheduleSaga.create(
            idempotencyKey,
            orderId,
            context.session().getBookingId(),
            context.session().getCustomerSubjectId(),
            PlanningPublishedRecoveryOperation.RESCHEDULE,
            withdrawal.sourcePlanId(),
            withdrawal.expectedSourcePlanVersion(),
            withdrawal.replacementPlanVersion(),
            withdrawal.warehouseId(),
            withdrawal.date(),
            withdrawal.removedAssignment().documentId(),
            withdrawal.removedAssignment().externalTaskId(),
            requestHash,
            requestJson,
            now());
    return sagas.saveAndFlush(created);
  }

  private void requireNoActivePublishedChange(CustomerRentalSession session) {
    boolean active =
        sagas.findAllForBookingAdmission(session.getOrderId(), session.getBookingId()).stream()
            .anyMatch(saga -> !TERMINAL_ADMISSION_STATES.contains(saga.getState()));
    if (active) {
      throw conflict(
          "CUSTOMER_BOOKING_PUBLISHED_CHANGE_IN_PROGRESS",
          "Изменение опубликованной доставки ещё выполняется; обновите бронирование и повторите"
              + " действие");
    }
  }

  private PlanningPublishedRescheduleSaga prepareCancellationIntent(
      UUID sourcePlanId,
      UUID idempotencyKey,
      PlanningPublishedAssignmentWithdrawalRequest withdrawal,
      String requestHash,
      String requestJson) {
    transactionLock.acquire("planning-published-cancellation:" + idempotencyKey);
    transactionLock.acquire("planning-published-recovery-source:" + sourcePlanId);
    PlanningPublishedRescheduleSaga existing = sagas.findById(idempotencyKey).orElse(null);
    if (existing != null) {
      if (existing.getOperation() != PlanningPublishedRecoveryOperation.CANCELLATION
          || !sourcePlanId.equals(existing.getSourcePlanId())
          || !requestHash.equals(existing.getRequestSha256())) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key уже использован для другого изменения опубликованного плана");
      }
      return existing;
    }
    if (withdrawal.replacementPlanVersion() <= withdrawal.expectedSourcePlanVersion()
        || !sourcePlanId.equals(withdrawal.sourcePlanId())
        || !withdrawal.date().equals(withdrawal.removedAssignment().scheduledDate())) {
      throw conflict(
          "PUBLISHED_CANCELLATION_INVALID",
          "Отмена не соответствует текущей версии опубликованного плана");
    }
    LogisticsDocument removedDocument =
        documents
            .findForUpdate(withdrawal.removedAssignment().documentId())
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (removedDocument.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || removedDocument.getState() != LogisticsDocumentState.CANCELLED
        || removedDocument.getRentalOrderId() == null) {
      throw conflict(
          "PUBLISHED_CANCELLATION_OWNER_CHANGED",
          "Владелец заказа не подтверждает отмену опубликованной доставки");
    }
    RentalOrder order =
        orders
            .findForUpdate(removedDocument.getRentalOrderId())
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (order.getStatus() != RentalOrderStatus.CANCELLED) {
      throw conflict(
          "PUBLISHED_CANCELLATION_OWNER_CHANGED",
          "Владелец заказа не подтверждает отмену опубликованной доставки");
    }
    PlanningPublishedRescheduleSaga created =
        PlanningPublishedRescheduleSaga.create(
            idempotencyKey,
            order.getId(),
            null,
            null,
            PlanningPublishedRecoveryOperation.CANCELLATION,
            sourcePlanId,
            withdrawal.expectedSourcePlanVersion(),
            withdrawal.replacementPlanVersion(),
            withdrawal.warehouseId(),
            withdrawal.date(),
            withdrawal.removedAssignment().documentId(),
            withdrawal.removedAssignment().externalTaskId(),
            requestHash,
            requestJson,
            now());
    return sagas.saveAndFlush(created);
  }

  private PreparedOwnerSnapshot preparedOwnerSnapshot(
      UUID sagaId,
      PlanningPublishedAssignmentWithdrawalRequest withdrawal,
      List<LogisticsDependencyGateway.PlanningReplacementShift> shiftSnapshots,
      Map<UUID, DriverBoardTask> boardTasks) {
    PlanningPublishedRescheduleSaga saga = locked(sagaId);
    PlanningOrderRescheduleRequest request =
        saga.getOperation() == PlanningPublishedRecoveryOperation.RESCHEDULE
            ? decodeRequest(saga.getRequestJson())
            : null;
    PlanningMembership membership = requireLocalMembership(saga, request, withdrawal);
    UUID removedExternalTaskId = withdrawal.removedAssignment().externalTaskId();
    for (DriverLogisticsTask task : membership.tasks()) {
      DriverBoardTask board = boardTasks.get(task.getExternalTaskId());
      requireBoardTask(
          task,
          board,
          removedExternalTaskId.equals(task.getExternalTaskId()),
          saga.getOperation());
    }
    PlanningPublishedAssignmentRemovalRequest removed = withdrawal.removedAssignment();
    DriverLogisticsTask removedTask = membership.byExternalId().get(removed.externalTaskId());
    DriverBoardTask removedBoard = boardTasks.get(removed.externalTaskId());
    PlanningReplanPrepareSnapshot snapshot =
        new PlanningReplanPrepareSnapshot(
            withdrawal.warehouseId(),
            withdrawal.date(),
            withdrawal.expectedSourcePlanVersion(),
            withdrawal.replacementPlanVersion(),
            new LogisticsDependencyGateway.PlanningReplanRemovedTask(
                removed.externalTaskId(),
                removedTask.getId(),
                removed.serviceWarehouseId(),
                removed.scheduledDate(),
                removedBoard.taskVersion(),
                removedBoard.entryVersion()),
            withdrawal.remainingAssignments().stream()
                .map(
                    item -> {
                      DriverLogisticsTask task = membership.byExternalId().get(item.externalTaskId());
                      DriverBoardTask board = boardTasks.get(item.externalTaskId());
                      return new LogisticsDependencyGateway.PlanningReplacementTask(
                          item.externalTaskId(),
                          task.getId(),
                          item.serviceWarehouseId(),
                          item.scheduledDate(),
                          board.taskVersion(),
                          board.entryVersion(),
                          item.targetQueuePosition(),
                          audience(item));
                    })
                .toList(),
            shiftSnapshots);
    return new PreparedOwnerSnapshot(saga.getSourcePlanId(), snapshot);
  }

  private PlanningMembership requireLocalMembership(
      PlanningPublishedRescheduleSaga saga,
      PlanningOrderRescheduleRequest request,
      PlanningPublishedAssignmentWithdrawalRequest withdrawal) {
    List<DriverLogisticsTask> membership =
        tasks.findAllForUpdateBySourcePlanId(saga.getSourcePlanId());
    Map<UUID, DriverLogisticsTask> byExternal =
        membership.stream()
            .collect(Collectors.toMap(DriverLogisticsTask::getExternalTaskId, Function.identity()));
    Set<UUID> requested =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(withdrawal.removedAssignment().externalTaskId()),
                withdrawal.remainingAssignments().stream()
                    .map(PlanningAssignmentReplacementRequest::externalTaskId))
            .collect(Collectors.toUnmodifiableSet());
    if (membership.isEmpty() || membership.size() != requested.size() || !byExternal.keySet().equals(requested)) {
      throw conflict(
          "PUBLISHED_RESCHEDULE_MEMBERSHIP_CHANGED",
          "Состав опубликованного плана изменился; запросите его заново");
    }
    for (DriverLogisticsTask task : membership) {
      if (!saga.getSourcePlanId().equals(task.getSourcePlanId())
          || task.getSourcePlanVersion() == null
          || task.getSourcePlanVersion() != saga.getExpectedSourcePlanVersion()
          || !saga.getSourcePlanWarehouseId().equals(task.getSourcePlanWarehouseId())
          || !saga.getSourcePlanDate().equals(task.getSourcePlanDate())) {
        throw conflict(
            "PUBLISHED_RESCHEDULE_SOURCE_CHANGED", "Версия опубликованного плана изменилась");
      }
    }
    PlanningPublishedAssignmentRemovalRequest removed = withdrawal.removedAssignment();
    DriverLogisticsTask removedTask = byExternal.get(removed.externalTaskId());
    LogisticsDocument removedDocument =
        documents
            .findForUpdate(removed.documentId())
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    List<UUID> removedUnits =
        removedTask.getMembers().stream().map(member -> member.getCabinId()).toList();
    DriverTaskState expectedRemovedState =
        saga.getOperation() == PlanningPublishedRecoveryOperation.CANCELLATION
            ? DriverTaskState.CANCELLED
            : DriverTaskState.SCHEDULED;
    LogisticsDocumentState expectedDocumentState =
        saga.getOperation() == PlanningPublishedRecoveryOperation.CANCELLATION
            ? LogisticsDocumentState.CANCELLED
            : LogisticsDocumentState.DRAFT;
    if (removedTask.getVersion() != removed.expectedTaskVersion()
        || removedTask.getState() != expectedRemovedState
        || removedTask.getKind() != DriverTaskKind.SHIPMENT
        || removedTask.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || !removed.documentId().equals(removedTask.getSourceId())
        || !removed.serviceWarehouseId().equals(removedTask.getWarehouseId())
        || !removed.scheduledDate().equals(removedTask.getScheduledDate())
        || !removedUnits.equals(removed.unitIds())
        || removedDocument.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || removedDocument.getState() != expectedDocumentState
        || !saga.getOrderId().equals(removedDocument.getRentalOrderId())
        || !removed.serviceWarehouseId().equals(removedDocument.getWarehouseId())
        || !removed.scheduledDate().equals(removedDocument.getScheduledDate())
        || !RentalOrderPlanningIntegrationService.plannerSubjectId()
            .equals(removedDocument.getRequestedBySubjectId())) {
      throw conflict(
          "PUBLISHED_RESCHEDULE_ASSIGNMENT_CHANGED",
          "Опубликованная отгрузка уже изменилась или началась");
    }
    for (PlanningAssignmentReplacementRequest item : withdrawal.remainingAssignments()) {
      if (item.orderId().equals(saga.getOrderId())) {
        throw conflict(
            "PUBLISHED_RESCHEDULE_SPLIT_ORDER_UNSUPPORTED",
            "Все части одного заказа должны переноситься одним заданием");
      }
      DriverLogisticsTask task = byExternal.get(item.externalTaskId());
      if (task == null
          || task.getVersion() != item.expectedTaskVersion()
          || task.getState() != DriverTaskState.SCHEDULED
          || task.getKind() != DriverTaskKind.SHIPMENT
          || !item.documentId().equals(task.getSourceId())
          || !item.serviceWarehouseId().equals(task.getWarehouseId())
          || !item.scheduledDate().equals(task.getScheduledDate())
          || !task.getMembers().stream().map(member -> member.getCabinId()).toList().equals(item.unitIds())) {
        throw conflict(
            "PUBLISHED_RESCHEDULE_REMAINDER_CHANGED",
            "Оставшаяся часть опубликованного плана уже изменилась");
      }
    }
    RentalOrder order =
        orders
            .findForUpdate(saga.getOrderId())
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (saga.getOperation() == PlanningPublishedRecoveryOperation.RESCHEDULE) {
      if (request == null
          || order.getStatus() != RentalOrderStatus.SAVED
          || order.getVersion() != request.expectedOrderVersion()) {
        throw conflict("ORDER_VERSION_CONFLICT", "Заказ изменился после согласования переноса");
      }
    } else if (order.getStatus() != RentalOrderStatus.CANCELLED) {
      throw conflict(
          "PUBLISHED_CANCELLATION_OWNER_CHANGED",
          "Владелец заказа больше не подтверждает отмену опубликованной доставки");
    }
    return new PlanningMembership(List.copyOf(membership), Map.copyOf(byExternal));
  }

  private static void requireBoardTask(
      DriverLogisticsTask task,
      DriverBoardTask board,
      boolean removed,
      PlanningPublishedRecoveryOperation operation) {
    boolean cancelledRemoval =
        removed && operation == PlanningPublishedRecoveryOperation.CANCELLATION;
    boolean stateMatches =
        cancelledRemoval
            ? "CANCELLED".equals(board == null ? null : board.status())
                && "SCHEDULED".equals(board.lane())
                && "CANCELLED".equals(board.entryStatus())
                && board.doneAt() != null
            : "ACTIVE".equals(board == null ? null : board.status())
                && "SCHEDULED".equals(board.lane())
                && "WAITING".equals(board.entryStatus())
                && board.doneAt() == null;
    if (board == null
        || !task.getTaskBoardTaskId().equals(board.taskId())
        || !task.getExternalTaskId().equals(board.externalTaskId())
        || !task.getTaskBoardEntryId().equals(board.entryId())
        || !stateMatches) {
      throw conflict(
          "PUBLISHED_RESCHEDULE_TASK_STARTED",
          "Задание водителя уже назначено, начато или завершено");
    }
  }

  private Context requiredContextForUpdate(
      UUID orderId, Long expectedOrderVersion, Long expectedSessionVersion) {
    CustomerRentalSession located =
        sessions
            .findFirstByOrderIdOrderByCreatedAtAscIdAsc(orderId)
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (located.getBookingId() == null) throw bookingNotEditable();
    CustomerRentalSession locked =
        sessions
            .findByBookingIdForUpdate(located.getBookingId())
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (!Objects.equals(located.getId(), locked.getId())
        || !orderId.equals(locked.getOrderId())) {
      throw bookingNotEditable();
    }
    return requiredContext(orderId, expectedOrderVersion, expectedSessionVersion, locked);
  }

  private Context requiredContext(
      UUID orderId,
      Long expectedOrderVersion,
      Long expectedSessionVersion,
      CustomerRentalSession session) {
    RentalOrder order =
        orders
            .findWithClientById(orderId)
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (session.getState() != CustomerSessionState.BOOKED
        || session.getBookingId() == null
        || !orderId.equals(session.getOrderId())) {
      throw bookingNotEditable();
    }
    if (expectedOrderVersion != null && order.getVersion() != expectedOrderVersion) {
      throw conflict("ORDER_VERSION_CONFLICT", "Заказ изменился после расчёта вариантов переноса");
    }
    if (expectedSessionVersion != null && session.getVersion() != expectedSessionVersion) {
      throw conflict(
          "CUSTOMER_BOOKING_VERSION_CONFLICT",
          "Бронирование изменилось; запросите варианты переноса заново");
    }
    CustomerDeliverySlot slot =
        slots
            .findByOrderIdAndState(orderId, CustomerDeliverySlotState.CONFIRMED)
            .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
    if (!Objects.equals(session.getDeliverySlotId(), slot.getId())
        || !Objects.equals(session.getBookingId(), slot.getBookingId())) {
      throw bookingNotEditable();
    }
    return new Context(order, session, slot);
  }

  private void scheduleRetry(UUID sagaId, RuntimeException failure) {
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga saga = locked(sagaId);
          if (saga.getAttemptCount() + 1 >= MAX_ATTEMPTS) {
            saga.quarantine("PUBLISHED_RESCHEDULE_RECOVERY_REQUIRED", safeMessage(failure), now());
          } else {
            int nextAttempt = saga.getAttemptCount() + 1;
            long seconds = Math.min(300, 1L << Math.min(nextAttempt, 8));
            saga.retry(
                "PUBLISHED_RESCHEDULE_PENDING",
                safeMessage(failure),
                now().plusSeconds(seconds),
                now());
          }
          sagas.saveAndFlush(saga);
        });
  }

  private void quarantine(UUID sagaId, String code, String message) {
    transactions.executeWithoutResult(
        ignored -> {
          PlanningPublishedRescheduleSaga saga = locked(sagaId);
          saga.quarantine(code, message, now());
          sagas.saveAndFlush(saga);
        });
  }

  private PlanningPublishedRescheduleSaga locked(UUID id) {
    return sagas
        .findForUpdate(id)
        .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
  }

  private PlanningPublishedRescheduleSaga required(UUID id) {
    return sagas
        .findById(id)
        .orElseThrow(PlanningPublishedRescheduleSagaService::bookingNotFound);
  }

  private PlanningPublishedRescheduleSagaState state(UUID id) {
    return required(id).getState();
  }

  private PlanningOrderRescheduleResponse completedResponse(UUID id) {
    return decodeResponse(required(id).getResponseJson());
  }

  private static CustomerIdentity identity(CustomerRentalSession session) {
    return new CustomerIdentity(
        session.getCustomerSubjectId(), "planning-customer-commitment");
  }

  private static LogisticsDependencyGateway.DriverTaskAudience audience(
      PlanningAssignmentReplacementRequest item) {
    return new LogisticsDependencyGateway.DriverTaskAudience(
        domainAudience(item.driverAudienceMode()), item.driverWorkerId(), item.driverName());
  }

  private static DriverTaskAudienceMode domainAudience(PlanningDriverAudienceMode mode) {
    if (mode == null) throw new IllegalArgumentException("Driver audience is required");
    return switch (mode) {
      case ASSIGNED_DRIVER -> DriverTaskAudienceMode.ASSIGNED_DRIVER;
      case WAREHOUSE_DRIVERS -> DriverTaskAudienceMode.WAREHOUSE_DRIVERS;
    };
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private static PlanningOrderRescheduleResponse response(
      CustomerBookingRescheduleReceipt receipt) {
    CustomerBookingRescheduleReceipt.Slot slot = receipt.confirmedSlot();
    return new PlanningOrderRescheduleResponse(
        receipt.orderId(),
        receipt.orderVersion(),
        receipt.sessionId(),
        receipt.sessionVersion(),
        receipt.bookingId(),
        receipt.warehouseId(),
        new PlanningRescheduleSlot(
            slot.slotId(),
            slot.version(),
            slot.date(),
            slot.kind(),
            slot.windowStart(),
            slot.windowEnd(),
            slot.deliveryPriceRubles(),
            slot.expiresAt()));
  }

  private String fingerprint(UUID identity, Object request) {
    try {
      return dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore.sha256(
          objectMapper.writeValueAsBytes(Map.of("identity", identity, "request", request)));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Published reschedule cannot be serialized", exception);
    }
  }

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Published reschedule cannot be serialized", exception);
    }
  }

  private PlanningOrderRescheduleRequest decodeRequest(String json) {
    try {
      return objectMapper.readValue(json, PlanningOrderRescheduleRequest.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored published reschedule request is invalid", exception);
    }
  }

  private PlanningPublishedAssignmentWithdrawalRequest decodeWithdrawal(String json) {
    try {
      return objectMapper.readValue(json, PlanningPublishedAssignmentWithdrawalRequest.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Stored published cancellation withdrawal is invalid", exception);
    }
  }

  private PlanningReplanCommitResult decodeCommit(String json) {
    try {
      return objectMapper.readValue(json, PlanningReplanCommitResult.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored task-board reschedule receipt is invalid", exception);
    }
  }

  private PlanningOrderRescheduleResponse decodeResponse(String json) {
    try {
      return objectMapper.readValue(json, PlanningOrderRescheduleResponse.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored published reschedule response is invalid", exception);
    }
  }

  private PlanningPublishedAssignmentWithdrawalResult decodeWithdrawalResponse(String json) {
    try {
      return objectMapper.readValue(json, PlanningPublishedAssignmentWithdrawalResult.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Stored published cancellation response is invalid", exception);
    }
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String safeMessage(Throwable failure) {
    if (failure instanceof OrderProblemException problem) return problem.getMessage();
    if (failure instanceof LogisticsDependencyException dependency) return dependency.getMessage();
    return "Требуется повторная проверка опубликованного переноса";
  }

  private static OrderProblemException terminalFailure(PlanningPublishedRescheduleSaga saga) {
    return conflict(
        saga.getLastErrorCode() == null
            ? "PUBLISHED_RESCHEDULE_RECOVERY_REQUIRED"
            : saga.getLastErrorCode(),
        saga.getLastErrorMessage() == null
            ? "Перенос требует ручной проверки"
            : saga.getLastErrorMessage());
  }

  private static OrderProblemException pending() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "PUBLISHED_RESCHEDULE_PENDING",
        "Перенос сохраняется; система продолжит безопасное восстановление");
  }

  private static OrderProblemException bookingNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_BOOKING_NOT_FOUND", "Бронирование не найдено");
  }

  private static OrderProblemException bookingNotEditable() {
    return conflict(
        "CUSTOMER_BOOKING_NOT_EDITABLE",
        "Бронирование нельзя перенести в его текущем состоянии");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  /** Coherent order, session and current confirmed-slot snapshot. */
  private record Context(
      RentalOrder order, CustomerRentalSession session, CustomerDeliverySlot slot) {}

  /** Locked local membership used to build one exact remote PREPARE snapshot. */
  private record PlanningMembership(
      List<DriverLogisticsTask> tasks, Map<UUID, DriverLogisticsTask> byExternalId) {}

  /** Source identity and task-board request produced from one locked local snapshot. */
  private record PreparedOwnerSnapshot(
      UUID sourcePlanId, PlanningReplanPrepareSnapshot remoteSnapshot) {}
}
