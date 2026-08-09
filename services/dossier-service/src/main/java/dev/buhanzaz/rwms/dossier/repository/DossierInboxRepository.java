package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierInbox;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the source-event inbox keyed by canonical event identity. */
public interface DossierInboxRepository extends JpaRepository<DossierInbox, UUID> {}
