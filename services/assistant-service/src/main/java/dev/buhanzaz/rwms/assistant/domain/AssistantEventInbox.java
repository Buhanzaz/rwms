package dev.buhanzaz.rwms.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** JPA projection of the deduplicating inbox; inserts use PostgreSQL ON CONFLICT. */
@Entity
@Table(name = "assistant_event_inbox")
public class AssistantEventInbox {
  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "rental_inquiry_id", nullable = false)
  private UUID rentalInquiryId;

  @Column(name = "conversation_id", nullable = false)
  private UUID conversationId;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "received_at", nullable = false)
  private OffsetDateTime receivedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode payload;

  protected AssistantEventInbox() {}

  public UUID getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }

  public UUID getRentalInquiryId() {
    return rentalInquiryId;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public OffsetDateTime getReceivedAt() {
    return receivedAt;
  }

  public JsonNode getPayload() {
    return payload == null ? null : payload.deepCopy();
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
    if (thisClass != otherClass) return false;
    AssistantEventInbox value = (AssistantEventInbox) other;
    return eventId != null && Objects.equals(eventId, value.eventId);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
