package dev.buhanzaz.rwms.assistant.eventing;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Safe identities derived from one strictly validated canonical logistics booking envelope. The
 * aggregate ID is the rental-inquiry identity; it is not duplicated in the payload. The complete
 * canonical envelope remains in the inbox for conflict detection and reviewed replay.
 */
public record RentalInquiryBookedEvent(
    UUID eventId,
    String eventType,
    OffsetDateTime occurredAt,
    OffsetDateTime recordedAt,
    long aggregateVersion,
    UUID aggregateId,
    UUID conversationId,
    UUID orderId,
    UUID causationId) {
  public static final String TYPE = "logistics.rental-inquiry.booked.v1";
  public static final String PRODUCER = "logistics-service";
  public static final String AGGREGATE_TYPE = "RENTAL_INQUIRY";
  public static final String SOURCE_TOPIC = "rwms.logistics.rental-inquiry.events.v1";
}
