package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventorySessionRepository
    extends JpaRepository<InventorySession, UUID>, JpaSpecificationExecutor<InventorySession> {
  Optional<InventorySession> findByWarehouseIdAndLifecycle(
      UUID warehouseId, SessionLifecycle lifecycle);

  Optional<InventorySession> findByStartOperationId(UUID operationId);

  Optional<InventorySession> findByIdAndWarehouseIdIn(UUID id, Set<UUID> warehouseIds);

  Page<InventorySession> findByWarehouseId(UUID warehouseId, Pageable pageable);

  Page<InventorySession> findByWarehouseIdAndLifecycle(
      UUID warehouseId, SessionLifecycle lifecycle, Pageable pageable);
}
