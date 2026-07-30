package dev.buhanzaz.rwms.analytics.repository;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsSourceFact;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsSourceFactRepository extends JpaRepository<AnalyticsSourceFact, UUID> {
  Optional<AnalyticsSourceFact> findByEventId(UUID eventId);

  Optional<AnalyticsSourceFact> findBySourceTopicAndSourcePartitionAndSourceOffset(
      String sourceTopic, int sourcePartition, long sourceOffset);

  List<AnalyticsSourceFact> findAllByAggregateIdAndAggregateVersionOrderByEventIdAsc(
      UUID aggregateId, long aggregateVersion);

  List<AnalyticsSourceFact> findAllByAggregateIdOrderByAggregateVersionAscEventIdAsc(
      UUID aggregateId);
}
