package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerDeletionIntent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerDeletionIntentRepository extends JpaRepository<WorkerDeletionIntent, UUID> {
  Optional<WorkerDeletionIntent> findByWorkerId(UUID workerId);
}
