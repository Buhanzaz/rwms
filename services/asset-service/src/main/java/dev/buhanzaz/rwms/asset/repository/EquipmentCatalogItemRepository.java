package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local equipment catalog item persistence.
 */
public interface EquipmentCatalogItemRepository extends JpaRepository<EquipmentCatalogItem, UUID> {
  boolean existsByNormalizedName(String normalizedName);

  boolean existsByNormalizedNameAndIdNot(String normalizedName, UUID id);

  List<EquipmentCatalogItem> findAllByOrderByNameAscIdAsc();

  List<EquipmentCatalogItem> findAllByCategoryAndActiveTrueOrderByNameAscIdAsc(
      EquipmentCategory category);

  /** Locks one item before applying a version-fenced maintenance editor mutation. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select item from EquipmentCatalogItem item where item.id = :id")
  Optional<EquipmentCatalogItem> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select item
      from EquipmentCatalogItem item
      where item.category = :category and item.active = true
      order by item.name, item.id
      """)
  List<EquipmentCatalogItem> findAllActiveByCategoryForUpdate(
      @Param("category") EquipmentCategory category);

  /** Locks only the requested active catalog rows in the existing canonical catalog order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select item
      from EquipmentCatalogItem item
      where item.id in :ids and item.category = :category and item.active = true
      order by item.name, item.id
      """)
  List<EquipmentCatalogItem> findAllActiveByIdInAndCategoryForUpdate(
      @Param("ids") Collection<UUID> ids,
      @Param("category") EquipmentCategory category);
}
