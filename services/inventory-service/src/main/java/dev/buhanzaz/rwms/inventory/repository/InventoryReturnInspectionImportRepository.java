package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryReturnInspectionImport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data boundary for immutable normal-return inspection imports. */
public interface InventoryReturnInspectionImportRepository
    extends JpaRepository<InventoryReturnInspectionImport, InventoryReturnInspectionImport.Key> {
  Optional<InventoryReturnInspectionImport> findByEstimateIdAndInventoryId(
      UUID estimateId, UUID inventoryId);

  Optional<InventoryReturnInspectionImport> findByEstimateIdAndWarehouseId(
      UUID estimateId, UUID warehouseId);
}
