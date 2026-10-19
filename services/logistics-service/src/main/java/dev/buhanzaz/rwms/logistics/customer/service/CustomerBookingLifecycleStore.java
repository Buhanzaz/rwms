package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingCabin;
import static dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinEquipmentSelection;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityPriceZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityRestrictionZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeCharge;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationOperation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingMutationRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderCustomerLifecycleService;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderCustomerLifecycleService.CustomerOrderFence;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSaga;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSagaState;
import dev.buhanzaz.rwms.logistics.planning.repository.PlanningPublishedRescheduleSagaRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns short logistics-local booking mutation transactions. Remote order cancellation is never
 * invoked here; it resumes from the persisted cancellation checkpoint outside the transaction.
 */
@Service
@RequiredArgsConstructor
class CustomerBookingLifecycleStore {
  private static final int RECOVERY_BATCH_SIZE = 25;
  private static final int MAX_RECOVERY_ATTEMPTS = 8;
  private static final Duration RECOVERY_LEASE = Duration.ofMinutes(5);
  private static final long MAX_RECOVERY_DELAY_SECONDS = 300;
  private static final Set<CustomerBookingMutationState> OPEN_STATES =
      Set.of(CustomerBookingMutationState.PENDING, CustomerBookingMutationState.QUARANTINED);
  private static final Set<PlanningPublishedRescheduleSagaState> TERMINAL_PUBLISHED_SAGA_STATES =
      Set.of(
          PlanningPublishedRescheduleSagaState.COMPLETE,
          PlanningPublishedRescheduleSagaState.RELEASED);

  private final CustomerBookingMutationRepository mutations;
  private final CustomerBookingChangeChargeStore changeCharges;
  private final CustomerRentalSessionRepository sessions;
  private final CustomerDeliverySlotRepository slots;
  private final LogisticsDocumentRepository documents;
  private final EquipmentMovementTaskRepository movementTasks;
  private final EquipmentMovementTaskService movementTaskService;
  private final LogisticsDocumentService documentService;
  private final CustomerEquipmentCodec equipmentCodec;
  private final RentalOrderCustomerLifecycleService orderLifecycle;
  private final CustomerAuthorizer access;
  private final LogisticsTransactionLock transactionLock;
  private final CustomerDeliveryCapacityFence capacityFence;
  private final WarehouseCapacityJobRepository capacityJobs;
  private final WarehouseCapacityShiftRepository capacityShifts;
  private final WarehouseCapacityIsochroneTariffRepository isochroneTariffs;
  private final WarehouseCapacityPriceZoneRepository priceZones;
  private final WarehouseCapacityRestrictionZoneRepository restrictionZones;
  private final WarehouseCapacitySnapshotRepository capacitySnapshots;
  private final DriverLogisticsTaskRepository driverTasks;
  private final PlanningPublishedRescheduleSagaRepository publishedRescheduleSagas;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /**
   * Validates ownership and pre-start editability, cancels untouched local preparation, then
   * persists the recoverable order-cancellation checkpoint in the same transaction.
   */
  @Transactional
  CancellationStart prepareCancellation(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      long expectedSessionVersion) {
    return prepareCancellation(
        identity,
        bookingId,
        idempotencyKey,
        requestSha256,
        expectedSessionVersion,
        new ChangeConsent(null, null, false));
  }

