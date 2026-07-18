package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierSubjectAssociation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DossierSubjectAssociationRepository
    extends JpaRepository<DossierSubjectAssociation, UUID> {
  Optional<DossierSubjectAssociation> findByProducerAndSourceTypeAndSourceIdAndGenerationId(
      DossierProducer producer, String sourceType, UUID sourceId, UUID generationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DossierSubjectAssociation>
      findForUpdateByProducerAndSourceTypeAndSourceIdAndGenerationId(
          DossierProducer producer, String sourceType, UUID sourceId, UUID generationId);

  List<DossierSubjectAssociation> findAllByCabinIdAndGenerationId(UUID cabinId, UUID generationId);
}
