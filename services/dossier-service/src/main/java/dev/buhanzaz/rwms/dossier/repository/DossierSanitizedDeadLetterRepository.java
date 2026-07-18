package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DossierSanitizedDeadLetterRepository
    extends JpaRepository<DossierSanitizedDeadLetter, UUID> {
  long countBySourceEventIdIsNotNull();

  List<DossierSanitizedDeadLetter> findAllByStatusInAndNextAttemptAtLessThanEqualOrderByFailedAtAscIdAsc(
      Collection<DossierOutboxState> states, OffsetDateTime now, Pageable pageable);
}
