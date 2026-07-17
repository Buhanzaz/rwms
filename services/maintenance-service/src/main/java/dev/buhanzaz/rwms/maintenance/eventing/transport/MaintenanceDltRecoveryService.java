package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Internal operator-reviewed recovery; deliberately has no public HTTP endpoint. */
@Service
public class MaintenanceDltRecoveryService {
  private final MaintenanceSanitizedDltStore deadLetters;
  private final MaintenanceInboxProcessor inbox;

  public MaintenanceDltRecoveryService(
      MaintenanceSanitizedDltStore deadLetters, MaintenanceInboxProcessor inbox) {
    this.deadLetters = deadLetters;
    this.inbox = inbox;
  }

  public boolean approveAndReplay(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    var ticket = deadLetters.approveReplay(dltId, expectedReviewVersion, reviewerSubjectId);
    if (ticket.isEmpty()) {
      return false;
    }
    MaintenanceInboxProcessor.Outcome outcome = inbox.replay(ticket.get().eventId());
    return replayCompleted(outcome)
        && deadLetters.markReplayed(dltId, ticket.get().reviewVersion());
  }

  public boolean reject(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    return deadLetters.rejectReplay(dltId, expectedReviewVersion, reviewerSubjectId);
  }

  public boolean resumeApprovedReplay(UUID dltId, long approvedReviewVersion) {
    var ticket = deadLetters.approvedReplay(dltId, approvedReviewVersion);
    if (ticket.isEmpty()) {
      return false;
    }
    MaintenanceInboxProcessor.Outcome outcome = inbox.replay(ticket.get().eventId());
    return replayCompleted(outcome)
        && deadLetters.markReplayed(dltId, ticket.get().reviewVersion());
  }

  public boolean requeueFailedBrokerDelivery(UUID dltId) {
    return deadLetters.requeueFailedDelivery(dltId);
  }

  private static boolean replayCompleted(MaintenanceInboxProcessor.Outcome outcome) {
    return outcome == MaintenanceInboxProcessor.Outcome.PROCESSED
        || outcome == MaintenanceInboxProcessor.Outcome.DUPLICATE;
  }
}
