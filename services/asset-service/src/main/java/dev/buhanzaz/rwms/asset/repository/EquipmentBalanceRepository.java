package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentBalance;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentBalanceRepository extends JpaRepository<EquipmentBalance, UUID> {
  List<EquipmentBalance> findAllByRentalItemIdInAndQuantityGreaterThanAndLocationKindIn(
      Collection<UUID> rentalItemIds,
      long minimumQuantity,
      Collection<BalanceLocationKind> locationKinds);
}
