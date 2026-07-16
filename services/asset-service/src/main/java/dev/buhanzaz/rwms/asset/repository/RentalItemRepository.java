package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.RentalItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalItemRepository extends JpaRepository<RentalItem, UUID> {
  boolean existsByNumber(String number);
  boolean existsByNumberAndIdNot(String number, UUID id);
  List<RentalItem> findAllByWarehouseIdOrderByNumber(UUID warehouseId);
  Optional<RentalItem> findByIdAndWarehouseId(UUID id, UUID warehouseId);
}
