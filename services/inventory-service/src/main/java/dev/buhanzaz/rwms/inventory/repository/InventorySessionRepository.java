package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory session persistence.
 */
public interface InventorySessionRepository
    extends JpaRepository<InventorySession, UUID>, JpaSpecificationExecutor<InventorySession> {
  Optional<InventorySession> findByWarehouseIdAndLifecycle(
      UUID warehouseId, SessionLifecycle lifecycle);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select session from InventorySession session
       where session.warehouseId = :warehouseId and session.lifecycle = :lifecycle
      """)
  Optional<InventorySession> findByWarehouseIdAndLifecycleForUpdate(
      @Param("warehouseId") UUID warehouseId,
      @Param("lifecycle") SessionLifecycle lifecycle);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select session from InventorySession session
       where session.id = :inventoryId and session.lifecycle = :lifecycle
      """)
  Optional<InventorySession> findByIdAndLifecycleForUpdate(
      @Param("inventoryId") UUID inventoryId,
      @Param("lifecycle") SessionLifecycle lifecycle);

  Optional<InventorySession> findByStartOperationId(UUID operationId);

  Optional<InventorySession> findByIdAndWarehouseIdIn(UUID id, Set<UUID> warehouseIds);

  Page<InventorySession> findByWarehouseId(UUID warehouseId, Pageable pageable);

  Page<InventorySession> findByWarehouseIdAndLifecycle(
      UUID warehouseId, SessionLifecycle lifecycle, Pageable pageable);
}
