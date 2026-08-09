package dev.buhanzaz.rwms.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

  @Column(
      name = "canonical_envelope_sha256",
      length = 64,
      columnDefinition = "char(64)")
  @JdbcTypeCode(SqlTypes.CHAR)
  private String canonicalEnvelopeSha256;

  @Column(name = "source_topic", length = 249)
  private String sourceTopic;

  @Column(name = "source_partition")
  private Integer sourcePartition;

  @Column(name = "source_offset")
  private Long sourceOffset;

  @Enumerated(EnumType.STRING)
  @Column(name = "processing_state", nullable = false, length = 32)
  private AssistantEventInboxState processingState;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at")
  private OffsetDateTime nextAttemptAt;

  @Column(name = "processed_at")
  private OffsetDateTime processedAt;

  @Column(name = "dead_lettered_at")
  private OffsetDateTime deadLetteredAt;

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

  public String getCanonicalEnvelopeSha256() {
    return canonicalEnvelopeSha256 == null ? null : canonicalEnvelopeSha256.trim();
  }

  public String getSourceTopic() {
    return sourceTopic;
  }

  public Integer getSourcePartition() {
    return sourcePartition;
  }

  public Long getSourceOffset() {
    return sourceOffset;
  }

  public AssistantEventInboxState getProcessingState() {
    return processingState;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public OffsetDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public OffsetDateTime getProcessedAt() {
    return processedAt;
  }

  public OffsetDateTime getDeadLetteredAt() {
    return deadLetteredAt;
  }

  /** Claims one lifetime-bounded processing attempt and leaves a short concurrent-delivery lease. */
  public void claimAttempt(OffsetDateTime leaseUntil) {
    if (processingState != AssistantEventInboxState.STAGED || attemptCount >= 4) {
      throw new IllegalStateException("Assistant event is not claimable");
    }
    attemptCount = Math.addExact(attemptCount, 1);
    nextAttemptAt = Objects.requireNonNull(leaseUntil, "leaseUntil");
  }

  /** Schedules only the next bounded retry for the attempt that just failed. */
  public void scheduleRetry(int expectedAttemptCount, OffsetDateTime retryAt) {
    if (processingState != AssistantEventInboxState.STAGED
        || attemptCount != expectedAttemptCount
        || attemptCount >= 4) {
      throw new IllegalStateException("Assistant event retry state changed concurrently");
    }
    nextAttemptAt = Objects.requireNonNull(retryAt, "retryAt");
  }

  /** Completes either normal processing or an approved replay without resetting attempt history. */
  public void markProcessed(OffsetDateTime now) {
    if (processingState == AssistantEventInboxState.PROCESSED) return;
    if ((processingState != AssistantEventInboxState.STAGED
            && processingState != AssistantEventInboxState.DLT)
        || attemptCount < 1) {
      throw new IllegalStateException("Assistant event cannot be marked processed");
    }
    processingState = AssistantEventInboxState.PROCESSED;
    processedAt = Objects.requireNonNull(now, "now");
    deadLetteredAt = null;
    nextAttemptAt = null;
  }

  /** Moves an exhausted staged envelope to review without retaining a new payload copy. */
  public void markDeadLettered(OffsetDateTime now) {
    if (processingState == AssistantEventInboxState.DLT) return;
    if (processingState != AssistantEventInboxState.STAGED || attemptCount != 4) {
      throw new IllegalStateException("Assistant event is not exhausted");
    }
    processingState = AssistantEventInboxState.DLT;
    deadLetteredAt = Objects.requireNonNull(now, "now");
    nextAttemptAt = null;
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
