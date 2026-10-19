package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Repository for locked aggregate checkpoints that enforce source version ordering and quarantine. */
public interface DossierAggregateCheckpointRepository
    extends JpaRepository<DossierAggregateCheckpoint, UUID> {
  Optional<DossierAggregateCheckpoint>
      findByConsumerGroupAndProducerAndSourceTopicAndAggregateTypeAndAggregateId(
          String consumerGroup,
          DossierProducer producer,
          String sourceTopic,
          String aggregateType,
          UUID aggregateId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DossierAggregateCheckpoint>
      findForUpdateByConsumerGroupAndProducerAndSourceTopicAndAggregateTypeAndAggregateId(
          String consumerGroup,
          DossierProducer producer,
          String sourceTopic,
          String aggregateType,
          UUID aggregateId);

  /**
   * Counts every retained checkpoint whose source aggregate is quarantined.
   *
   * <p>This is an operational observation only. It does not reconcile a gap, recover a failed
   * projection, or ask the producer to replay data.
   *
   * @return number of retained blocked checkpoints
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(checkpoint)
      from DossierAggregateCheckpoint checkpoint
      where checkpoint.blocked = true
      """)
  long countBlockedCheckpoints();

  /**
   * Finds the block time of the oldest retained quarantined checkpoint.
   *
   * <p>The schema requires a block timestamp for every blocked checkpoint, so an empty result
   * means that no checkpoint is currently blocked.
   *
   * @return oldest checkpoint block time, or empty when no checkpoint is blocked
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select min(checkpoint.blockedAt)
      from DossierAggregateCheckpoint checkpoint
      where checkpoint.blocked = true
      """)
  Optional<OffsetDateTime> findOldestBlockedAt();
}
