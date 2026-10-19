package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierPartitionCheckpoint;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Repository for source-partition checkpoints locked during transactional consumer processing. */
public interface DossierPartitionCheckpointRepository
    extends JpaRepository<DossierPartitionCheckpoint, UUID> {
  Optional<DossierPartitionCheckpoint> findByConsumerGroupAndSourceTopicAndSourcePartition(
      String consumerGroup, String sourceTopic, int sourcePartition);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DossierPartitionCheckpoint> findForUpdateByConsumerGroupAndSourceTopicAndSourcePartition(
      String consumerGroup, String sourceTopic, int sourcePartition);
}
