package dev.buhanzaz.rwms.logistics.inquiry.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "presentation_booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PresentationBooking {
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
      OffsetDateTime now) {
    if (presentationRevision < 1) {
      throw new IllegalArgumentException("presentationRevision is invalid");
    }
    PresentationBooking booking = new PresentationBooking();
    booking.presentationId = Objects.requireNonNull(presentationId, "presentationId");
    booking.presentationRevision = presentationRevision;
    booking.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    booking.selectedItemIdsJson = requireJson(selectedItemIdsJson);
    booking.state = PresentationBookingState.PENDING;
    booking.createdAt = Objects.requireNonNull(now, "now");
    booking.updatedAt = now;
    return booking;
  }

  public void assignOrder(UUID nextOrderId, OffsetDateTime now) {
    UUID required = Objects.requireNonNull(nextOrderId, "orderId");
    if (orderId != null && !orderId.equals(required)) {
      throw new IllegalStateException("Booking already belongs to another order");
    }
    orderId = required;
    updatedAt = now;
  }

  public void attempted(String errorCode, OffsetDateTime now) {
    if (state != PresentationBookingState.PENDING) return;
    attemptCount = Math.addExact(attemptCount, 1);
    lastErrorCode = optionalCode(errorCode);
    updatedAt = now;
  }

  public void complete(OffsetDateTime now) {
    if (state == PresentationBookingState.COMPLETED) return;
    if (state != PresentationBookingState.PENDING || orderId == null) {
      throw new IllegalStateException("Booking cannot be completed");
    }
    state = PresentationBookingState.COMPLETED;
    lastErrorCode = null;
    completedAt = now;
    updatedAt = now;
  }

  public void reject(String errorCode, OffsetDateTime now) {
    if (state != PresentationBookingState.PENDING) return;
    state = PresentationBookingState.REJECTED;
    lastErrorCode = optionalCode(errorCode);
    completedAt = now;
    updatedAt = now;
  }

  public boolean hasManagerAction(
      PresentationBookingManagerAction action, UUID actionIdempotencyKey) {
    return managerAction == action
        && Objects.equals(managerActionIdempotencyKey, actionIdempotencyKey);
  }

  public void recordManagerAction(
      PresentationBookingManagerAction action,
      UUID actionIdempotencyKey,
      OffsetDateTime now) {
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

  private static String optionalCode(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
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
