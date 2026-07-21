package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.Worker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {
  List<Worker> findAllByWarehouseIdOrderByDisplayNameAsc(UUID warehouseId);

  Optional<Worker> findByAppLoginIgnoreCase(String appLogin);

  boolean existsByAppLoginIgnoreCaseAndIdNot(String appLogin, UUID id);
}
