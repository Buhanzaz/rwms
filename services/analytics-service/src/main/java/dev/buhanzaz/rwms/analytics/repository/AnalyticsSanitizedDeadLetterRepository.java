package dev.buhanzaz.rwms.analytics.repository;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsOutboxState;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsSanitizedDeadLetter;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AnalyticsSanitizedDeadLetterRepository
    extends JpaRepository<AnalyticsSanitizedDeadLetter, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AnalyticsSanitizedDeadLetter>
      findFirstByStatusInAndNextAttemptAtLessThanEqualOrderByFailedAtAsc(
          Collection<AnalyticsOutboxState> statuses, OffsetDateTime now);
}
