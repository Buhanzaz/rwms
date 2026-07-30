package dev.buhanzaz.rwms.analytics.eventing;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsOutboxState;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsSanitizedDeadLetter;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsSanitizedDeadLetterRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsDltRelayTransactions {
  private static final int MAXIMUM_ATTEMPTS = 8;
  private final AnalyticsSanitizedDeadLetterRepository repository;
  private final Clock clock;

  public AnalyticsDltRelayTransactions(
      AnalyticsSanitizedDeadLetterRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public Optional<AnalyticsSanitizedDeadLetter> next() {
    return repository.findFirstByStatusInAndNextAttemptAtLessThanEqualOrderByFailedAtAsc(
        List.of(AnalyticsOutboxState.PENDING, AnalyticsOutboxState.RETRY),
        OffsetDateTime.now(clock));
  }

  @Transactional
  public void published(UUID id) {
    repository.findById(id).orElseThrow().published(OffsetDateTime.now(clock));
  }

  @Transactional
  public void failed(UUID id) {
    AnalyticsSanitizedDeadLetter value = repository.findById(id).orElseThrow();
    if (value.getAttemptCount() + 1 >= MAXIMUM_ATTEMPTS) {
      value.terminalFailure();
      return;
    }
    long delaySeconds = 1L << Math.min(value.getAttemptCount(), 6);
    value.retry(OffsetDateTime.now(clock).plusSeconds(delaySeconds));
  }
}
