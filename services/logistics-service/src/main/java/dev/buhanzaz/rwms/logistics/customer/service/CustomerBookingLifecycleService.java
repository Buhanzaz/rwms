package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CancelCustomerBookingRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.RescheduleCustomerBookingRequest;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.CancellationClaim;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.CancellationFailure;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.CancellationStart;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.RescheduleDecision;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleStore.RescheduleAudit;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CustomerApp facade for post-checkout cancellation and rescheduling. Cancellation delegates the
 * remotely effective release to the existing rental-order recovery saga; rescheduling commits only
 * through one local atomic slot/order/session transaction.
 */
@Service
@RequiredArgsConstructor
public class CustomerBookingLifecycleService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(CustomerBookingLifecycleService.class);

  private final CustomerBookingLifecycleStore store;
  private final CustomerDeliverySlotService deliverySlots;
  private final CustomerBookingService bookings;
  private final CustomerAuthorizer access;
  private final RentalOrderService rentalOrders;

  /** Submits or idempotently resumes cancellation of one untouched completed booking. */
  public CustomerBookingResponse cancel(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      CancelCustomerBookingRequest request) {
    String requestHash = sha256("CANCEL\n" + bookingId + "\n" + request.expectedVersion());
    CancellationStart start =
        store.prepareCancellation(
            identity, bookingId, idempotencyKey, requestHash, request.expectedVersion());
    if (start.mutation().getState() == CustomerBookingMutationState.COMPLETED) {
      return bookings.response(identity, start.session(), "CANCELLED", null);
    }
    if (start.mutation().getState() == CustomerBookingMutationState.QUARANTINED) {
      return bookings.response(
          identity,
          start.session(),
          "CANCELLATION_PENDING",
          "CUSTOMER_BOOKING_RECONCILIATION_REQUIRED");
    }
    Optional<CancellationClaim> claim =
        store.claim(start.mutation().getId());
    if (claim.isEmpty()) {
      CustomerRentalSession current =
          store.requiredOwnedSession(identity.subjectId(), bookingId);
      return bookings.response(
          identity,
          current,
          CustomerBookingService.status(current),
          start.mutation().getLastErrorCode());
    }
    return process(identity, claim.get());
  }

  /** Atomically moves one still-editable booking to a recalculated replacement slot. */
  public CustomerBookingResponse reschedule(
      CustomerIdentity identity,
      UUID bookingId,
      UUID idempotencyKey,
      RescheduleCustomerBookingRequest request) {
    String requestHash =
        sha256(
            "RESCHEDULE\n"
                + bookingId
                + "\n"
                + request.expectedVersion()
                + "\n"
                + request.slotId()
                + "\n"
                + request.slotVersion());
    Optional<CustomerBookingRescheduleReceipt> replay =
        store.rescheduleReplay(identity, bookingId, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return replay.get().customerResponse();
    }
    RescheduleDecision decision = deliverySlots.prepareReschedule(identity, bookingId, request);
    CustomerRentalSession current =
        store.requiredOwnedSession(identity.subjectId(), bookingId);
    CustomerBookingResponse currentProjection =
        bookings.response(identity, current, CustomerBookingService.status(current), null);
    CustomerBookingRescheduleReceipt receipt =
        store.rescheduleWithCustomerProjection(
            identity,
            bookingId,
            idempotencyKey,
            requestHash,
            decision,
            currentProjection.cabins());
    return receipt.customerResponse();
  }

  /**
   * Applies one dispatcher-recorded customer agreement through the normal route-feasible slot
   * swap. The supplied order fence prevents a stale recovery proposal from changing a newer
   * customer commitment.
   */
  public CustomerBookingRescheduleReceipt rescheduleForDispatcher(
      CustomerIdentity customerIdentity,
      UUID bookingId,
      UUID orderId,
      UUID idempotencyKey,
      long expectedOrderVersion,
      String decisionCode,
      UUID decisionActorSubjectId,
      String decisionReason,
      RescheduleCustomerBookingRequest request) {
    String requestHash =
        sha256(
            "DISPATCHER_RESCHEDULE\n"
                + bookingId
                + "\n"
                + orderId
                + "\n"
                + expectedOrderVersion
                + "\n"
                + request.expectedVersion()
                + "\n"
                + request.slotId()
                + "\n"
                + request.slotVersion()
                + "\n"
                + decisionCode
                + "\n"
                + decisionActorSubjectId
                + "\n"
                + (decisionReason == null ? "" : decisionReason.trim()));
    Optional<CustomerBookingRescheduleReceipt> replay =
        store.rescheduleReplay(customerIdentity, bookingId, idempotencyKey, requestHash);
    if (replay.isPresent()) return replay.get();
    RescheduleDecision decision =
        deliverySlots
            .prepareReschedule(customerIdentity, bookingId, request)
            .withExpectedOrderVersion(expectedOrderVersion);
    return store.reschedule(
        customerIdentity,
        bookingId,
        idempotencyKey,
        requestHash,
        decision,
        new RescheduleAudit(decisionCode, decisionActorSubjectId, decisionReason));
  }

  /**
   * Performs route and capacity calculation before the owner saga takes its local commit locks.
   * The returned opaque preparation is not authoritative until the atomic commit rechecks it.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public PublishedReschedulePreparation preparePublishedForDispatcher(
      CustomerIdentity customerIdentity,
      UUID bookingId,
      UUID orderId,
      UUID idempotencyKey,
      long expectedOrderVersion,
      String decisionCode,
      UUID decisionActorSubjectId,
      String decisionReason,
      RescheduleCustomerBookingRequest request) {
    String requestHash =
        sha256(
            "DISPATCHER_PUBLISHED_RESCHEDULE\n"
                + bookingId
                + "\n"
                + orderId
                + "\n"
                + expectedOrderVersion
                + "\n"
                + request.expectedVersion()
                + "\n"
                + request.slotId()
                + "\n"
                + request.slotVersion()
                + "\n"
                + decisionCode
                + "\n"
                + decisionActorSubjectId
                + "\n"
                + (decisionReason == null ? "" : decisionReason.trim()));
    Optional<CustomerBookingRescheduleReceipt> replay =
        store.rescheduleReplay(customerIdentity, bookingId, idempotencyKey, requestHash);
    if (replay.isPresent()) return PublishedReschedulePreparation.replay(replay.get());
    RescheduleDecision decision =
        deliverySlots
            .prepareReschedule(customerIdentity, bookingId, request)
            .withExpectedOrderVersion(expectedOrderVersion);
    return PublishedReschedulePreparation.fresh(
        customerIdentity,
        bookingId,
        idempotencyKey,
        requestHash,
        decision,
        new RescheduleAudit(decisionCode, decisionActorSubjectId, decisionReason));
  }

  /**
   * Atomically consumes an earlier route preparation while the owner saga is locked. The store
   * repeats every mutable session, slot, workload and order fence before applying the effect.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustomerBookingRescheduleReceipt commitPreparedPublishedForDispatcher(
      PublishedReschedulePreparation preparation) {
    PublishedReschedulePreparation required =
        Objects.requireNonNull(preparation, "preparation");
    if (required.replay != null) return required.replay;
    return store.reschedulePublishedPreStart(
        required.customerIdentity,
        required.bookingId,
        required.idempotencyKey,
        required.requestHash,
        required.decision,
        required.audit);
  }

  /** Opaque route result whose mutable inputs are revalidated by the atomic booking store. */
  public static final class PublishedReschedulePreparation {
    private final CustomerIdentity customerIdentity;
    private final UUID bookingId;
    private final UUID idempotencyKey;
    private final String requestHash;
    private final RescheduleDecision decision;
    private final RescheduleAudit audit;
    private final CustomerBookingRescheduleReceipt replay;

    private PublishedReschedulePreparation(
        CustomerIdentity customerIdentity,
        UUID bookingId,
        UUID idempotencyKey,
        String requestHash,
        RescheduleDecision decision,
        RescheduleAudit audit,
        CustomerBookingRescheduleReceipt replay) {
      this.customerIdentity = customerIdentity;
      this.bookingId = bookingId;
      this.idempotencyKey = idempotencyKey;
      this.requestHash = requestHash;
      this.decision = decision;
      this.audit = audit;
      this.replay = replay;
    }

    private static PublishedReschedulePreparation fresh(
        CustomerIdentity customerIdentity,
        UUID bookingId,
        UUID idempotencyKey,
        String requestHash,
        RescheduleDecision decision,
        RescheduleAudit audit) {
      return new PublishedReschedulePreparation(
          Objects.requireNonNull(customerIdentity, "customerIdentity"),
          Objects.requireNonNull(bookingId, "bookingId"),
          Objects.requireNonNull(idempotencyKey, "idempotencyKey"),
          Objects.requireNonNull(requestHash, "requestHash"),
          Objects.requireNonNull(decision, "decision"),
          Objects.requireNonNull(audit, "audit"),
          null);
    }

    private static PublishedReschedulePreparation replay(
        CustomerBookingRescheduleReceipt replay) {
      return new PublishedReschedulePreparation(
          null, null, null, null, null, null, Objects.requireNonNull(replay, "replay"));
    }
  }

  /** Reclaims due cancellation checkpoints across service instances using database leases. */
  @Scheduled(
      fixedDelayString = "${rwms.logistics.customer.booking-mutation-reconcile-delay:2s}",
      initialDelayString = "${rwms.logistics.customer.booking-mutation-reconcile-initial-delay:3s}")
  public void recoverPendingCancellations() {
    for (CancellationClaim claim : store.claimDue()) {
      CustomerIdentity identity =
          new CustomerIdentity(
              claim.customerSubjectId(), claim.customerSubjectId().toString());
      process(identity, claim);
    }
  }

  private CustomerBookingResponse process(CustomerIdentity identity, CancellationClaim claim) {
    try {
      CustomerRentalSession current =
          store.requiredOwnedSession(identity.subjectId(), claim.bookingId());
      RentalOrderService.MutationResult result =
          rentalOrders.cancel(
              access.orderActor(identity, current.getWarehouseId()),
              claim.orderId(),
              claim.expectedOrderVersion(),
              claim.idempotencyKey());
      if (result.response().status() != RentalOrderStatus.CANCELLED) {
        throw new IllegalStateException("Customer order cancellation did not reach CANCELLED");
      }
      CustomerRentalSession completed =
          store.completeCancellation(claim.mutationId(), claim.leaseToken());
      return bookings.response(identity, completed, "CANCELLED", null);
    } catch (RuntimeException exception) {
      CancellationFailure failure;
      try {
        failure =
            store.fail(
                claim.mutationId(),
                claim.leaseToken(),
                errorCode(exception),
                nonRetryable(exception));
      } catch (RuntimeException staleLease) {
        LOGGER.warn(
            "Customer booking cancellation lease could not be finalized: mutationId={},"
                + " bookingId={}",
            claim.mutationId(),
            claim.bookingId());
        CustomerRentalSession current =
            store.requiredOwnedSession(identity.subjectId(), claim.bookingId());
        return bookings.response(
            identity,
            current,
            CustomerBookingService.status(current),
            "CUSTOMER_BOOKING_CANCELLATION_PENDING");
      }
      LOGGER.warn(
          "Customer booking cancellation deferred: mutationId={}, bookingId={}, code={},"
              + " attempt={}, quarantined={}",
          claim.mutationId(),
          claim.bookingId(),
          failure.errorCode(),
          failure.attemptCount(),
          failure.quarantined());
      CustomerRentalSession current =
          store.requiredOwnedSession(identity.subjectId(), claim.bookingId());
      return bookings.response(
          identity,
          current,
          "CANCELLATION_PENDING",
          failure.quarantined() ? "CUSTOMER_BOOKING_RECONCILIATION_REQUIRED" : failure.errorCode());
    }
  }

  private static boolean nonRetryable(RuntimeException exception) {
    return exception instanceof OrderProblemException problem
        && (problem.status().is4xxClientError()
            || "ORDER_MUTATION_RECONCILIATION_REQUIRED".equals(problem.code()));
  }

  private static String errorCode(RuntimeException exception) {
    if (exception instanceof OrderProblemException problem
        && problem.code() != null
        && !problem.code().isBlank()) {
      return bounded(problem.code());
    }
    return "CUSTOMER_BOOKING_CANCELLATION_FAILED";
  }

  private static String bounded(String value) {
    String normalized = value.trim();
    return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
