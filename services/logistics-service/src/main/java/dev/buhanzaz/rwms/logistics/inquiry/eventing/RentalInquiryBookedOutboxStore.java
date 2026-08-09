package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores and leases rental-inquiry booking notifications so failed delivery can be retried locally.
 */
@Repository
@RequiredArgsConstructor
public class RentalInquiryBookedOutboxStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  /**
   * Persists the canonical booked fact in the caller's booking transaction. The generated eventId
   * and exact JSON remain stable for every later relay retry.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      UUID inquiryId,
      long inquiryVersion,
      UUID conversationId,
      UUID bookingId,
      UUID orderId,
      UUID managerSubjectId,
      OffsetDateTime occurredAt) {
    if (inquiryVersion < 0) {
      throw new IllegalArgumentException("inquiryVersion must not be negative");
    }
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recordedAt = now();
    DomainEventEnvelopeV2<BookedPayload> body =
        new DomainEventEnvelopeV2<>(
            2,
            eventId,
            "logistics.rental-inquiry.booked.v1",
            1,
            occurredAt.toInstant(),
            recordedAt.toInstant(),
            "logistics-service",
            "RENTAL_INQUIRY",
            inquiryId.toString(),
            inquiryVersion,
            new CorrelationContext(conversationId, bookingId),
            new OpaqueActorReference(managerSubjectId.toString(), "USER", null),
            new BookedPayload(conversationId, orderId));
    try {
      jdbc.update(
          """
          insert into rental_inquiry_outbox(
              event_id,event_type,inquiry_id,conversation_id,order_id,
              payload,status,attempt_count,next_attempt_at,created_at)
          values (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?, ?)
          """,
          eventId,
          body.eventType(),
          inquiryId,
          conversationId,
          orderId,
          json.writeValueAsString(body),
          recordedAt,
          recordedAt);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Rental inquiry event cannot be serialized", exception);
    }
  }

  /** Locks and returns the next bounded batch of pending events for the outer relay transaction. */
  public List<PendingEvent> claimDueForRelay() {
    return jdbc.query(
        """
        select event_id,conversation_id,payload::text
        from rental_inquiry_outbox
        where status='PENDING' and next_attempt_at <= clock_timestamp()
        order by created_at,event_id
        for update skip locked
        limit 50
        """,
        (result, row) ->
            new PendingEvent(
                result.getObject("event_id", UUID.class),
                result.getObject("conversation_id", UUID.class),
                result.getString("payload")));
  }

  /** Marks a successfully delivered event as published inside the relay transaction. */
  public void markPublished(UUID eventId) {
    jdbc.update(
        """
        update rental_inquiry_outbox
        set status='PUBLISHED',published_at=clock_timestamp()
        where event_id=?
        """,
        eventId);
  }

  /** Schedules the existing fixed retry delay after a failed non-transactional broker send. */
  public void scheduleRetry(UUID eventId) {
    jdbc.update(
        """
        update rental_inquiry_outbox
        set attempt_count=attempt_count+1,
            next_attempt_at=clock_timestamp() + interval '5 seconds'
        where event_id=?
        """,
        eventId);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Exact business payload carried by the canonical booked event envelope. */
  public record BookedPayload(UUID conversationId, UUID orderId) {}

  /** Locked outbox row whose payload can be relayed by the current transaction. */
  public record PendingEvent(UUID eventId, UUID conversationId, String payload) {}
}
