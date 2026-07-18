package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierGenerationState;
import dev.buhanzaz.rwms.dossier.domain.DossierProjectionGeneration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DossierProjectionGenerationRepository
    extends JpaRepository<DossierProjectionGeneration, UUID> {
  Optional<DossierProjectionGeneration> findByState(DossierGenerationState state);

  List<DossierProjectionGeneration> findAllByOrderByCreatedAtAsc();
}
