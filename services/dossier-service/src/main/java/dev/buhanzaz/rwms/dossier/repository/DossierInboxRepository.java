package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierInbox;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DossierInboxRepository extends JpaRepository<DossierInbox, UUID> {}
