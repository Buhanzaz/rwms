package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantEventInbox;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for the booking-event inbox, fenced by both event identity and durable Kafka source
 * receipt.
 */
public interface AssistantEventInboxRepository extends JpaRepository<AssistantEventInbox, UUID> {
  /** Inserts one canonical event/source receipt, leaving either unique conflict untouched. */
  @Modifying
  @Query(
      value =
          """
          insert into assistant_event_inbox (
            event_id, event_type, occurred_at, rental_inquiry_id, conversation_id,
            order_id, received_at, payload, canonical_envelope_sha256,
            source_topic, source_partition, source_offset, processing_state,
            attempt_count, next_attempt_at)
          values (
            :eventId, :eventType, :occurredAt, :rentalInquiryId, :conversationId,
            :orderId, :receivedAt, cast(:payload as jsonb), :canonicalEnvelopeSha256,
            :sourceTopic, :sourcePartition, :sourceOffset, 'STAGED', 0, :receivedAt)
          on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("eventId") UUID eventId,
      @Param("eventType") String eventType,
      @Param("occurredAt") OffsetDateTime occurredAt,
      @Param("rentalInquiryId") UUID rentalInquiryId,
      @Param("conversationId") UUID conversationId,
      @Param("orderId") UUID orderId,
      @Param("receivedAt") OffsetDateTime receivedAt,
      @Param("payload") String payload,
      @Param("canonicalEnvelopeSha256") String canonicalEnvelopeSha256,
      @Param("sourceTopic") String sourceTopic,
      @Param("sourcePartition") int sourcePartition,
      @Param("sourceOffset") long sourceOffset);

  /** Locks an event identity while a processing or replay transition is decided. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select receipt from AssistantEventInbox receipt where receipt.eventId = :eventId")
  Optional<AssistantEventInbox> findByIdForUpdate(@Param("eventId") UUID eventId);

  /** Locks the unique Kafka coordinate receipt so a changed redelivery cannot replace evidence. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select receipt from AssistantEventInbox receipt
      where receipt.sourceTopic = :sourceTopic
        and receipt.sourcePartition = :sourcePartition
        and receipt.sourceOffset = :sourceOffset
      """)
  Optional<AssistantEventInbox> findBySourceForUpdate(
      @Param("sourceTopic") String sourceTopic,
      @Param("sourcePartition") int sourcePartition,
      @Param("sourceOffset") long sourceOffset);
}
