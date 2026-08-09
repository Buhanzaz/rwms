package dev.buhanzaz.rwms.analytics.repository;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsPartitionCheckpoint;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Repository for locked source-partition checkpoints used to make offset handling durable. */
public interface AnalyticsPartitionCheckpointRepository
    extends JpaRepository<AnalyticsPartitionCheckpoint, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AnalyticsPartitionCheckpoint>
      findForUpdateByConsumerGroupAndSourceTopicAndSourcePartition(
          String consumerGroup, String sourceTopic, int sourcePartition);
}
