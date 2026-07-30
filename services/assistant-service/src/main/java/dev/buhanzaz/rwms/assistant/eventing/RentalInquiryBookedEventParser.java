package dev.buhanzaz.rwms.assistant.eventing;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RentalInquiryBookedEventParser {
  private static final Set<String> FIELDS =
      Set.of(
          "eventId",
          "eventType",
          "occurredAt",
          "rentalInquiryId",
          "conversationId",
          "orderId");

  private final ObjectMapper mapper;

  public RentalInquiryBookedEventParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ParsedEvent parse(String raw) {
    JsonNode root;
    try {
      root = mapper.readTree(raw);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Rental inquiry event is invalid", failure);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("Rental inquiry event must be an object");
    }
    Set<String> actual = new HashSet<>();
    actual.addAll(root.propertyNames());
    if (!actual.equals(FIELDS)) {
      throw new IllegalArgumentException("Rental inquiry event has an unsafe shape");
    }
    RentalInquiryBookedEvent event =
        new RentalInquiryBookedEvent(
            uuid(root, "eventId"),
            requiredText(root, "eventType"),
            timestamp(root, "occurredAt"),
            uuid(root, "rentalInquiryId"),
            uuid(root, "conversationId"),
            uuid(root, "orderId"));
    if (!RentalInquiryBookedEvent.TYPE.equals(event.eventType())) {
      throw new IllegalArgumentException("Unsupported rental inquiry event type");
    }
    return new ParsedEvent(event, mapper.writeValueAsString(root));
  }

  private static UUID uuid(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a UUID");
    }
    try {
      return UUID.fromString(value.asText());
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException(field + " must be a UUID", failure);
    }
  }

  private static OffsetDateTime timestamp(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a timestamp");
    }
    try {
      return OffsetDateTime.parse(value.asText());
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException(field + " must be a timestamp", failure);
    }
  }

  private static String requiredText(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.asText();
  }

  public record ParsedEvent(RentalInquiryBookedEvent event, String canonicalPayload) {}
}
