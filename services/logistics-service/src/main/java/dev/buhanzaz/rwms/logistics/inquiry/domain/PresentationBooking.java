package dev.buhanzaz.rwms.logistics.inquiry.domain;

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
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

/**
 * Logistics-owned booking receipt and its bounded recovery state. Remote effects are attempted
 * only under a short-lived random lease; terminal results clear that operational metadata.
 */
@Entity
@Table(
    name = "presentation_booking",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_presentation_booking_idempotency",
          columnNames = {"presentation_id", "idempotency_key"}),
      @UniqueConstraint(
          name = "uk_presentation_booking_revision",
          columnNames = {"presentation_id", "presentation_revision"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PresentationBooking {
  private static final int MAXIMUM_RECOVERY_ATTEMPTS = 8;
  private static final long FIRST_RECOVERY_DELAY_SECONDS = 2;
  private static final long MAXIMUM_RECOVERY_DELAY_SECONDS = 300;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "presentation_id", nullable = false)
  private UUID presentationId;

  @Column(name = "presentation_revision", nullable = false)
  private long presentationRevision;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "order_id")
  private UUID orderId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "selected_item_ids_json", nullable = false, columnDefinition = "jsonb")
  private String selectedItemIdsJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "desired_delivery_windows_json", nullable = false, columnDefinition = "jsonb")
  private String desiredDeliveryWindowsJson;

  /** Client-selected initial duration for NORMAL bookings; historical and replacement rows keep null. */
  @Min(1)
  @Column(name = "rental_months")
  private Long rentalMonths;

  /**
   * Durable normalized NORMAL-confirmation snapshot used only to replay the same booking into the
   * existing order after an interrupted asset conversion. It is not an order source of truth.
   */
  @Column(name = "delivery_address", length = 1_000)
  private String deliveryAddress;

  @Column(name = "latitude", precision = 9, scale = 6)
  private BigDecimal latitude;

  @Column(name = "longitude", precision = 10, scale = 6)
  private BigDecimal longitude;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "additional_contacts_json", columnDefinition = "jsonb")
  private String additionalContactsJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private PresentationBookingState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "manager_action", length = 16)
  private PresentationBookingManagerAction managerAction;

  @Column(name = "manager_action_idempotency_key")
  private UUID managerActionIdempotencyKey;

  @Column(name = "manager_acted_at")
  private OffsetDateTime managerActedAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "last_error_code", length = 64)
  private String lastErrorCode;

  /** Earliest database time at which another recovery worker may claim this pending booking. */
  @Column(name = "recovery_next_attempt_at")
  private OffsetDateTime recoveryNextAttemptAt;

  /** Random, single-use capability owned by the worker currently processing this booking. */
  @Column(name = "recovery_lease_token")
  private UUID recoveryLeaseToken;

  /** Exclusive processing deadline after which another worker may recover an abandoned claim. */
  @Column(name = "recovery_lease_until")
  private OffsetDateTime recoveryLeaseUntil;

  /** Terminal recovery timestamp for a booking that exhausted the bounded automatic retries. */
  @Column(name = "recovery_quarantined_at")
  private OffsetDateTime recoveryQuarantinedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  public static PresentationBooking create(
      UUID presentationId,
      long presentationRevision,
      UUID idempotencyKey,
      String selectedItemIdsJson,
      String desiredDeliveryWindowsJson,
      Long rentalMonths,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      String additionalContactsJson,
      OffsetDateTime now) {
    if (presentationRevision < 1) {
      throw new IllegalArgumentException("presentationRevision is invalid");
    }
    PresentationBooking booking = new PresentationBooking();
    booking.presentationId = Objects.requireNonNull(presentationId, "presentationId");
    booking.presentationRevision = presentationRevision;
    booking.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    booking.selectedItemIdsJson = requireJson(selectedItemIdsJson);
    booking.desiredDeliveryWindowsJson = requireJson(desiredDeliveryWindowsJson);
    booking.rentalMonths = optionalRentalMonths(rentalMonths);
    booking.deliveryAddress = optionalText(deliveryAddress, 1_000, "deliveryAddress");
    booking.latitude = latitude;
    booking.longitude = longitude;
    booking.additionalContactsJson = optionalJson(additionalContactsJson);
    booking.state = PresentationBookingState.PENDING;
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    booking.recoveryNextAttemptAt = timestamp;
    booking.createdAt = timestamp;
    booking.updatedAt = timestamp;
    return booking;
  }

  /**
   * Attaches the idempotently created order while preserving the current recovery lease. Only the
   * worker holding the exact unexpired claim may advance this pending booking.
   */
  public void assignOrder(UUID nextOrderId, UUID leaseToken, OffsetDateTime now) {
    requireRecoveryClaim(leaseToken, now);
    UUID required = Objects.requireNonNull(nextOrderId, "orderId");
    if (orderId != null && !orderId.equals(required)) {
      throw new IllegalStateException("Booking already belongs to another order");
    }
    orderId = required;
    updatedAt = now;
  }

  /**
   * Acquires one bounded recovery lease after the repository locked a due row with SKIP LOCKED.
   */
  public void claimRecovery(UUID leaseToken, OffsetDateTime leaseUntil, OffsetDateTime now) {
    UUID requiredToken = Objects.requireNonNull(leaseToken, "leaseToken");
    OffsetDateTime requiredUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    if (!requiredUntil.isAfter(timestamp)) {
      throw new IllegalArgumentException("Recovery lease must expire after it starts");
    }
    if (!isRecoveryClaimable(timestamp)) {
      throw new IllegalStateException("Booking recovery is not claimable");
    }
    recoveryLeaseToken = requiredToken;
    recoveryLeaseUntil = requiredUntil;
    updatedAt = timestamp;
  }

  /** Returns whether this pending row still belongs to the supplied unexpired lease capability. */
  public boolean hasRecoveryClaim(UUID leaseToken, OffsetDateTime now) {
    return state == PresentationBookingState.PENDING
        && leaseToken != null
        && leaseToken.equals(recoveryLeaseToken)
        && recoveryLeaseUntil != null
        && recoveryLeaseUntil.isAfter(Objects.requireNonNull(now, "now"));
  }

  /**
   * Records one failed remote attempt, releases its lease and schedules bounded exponential
   * backoff. The eighth failure quarantines the still-pending booking for operator recovery.
   */
  public void recoveryFailed(UUID leaseToken, String errorCode, OffsetDateTime now) {
    requireRecoveryClaim(leaseToken, now);
    attemptCount = Math.addExact(attemptCount, 1);
    lastErrorCode = optionalCode(errorCode);
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    clearRecoveryLease();
    if (attemptCount >= MAXIMUM_RECOVERY_ATTEMPTS) {
      recoveryNextAttemptAt = null;
      recoveryQuarantinedAt = timestamp;
    } else {
      recoveryNextAttemptAt = timestamp.plusSeconds(recoveryDelaySeconds(attemptCount));
    }
    updatedAt = timestamp;
  }

  /** Completes an exact leased recovery attempt and clears every retry/lease field. */
  public void complete(UUID leaseToken, OffsetDateTime now) {
    if (state == PresentationBookingState.COMPLETED) return;
    if (state != PresentationBookingState.PENDING || orderId == null) {
      throw new IllegalStateException("Booking cannot be completed");
    }
    requireRecoveryClaim(leaseToken, now);
    state = PresentationBookingState.COMPLETED;
    lastErrorCode = null;
    clearRecoveryMetadata();
    completedAt = now;
    updatedAt = now;
  }

  /** Rejects an exact leased recovery attempt and clears every retry/lease field. */
  public void reject(UUID leaseToken, String errorCode, OffsetDateTime now) {
    if (state != PresentationBookingState.PENDING) return;
    requireRecoveryClaim(leaseToken, now);
    state = PresentationBookingState.REJECTED;
    lastErrorCode = optionalCode(errorCode);
    clearRecoveryMetadata();
    completedAt = now;
    updatedAt = now;
  }

  private boolean isRecoveryClaimable(OffsetDateTime now) {
    return state == PresentationBookingState.PENDING
        && recoveryQuarantinedAt == null
        && (recoveryNextAttemptAt == null || !recoveryNextAttemptAt.isAfter(now))
        && (recoveryLeaseUntil == null || !recoveryLeaseUntil.isAfter(now));
  }

  private void requireRecoveryClaim(UUID leaseToken, OffsetDateTime now) {
    if (!hasRecoveryClaim(leaseToken, now)) {
      throw new IllegalStateException("Presentation booking recovery claim is no longer current");
    }
  }

  private static long recoveryDelaySeconds(int failedAttempts) {
    int exponent = Math.min(failedAttempts - 1, 30);
    long delay = FIRST_RECOVERY_DELAY_SECONDS << exponent;
    return Math.min(delay, MAXIMUM_RECOVERY_DELAY_SECONDS);
  }

  private void clearRecoveryLease() {
    recoveryLeaseToken = null;
    recoveryLeaseUntil = null;
  }

  private void clearRecoveryMetadata() {
    recoveryNextAttemptAt = null;
    recoveryQuarantinedAt = null;
    clearRecoveryLease();
  }

  public boolean hasManagerAction(
      PresentationBookingManagerAction action, UUID actionIdempotencyKey) {
    return managerAction == action
        && Objects.equals(managerActionIdempotencyKey, actionIdempotencyKey);
  }

  public void recordManagerAction(
      PresentationBookingManagerAction action, UUID actionIdempotencyKey, OffsetDateTime now) {
    if (state != PresentationBookingState.COMPLETED) {
      throw new IllegalStateException("Only a completed booking can be acknowledged");
    }
    PresentationBookingManagerAction requiredAction =
        Objects.requireNonNull(action, "managerAction");
    UUID requiredIdempotencyKey =
        Objects.requireNonNull(actionIdempotencyKey, "managerActionIdempotencyKey");
    OffsetDateTime timestamp = Objects.requireNonNull(now, "now");
    if (managerAction != null) {
      if (hasManagerAction(requiredAction, requiredIdempotencyKey)) return;
      throw new IllegalStateException("Booking manager action is immutable");
    }
    managerAction = requiredAction;
    managerActionIdempotencyKey = requiredIdempotencyKey;
    managerActedAt = timestamp;
    updatedAt = timestamp;
  }

  private static String requireJson(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.startsWith("[") || !normalized.endsWith("]") || normalized.length() > 8_000) {
      throw new IllegalArgumentException("selectedItemIdsJson is invalid");
    }
    return normalized;
  }

  private static String optionalJson(String value) {
    if (value == null) return null;
    return requireJson(value);
  }

  private static String optionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String optionalCode(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
  }

  private static Long optionalRentalMonths(Long value) {
    if (value == null) return null;
    if (value < 1) {
      throw new IllegalArgumentException("rentalMonths is invalid");
    }
    return value;
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
        && Objects.equals(id, ((PresentationBooking) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
