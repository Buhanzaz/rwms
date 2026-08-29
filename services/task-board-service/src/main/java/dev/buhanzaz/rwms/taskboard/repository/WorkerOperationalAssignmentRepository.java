package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignment;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Queries and locks transfer-backed worker operational assignment history. */
public interface WorkerOperationalAssignmentRepository
    extends JpaRepository<WorkerOperationalAssignment, UUID> {
  Optional<WorkerOperationalAssignment> findByTransferIdAndWorkerId(
      UUID transferId, UUID workerId);

  List<WorkerOperationalAssignment> findAllByTransferIdOrderByCreatedAtAscIdAsc(UUID transferId);

  List<WorkerOperationalAssignment> findAllByWorkerIdOrderByEffectiveFromAscCreatedAtAscIdAsc(
      UUID workerId);

  List<WorkerOperationalAssignment> findAllByWorkerIdIn(Collection<UUID> workerIds);

  /** Locks one assignment for an expected-version lifecycle transition. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select assignment from WorkerOperationalAssignment assignment where assignment.id = :id")
  Optional<WorkerOperationalAssignment> findByIdForUpdate(@Param("id") UUID id);
}
