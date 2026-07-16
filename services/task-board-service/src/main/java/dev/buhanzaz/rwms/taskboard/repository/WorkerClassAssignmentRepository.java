package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerClassAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerClassAssignmentRepository
    extends JpaRepository<WorkerClassAssignment, UUID> {
  List<WorkerClassAssignment> findAllByWorkerId(UUID workerId);

  boolean existsByWorkerId(UUID workerId);

  boolean existsByWorkerClassId(UUID classId);
}