  /**
   * Requires quoted customer consent inside the same transaction as the cancellation checkpoint.
   */
  @Transactional
  CancellationStart prepareCancellation(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      long expectedSessionVersion,
      ChangeConsent consent) {
    acquire(identity.subjectId(), idempotencyKey);
    CustomerBookingMutation replay =
        mutations
            .findByCustomerSubjectIdAndIdempotencyKey(identity.subjectId(), idempotencyKey)
            .orElse(null);
    if (replay != null) {
      requireReplay(replay, CustomerBookingMutationOperation.CANCEL, bookingId, requestSha256);
      return new CancellationStart(
          replay,
          requiredOwnedSession(identity.subjectId(), bookingId),
          true);
    }

    CustomerRentalSession session =
        lockedOwnedSession(identity.subjectId(), bookingId);
    requirePublishedSagaAdmission(session, null);
    if (session.getVersion() != expectedSessionVersion) throw versionConflict();
    if (session.getState() != CustomerSessionState.BOOKED) {
      throw notEditable("Бронирование уже отменяется или больше недоступно для отмены");
    }
    requireNoOpenMutation(bookingId);
    CustomerOrderFence order =
        orderLifecycle.requireCancellation(
            access.orderActor(identity, session.getWarehouseId()), session.getOrderId());
    CustomerDeliverySlot slot = lockedConfirmedSlot(session);
    CustomerBookingChangeCharge charge =
        changeCharges.admit(
            session,
            slot,
            CustomerBookingMutationOperation.CANCEL,
            null,
            null,
            consent.quoteId(),
            consent.quoteVersion(),
            consent.testPaymentRequested());
    cancelUntouchedFurniturePreparation(identity.subjectId(), session);
    cancelUntouchedShipmentDrafts(identity.subjectId(), session.getOrderId());

    OffsetDateTime timestamp = now();
    session.beginCancellation(expectedSessionVersion, timestamp);
    sessions.saveAndFlush(session);
    CustomerBookingMutation mutation =
        mutations.saveAndFlush(
            CustomerBookingMutation.cancel(
                identity.subjectId(),
                bookingId,
                session.getInquiryId(),
                session.getOrderId(),
                idempotencyKey,
                requestSha256,
                expectedSessionVersion,
                order.version(),
                slot.getId(),
                timestamp));
    changeCharges.bind(charge, mutation.getId(), consent.testPaymentRequested());
    return new CancellationStart(mutation, session, false);
  }

  /** Claims one exact cancellation for immediate processing if its lease is available. */
  @Transactional
  Optional<CancellationClaim> claim(UUID mutationId) {
    CustomerBookingMutation mutation =
        mutations
            .findForUpdate(mutationId)
            .orElseThrow(CustomerBookingLifecycleStore::notFound);
    return claim(mutation);
  }

