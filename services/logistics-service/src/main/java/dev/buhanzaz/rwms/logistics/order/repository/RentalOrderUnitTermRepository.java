package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalOrderUnitTermRepository
    extends JpaRepository<RentalOrderUnitTerm, UUID> {
  List<RentalOrderUnitTerm> findAllByOrder_IdOrderByRentalItemIdAsc(UUID orderId);

  List<RentalOrderUnitTerm> findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(
      UUID orderId, Collection<UUID> rentalItemIds);

  List<RentalOrderUnitTerm> findAllByRentalShipmentIdOrderByRentalItemIdAsc(UUID rentalShipmentId);

  void deleteAllByOrder_IdAndRentalItemId(UUID orderId, UUID rentalItemId);
}
