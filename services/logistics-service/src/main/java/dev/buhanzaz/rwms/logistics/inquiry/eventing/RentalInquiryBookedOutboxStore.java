package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class RentalInquiryBookedOutboxStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(UUID inquiryId, UUID conversationId, UUID orderId) {
    UUID eventId = UUID.randomUUID();
    OffsetDateTime occurredAt = now();
    EventBody body =
        new EventBody(
            eventId,
            "logistics.rental-inquiry.booked.v1",
            occurredAt,
            inquiryId,
            conversationId,
            orderId);
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
          occurredAt,
          occurredAt);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Rental inquiry event cannot be serialized", exception);
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  public record EventBody(
      UUID eventId,
      String eventType,
      OffsetDateTime occurredAt,
      UUID rentalInquiryId,
      UUID conversationId,
      UUID orderId) {}
}
