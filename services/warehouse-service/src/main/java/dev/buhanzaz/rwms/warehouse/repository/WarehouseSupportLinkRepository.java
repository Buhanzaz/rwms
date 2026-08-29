package dev.buhanzaz.rwms.warehouse.repository;

import dev.buhanzaz.rwms.warehouse.domain.WarehouseSupportLink;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for directed warehouse support links and their calendar value sets. */
public interface WarehouseSupportLinkRepository
    extends JpaRepository<WarehouseSupportLink, UUID> {

  @EntityGraph(attributePaths = {"allowedWeekdays", "allowedDates", "excludedDates"})
  @Query(
      """
      select distinct link
        from WarehouseSupportLink link
       where link.servedWarehouseId = :servedWarehouseId
       order by link.priority, link.supportWarehouseId, link.id
      """)
  List<WarehouseSupportLink> findAllByServedWarehouseId(
      @Param("servedWarehouseId") UUID servedWarehouseId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = {"allowedWeekdays", "allowedDates", "excludedDates"})
  @Query(
      """
      select distinct link
        from WarehouseSupportLink link
       where link.servedWarehouseId = :servedWarehouseId
       order by link.priority, link.supportWarehouseId, link.id
      """)
  List<WarehouseSupportLink> findAllByServedWarehouseIdForUpdate(
      @Param("servedWarehouseId") UUID servedWarehouseId);

  boolean existsByServedWarehouseId(UUID servedWarehouseId);
}