  /** Claims a bounded due page while concurrent service instances skip locked rows. */
  @Transactional
  List<CancellationClaim> claimDue() {
    OffsetDateTime timestamp = databaseNow();
    return mutations.findDueForUpdate(timestamp, RECOVERY_BATCH_SIZE).stream()
        .map(this::claim)
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * Releases permanent capacity and completes the customer session only after order cancellation
   * has returned a durable CANCELLED result.
   */
  @Transactional
  CustomerRentalSession completeCancellation(UUID mutationId, UUID leaseToken) {
    CustomerBookingMutation mutation = lockedMutation(mutationId, leaseToken);
    CustomerDeliverySlot slotSnapshot =
        slots
            .findById(mutation.getOldSlotId())
            .orElseThrow(CustomerBookingLifecycleStore::slotNotFound);
    capacityFence.acquireDayAndWarehouseCapacity(
        slotSnapshot.getWarehouseId(), slotSnapshot.getDeliveryDate());
    CustomerRentalSession session =
        lockedOwnedSession(mutation.getCustomerSubjectId(), mutation.getBookingId());
    CustomerDeliverySlot slot =
        slots
            .findByIdForUpdate(mutation.getOldSlotId())
            .orElseThrow(CustomerBookingLifecycleStore::slotNotFound);
    slot.releaseConfirmed(mutation.getBookingId(), mutation.getOrderId());
    slots.saveAndFlush(slot);
    OffsetDateTime timestamp = databaseNow();
    session.completeCancellation(timestamp);
    sessions.saveAndFlush(session);
    mutation.complete(leaseToken, timestamp);
    mutations.saveAndFlush(mutation);
    changeCharges.complete(mutation.getId());
    return session;
  }

  /** Persists bounded backoff or quarantine for a failed leased cancellation attempt. */
  @Transactional
  CancellationFailure fail(
      UUID mutationId,
      UUID leaseToken,
      String errorCode,
      boolean nonRetryable) {
    CustomerBookingMutation mutation = lockedMutation(mutationId, leaseToken);
    OffsetDateTime timestamp = databaseNow();
    int nextAttempt = Math.addExact(mutation.getAttemptCount(), 1);
    boolean quarantine = nonRetryable || nextAttempt >= MAX_RECOVERY_ATTEMPTS;
    OffsetDateTime retryAt =
        quarantine ? null : timestamp.plusSeconds(recoveryDelaySeconds(nextAttempt));
    mutation.fail(leaseToken, errorCode, timestamp, retryAt, quarantine);
    mutations.saveAndFlush(mutation);
    return new CancellationFailure(nextAttempt, quarantine, mutation.getLastErrorCode());
  }

  /**
   * Atomically validates the recalculated workload, changes the order date, swaps confirmed slots
   * and records the replay receipt. Any failure rolls the old slot and order date back together.
   */
  @Transactional
  CustomerBookingRescheduleReceipt reschedule(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      RescheduleDecision decision) {
    return rescheduleWithCustomerProjection(
        identity, bookingId, idempotencyKey, requestSha256, decision, List.of());
  }

  /** Persists the exact CustomerApp cabin projection together with the atomic slot swap. */
  @Transactional
  CustomerBookingRescheduleReceipt rescheduleWithCustomerProjection(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      RescheduleDecision decision,
      List<CustomerBookingCabin> customerCabins) {
    return rescheduleWithCustomerProjection(
        identity,
        bookingId,
        idempotencyKey,
        requestSha256,
        decision,
        customerCabins,
        new ChangeConsent(null, null, false));
  }

  /** Quotes are admitted only after the unchanged route-capacity and owner editability fences. */
  @Transactional
  CustomerBookingRescheduleReceipt rescheduleWithCustomerProjection(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      RescheduleDecision decision,
      List<CustomerBookingCabin> customerCabins,
      ChangeConsent consent) {
    return reschedule(
        identity,
        bookingId,
        idempotencyKey,
        requestSha256,
        decision,
        new RescheduleAudit("CUSTOMER_SELECTED_SLOT", identity.subjectId(), null),
        null,
        customerCabins,
        consent);
  }

  /** Applies an audited dispatcher-approved reschedule through the same atomic booking mutation. */
  @Transactional
  CustomerBookingRescheduleReceipt reschedule(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      RescheduleDecision decision,
      RescheduleAudit audit) {
    return reschedule(
        identity, bookingId, idempotencyKey, requestSha256, decision, audit, null, List.of(), null);
  }

  /**
   * Applies the same atomic slot/session/audit mutation after an exact published task-board hold.
   * The caller's outer transaction must persist its owner-committed saga checkpoint with this
   * change.
   */
  @Transactional
  CustomerBookingRescheduleReceipt reschedulePublishedPreStart(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      RescheduleDecision decision,
      RescheduleAudit audit) {
    return reschedule(
        identity,
        bookingId,
        idempotencyKey,
        requestSha256,
        decision,
        audit,
        idempotencyKey,
        List.of(),
        null);
  }

  private CustomerBookingRescheduleReceipt reschedule(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      String requestSha256,
      RescheduleDecision decision,
      RescheduleAudit audit,
      UUID owningPublishedSagaId,
      List<CustomerBookingCabin> customerCabins,
      ChangeConsent consent) {
    acquire(identity.subjectId(), idempotencyKey);
    CustomerBookingMutation replay =
        mutations
            .findByCustomerSubjectIdAndIdempotencyKey(identity.subjectId(), idempotencyKey)
            .orElse(null);
    if (replay != null) {
      requireReplay(replay, CustomerBookingMutationOperation.RESCHEDULE, bookingId, requestSha256);
      return rescheduleReceipt(replay);
    }

    lockCapacityDays(
        decision.warehouseId(), decision.oldDeliveryDate(), decision.newDeliveryDate());
    CustomerRentalSession session =
        lockedOwnedSession(identity.subjectId(), bookingId);
    requirePublishedSagaAdmission(session, owningPublishedSagaId);
    if (session.getVersion() != decision.expectedSessionVersion()) throw versionConflict();
    if (session.getState() != CustomerSessionState.BOOKED) {
      throw notEditable("Бронирование нельзя перенести в его текущем состоянии");
    }
    requireNoOpenMutation(bookingId);
    CustomerDeliverySlot oldSlot = lockedConfirmedSlot(session);
    CustomerDeliverySlot newSlot =
        slots
            .findByIdForUpdate(decision.slotId())
            .orElseThrow(CustomerBookingLifecycleStore::slotNotFound);
    if (!decision.oldSlotId().equals(oldSlot.getId())
        || !decision.oldDeliveryDate().equals(oldSlot.getDeliveryDate())
        || !decision.newDeliveryDate().equals(newSlot.getDeliveryDate())
        || !decision.warehouseId().equals(newSlot.getWarehouseId())
        || oldSlot.getId().equals(newSlot.getId())
        || !identity.subjectId().equals(newSlot.getCustomerSubjectId())
        || !session.getInquiryId().equals(newSlot.getInquiryId())
        || !session.getWarehouseId().equals(newSlot.getWarehouseId())) {
      throw slotNotFound();
    }
    OffsetDateTime timestamp = now();
    if (newSlot.getVersion() != decision.expectedSlotVersion()
        || newSlot.getState() != CustomerDeliverySlotState.OFFERED
        || !newSlot.getExpiresAt().isAfter(timestamp)) {
      throw slotTaken();
    }
    String currentFingerprint = workloadFingerprint(newSlot, oldSlot.getId(), timestamp);
    if (!decision.workloadSha256().equals(currentFingerprint)) throw slotTaken();

    CustomerBookingChangeCharge charge =
        consent == null
            ? null
            : changeCharges.admit(
                session,
                oldSlot,
                CustomerBookingMutationOperation.RESCHEDULE,
                newSlot.getId(),
                newSlot.getVersion(),
                consent.quoteId(),
                consent.quoteVersion(),
                consent.testPaymentRequested());

    CustomerOrderFence order;
    if (owningPublishedSagaId != null) {
      if (decision.expectedOrderVersion() == null) {
        throw new IllegalArgumentException("Published reschedule requires an order version fence");
      }
      order =
          orderLifecycle.reschedulePublishedPreStart(
              access.orderActor(identity, session.getWarehouseId()),
              session.getOrderId(),
              newSlot.getDeliveryDate(),
              decision.expectedOrderVersion());
    } else {
      order =
          decision.expectedOrderVersion() == null
              ? orderLifecycle.reschedule(
                  access.orderActor(identity, session.getWarehouseId()),
                  session.getOrderId(),
                  newSlot.getDeliveryDate())
              : orderLifecycle.reschedule(
                  access.orderActor(identity, session.getWarehouseId()),
                  session.getOrderId(),
                  newSlot.getDeliveryDate(),
                  decision.expectedOrderVersion());
    }
    oldSlot.releaseConfirmed(bookingId, session.getOrderId());
    slots.saveAndFlush(oldSlot);
    newSlot.confirmReschedule(bookingId, session.getOrderId(), decision.capacityRemaining());
    slots.saveAndFlush(newSlot);
    session.reschedule(decision.expectedSessionVersion(), newSlot.getId(), timestamp);
    sessions.saveAndFlush(session);
    CustomerBookingRescheduleReceipt receipt = receipt(order, session, newSlot, customerCabins);
    String receiptJson = encodeReceipt(receipt);
    CustomerBookingMutation mutation =
        mutations.saveAndFlush(
            CustomerBookingMutation.completedReschedule(
                identity.subjectId(),
                bookingId,
                session.getInquiryId(),
                session.getOrderId(),
                idempotencyKey,
                requestSha256,
                decision.expectedSessionVersion(),
                order.version(),
                oldSlot.getId(),
                newSlot.getId(),
                audit.decisionCode(),
                audit.actorSubjectId(),
                audit.reason(),
                receiptJson,
                timestamp));
    if (consent != null) {
      changeCharges.bind(charge, mutation.getId(), consent.testPaymentRequested());
      changeCharges.complete(mutation.getId());
    }
    return receipt;
  }

  /** Untrusted customer consent is checked against the persisted quote before any fee settles. */
  record ChangeConsent(UUID quoteId, Long quoteVersion, boolean testPaymentRequested) {}

  /** Returns an exact completed reschedule replay before stale request fences are recalculated. */
  @Transactional
  Optional<CustomerBookingRescheduleReceipt> rescheduleReplay(
      CustomerIdentity identity, UUID bookingId, UUID idempotencyKey, String requestSha256) {
    acquire(identity.subjectId(), idempotencyKey);
    CustomerBookingMutation mutation =
        mutations
            .findByCustomerSubjectIdAndIdempotencyKey(identity.subjectId(), idempotencyKey)
            .orElse(null);
    if (mutation == null) return Optional.empty();
    requireReplay(mutation, CustomerBookingMutationOperation.RESCHEDULE, bookingId, requestSha256);
    if (mutation.getState() != CustomerBookingMutationState.COMPLETED) {
      throw notEditable("Перенос бронирования ещё не завершён");
    }
    return Optional.of(rescheduleReceipt(mutation));
  }

  private CustomerBookingRescheduleReceipt receipt(
      CustomerOrderFence order,
      CustomerRentalSession session,
      CustomerDeliverySlot slot,
      List<CustomerBookingCabin> customerCabins) {
    return new CustomerBookingRescheduleReceipt(
        order.orderId(),
        order.version(),
        session.getId(),
        session.getVersion(),
        session.getBookingId(),
        session.getWarehouseId(),
        session.getInquiryId(),
        slot.getDeliveryAddress(),
        customerCabins,
        new CustomerBookingRescheduleReceipt.Slot(
            slot.getId(),
            slot.getVersion(),
            slot.getDeliveryDate(),
            slot.getKind().name(),
            slot.getWindowStart(),
            slot.getWindowEnd(),
            slot.getDeliveryPriceRubles(),
            slot.getExpiresAt()));
  }

  private String encodeReceipt(CustomerBookingRescheduleReceipt receipt) {
    try {
      return objectMapper.writeValueAsString(receipt);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Completed booking reschedule receipt cannot be serialized", exception);
    }
  }

  private CustomerBookingRescheduleReceipt rescheduleReceipt(CustomerBookingMutation mutation) {
    String json = mutation.getRescheduleResultJson();
    if (json == null) throw unsupportedRescheduleReplay();
    try {
      return objectMapper.readValue(json, CustomerBookingRescheduleReceipt.class);
    } catch (JacksonException exception) {
      throw unsupportedRescheduleReplay();
    }
  }

  @Transactional(readOnly = true)
  CustomerRentalSession requiredOwnedSession(UUID subjectId, UUID bookingId) {
    return sessions
        .findByBookingIdAndCustomerSubjectId(bookingId, subjectId)
        .orElseThrow(CustomerBookingLifecycleStore::notFound);
  }

  private Optional<CancellationClaim> claim(CustomerBookingMutation mutation) {
    OffsetDateTime timestamp = databaseNow();
    UUID leaseToken = UUID.randomUUID();
    if (!mutation.claim(leaseToken, timestamp, timestamp.plus(RECOVERY_LEASE))) {
      return Optional.empty();
    }
    mutations.saveAndFlush(mutation);
    return Optional.of(
        new CancellationClaim(
            mutation.getId(),
            mutation.getCustomerSubjectId(),
            mutation.getBookingId(),
            mutation.getOrderId(),
            mutation.getExpectedOrderVersion(),
            mutation.getIdempotencyKey(),
            leaseToken));
  }

  private CustomerBookingMutation lockedMutation(UUID mutationId, UUID leaseToken) {
    CustomerBookingMutation mutation =
        mutations
            .findForUpdate(mutationId)
            .orElseThrow(CustomerBookingLifecycleStore::notFound);
    if (!mutation.hasLiveLease(leaseToken, databaseNow())) {
      throw new IllegalStateException("Customer booking mutation lease is stale");
    }
    return mutation;
  }

  private CustomerRentalSession lockedOwnedSession(UUID subjectId, UUID bookingId) {
    CustomerRentalSession session =
        sessions
            .findByBookingIdForUpdate(bookingId)
            .orElseThrow(CustomerBookingLifecycleStore::notFound);
    if (!subjectId.equals(session.getCustomerSubjectId())) throw notFound();
    return session;
  }

  private CustomerDeliverySlot lockedConfirmedSlot(CustomerRentalSession session) {
    CustomerDeliverySlot slot =
        slots
            .findByIdForUpdate(session.getDeliverySlotId())
            .orElseThrow(CustomerBookingLifecycleStore::slotNotFound);
    if (!session.getCustomerSubjectId().equals(slot.getCustomerSubjectId())
        || !session.getInquiryId().equals(slot.getInquiryId())
        || slot.getState() != CustomerDeliverySlotState.CONFIRMED
        || !session.getBookingId().equals(slot.getBookingId())
        || !session.getOrderId().equals(slot.getOrderId())) {
      throw slotNotFound();
    }
    return slot;
  }

  private void cancelUntouchedFurniturePreparation(
      UUID customerSubjectId, CustomerRentalSession session) {
    List<UUID> cabinIds =
        equipmentCodec.decode(session.getEquipmentSelectionJson()).stream()
            .map(CustomerCabinEquipmentSelection::cabinUnitId)
            .distinct()
            .toList();
    for (UUID cabinId : cabinIds) {
      UUID creationKey =
          deterministic("customer-furniture-task:" + session.getBookingId() + ":" + cabinId);
      EquipmentMovementTask task =
          movementTasks
              .findByCreatedBySubjectIdAndIdempotencyKey(customerSubjectId, creationKey)
              .orElse(null);
      if (task == null
          || task.getState() == EquipmentMovementTaskState.CANCELLED
          || task.getState() == EquipmentMovementTaskState.CANCELLING) {
        continue;
      }
      if (task.getState() == EquipmentMovementTaskState.EXECUTING || task.getState().isTerminal()) {
        throw notEditable(
            "Бронирование нельзя отменить после начала подготовки мебели или отгрузки");
      }
      movementTaskService.cancel(
          customerSubjectId,
          task.getId(),
          deterministic(
              "customer-booking-cancel-furniture:" + session.getBookingId() + ":" + cabinId),
          new CancelEquipmentMovementTaskRequest(task.getVersion()));
    }
  }

  private void cancelUntouchedShipmentDrafts(UUID customerSubjectId, UUID orderId) {
    for (LogisticsDocument document :
        documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.SHIPMENT, orderId)) {
      if (document.getState() == LogisticsDocumentState.CANCELLED) continue;
      if (document.getState() != LogisticsDocumentState.DRAFT
          || document.getScheduledDate() != null) {
        throw notEditable("Бронирование нельзя отменить после начала отгрузки");
      }
      documentService.cancelShipment(
          customerSubjectId,
          deterministic("customer-booking-cancel-shipment:" + orderId + ":" + document.getId()),
          deterministic("customer-booking-cancel-correlation:" + orderId + ":" + document.getId()),
          document.getId(),
          document.getVersion());
    }
  }

