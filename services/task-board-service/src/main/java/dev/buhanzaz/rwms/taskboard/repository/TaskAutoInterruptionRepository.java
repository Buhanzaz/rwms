package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.TaskAutoInterruption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores unresolved and completed automatic task-interruption records. */
public interface TaskAutoInterruptionRepository extends JpaRepository<TaskAutoInterruption, UUID> {
  List<TaskAutoInterruption> findAllByInterruptingEntryIdAndActiveTrue(UUID entryId);

  List<TaskAutoInterruption> findAllByInterruptedEntryIdAndActiveTrue(UUID entryId);

  List<TaskAutoInterruption> findAllByInterruptedEntryIdOrInterruptingEntryId(
      UUID interruptedEntryId, UUID interruptingEntryId);

  boolean existsByInterruptedEntryIdAndActiveTrue(UUID entryId);
}
