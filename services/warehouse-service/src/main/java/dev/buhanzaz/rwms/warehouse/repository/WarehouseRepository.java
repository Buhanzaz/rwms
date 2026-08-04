package dev.buhanzaz.rwms.warehouse.repository;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
  List<Warehouse> findAllByActiveTrue();

  boolean existsByNormalizedName(String normalizedName);

  boolean existsByNormalizedNameAndIdNot(String normalizedName, UUID id);
}
