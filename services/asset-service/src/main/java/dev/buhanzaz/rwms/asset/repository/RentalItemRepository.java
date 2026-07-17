package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalItemRepository extends JpaRepository<RentalItem, UUID> {
  boolean existsByIdentityMatchKey(String identityMatchKey);
  Optional<RentalItem> findByIdentityMatchKey(String identityMatchKey);
  List<RentalItem> findAllByWarehouseIdOrderByNumber(UUID warehouseId);
  Optional<RentalItem> findByIdAndWarehouseId(UUID id, UUID warehouseId);
  List<RentalItem> findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
      UUID warehouseId, Collection<RentalItemStatus> statuses);
}
