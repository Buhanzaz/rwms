package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/**
 * JPA persistence model for Order Command Receipt in the logistics-owned database.
 */
@Entity
@Table(name = "rental_order_command_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCommandReceipt {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private RentalOrder order;

  @Column(name = "actor_subject_id", nullable = false)
  private UUID actorSubjectId;

  @Column(name = "operation_name", nullable = false, length = 96)
  private String operationName;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "completed_at", nullable = false)
  private OffsetDateTime completedAt;

  public static OrderCommandReceipt complete(
      RentalOrder order,
      UUID actorSubjectId,
      String operationName,
      UUID idempotencyKey,
      String requestSha256) {
    OrderCommandReceipt receipt = new OrderCommandReceipt();
    receipt.order = Objects.requireNonNull(order, "order");
    receipt.actorSubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    receipt.operationName = requireText(operationName, 96, "operationName");
    receipt.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    receipt.requestSha256 = requireHash(requestSha256);
    receipt.completedAt =
        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    return receipt;
  }

  public boolean matches(String hash) {
    return requestSha256.equals(hash);
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
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
        && Objects.equals(id, ((OrderCommandReceipt) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
