package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierReplayPartitionHighWater;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DossierReplayPartitionHighWaterRepository
    extends JpaRepository<DossierReplayPartitionHighWater, UUID> {
  List<DossierReplayPartitionHighWater> findAllByRunIdOrderBySourceTopicAscSourcePartitionAsc(
      UUID runId);
}
