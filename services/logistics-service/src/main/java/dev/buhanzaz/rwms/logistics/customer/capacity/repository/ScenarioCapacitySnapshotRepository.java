package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacitySnapshot;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists and locks the single active simulator-capacity projection for each warehouse. */
public interface ScenarioCapacitySnapshotRepository
    extends JpaRepository<ScenarioCapacitySnapshot, UUID> {
  /** Locks the current warehouse projection before a complete idempotent replacement. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select snapshot from ScenarioCapacitySnapshot snapshot where snapshot.warehouseId = :warehouseId")
  Optional<ScenarioCapacitySnapshot> findByWarehouseIdForUpdate(
      @Param("warehouseId") UUID warehouseId);
}
