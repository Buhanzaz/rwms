package dev.buhanzaz.rwms.logistics.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Optimistically fenced customer cart/session layered over the authoritative inquiry hold set.
 * Equipment JSON is a local checkout intent; cabin availability remains asset-owned. A saved
 * checkout receipt carries only logistics-owned lease, retry and quarantine state until the
 * upstream booking reaches a terminal outcome.
 */
@Entity
@Table(
    name = "customer_rental_session",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_rental_session_inquiry",
            columnNames = "inquiry_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerRentalSession {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "inquiry_id", nullable = false)
  private UUID inquiryId;

  @Column(name = "customer_subject_id", nullable = false)
  private UUID customerSubjectId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private CustomerSessionState state;

  @Column(name = "equipment_selection_json", nullable = false, columnDefinition = "text")
  private String equipmentSelectionJson;

  @Column(name = "rental_terms_json", nullable = false, columnDefinition = "text")
  private String rentalTermsJson;

  @Column(name = "pending_command_key")
  private UUID pendingCommandKey;

  @Column(name = "pending_command_sha256", length = 64)
  private String pendingCommandSha256;

  @Column(name = "last_selection_key")
  private UUID lastSelectionKey;

  @Column(name = "last_selection_sha256", length = 64)
  private String lastSelectionSha256;

  @Column(name = "delivery_slot_id")
  private UUID deliverySlotId;

  @Column(name = "booking_id")
  private UUID bookingId;

  @Column(name = "order_id")
  private UUID orderId;

  @Column(name = "presentation_token", length = 2_048)
  private String presentationToken;

  @Column(name = "checkout_command_key")
  private UUID checkoutCommandKey;

  @Column(name = "checkout_command_sha256", length = 64)
  private String checkoutCommandSha256;

  @Column(name = "recovery_attempt_count", nullable = false)
  private int recoveryAttemptCount;

  @Column(name = "recovery_next_attempt_at")
  private OffsetDateTime recoveryNextAttemptAt;

  @Column(name = "recovery_lease_token")
  private UUID recoveryLeaseToken;

  @Column(name = "recovery_lease_until")
  private OffsetDateTime recoveryLeaseUntil;

  @Column(name = "recovery_quarantined_at")
  private OffsetDateTime recoveryQuarantinedAt;

  @Column(name = "recovery_last_error_code", length = 64)
  private String recoveryLastErrorCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates a customer-owned active cart for a newly created inquiry. */
  public static CustomerRentalSession create(
      UUID inquiryId, UUID customerSubjectId, UUID warehouseId) {
    CustomerRentalSession session = new CustomerRentalSession();
    session.inquiryId = Objects.requireNonNull(inquiryId, "inquiryId");
    session.customerSubjectId = Objects.requireNonNull(customerSubjectId, "customerSubjectId");
    session.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    session.state = CustomerSessionState.ACTIVE;
    session.equipmentSelectionJson = "[]";
    session.rentalTermsJson = "[]";
    session.createdAt = now();
    session.updatedAt = session.createdAt;
    return session;
  }

  /** Claims a cabin-selection command before the remote asset mutation starts. */
  public void beginSelection(long expectedVersion, UUID commandKey, String commandSha256) {
    requireExpectedVersion(expectedVersion);
    requireActive();
    state = CustomerSessionState.SELECTION_PENDING;
    pendingCommandKey = Objects.requireNonNull(commandKey, "commandKey");
    pendingCommandSha256 = requireHash(commandSha256);
    touch();
  }

  /** Completes the exact pending cabin-selection command. */
  public void completeSelection(
      UUID commandKey,
      String commandSha256,
      String normalizedEquipmentSelectionJson,
      String normalizedRentalTermsJson) {
    requirePending(CustomerSessionState.SELECTION_PENDING, commandKey, commandSha256);
    state = CustomerSessionState.ACTIVE;
    lastSelectionKey = commandKey;
    lastSelectionSha256 = commandSha256;
    equipmentSelectionJson =
        Objects.requireNonNull(normalizedEquipmentSelectionJson, "normalizedEquipmentSelectionJson");
    rentalTermsJson = Objects.requireNonNull(normalizedRentalTermsJson, "normalizedRentalTermsJson");
    deliverySlotId = null;
    clearPending();
    touch();
  }

  /** Makes a failed selection retryable with the same optimistic version rules. */
  public void failSelection(UUID commandKey, String commandSha256) {
    requirePending(CustomerSessionState.SELECTION_PENDING, commandKey, commandSha256);
    state = CustomerSessionState.ACTIVE;
    clearPending();
    touch();
  }

  /** Replaces the complete per-cabin furniture intent under the session version fence. */
  public void replaceEquipment(long expectedVersion, String normalizedJson) {
    requireExpectedVersion(expectedVersion);
    requireActive();
    equipmentSelectionJson = Objects.requireNonNull(normalizedJson, "normalizedJson");
    deliverySlotId = null;
    touch();
  }

  /** Replaces the complete per-cabin rental terms under the session version fence. */
  public void replaceRentalTerms(long expectedVersion, String normalizedJson) {
    requireExpectedVersion(expectedVersion);
    requireActive();
    rentalTermsJson = Objects.requireNonNull(normalizedJson, "normalizedJson");
    deliverySlotId = null;
    touch();
  }

  /** Binds one freshly held delivery slot to this cart. */
  public void selectDeliverySlot(long expectedVersion, UUID slotId) {
    requireExpectedVersion(expectedVersion);
    requireActive();
    deliverySlotId = Objects.requireNonNull(slotId, "slotId");
    touch();
  }

  /** Claims checkout so cabin, furniture and slot effects cannot be submitted concurrently. */
  public void beginCheckout(long expectedVersion, UUID commandKey, String commandSha256) {
    requireExpectedVersion(expectedVersion);
    requireActive();
    state = CustomerSessionState.CHECKOUT_PENDING;
    pendingCommandKey = Objects.requireNonNull(commandKey, "commandKey");
    pendingCommandSha256 = requireHash(commandSha256);
    checkoutCommandKey = commandKey;
    checkoutCommandSha256 = commandSha256;
    touch();
  }

  /** Stores the existing presentation-booking identity for retry reconciliation. */
  public void recordPendingBooking(
      UUID commandKey,
      String commandSha256,
      UUID bookingId,
      UUID orderId,
      String presentationToken,
      OffsetDateTime timestamp) {
    requirePending(CustomerSessionState.CHECKOUT_PENDING, commandKey, commandSha256);
    this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
    this.orderId = orderId;
    this.presentationToken = required(presentationToken, 2_048, "presentationToken");
    clearRecovery();
    recoveryNextAttemptAt = Objects.requireNonNull(timestamp, "timestamp");
    updatedAt = timestamp;
  }

  /** Claims a due booking receipt for one bounded recovery attempt. */
  public boolean claimCheckoutRecovery(
      UUID leaseToken, OffsetDateTime timestamp, OffsetDateTime leaseUntil) {
    OffsetDateTime requiredNow = Objects.requireNonNull(timestamp, "timestamp");
    OffsetDateTime requiredLeaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
    if (!requiredLeaseUntil.isAfter(requiredNow)) {
      throw new IllegalArgumentException("recovery lease is invalid");
    }
    if (state != CustomerSessionState.CHECKOUT_PENDING
        || bookingId == null
        || presentationToken == null
        || recoveryQuarantinedAt != null
        || recoveryNextAttemptAt == null
        || recoveryNextAttemptAt.isAfter(requiredNow)
        || (recoveryLeaseUntil != null && recoveryLeaseUntil.isAfter(requiredNow))) {
      return false;
    }
    recoveryLeaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
    recoveryLeaseUntil = requiredLeaseUntil;
    updatedAt = requiredNow;
    return true;
  }

  /** Returns whether this cart is fenced by the supplied live recovery lease. */
  public boolean hasCheckoutRecoveryLease(UUID leaseToken, OffsetDateTime timestamp) {
    return state == CustomerSessionState.CHECKOUT_PENDING
        && Objects.equals(recoveryLeaseToken, leaseToken)
        && recoveryLeaseUntil != null
        && recoveryLeaseUntil.isAfter(Objects.requireNonNull(timestamp, "timestamp"));
  }

  /** Defers or quarantines a failed leased recovery attempt without changing checkout state. */
  public void failCheckoutRecovery(
      UUID leaseToken,
      String errorCode,
      OffsetDateTime timestamp,
      OffsetDateTime nextAttemptAt,
      boolean quarantine) {
    OffsetDateTime requiredNow = Objects.requireNonNull(timestamp, "timestamp");
    requireCheckoutRecoveryLease(leaseToken, requiredNow);
    recoveryAttemptCount = Math.addExact(recoveryAttemptCount, 1);
    recoveryLastErrorCode = optionalCode(errorCode);
    recoveryLeaseToken = null;
    recoveryLeaseUntil = null;
    if (quarantine) {
      recoveryQuarantinedAt = requiredNow;
      recoveryNextAttemptAt = null;
    } else {
      OffsetDateTime requiredNext = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
      if (!requiredNext.isAfter(requiredNow)) {
        throw new IllegalArgumentException("next recovery attempt is invalid");
      }
      recoveryNextAttemptAt = requiredNext;
    }
    updatedAt = requiredNow;
  }

  /** Releases a checkout lease while awaiting payment; healthy waiting does not consume retries. */
  public void awaitPayment(
      UUID bookingId,
      UUID orderId,
      UUID leaseToken,
      OffsetDateTime timestamp,
      OffsetDateTime nextCheckAt) {
    requireCheckoutRecoveryLease(leaseToken, timestamp);
    if (!Objects.equals(this.bookingId, bookingId)
        || orderId == null
        || (this.orderId != null && !this.orderId.equals(orderId))
        || nextCheckAt == null
        || !nextCheckAt.isAfter(timestamp)) {
      throw new IllegalArgumentException("Payment wait does not match pending checkout");
    }
    this.orderId = orderId;
    clearRecovery();
    recoveryNextAttemptAt = nextCheckAt;
    updatedAt = timestamp;
  }

  /** Marks a successfully saved and payment-admitted booking as the terminal cart outcome. */
  public void completeBooking(
      UUID bookingId, UUID orderId, UUID recoveryLeaseToken, OffsetDateTime timestamp) {
    requireCheckoutRecoveryLease(recoveryLeaseToken, timestamp);
    if (state != CustomerSessionState.CHECKOUT_PENDING || !Objects.equals(this.bookingId, bookingId)) {
      throw new IllegalStateException("Customer booking does not match pending checkout");
    }
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    state = CustomerSessionState.BOOKED;
    clearPending();
    clearRecovery();
    updatedAt = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** Releases a rejected checkout so the customer can correct the cart. */
  public void rejectBooking(
      UUID bookingId, UUID recoveryLeaseToken, OffsetDateTime timestamp) {
    requireCheckoutRecoveryLease(recoveryLeaseToken, timestamp);
    if (state != CustomerSessionState.CHECKOUT_PENDING || !Objects.equals(this.bookingId, bookingId)) {
      throw new IllegalStateException("Customer booking does not match pending checkout");
    }
    state = CustomerSessionState.ACTIVE;
    this.bookingId = null;
    this.orderId = null;
    this.presentationToken = null;
    this.checkoutCommandKey = null;
    this.checkoutCommandSha256 = null;
    clearPending();
    clearRecovery();
    updatedAt = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** Completes an unpaid checkout only after its exact order reservation has been released. */
  public void expirePaymentReservation(
      UUID bookingId, UUID orderId, String presentationToken, OffsetDateTime timestamp) {
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(orderId, "orderId");
    if ((state != CustomerSessionState.CHECKOUT_PENDING && state != CustomerSessionState.CANCELLED)
        || (this.bookingId != null && !this.bookingId.equals(bookingId))
        || (this.orderId != null && !this.orderId.equals(orderId))) {
      throw new IllegalStateException("Expired payment does not match pending checkout");
    }
    this.bookingId = bookingId;
    this.orderId = orderId;
    if (this.presentationToken == null) {
      this.presentationToken = Objects.requireNonNull(presentationToken, "presentationToken");
    }
    requireCompletedBookingIdentity();
    state = CustomerSessionState.CANCELLED;
    clearPending();
    clearRecovery();
    updatedAt = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** Fences a completed booking while its durable cancellation releases owned resources. */
  public void beginCancellation(long expectedVersion, OffsetDateTime timestamp) {
    requireExpectedVersion(expectedVersion);
    if (state != CustomerSessionState.BOOKED) {
      throw new IllegalStateException("Customer booking cannot be cancelled in its current state");
    }
    requireCompletedBookingIdentity();
    state = CustomerSessionState.CANCEL_PENDING;
    updatedAt = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** Marks the customer booking terminal only after order and slot cancellation both completed. */
  public void completeCancellation(OffsetDateTime timestamp) {
    if (state == CustomerSessionState.CANCELLED) return;
    if (state != CustomerSessionState.CANCEL_PENDING) {
      throw new IllegalStateException("Customer booking cancellation is not pending");
    }
    requireCompletedBookingIdentity();
    state = CustomerSessionState.CANCELLED;
    updatedAt = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** Rebinds only the delivery slot of a still-booked order under the customer version fence. */
  public void reschedule(long expectedVersion, UUID nextSlotId, OffsetDateTime timestamp) {
    requireExpectedVersion(expectedVersion);
    if (state != CustomerSessionState.BOOKED) {
      throw new IllegalStateException("Customer booking cannot be rescheduled in its current state");
    }
    requireCompletedBookingIdentity();
    deliverySlotId = Objects.requireNonNull(nextSlotId, "nextSlotId");
    updatedAt = Objects.requireNonNull(timestamp, "timestamp");
  }

  private void requireCompletedBookingIdentity() {
    if (bookingId == null || orderId == null || presentationToken == null || deliverySlotId == null) {
      throw new IllegalStateException("Customer booking identity is incomplete");
    }
  }

  private void requireCheckoutRecoveryLease(UUID leaseToken, OffsetDateTime timestamp) {
    if (!hasCheckoutRecoveryLease(leaseToken, timestamp)) {
      throw new IllegalStateException("Customer checkout recovery lease is stale");
    }
  }

  private void requireActive() {
    if (state == CustomerSessionState.BOOKED
        || state == CustomerSessionState.CANCEL_PENDING
        || state == CustomerSessionState.CANCELLED) {
      throw new IllegalStateException("Customer cart is already booked");
    }
    if (state != CustomerSessionState.ACTIVE) throw new IllegalStateException("Customer cart is busy");
  }

  private void requireExpectedVersion(long expectedVersion) {
    if (expectedVersion < 0 || version != expectedVersion) {
      throw new IllegalArgumentException("Customer cart version conflict");
    }
  }

  private void requirePending(
      CustomerSessionState expectedState, UUID commandKey, String commandSha256) {
    if (state != expectedState
        || !Objects.equals(pendingCommandKey, commandKey)
        || !Objects.equals(pendingCommandSha256, commandSha256)) {
      throw new IllegalStateException("Customer command does not match pending state");
    }
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("commandSha256 is invalid");
    }
    return value;
  }

  private static String optionalCode(String value) {
    if (value == null || value.isBlank()) return "CUSTOMER_CHECKOUT_RECOVERY_FAILED";
    String normalized = value.trim();
    return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
  }

  private static String required(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private void clearPending() {
    pendingCommandKey = null;
    pendingCommandSha256 = null;
  }

  private void clearRecovery() {
    recoveryAttemptCount = 0;
    recoveryNextAttemptAt = null;
    recoveryLeaseToken = null;
    recoveryLeaseUntil = null;
    recoveryQuarantinedAt = null;
    recoveryLastErrorCode = null;
  }

  private void touch() {
    updatedAt = now();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
        && Objects.equals(id, ((CustomerRentalSession) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
