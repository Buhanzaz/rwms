package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentCatalogItemRepository extends JpaRepository<EquipmentCatalogItem, UUID> {
  List<EquipmentCatalogItem> findAllByOrderByNameAscIdAsc();
}
