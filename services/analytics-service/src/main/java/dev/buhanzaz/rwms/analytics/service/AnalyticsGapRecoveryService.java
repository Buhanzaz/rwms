package dev.buhanzaz.rwms.analytics.service;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsInboxDecision;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsDeadLetterService;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsAggregateCheckpointRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsInboxRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsSourceFactRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsGapRecoveryService {
  private final AnalyticsAggregateCheckpointRepository checkpoints;
  private final AnalyticsSourceFactRepository sourceFacts;
  private final AnalyticsInboxRepository inboxes;
  private final AnalyticsDeadLetterService deadLetters;
  private final Clock clock;
  private final int maximumAttempts;

  public AnalyticsGapRecoveryService(
      AnalyticsAggregateCheckpointRepository checkpoints,
      AnalyticsSourceFactRepository sourceFacts,
      AnalyticsInboxRepository inboxes,
      AnalyticsDeadLetterService deadLetters,
      Clock clock,
      @Value("${rwms.analytics.gap.maximum-attempts:4}") int maximumAttempts) {
    if (maximumAttempts < 1) {
      throw new IllegalArgumentException("Analytics gap maximum attempts must be positive");
    }
    this.checkpoints = checkpoints;
    this.sourceFacts = sourceFacts;
    this.inboxes = inboxes;
    this.deadLetters = deadLetters;
    this.clock = clock;
    this.maximumAttempts = maximumAttempts;
  }

  @Scheduled(fixedDelayString = "${rwms.analytics.gap.retry-delay:30s}")
  @Transactional
  public void retryOpenGaps() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    checkpoints
        .findAllForUpdateByGapOpenTrueAndTerminallyBlockedFalse()
        .forEach(
            checkpoint -> {
              if (!checkpoint.retryGap(maximumAttempts, now)) return;
              sourceFacts
                  .findAllByAggregateIdOrderByAggregateVersionAscEventIdAsc(
                      checkpoint.getAggregateId())
                  .forEach(
                      fact ->
                          inboxes
                              .findById(fact.getEventId())
                              .filter(
                                  inbox -> inbox.getDecision() == AnalyticsInboxDecision.HELD)
                              .ifPresent(
                                  inbox -> {
                                    inbox.deadLetter(now);
                                    deadLetters.gapFailure(fact);
                                  }));
            });
  }
}
