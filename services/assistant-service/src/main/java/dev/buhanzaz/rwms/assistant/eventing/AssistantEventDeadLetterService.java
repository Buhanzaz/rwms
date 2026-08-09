package dev.buhanzaz.rwms.assistant.eventing;

import dev.buhanzaz.rwms.assistant.domain.AssistantEventDeadLetter;
import dev.buhanzaz.rwms.assistant.domain.AssistantEventInbox;
import dev.buhanzaz.rwms.assistant.domain.AssistantEventInboxState;
import dev.buhanzaz.rwms.assistant.domain.AssistantEventReplayState;
import dev.buhanzaz.rwms.assistant.repository.AssistantEventDeadLetterRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantEventInboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists only hash/coordinate/failure evidence for assistant booking-event failures and owns
 * version-fenced internal review transitions.
 */
@Service
public class AssistantEventDeadLetterService {
  private static final Set<String> FAILURE_CODES =
      Set.of(
          "SOURCE_RECORD_INVALID",
          "SOURCE_SCHEMA_REJECTED",
          "SOURCE_RECORD_KEY_MISMATCH",
          "EVENT_ID_CONFLICT",
          "PROCESSING_FAILED");

  private final AssistantEventDeadLetterRepository deadLetters;
  private final AssistantEventInboxRepository inbox;

  public AssistantEventDeadLetterService(
      AssistantEventDeadLetterRepository deadLetters,
      AssistantEventInboxRepository inbox) {
    this.deadLetters = deadLetters;
    this.inbox = inbox;
  }

  /** Records a non-replayable rejected receipt without retaining its raw value or exception text. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID recordRejected(
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      String messageSha256,
      String failureCode) {
    return record(
        sourceTopic,
        sourcePartition,
        sourceOffset,
        null,
        messageSha256,
        failureCode,
        AssistantEventReplayState.NOT_REPLAYABLE);
  }

  /**
   * Atomically records reviewable evidence and moves the exhausted inbox row to DLT. If either
   * write fails, both roll back and the Kafka delivery remains failed for safe redelivery.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID recordProcessingFailure(RentalInquiryBookedEventParser.ParsedEvent parsed) {
    UUID id =
        record(
            parsed.sourceTopic(),
            parsed.sourcePartition(),
            parsed.sourceOffset(),
            parsed.event().eventId(),
            parsed.canonicalSha256(),
            "PROCESSING_FAILED",
            AssistantEventReplayState.AWAITING_REVIEW);
    AssistantEventInbox receipt =
        inbox
            .findByIdForUpdate(parsed.event().eventId())
            .orElseThrow(IllegalStateException::new);
    if (!parsed.canonicalSha256().equals(receipt.getCanonicalEnvelopeSha256())
        || !parsed.sourceTopic().equals(receipt.getSourceTopic())
        || !Integer.valueOf(parsed.sourcePartition()).equals(receipt.getSourcePartition())
        || !Long.valueOf(parsed.sourceOffset()).equals(receipt.getSourceOffset())) {
      throw new AssistantEventIdentityConflictException();
    }
    if (receipt.getProcessingState() != AssistantEventInboxState.DLT) {
      receipt.markDeadLettered(now());
    }
    return id;
  }

  /** Approves one exact review version and returns an idempotent replay ticket. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<ReplayTicket> approveReplay(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    AssistantEventDeadLetter evidence =
        deadLetters.findByIdForUpdate(dltId).orElse(null);
    if (evidence == null
        || !evidence.approve(expectedReviewVersion, reviewerSubjectId, now())) {
      return Optional.empty();
    }
    return Optional.of(
        new ReplayTicket(
            evidence.getSourceEventId(),
            evidence.getReviewVersion(),
            evidence.getReplayState() == AssistantEventReplayState.REPLAYED));
  }

  /** Applies a version-fenced rejection and treats the identical retry as success. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean rejectReplay(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    AssistantEventDeadLetter evidence =
        deadLetters.findByIdForUpdate(dltId).orElse(null);
    return evidence != null
        && evidence.reject(expectedReviewVersion, reviewerSubjectId, now());
  }

  /** Loads an approved ticket at its exact review version for an interrupted internal replay. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<ReplayTicket> approvedReplay(UUID dltId, long approvedReviewVersion) {
    AssistantEventDeadLetter evidence =
        deadLetters.findByIdForUpdate(dltId).orElse(null);
    if (evidence == null
        || evidence.getReviewVersion() != approvedReviewVersion
        || (evidence.getReplayState() != AssistantEventReplayState.APPROVED
            && evidence.getReplayState() != AssistantEventReplayState.REPLAYED)) {
      return Optional.empty();
    }
    return Optional.of(
        new ReplayTicket(
            evidence.getSourceEventId(),
            evidence.getReviewVersion(),
            evidence.getReplayState() == AssistantEventReplayState.REPLAYED));
  }

  /** Marks one approved review version replayed and makes the exact repeat harmless. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markReplayed(UUID dltId, long approvedReviewVersion) {
    AssistantEventDeadLetter evidence =
        deadLetters.findByIdForUpdate(dltId).orElse(null);
    return evidence != null && evidence.markReplayed(approvedReviewVersion, now());
  }

  private UUID record(
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      UUID sourceEventId,
      String messageSha256,
      String failureCode,
      AssistantEventReplayState initialReplayState) {
    if (!RentalInquiryBookedEventParser.hasDurableSource(
            sourceTopic, sourcePartition, sourceOffset)
        || messageSha256 == null
        || !messageSha256.matches("[0-9a-f]{64}")
        || !FAILURE_CODES.contains(failureCode)) {
      throw new IllegalArgumentException("Assistant dead-letter evidence is invalid");
    }
    String receiptIdentity =
        "assistant-booking-dlt"
            + '\u001f'
            + sourceTopic
            + '\u001f'
            + sourcePartition
            + '\u001f'
            + sourceOffset;
    UUID id = UUID.nameUUIDFromBytes(receiptIdentity.getBytes(StandardCharsets.UTF_8));
    deadLetters.insertIfAbsent(
        id,
        sourceTopic,
        sourcePartition,
        sourceOffset,
        sourceEventId,
        messageSha256,
        failureCode,
        initialReplayState.name(),
        now());
    AssistantEventDeadLetter stored =
        deadLetters
            .findBySourceForUpdate(sourceTopic, sourcePartition, sourceOffset)
            .orElseThrow(IllegalStateException::new);
    if (!id.equals(stored.getId())
        || !Objects.equals(sourceEventId, stored.getSourceEventId())
        || !messageSha256.equals(stored.getMessageSha256())
        || !failureCode.equals(stored.getFailureCode())) {
      throw new AssistantEventIdentityConflictException();
    }
    return id;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Version-fenced source identity for one approved reviewed replay. */
  public record ReplayTicket(UUID eventId, long reviewVersion, boolean alreadyReplayed) {}
}
