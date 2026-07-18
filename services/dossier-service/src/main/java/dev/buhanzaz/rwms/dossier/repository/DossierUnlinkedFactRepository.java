package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedFact;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DossierUnlinkedFactRepository extends JpaRepository<DossierUnlinkedFact, UUID> {
  Optional<DossierUnlinkedFact> findBySourceEventIdAndGenerationId(
      UUID sourceEventId, UUID generationId);

  long countByGenerationId(UUID generationId);

  long countByGenerationIdAndResolvedAtIsNull(UUID generationId);

  long countByGenerationIdAndSubjectCabinIdAndResolvedAtIsNull(
      UUID generationId, UUID subjectCabinId);
}
