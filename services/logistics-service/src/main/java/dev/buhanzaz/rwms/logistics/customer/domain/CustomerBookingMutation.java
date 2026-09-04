package dev.buhanzaz.rwms.logistics.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable idempotency and recovery checkpoint for cancellation or rescheduling of one completed
 * CustomerApp booking. The original checkout, cabin selection and furniture intent remain
 * immutable; this row records only the later lifecycle command.
 */
@Entity
@Table(name = "customer_booking_mutation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerBookingMutation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "customer_subject_id", nullable = false)
  private UUID customerSubjectId;

  @Column(name = "booking_id", nullable = false)
  private UUID bookingId;

  @Column(name = "inquiry_id", nullable = false)
  private UUID inquiryId;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation", nullable = false, length = 32)
  private CustomerBookingMutationOperation operation;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private CustomerBookingMutationState state;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "expected_session_version", nullable = false)
  private long expectedSessionVersion;

  @Column(name = "expected_order_version", nullable = false)
  private long expectedOrderVersion;

  @Column(name = "old_slot_id", nullable = false)
  private UUID oldSlotId;

  @Column(name = "new_slot_id")
  private UUID newSlotId;

  @Column(name = "decision_code", length = 64)
  private String decisionCode;

  @Column(name = "decision_actor_subject_id")
  private UUID decisionActorSubjectId;

  @Column(name = "decision_reason", length = 2_000)
  private String decisionReason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "reschedule_result_json", columnDefinition = "jsonb")
  private String rescheduleResultJson;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at")
  private OffsetDateTime nextAttemptAt;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "lease_until")
  private OffsetDateTime leaseUntil;

  @Column(name = "last_error_code", length = 64)
  private String lastErrorCode;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Starts a cancellation checkpoint before any remotely effective order release is attempted. */
  public static CustomerBookingMutation cancel(
      UUID customerSubjectId,
      UUID bookingId,
      UUID inquiryId,
      UUID orderId,
      UUID idempotencyKey,
      String requestSha256,
      long expectedSessionVersion,
      long expectedOrderVersion,
      UUID oldSlotId,
      OffsetDateTime timestamp) {
    return create(
        customerSubjectId,
        bookingId,
        inquiryId,
        orderId,
        CustomerBookingMutationOperation.CANCEL,
        idempotencyKey,
        requestSha256,
        expectedSessionVersion,
        expectedOrderVersion,
        oldSlotId,
        null,
        CustomerBookingMutationState.PENDING,
        timestamp);
  }

  /** Records an atomically completed delivery-slot replacement for idempotent replay. */
  public static CustomerBookingMutation completedReschedule(
      UUID customerSubjectId,
      UUID bookingId,
      UUID inquiryId,
      UUID orderId,
      UUID idempotencyKey,
      String requestSha256,
      long expectedSessionVersion,
      long expectedOrderVersion,
      UUID oldSlotId,
      UUID newSlotId,
      String decisionCode,
      UUID decisionActorSubjectId,
      String decisionReason,
      String rescheduleResultJson,
      OffsetDateTime timestamp) {
    CustomerBookingMutation mutation =
        create(
            customerSubjectId,
            bookingId,
            inquiryId,
            orderId,
            CustomerBookingMutationOperation.RESCHEDULE,
            idempotencyKey,
            requestSha256,
            expectedSessionVersion,
            expectedOrderVersion,
            oldSlotId,
            Objects.requireNonNull(newSlotId, "newSlotId"),
            CustomerBookingMutationState.COMPLETED,
            timestamp);
    mutation.decisionCode = requiredCode(decisionCode);
    mutation.decisionActorSubjectId =
        Objects.requireNonNull(decisionActorSubjectId, "decisionActorSubjectId");
    mutation.decisionReason = optionalReason(decisionReason);
    mutation.rescheduleResultJson =
        Objects.requireNonNull(rescheduleResultJson, "rescheduleResultJson");
    mutation.completedAt = timestamp;
    mutation.nextAttemptAt = null;
    return mutation;
  }

  private static CustomerBookingMutation create(
      UUID customerSubjectId,
      UUID bookingId,
      UUID inquiryId,
      UUID orderId,
      CustomerBookingMutationOperation operation,
      UUID idempotencyKey,
      String requestSha256,
      long expectedSessionVersion,
      long expectedOrderVersion,
      UUID oldSlotId,
      UUID newSlotId,
      CustomerBookingMutationState state,
      OffsetDateTime timestamp) {
    if (expectedSessionVersion < 0 || expectedOrderVersion < 0) {
      throw new IllegalArgumentException("Booking mutation versions are invalid");
    }
    CustomerBookingMutation mutation = new CustomerBookingMutation();
    mutation.customerSubjectId = Objects.requireNonNull(customerSubjectId, "customerSubjectId");
    mutation.bookingId = Objects.requireNonNull(bookingId, "bookingId");
    mutation.inquiryId = Objects.requireNonNull(inquiryId, "inquiryId");
    mutation.orderId = Objects.requireNonNull(orderId, "orderId");
    mutation.operation = Objects.requireNonNull(operation, "operation");
    mutation.state = Objects.requireNonNull(state, "state");
    mutation.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    mutation.requestSha256 = requireHash(requestSha256);
    mutation.expectedSessionVersion = expectedSessionVersion;
    mutation.expectedOrderVersion = expectedOrderVersion;
    mutation.oldSlotId = Objects.requireNonNull(oldSlotId, "oldSlotId");
    mutation.newSlotId = newSlotId;
    mutation.attemptCount = 0;
    mutation.createdAt = Objects.requireNonNull(timestamp, "timestamp");
    mutation.updatedAt = timestamp;
    mutation.nextAttemptAt = state == CustomerBookingMutationState.PENDING ? timestamp : null;
    return mutation;
  }

  /** Claims a due cancellation for one bounded recovery attempt. */
  public boolean claim(
      UUID nextLeaseToken, OffsetDateTime timestamp, OffsetDateTime nextLeaseUntil) {
    if (state != CustomerBookingMutationState.PENDING
        || nextAttemptAt == null
        || nextAttemptAt.isAfter(timestamp)
        || leaseUntil != null && leaseUntil.isAfter(timestamp)) {
      return false;
    }
    if (!nextLeaseUntil.isAfter(timestamp)) {
      throw new IllegalArgumentException("Booking mutation lease is invalid");
    }
    leaseToken = Objects.requireNonNull(nextLeaseToken, "nextLeaseToken");
    leaseUntil = Objects.requireNonNull(nextLeaseUntil, "nextLeaseUntil");
    updatedAt = timestamp;
    return true;
  }

  /** Returns whether the supplied worker still owns this cancellation checkpoint. */
  public boolean hasLiveLease(UUID expectedLeaseToken, OffsetDateTime timestamp) {
    return state == CustomerBookingMutationState.PENDING
        && Objects.equals(leaseToken, expectedLeaseToken)
        && leaseUntil != null
        && leaseUntil.isAfter(timestamp);
  }

  /** Schedules a bounded retry or quarantines an unrecoverable customer cancellation. */
  public void fail(
      UUID expectedLeaseToken,
      String errorCode,
      OffsetDateTime timestamp,
      OffsetDateTime retryAt,
      boolean quarantine) {
    requireLease(expectedLeaseToken, timestamp);
    attemptCount = Math.addExact(attemptCount, 1);
    lastErrorCode = boundedCode(errorCode);
    leaseToken = null;
    leaseUntil = null;
    if (quarantine) {
      state = CustomerBookingMutationState.QUARANTINED;
      nextAttemptAt = null;
    } else {
      if (retryAt == null || !retryAt.isAfter(timestamp)) {
        throw new IllegalArgumentException("Booking mutation retry is invalid");
      }
      nextAttemptAt = retryAt;
    }
    updatedAt = timestamp;
  }

  /** Completes cancellation only after the order, slot and session transitions all succeeded. */
  public void complete(UUID expectedLeaseToken, OffsetDateTime timestamp) {
    requireLease(expectedLeaseToken, timestamp);
    state = CustomerBookingMutationState.COMPLETED;
    nextAttemptAt = null;
    leaseToken = null;
    leaseUntil = null;
    lastErrorCode = null;
    completedAt = timestamp;
    updatedAt = timestamp;
  }

  /** Verifies exact replay identity without exposing mutable state. */
  public boolean matches(
      CustomerBookingMutationOperation expectedOperation,
      UUID expectedBookingId,
      String expectedRequestSha256) {
    return operation == expectedOperation
        && bookingId.equals(expectedBookingId)
        && requestSha256.equals(expectedRequestSha256);
  }

  private void requireLease(UUID expectedLeaseToken, OffsetDateTime timestamp) {
    if (!hasLiveLease(expectedLeaseToken, timestamp)) {
      throw new IllegalStateException("Customer booking mutation lease is stale");
    }
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
  }

  private static String boundedCode(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) return "CUSTOMER_BOOKING_MUTATION_FAILED";
    return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
  }

  private static String requiredCode(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException("decisionCode is invalid");
    }
    return normalized;
  }

  private static String optionalReason(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > 2_000) {
      throw new IllegalArgumentException("decisionReason is invalid");
    }
    return normalized;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((CustomerBookingMutation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
