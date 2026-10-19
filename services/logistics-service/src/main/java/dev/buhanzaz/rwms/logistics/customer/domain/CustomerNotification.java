package dev.buhanzaz.rwms.logistics.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** Durable customer inbox entry committed with the owning order's terminal transition. */
@Entity
@Table(name = "customer_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerNotification {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "customer_subject_id", nullable = false, updatable = false)
  private UUID customerSubjectId;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "booking_id", updatable = false)
  private UUID bookingId;

  @Column(name = "kind", nullable = false, updatable = false, length = 32)
  private String kind;

  @Column(name = "message", nullable = false, updatable = false, columnDefinition = "text")
  private String message;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "read_at")
  private OffsetDateTime readAt;

  /** Records one actual release, never an expiry prediction or a failed release attempt. */
  public static CustomerNotification paymentExpired(
      UUID customerSubjectId,
      UUID orderId,
      UUID bookingId,
      String orderNumber,
      OffsetDateTime timestamp) {
    CustomerNotification entry = new CustomerNotification();
    entry.customerSubjectId = Objects.requireNonNull(customerSubjectId, "customerSubjectId");
    entry.orderId = Objects.requireNonNull(orderId, "orderId");
    entry.bookingId = bookingId;
    entry.kind = "PAYMENT_EXPIRED";
    entry.message =
        "Время оплаты заказа №"
            + Objects.requireNonNull(orderNumber, "orderNumber")
            + " истекло. Бытовки сняты с резерва. Вы можете выбрать их и оформить новый заказ.";
    entry.createdAt = Objects.requireNonNull(timestamp, "timestamp");
    return entry;
  }

  /** Monotone acknowledgement; the caller must hold this owned inbox row's write lock. */
  public void markRead(OffsetDateTime timestamp) {
    Objects.requireNonNull(timestamp, "timestamp");
    if (timestamp.isBefore(createdAt))
      throw new IllegalArgumentException("Invalid acknowledgement time");
    if (readAt == null) readAt = timestamp;
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
        && getId() != null
        && getId().equals(((CustomerNotification) other).getId());
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
