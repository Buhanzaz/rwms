package dev.buhanzaz.rwms.assistant.eventing;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Internal operator-reviewed assistant recovery. It deliberately has no controller or automatic
 * production caller and never accepts a raw event body.
 */
@Service
public class AssistantDltRecoveryService {
  private final AssistantEventDeadLetterService deadLetters;
  private final RentalInquiryArchiveService archive;
  private final RentalInquiryBookedEventParser parser;

  public AssistantDltRecoveryService(
      AssistantEventDeadLetterService deadLetters,
      RentalInquiryArchiveService archive,
      RentalInquiryBookedEventParser parser) {
    this.deadLetters = deadLetters;
    this.archive = archive;
    this.parser = parser;
  }

  /** Approves the expected review version and immediately attempts its canonical staged source. */
  public boolean approveAndReplay(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    var ticket =
        deadLetters.approveReplay(dltId, expectedReviewVersion, reviewerSubjectId);
    return ticket.isPresent() && replay(dltId, ticket.orElseThrow());
  }

  /** Rejects the expected review version without exposing or deleting its sanitized evidence. */
  public boolean reject(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    return deadLetters.rejectReplay(dltId, expectedReviewVersion, reviewerSubjectId);
  }

  /** Resumes a previously approved replay after an internal interruption. */
  public boolean resumeApprovedReplay(UUID dltId, long approvedReviewVersion) {
    var ticket = deadLetters.approvedReplay(dltId, approvedReviewVersion);
    return ticket.isPresent() && replay(dltId, ticket.orElseThrow());
  }

  private boolean replay(
      UUID dltId, AssistantEventDeadLetterService.ReplayTicket ticket) {
    if (ticket.alreadyReplayed()) return true;
    RentalInquiryArchiveService.ReplaySource source = archive.replaySource(ticket.eventId());
    RentalInquiryBookedEventParser.ParsedEvent parsed =
        parser.parse(
            source.sourceTopic(),
            source.sourcePartition(),
            source.sourceOffset(),
            source.recordKey().toString(),
            source.canonicalEnvelope());
    if (!source.canonicalSha256().equals(parsed.canonicalSha256())) {
      throw new AssistantEventIdentityConflictException();
    }
    archive.replay(parsed);
    return deadLetters.markReplayed(dltId, ticket.reviewVersion());
  }
}
