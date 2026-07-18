package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DossierActiveGenerationRepository
    extends JpaRepository<DossierActiveGeneration, UUID> {
  Optional<DossierActiveGeneration> findByPointerName(String pointerName);

  @Lock(LockModeType.PESSIMISTIC_READ)
  Optional<DossierActiveGeneration> findSharedByPointerName(String pointerName);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DossierActiveGeneration> findForUpdateByPointerName(String pointerName);
}
