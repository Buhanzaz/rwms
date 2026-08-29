package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists and locks the single active simulator-capacity projection for each warehouse. */
public interface WarehouseCapacitySnapshotRepository
    extends JpaRepository<WarehouseCapacitySnapshot, UUID> {
  /** Reads scalar tariff and revision facts for customer slot calculation. */
  Optional<WarehouseCapacitySnapshot> findByWarehouseId(UUID warehouseId);

  /** Locks the current warehouse projection before a complete idempotent replacement. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select snapshot from WarehouseCapacitySnapshot snapshot where snapshot.warehouseId = :warehouseId")
  Optional<WarehouseCapacitySnapshot> findByWarehouseIdForUpdate(
      @Param("warehouseId") UUID warehouseId);
}
