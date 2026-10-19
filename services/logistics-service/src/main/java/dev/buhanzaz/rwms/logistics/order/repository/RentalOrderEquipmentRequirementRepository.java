package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Rental Order Equipment Requirement
 * Repository; it does not own cross-service workflow decisions.
 */
public interface RentalOrderEquipmentRequirementRepository
    extends JpaRepository<RentalOrderEquipmentRequirement, UUID> {
  List<RentalOrderEquipmentRequirement>
      findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(UUID orderId);

  List<RentalOrderEquipmentRequirement>
      findAllByOrder_IdInOrderByOrder_IdAscRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
          java.util.Collection<UUID> orderIds);

  List<RentalOrderEquipmentRequirement>
      findAllByOrder_IdAndRentalItemIdOrderByEquipmentNameAscEquipmentIdAsc(
          UUID orderId, UUID rentalItemId);
}
