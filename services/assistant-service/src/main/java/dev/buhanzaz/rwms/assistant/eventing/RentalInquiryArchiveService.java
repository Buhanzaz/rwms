package dev.buhanzaz.rwms.assistant.eventing;

import dev.buhanzaz.rwms.assistant.domain.AssistantEventInbox;
import dev.buhanzaz.rwms.assistant.domain.AssistantEventInboxState;
import dev.buhanzaz.rwms.assistant.repository.AssistantEventInboxRepository;
import dev.buhanzaz.rwms.assistant.service.AssistantConversationService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the durable booking-event inbox state machine and archives only the matching assistant
 * conversation inside a bounded, independently committed processing attempt.
 */
@Service
public class RentalInquiryArchiveService {
  private static final Duration ATTEMPT_LEASE = Duration.ofSeconds(30);

  private final AssistantEventInboxRepository inbox;
  private final AssistantConversationService conversations;

  public RentalInquiryArchiveService(
      AssistantEventInboxRepository inbox, AssistantConversationService conversations) {
    this.inbox = inbox;
    this.conversations = conversations;
  }

  /**
   * Stages one exact canonical envelope. Both event ID and source coordinates are compared after
   * an insert-on-conflict fence, so neither identity can be rebound to another canonical hash.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public StageOutcome stage(RentalInquiryBookedEventParser.ParsedEvent parsed) {
    Objects.requireNonNull(parsed, "parsed");
    RentalInquiryBookedEvent event = parsed.event();
    OffsetDateTime receivedAt = now();
    inbox.insertIfAbsent(
        event.eventId(),
        event.eventType(),
        event.occurredAt(),
        event.aggregateId(),
        event.conversationId(),
        event.orderId(),
        receivedAt,
        parsed.canonicalEnvelope(),
        parsed.canonicalSha256(),
        parsed.sourceTopic(),
        parsed.sourcePartition(),
        parsed.sourceOffset());

    AssistantEventInbox byEvent = inbox.findByIdForUpdate(event.eventId()).orElse(null);
    AssistantEventInbox bySource =
        inbox
            .findBySourceForUpdate(
                parsed.sourceTopic(), parsed.sourcePartition(), parsed.sourceOffset())
            .orElse(null);
    if (byEvent == null || bySource == null) {
      throw new AssistantEventIdentityConflictException();
    }
    if (!byEvent.getEventId().equals(bySource.getEventId()) || !matches(byEvent, parsed)) {
      throw new AssistantEventIdentityConflictException();
    }
    return switch (byEvent.getProcessingState()) {
      case STAGED -> StageOutcome.READY;
      case PROCESSED -> StageOutcome.DUPLICATE;
      case DLT -> StageOutcome.DEAD_LETTERED;
      case LEGACY_PROCESSED -> throw new AssistantEventIdentityConflictException();
    };
  }

  /** Claims the next persisted lifetime attempt or returns its remaining durable delay. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AttemptDecision claimNextAttempt(UUID eventId) {
    AssistantEventInbox receipt =
        inbox.findByIdForUpdate(eventId).orElseThrow(IllegalStateException::new);
    if (receipt.getProcessingState() == AssistantEventInboxState.PROCESSED) {
      return AttemptDecision.terminal(AttemptStatus.PROCESSED);
    }
    if (receipt.getProcessingState() == AssistantEventInboxState.DLT) {
      return AttemptDecision.terminal(AttemptStatus.DEAD_LETTERED);
    }
    if (receipt.getProcessingState() != AssistantEventInboxState.STAGED) {
      throw new AssistantEventIdentityConflictException();
    }
    if (receipt.getAttemptCount() >= 4) {
      return AttemptDecision.terminal(AttemptStatus.EXHAUSTED);
    }
    OffsetDateTime timestamp = now();
    OffsetDateTime eligibleAt = receipt.getNextAttemptAt();
    if (eligibleAt != null && eligibleAt.isAfter(timestamp)) {
      return AttemptDecision.waitFor(Duration.between(timestamp, eligibleAt));
    }
    receipt.claimAttempt(timestamp.plus(ATTEMPT_LEASE));
    return AttemptDecision.claimed(receipt.getAttemptCount());
  }

  /** Persists the 1s/2s/4s retry boundary for the exact failed lifetime attempt. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void scheduleRetry(UUID eventId, int failedAttempt) {
    if (failedAttempt < 1 || failedAttempt > 3) {
      throw new IllegalArgumentException("Only attempts one through three can be retried");
    }
    AssistantEventInbox receipt =
        inbox.findByIdForUpdate(eventId).orElseThrow(IllegalStateException::new);
    receipt.scheduleRetry(failedAttempt, now().plusSeconds(1L << (failedAttempt - 1)));
  }

  /**
   * Applies the assistant-owned archival effect and marks the staged receipt processed in the same
   * transaction. A missing or mismatched conversation is a processed no-op, not another owner.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ProcessingOutcome process(RentalInquiryBookedEventParser.ParsedEvent parsed) {
    AssistantEventInbox receipt =
        inbox.findByIdForUpdate(parsed.event().eventId()).orElseThrow(IllegalStateException::new);
    verify(receipt, parsed);
    if (receipt.getProcessingState() == AssistantEventInboxState.PROCESSED) {
      return ProcessingOutcome.DUPLICATE;
    }
    if (receipt.getProcessingState() != AssistantEventInboxState.STAGED
        || receipt.getAttemptCount() < 1) {
      throw new IllegalStateException("Assistant event is not staged for processing");
    }
    conversations.archiveFromRentalInquiry(
        parsed.event().conversationId(), parsed.event().aggregateId());
    receipt.markProcessed(now());
    return ProcessingOutcome.PROCESSED;
  }

  /** Loads only a previously staged canonical envelope as an internal reviewed-replay source. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public ReplaySource replaySource(UUID eventId) {
    AssistantEventInbox receipt = inbox.findById(eventId).orElseThrow(IllegalStateException::new);
    if (receipt.getProcessingState() != AssistantEventInboxState.DLT
        && receipt.getProcessingState() != AssistantEventInboxState.PROCESSED) {
      throw new IllegalStateException("Assistant event is not an approved replay source");
    }
    return new ReplaySource(
        receipt.getEventId(),
        receipt.getConversationId(),
        receipt.getPayload().toString(),
        receipt.getCanonicalEnvelopeSha256(),
        receipt.getSourceTopic(),
        receipt.getSourcePartition(),
        receipt.getSourceOffset());
  }

  /** Reapplies only a validated canonical DLT source and makes a completed replay idempotent. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ProcessingOutcome replay(RentalInquiryBookedEventParser.ParsedEvent parsed) {
    AssistantEventInbox receipt =
        inbox.findByIdForUpdate(parsed.event().eventId()).orElseThrow(IllegalStateException::new);
    verify(receipt, parsed);
    if (receipt.getProcessingState() == AssistantEventInboxState.PROCESSED) {
      return ProcessingOutcome.DUPLICATE;
    }
    if (receipt.getProcessingState() != AssistantEventInboxState.DLT) {
      throw new IllegalStateException("Assistant event is not reviewable");
    }
    conversations.archiveFromRentalInquiry(
        parsed.event().conversationId(), parsed.event().aggregateId());
    receipt.markProcessed(now());
    return ProcessingOutcome.PROCESSED;
  }

  private static void verify(
      AssistantEventInbox receipt, RentalInquiryBookedEventParser.ParsedEvent parsed) {
    if (!matches(receipt, parsed)) throw new AssistantEventIdentityConflictException();
  }

  private static boolean matches(
      AssistantEventInbox receipt, RentalInquiryBookedEventParser.ParsedEvent parsed) {
    RentalInquiryBookedEvent event = parsed.event();
    return receipt.getEventId().equals(event.eventId())
        && parsed.canonicalSha256().equals(receipt.getCanonicalEnvelopeSha256())
        && parsed.sourceTopic().equals(receipt.getSourceTopic())
        && Integer.valueOf(parsed.sourcePartition()).equals(receipt.getSourcePartition())
        && Long.valueOf(parsed.sourceOffset()).equals(receipt.getSourceOffset())
        && event.aggregateId().equals(receipt.getRentalInquiryId())
        && event.conversationId().equals(receipt.getConversationId())
        && event.orderId().equals(receipt.getOrderId());
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Result of staging a canonical event receipt. */
  public enum StageOutcome {
    READY,
    DUPLICATE,
    DEAD_LETTERED
  }

  /** Durable consumer action available for the next lifetime attempt. */
  public enum AttemptStatus {
    WAIT,
    CLAIMED,
    PROCESSED,
    DEAD_LETTERED,
    EXHAUSTED
  }

  /** Attempt number or remaining wait associated with one claim decision. */
  public record AttemptDecision(AttemptStatus status, int attemptNumber, Duration delay) {
    private static AttemptDecision claimed(int attemptNumber) {
      return new AttemptDecision(AttemptStatus.CLAIMED, attemptNumber, Duration.ZERO);
    }

    private static AttemptDecision waitFor(Duration delay) {
      return new AttemptDecision(AttemptStatus.WAIT, 0, delay);
    }

    private static AttemptDecision terminal(AttemptStatus status) {
      return new AttemptDecision(status, 0, Duration.ZERO);
    }
  }

  /** Result of normal or reviewed processing. */
  public enum ProcessingOutcome {
    PROCESSED,
    DUPLICATE
  }

  /** Canonical, hash-bound inbox data allowed to enter the reviewed replay validator. */
  public record ReplaySource(
      UUID eventId,
      UUID recordKey,
      String canonicalEnvelope,
      String canonicalSha256,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset) {}
}
