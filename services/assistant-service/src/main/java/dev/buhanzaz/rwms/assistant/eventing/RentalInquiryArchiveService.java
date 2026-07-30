package dev.buhanzaz.rwms.assistant.eventing;

import dev.buhanzaz.rwms.assistant.repository.AssistantEventInboxRepository;
import dev.buhanzaz.rwms.assistant.service.AssistantConversationService;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RentalInquiryArchiveService {
  private final AssistantEventInboxRepository inbox;
  private final AssistantConversationService conversations;

  public RentalInquiryArchiveService(
      AssistantEventInboxRepository inbox, AssistantConversationService conversations) {
    this.inbox = inbox;
    this.conversations = conversations;
  }

  /**
   * The unique inbox key makes replay and duplicate Kafka delivery idempotent.
   * A non-matching inquiry is still recorded so it cannot later archive another
   * conversation if identifiers are reused maliciously.
   */
  @Transactional
  public boolean archive(RentalInquiryBookedEventParser.ParsedEvent parsed) {
    RentalInquiryBookedEvent event = parsed.event();
    int inserted =
        inbox.insertIfAbsent(
            event.eventId(),
            event.eventType(),
            event.occurredAt(),
            event.rentalInquiryId(),
            event.conversationId(),
            event.orderId(),
            OffsetDateTime.now(java.time.ZoneOffset.UTC),
            parsed.canonicalPayload());
    if (inserted == 0) return false;
    conversations.archiveFromRentalInquiry(event.conversationId(), event.rentalInquiryId());
    return true;
  }
}
