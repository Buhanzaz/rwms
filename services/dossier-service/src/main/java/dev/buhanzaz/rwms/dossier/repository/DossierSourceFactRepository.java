package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierSourceFact;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DossierSourceFactRepository extends JpaRepository<DossierSourceFact, UUID> {
  Optional<DossierSourceFact> findByEventId(UUID eventId);

  Optional<DossierSourceFact> findBySourceTopicAndSourcePartitionAndSourceOffset(
      String sourceTopic, int sourcePartition, long sourceOffset);

  List<DossierSourceFact> findAllByIngestedAtLessThanEqualOrderByIngestedAtAscEventIdAsc(
      OffsetDateTime highWaterAt);

  List<DossierSourceFact> findAllByProducerAndAggregateTypeOrderByRecordedAtAscEventIdAsc(
      DossierProducer producer, String aggregateType);

  List<DossierSourceFact> findAllByProducerAndSubjectSecondaryIdOrderByRecordedAtAscEventIdAsc(
      DossierProducer producer, UUID subjectSecondaryId);

  List<DossierSourceFact>
      findAllByProducerAndAggregateTypeAndSubjectSecondaryIdOrderByAggregateVersionAscEventIdAsc(
          DossierProducer producer, String aggregateType, UUID subjectSecondaryId);

  List<DossierSourceFact>
      findAllByProducerAndAggregateTypeAndAggregateIdOrderByAggregateVersionAscEventIdAsc(
          DossierProducer producer, String aggregateType, UUID aggregateId);

  List<DossierSourceFact>
      findAllBySourceTopicAndSourcePartitionAndSourceOffsetLessThanEqualOrderBySourceOffsetAscEventIdAsc(
          String sourceTopic, int sourcePartition, long maxOffset);

  List<DossierSourceFact>
      findAllByProducerAndSourceTopicAndAggregateTypeAndAggregateIdAndAggregateVersionOrderByEventIdAsc(
          DossierProducer producer,
          String sourceTopic,
          String aggregateType,
          UUID aggregateId,
          long aggregateVersion);
}
