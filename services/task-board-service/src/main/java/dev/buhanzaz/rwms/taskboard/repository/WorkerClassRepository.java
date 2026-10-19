package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for the global worker qualification registry. */
public interface WorkerClassRepository extends JpaRepository<WorkerClass, UUID> {
  List<WorkerClass> findAllByOrderBySortOrderAscNameAscIdAsc();
}
