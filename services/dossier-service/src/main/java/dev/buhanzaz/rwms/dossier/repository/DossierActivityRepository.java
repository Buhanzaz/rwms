package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierActivity;
import dev.buhanzaz.rwms.dossier.domain.DossierActivityCode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DossierActivityRepository
    extends JpaRepository<DossierActivity, UUID>, JpaSpecificationExecutor<DossierActivity> {
  Optional<DossierActivity> findByActivityIdAndGenerationId(UUID activityId, UUID generationId);

  boolean existsBySourceEventIdAndCabinIdAndGenerationId(
      UUID sourceEventId, UUID cabinId, UUID generationId);

  List<DossierActivity> findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
      UUID cabinId, UUID generationId, Pageable pageable);

  List<DossierActivity>
      findAllByCabinIdAndGenerationIdAndActivityCodeInOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
          UUID cabinId,
          UUID generationId,
          Collection<DossierActivityCode> activityCodes,
          Pageable pageable);

  long countByGenerationId(UUID generationId);
}
