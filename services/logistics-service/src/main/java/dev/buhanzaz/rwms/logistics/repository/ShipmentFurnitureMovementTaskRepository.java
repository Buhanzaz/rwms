package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Shipment Furniture Movement Task Repository; it does not own cross-service workflow decisions.
 */
public interface ShipmentFurnitureMovementTaskRepository
    extends JpaRepository<ShipmentFurnitureMovementTask, UUID> {
  List<ShipmentFurnitureMovementTask> findAllByDocument_IdOrderByUnitNumberAsc(
      UUID documentId);

  Optional<ShipmentFurnitureMovementTask> findByDocument_IdAndRentalItemId(
      UUID documentId, UUID rentalItemId);

  boolean existsByDocument_Id(UUID documentId);
}
