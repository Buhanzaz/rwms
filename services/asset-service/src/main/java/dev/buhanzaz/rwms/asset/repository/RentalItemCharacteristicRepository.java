package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.RentalItemCharacteristic;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalItemCharacteristicRepository
    extends JpaRepository<RentalItemCharacteristic, UUID> {
  boolean existsByCharacteristicId(UUID characteristicId);

  boolean existsByRentalItemIdAndCharacteristicId(
      UUID rentalItemId, UUID characteristicId);

  List<RentalItemCharacteristic> findAllByRentalItemIdOrderBySortOrderAscIdAsc(UUID rentalItemId);

  List<RentalItemCharacteristic> findAllByRentalItemIdInOrderByRentalItemIdAscSortOrderAscIdAsc(
      Collection<UUID> rentalItemIds);

  void deleteAllByRentalItemId(UUID rentalItemId);
}
