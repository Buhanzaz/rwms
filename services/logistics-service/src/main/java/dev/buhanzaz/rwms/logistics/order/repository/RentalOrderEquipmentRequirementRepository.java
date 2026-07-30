package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalOrderEquipmentRequirementRepository
    extends JpaRepository<RentalOrderEquipmentRequirement, UUID> {
  List<RentalOrderEquipmentRequirement> findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
      UUID orderId);

  List<RentalOrderEquipmentRequirement>
      findAllByOrder_IdAndRentalItemIdOrderByEquipmentNameAscEquipmentIdAsc(
          UUID orderId, UUID rentalItemId);
}
