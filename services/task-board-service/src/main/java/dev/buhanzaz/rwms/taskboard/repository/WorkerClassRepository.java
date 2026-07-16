package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerClassRepository extends JpaRepository<WorkerClass, UUID> {
  List<WorkerClass> findAllByOrderBySortOrderAscNameAsc();

  boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

  Optional<WorkerClass> findByCodeIgnoreCase(String code);
}
