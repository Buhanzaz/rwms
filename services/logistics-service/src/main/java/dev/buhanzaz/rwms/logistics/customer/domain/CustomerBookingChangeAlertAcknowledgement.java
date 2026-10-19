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

/** One manager's immutable acknowledgement; it cannot dismiss another manager's notification. */
@Entity
@Table(name = "customer_booking_change_alert_ack")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerBookingChangeAlertAcknowledgement {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "mutation_id", nullable = false)
  private UUID mutationId;

  @Column(name = "manager_id", nullable = false)
  private UUID managerId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "expected_version", nullable = false)
  private long expectedVersion;

  @Column(name = "acknowledged_at", nullable = false)
  private OffsetDateTime acknowledgedAt;

  public static CustomerBookingChangeAlertAcknowledgement create(
      UUID mutationId, UUID managerId, UUID key, long expectedVersion, OffsetDateTime now) {
    var result = new CustomerBookingChangeAlertAcknowledgement();
    result.mutationId = Objects.requireNonNull(mutationId);
    result.managerId = Objects.requireNonNull(managerId);
    result.idempotencyKey = Objects.requireNonNull(key);
    result.expectedVersion = expectedVersion;
    result.acknowledgedAt = Objects.requireNonNull(now);
    return result;
  }
}
