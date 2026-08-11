package dev.buhanzaz.rwms.logistics.driver.settings.repository;

import dev.buhanzaz.rwms.logistics.driver.settings.domain.ShipmentTaskSettings;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for one warehouse's shipment-task grouping policy.
 */
public interface ShipmentTaskSettingsRepository
    extends JpaRepository<ShipmentTaskSettings, UUID> {
  /** Locks an existing warehouse policy before its version-fenced update. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select settings from ShipmentTaskSettings settings where settings.warehouseId = :warehouseId")
  Optional<ShipmentTaskSettings> findForUpdate(@Param("warehouseId") UUID warehouseId);

  /**
   * Materializes the default only if another request has not already created the warehouse row.
   * PostgreSQL's conflict guard avoids a transient duplicate-key failure when the first read and a
   * shipment creation race for an unconfigured warehouse.
   */
  @Modifying
  @Query(
      """
          insert into ShipmentTaskSettings(
            warehouseId, version, maxCabinsPerShipmentTask, updatedBySubjectId, updatedAt)
          values (:warehouseId, 0, 1, :updatedBySubjectId, :updatedAt)
          on conflict (warehouseId) do nothing
          """)
  int insertDefaultIfAbsent(
      @Param("warehouseId") UUID warehouseId,
      @Param("updatedBySubjectId") UUID updatedBySubjectId,
      @Param("updatedAt") OffsetDateTime updatedAt);
}