  private void lockCapacityDays(
      UUID warehouseId, LocalDate oldDeliveryDate, LocalDate newDeliveryDate) {
    List<LocalDate> days =
        List.of(oldDeliveryDate, newDeliveryDate).stream().distinct().sorted().toList();
    days.forEach(day -> capacityFence.acquireDayAndWarehouseCapacity(warehouseId, day));
  }

  private String workloadFingerprint(
      CustomerDeliverySlot candidate, UUID excludedSlotId, OffsetDateTime timestamp) {
    List<CustomerDeliverySlot> workload =
        CustomerCapacityWorkloadFingerprint.capacitySlots(
            slots.findCapacityWorkloadForUpdate(
                candidate.getWarehouseId(),
                candidate.getDeliveryDate(),
                CustomerDeliverySlotState.CONFIRMED,
                CustomerDeliverySlotState.CHECKOUT_PENDING,
                CustomerDeliverySlotState.HELD,
                timestamp),
            excludedSlotId);
    List<WarehouseCapacityJob> jobs =
        capacityJobs
            .findCapacityWorkload(candidate.getWarehouseId(), candidate.getDeliveryDate())
            .stream()
            .filter(job -> job.getTaskType() == WarehouseCapacityTaskType.DELIVERY)
            .toList();
    WarehouseCapacitySnapshot snapshot =
        capacitySnapshots.findByWarehouseId(candidate.getWarehouseId()).orElse(null);
    long reservations =
        driverTasks.countWholeDayDeliveryReservations(
            candidate.getWarehouseId(), candidate.getDeliveryDate());
    return CustomerCapacityWorkloadFingerprint.sha256(
        workload,
        jobs,
        capacityShifts.findCapacityShifts(candidate.getWarehouseId(), candidate.getDeliveryDate()),
        snapshot,
        isochroneTariffs.findTariffs(candidate.getWarehouseId()),
        priceZones.findTariffZones(candidate.getWarehouseId()),
        restrictionZones.findRestrictionZones(candidate.getWarehouseId()),
        reservations);
  }

