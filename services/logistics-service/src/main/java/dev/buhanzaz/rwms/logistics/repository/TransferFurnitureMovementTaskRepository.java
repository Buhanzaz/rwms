package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.TransferFurnitureMovementTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Transfer Furniture Movement Task Repository; it does not own cross-service workflow decisions.
 */
public interface TransferFurnitureMovementTaskRepository
    extends JpaRepository<TransferFurnitureMovementTask, UUID> {
  List<TransferFurnitureMovementTask> findAllByDocument_IdOrderByUnitNumberAsc(UUID documentId);

  Optional<TransferFurnitureMovementTask> findByDocument_IdAndRentalItemId(
      UUID documentId, UUID rentalItemId);
}
