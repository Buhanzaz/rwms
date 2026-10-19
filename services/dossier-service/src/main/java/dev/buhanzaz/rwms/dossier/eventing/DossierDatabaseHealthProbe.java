package dev.buhanzaz.rwms.dossier.eventing;

import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** JPA-only health probe for the dossier-owned PostgreSQL boundary. */
@Service
public class DossierDatabaseHealthProbe {
  private final DossierActiveGenerationRepository activeGenerations;

  public DossierDatabaseHealthProbe(DossierActiveGenerationRepository activeGenerations) {
    this.activeGenerations = activeGenerations;
  }

  @Transactional(readOnly = true)
  public boolean healthy() {
    return activeGenerations
        .findByPointerName(DossierActiveGeneration.POINTER_NAME)
        .isPresent();
  }
}
