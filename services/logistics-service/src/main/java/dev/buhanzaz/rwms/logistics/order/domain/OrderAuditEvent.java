package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "rental_order_audit_event")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderAuditEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 48)
  private OrderAuditEventType eventType;

  @Column(name = "actor_subject_id", nullable = false)
  private UUID actorSubjectId;

  @Column(name = "actor_role", nullable = false, length = 32)
  private String actorRole;

  @Column(name = "subject_type", nullable = false, length = 48)
  private String subjectType;

  @Column(name = "subject_id", nullable = false, length = 128)
  private String subjectId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "previous_values", columnDefinition = "jsonb")
  private Map<String, Object> previousValues;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "new_values", columnDefinition = "jsonb")
  private Map<String, Object> newValues;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  public static OrderAuditEvent create(
      UUID orderId,
      OrderAuditEventType eventType,
      UUID actorSubjectId,
      String actorRole,
      String subjectType,
      String subjectId,
      Map<String, Object> previousValues,
      Map<String, Object> newValues) {
    OrderAuditEvent event = new OrderAuditEvent();
    event.orderId = Objects.requireNonNull(orderId, "orderId");
    event.eventType = Objects.requireNonNull(eventType, "eventType");
    event.actorSubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    event.actorRole = requireText(actorRole, 32, "actorRole");
    event.subjectType = requireText(subjectType, 48, "subjectType");
    event.subjectId = requireText(subjectId, 128, "subjectId");
    event.previousValues = immutableMap(previousValues);
    event.newValues = immutableMap(newValues);
    event.occurredAt =
        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    return event;
  }

  private static Map<String, Object> immutableMap(Map<String, Object> value) {
    return value == null || value.isEmpty() ? null : Map.copyOf(value);
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
