package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierCabinPublicationHead;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DossierCabinPublicationHeadRepository
    extends JpaRepository<DossierCabinPublicationHead, UUID> {
  Optional<DossierCabinPublicationHead> findByCabinId(UUID cabinId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DossierCabinPublicationHead> findForUpdateByCabinId(UUID cabinId);
}
