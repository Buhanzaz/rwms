package dev.buhanzaz.rwms.analytics.repository;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsAggregateCheckpoint;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Repository for per-aggregate version checkpoints and unresolved-gap recovery scans. */
public interface AnalyticsAggregateCheckpointRepository
    extends JpaRepository<AnalyticsAggregateCheckpoint, UUID> {
  /**
   * Locks one aggregate checkpoint while the owning analytics transaction applies or holds a
   * source version.
   *
   * @param consumerGroup contract-defined analytics consumer group
   * @param sourceTopic canonical producer topic
   * @param aggregateType canonical producer aggregate type
   * @param aggregateId producer aggregate identity
   * @return the locked checkpoint when the aggregate has already been observed
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AnalyticsAggregateCheckpoint>
      findForUpdateByConsumerGroupAndSourceTopicAndAggregateTypeAndAggregateId(
          String consumerGroup, String sourceTopic, String aggregateType, UUID aggregateId);

  /**
   * Locks every gap still eligible for the scheduled local retry transaction.
   *
   * @return locked, non-terminal checkpoints with an open version gap
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<AnalyticsAggregateCheckpoint> findAllForUpdateByGapOpenTrueAndTerminallyBlockedFalse();

  /**
   * Counts open checkpoint gaps still eligible for the bounded local retry worker.
   *
   * <p>The observation excludes terminally blocked gaps because they require reviewed,
   * owner-authoritative recovery rather than another local retry.
   *
   * @return count of open checkpoints still eligible for bounded local retry
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(checkpoint)
      from AnalyticsAggregateCheckpoint checkpoint
      where checkpoint.gapOpen = true
        and checkpoint.terminallyBlocked = false
      """)
  long countActiveOpenGaps();

  /**
   * Counts open checkpoint gaps whose local retry budget has been exhausted.
   *
   * <p>The result is observational only; it neither changes a checkpoint nor requests producer
   * replay.
   *
   * @return count of open checkpoints whose retry budget is exhausted
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(checkpoint)
      from AnalyticsAggregateCheckpoint checkpoint
      where checkpoint.gapOpen = true
        and checkpoint.terminallyBlocked = true
      """)
  long countTerminallyBlockedGaps();

  /**
   * Finds when the oldest retained open gap was first observed.
   *
   * <p>Terminally blocked gaps remain included so the age continues to show unresolved projection
   * incompleteness until reviewed owner recovery resolves it.
   *
   * @return first-observed time of the oldest retained open gap, or empty when none exists
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select min(checkpoint.gapFirstSeenAt)
      from AnalyticsAggregateCheckpoint checkpoint
      where checkpoint.gapOpen = true
      """)
  Optional<OffsetDateTime> findOldestOpenGapFirstSeenAt();

  /**
   * Returns the highest local retry attempt recorded for any retained open gap.
   *
   * <p>Terminally blocked gaps remain included to make exhausted retry budgets visible to
   * operational monitoring.
   *
   * @return the maximum recorded local retry attempt, or zero when no gap is open
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select coalesce(max(checkpoint.gapAttemptCount), 0)
      from AnalyticsAggregateCheckpoint checkpoint
      where checkpoint.gapOpen = true
      """)
  int findMaximumOpenGapAttemptCount();
}
