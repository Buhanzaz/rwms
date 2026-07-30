package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantEventInbox;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantEventInboxRepository extends JpaRepository<AssistantEventInbox, UUID> {
  @Modifying
  @Query(
      value =
          """
          insert into assistant_event_inbox (
            event_id, event_type, occurred_at, rental_inquiry_id, conversation_id,
            order_id, received_at, payload)
          values (
            :eventId, :eventType, :occurredAt, :rentalInquiryId, :conversationId,
            :orderId, :receivedAt, cast(:payload as jsonb))
          on conflict (event_id) do nothing
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
      @Param("payload") String payload);
}