  private void requireNoOpenMutation(UUID bookingId) {
    if (!mutations
        .findOpenForBookingForUpdate(bookingId, OPEN_STATES)
        .isEmpty()) {
      throw notEditable("Другая операция бронирования ещё выполняется");
    }
  }

  private void requirePublishedSagaAdmission(
      CustomerRentalSession session, UUID owningPublishedSagaId) {
    List<PlanningPublishedRescheduleSaga> active =
        publishedRescheduleSagas
            .findAllForBookingAdmission(session.getOrderId(), session.getBookingId())
            .stream()
            .filter(saga -> !TERMINAL_PUBLISHED_SAGA_STATES.contains(saga.getState()))
            .toList();
    if (owningPublishedSagaId == null) {
      if (!active.isEmpty()) throw publishedChangeInProgress();
      return;
    }
    boolean ownsActiveSaga = false;
    for (PlanningPublishedRescheduleSaga saga : active) {
      if (owningPublishedSagaId.equals(saga.getId())) {
        ownsActiveSaga = true;
      } else {
        throw publishedChangeInProgress();
      }
    }
    if (!ownsActiveSaga) throw publishedChangeInProgress();
  }

  private void acquire(UUID subjectId, UUID idempotencyKey) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Customer booking command identity is required");
    }
    transactionLock.acquire(
        "customer-booking-mutation:"
            + subjectId
            + ":"
            + idempotencyKey);
  }

  private static void requireReplay(
      CustomerBookingMutation mutation,
      CustomerBookingMutationOperation operation,
      UUID bookingId,
      String requestSha256) {
    if (!mutation.matches(operation, bookingId, requestSha256)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "Idempotency-Key уже использован для другой операции бронирования");
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private OffsetDateTime databaseNow() {
    return OffsetDateTime.ofInstant(mutations.currentDatabaseTimestamp(), ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static long recoveryDelaySeconds(int attemptNumber) {
    int shift = Math.max(0, Math.min(attemptNumber - 1, 20));
    return Math.min(MAX_RECOVERY_DELAY_SECONDS, 2L << shift);
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_BOOKING_NOT_FOUND", "Бронирование не найдено");
  }

  private static OrderProblemException slotNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_DELIVERY_SLOT_NOT_FOUND", "Слот доставки не найден");
  }

  private static OrderProblemException versionConflict() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_BOOKING_VERSION_CONFLICT",
        "Бронирование уже изменилось; обновите данные и повторите действие");
  }

  private static OrderProblemException slotTaken() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_DELIVERY_SLOT_TAKEN",
        "Нагрузка изменилась; выберите доступное время заново");
  }

  private static OrderProblemException notEditable(String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, "CUSTOMER_BOOKING_NOT_EDITABLE", message);
  }

  private static OrderProblemException publishedChangeInProgress() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_BOOKING_PUBLISHED_CHANGE_IN_PROGRESS",
        "Изменение опубликованной доставки ещё выполняется; обновите бронирование и повторите"
            + " действие");
  }

  private static OrderProblemException unsupportedRescheduleReplay() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_BOOKING_RESCHEDULE_REPLAY_UNSUPPORTED",
        "Сохранённый результат переноса недоступен; обновите бронирование и повторите действие с"
            + " новым Idempotency-Key");
  }

  /** Stored cancellation checkpoint and current session returned to the orchestrator. */
  record CancellationStart(
      CustomerBookingMutation mutation, CustomerRentalSession session, boolean replayed) {}

  /** Lease-fenced immutable cancellation work executed outside the local transaction. */
  record CancellationClaim(
      UUID mutationId,
      UUID customerSubjectId,
      UUID bookingId,
      UUID orderId,
      long expectedOrderVersion,
      UUID idempotencyKey,
      UUID leaseToken) {}

  /** Current bounded failure state exposed as a stable customer booking outcome. */
  record CancellationFailure(int attemptCount, boolean quarantined, String errorCode) {}

  /** Route and workload fence calculated before the atomic reschedule transaction. */
  record RescheduleDecision(
      long expectedSessionVersion,
      UUID warehouseId,
      UUID oldSlotId,
      LocalDate oldDeliveryDate,
      LocalDate newDeliveryDate,
      UUID slotId,
      long expectedSlotVersion,
      String workloadSha256,
      int capacityRemaining,
      Long expectedOrderVersion) {
    RescheduleDecision(
        long expectedSessionVersion,
        UUID warehouseId,
        UUID oldSlotId,
        LocalDate oldDeliveryDate,
        LocalDate newDeliveryDate,
        UUID slotId,
        long expectedSlotVersion,
        String workloadSha256,
        int capacityRemaining) {
      this(
          expectedSessionVersion,
          warehouseId,
          oldSlotId,
          oldDeliveryDate,
          newDeliveryDate,
          slotId,
          expectedSlotVersion,
          workloadSha256,
          capacityRemaining,
          null);
    }

    RescheduleDecision withExpectedOrderVersion(long value) {
      if (value < 0) throw new IllegalArgumentException("Expected order version is invalid");
      return new RescheduleDecision(
          expectedSessionVersion,
          warehouseId,
          oldSlotId,
          oldDeliveryDate,
          newDeliveryDate,
          slotId,
          expectedSlotVersion,
          workloadSha256,
          capacityRemaining,
          value);
    }

    RescheduleDecision {
      Objects.requireNonNull(warehouseId, "warehouseId");
      Objects.requireNonNull(oldSlotId, "oldSlotId");
      Objects.requireNonNull(oldDeliveryDate, "oldDeliveryDate");
      Objects.requireNonNull(newDeliveryDate, "newDeliveryDate");
      Objects.requireNonNull(slotId, "slotId");
      Objects.requireNonNull(workloadSha256, "workloadSha256");
      if (expectedSessionVersion < 0
          || expectedSlotVersion < 0
          || capacityRemaining < 0
          || expectedOrderVersion != null && expectedOrderVersion < 0) {
        throw new IllegalArgumentException("Reschedule decision is invalid");
      }
    }
  }

  /** Structured human-decision audit retained with a completed reschedule receipt. */
  record RescheduleAudit(String decisionCode, UUID actorSubjectId, String reason) {
    RescheduleAudit {
      Objects.requireNonNull(decisionCode, "decisionCode");
      Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    }
  }
}
