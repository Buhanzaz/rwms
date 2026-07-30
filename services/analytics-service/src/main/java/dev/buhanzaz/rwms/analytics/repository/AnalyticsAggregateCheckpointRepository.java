package dev.buhanzaz.rwms.analytics.repository;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsAggregateCheckpoint;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AnalyticsAggregateCheckpointRepository
    extends JpaRepository<AnalyticsAggregateCheckpoint, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AnalyticsAggregateCheckpoint>
      findForUpdateByConsumerGroupAndSourceTopicAndAggregateTypeAndAggregateId(
          String consumerGroup, String sourceTopic, String aggregateType, UUID aggregateId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<AnalyticsAggregateCheckpoint> findAllForUpdateByGapOpenTrueAndTerminallyBlockedFalse();
}
