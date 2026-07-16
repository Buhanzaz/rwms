package dev.buhanzaz.rwms.warehouse.repository;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
  List<Warehouse> findAllByActiveTrue();

  boolean existsByCode(String code);

  boolean existsByCodeAndIdNot(String code, UUID id);
}
