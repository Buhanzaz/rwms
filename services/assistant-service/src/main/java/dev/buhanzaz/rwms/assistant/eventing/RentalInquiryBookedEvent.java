package dev.buhanzaz.rwms.assistant.eventing;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RentalInquiryBookedEvent(
    UUID eventId,
    String eventType,
    OffsetDateTime occurredAt,
    UUID rentalInquiryId,
    UUID conversationId,
    UUID orderId) {
  public static final String TYPE = "logistics.rental-inquiry.booked.v1";
}
