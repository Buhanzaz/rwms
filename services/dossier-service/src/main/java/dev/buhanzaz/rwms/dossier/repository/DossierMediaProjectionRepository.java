package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierMediaProjection;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

/** Repository for generation-scoped media rows that are filtered with the same warehouse scope as dossier activities. */
public interface DossierMediaProjectionRepository
    extends JpaRepository<DossierMediaProjection, UUID>,
        JpaSpecificationExecutor<DossierMediaProjection> {
  Optional<DossierMediaProjection> findByCabinIdAndMediaIdAndGenerationId(
      UUID cabinId, UUID mediaId, UUID generationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DossierMediaProjection> findForUpdateByCabinIdAndMediaIdAndGenerationId(
      UUID cabinId, UUID mediaId, UUID generationId);

  List<DossierMediaProjection> findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(
      UUID cabinId, UUID generationId);

  long countByGenerationId(UUID generationId);
}
