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
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Repository for pending and terminal sanitized DLT relay records. */
public interface AnalyticsSanitizedDeadLetterRepository
    extends JpaRepository<AnalyticsSanitizedDeadLetter, UUID> {
  /**
   * Locks the oldest due sanitized DLT record in one of the supplied relay states.
   *
   * @param statuses relay states that remain eligible for delivery
   * @param now observation time used to select only due records
   * @return the locked oldest due record, or empty when no supplied state is due
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AnalyticsSanitizedDeadLetter>
      findFirstByStatusInAndNextAttemptAtLessThanEqualOrderByFailedAtAsc(
          Collection<AnalyticsOutboxState> statuses, OffsetDateTime now);

  /**
   * Counts sanitized DLT records that the local relay may still publish or retry.
   *
   * <p>Only {@link AnalyticsOutboxState#PENDING} and {@link AnalyticsOutboxState#RETRY} are
   * backlog; published and terminal records are intentionally excluded.
   *
   * @return count of sanitized DLT records that remain publishable or retryable
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(deadLetter)
      from AnalyticsSanitizedDeadLetter deadLetter
      where deadLetter.status in (
        dev.buhanzaz.rwms.analytics.domain.AnalyticsOutboxState.PENDING,
        dev.buhanzaz.rwms.analytics.domain.AnalyticsOutboxState.RETRY)
      """)
  long countRecoverableBacklog();

  /**
   * Finds the oldest sanitized DLT record that the local relay may still publish or retry.
   *
   * <p>The optional is empty when no {@link AnalyticsOutboxState#PENDING} or {@link
   * AnalyticsOutboxState#RETRY} record remains.
   *
   * @return oldest remaining backlog failure time, or empty when the backlog is clear
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select min(deadLetter.failedAt)
      from AnalyticsSanitizedDeadLetter deadLetter
      where deadLetter.status in (
        dev.buhanzaz.rwms.analytics.domain.AnalyticsOutboxState.PENDING,
        dev.buhanzaz.rwms.analytics.domain.AnalyticsOutboxState.RETRY)
      """)
  Optional<OffsetDateTime> findOldestRecoverableBacklogAt();

  /**
   * Counts sanitized DLT records whose local publication retry budget is exhausted.
   *
   * <p>The result is a read-only terminal-work signal and never retries or republishes a record.
   *
   * @return count of sanitized DLT records whose retry budget is exhausted
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(deadLetter)
      from AnalyticsSanitizedDeadLetter deadLetter
      where deadLetter.status = dev.buhanzaz.rwms.analytics.domain.AnalyticsOutboxState.DLT
      """)
  long countTerminallyDeadLettered();
}
