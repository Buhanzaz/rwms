package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedFact;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** Repository for unresolved source facts and their generation/cabin-scoped coverage queries. */
public interface DossierUnlinkedFactRepository extends JpaRepository<DossierUnlinkedFact, UUID> {
  Optional<DossierUnlinkedFact> findBySourceEventIdAndGenerationId(
      UUID sourceEventId, UUID generationId);

  long countByGenerationId(UUID generationId);

  long countByGenerationIdAndResolvedAtIsNull(UUID generationId);

  /**
   * Counts unresolved unlinked evidence retained across all projection generations.
   *
   * <p>This operational backlog is deliberately broader than a cabin visibility decision: only
   * the active-generation, cabin-scoped query can make one dossier response {@code PARTIAL}.
   *
   * @return number of retained facts not yet explicitly resolved
   */
  @Transactional(readOnly = true)
  long countByResolvedAtIsNull();

  /** Counts unresolved evidence explicitly linked to one cabin in one projection generation. */
  long countByGenerationIdAndSubjectCabinIdAndResolvedAtIsNull(
      UUID generationId, UUID subjectCabinId);
}
