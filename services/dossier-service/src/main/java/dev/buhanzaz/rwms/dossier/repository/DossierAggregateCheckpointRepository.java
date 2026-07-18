package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

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
}
