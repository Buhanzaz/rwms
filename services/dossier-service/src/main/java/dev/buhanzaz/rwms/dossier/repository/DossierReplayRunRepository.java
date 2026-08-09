package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierReplayRun;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayState;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for auditable replay-run claims and lifecycle transitions. */
public interface DossierReplayRunRepository extends JpaRepository<DossierReplayRun, UUID> {
  List<DossierReplayRun> findAllByStateInOrderByStartedAtAsc(
      Collection<DossierReplayState> states);
}
