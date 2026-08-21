package dev.buhanzaz.rwms.logistics.maintenance.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only JPA projection boundary for logistics-owned physical return arrivals. */
public interface MaintenanceReturnArrivalRepository
    extends Repository<LogisticsDocumentLine, UUID> {

  /** Returns at most one newest normal return line with immutable physical-arrival evidence. */
  @Query(
      """
      select line
      from LogisticsDocumentLine line
      join fetch line.document document
      where document.documentType = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType.RETURN
        and document.warehouseId = :warehouseId
        and line.assetId = :rentalItemId
        and document.returnArrivedAt is not null
      order by document.returnArrivedAt desc, document.id desc, line.id desc
      """)
  List<LogisticsDocumentLine> findLatestRows(
      @Param("warehouseId") UUID warehouseId,
      @Param("rentalItemId") UUID rentalItemId,
      Pageable page);

  /** Returns one exact normal return line only when its immutable arrival was recorded. */
  @Query(
      """
      select line
      from LogisticsDocumentLine line
      join fetch line.document document
      where document.id = :returnDocumentId
        and document.documentType = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType.RETURN
        and document.warehouseId = :warehouseId
        and line.assetId = :rentalItemId
        and document.returnArrivedAt is not null
      """)
  Optional<LogisticsDocumentLine> findForReturn(
      @Param("returnDocumentId") UUID returnDocumentId,
      @Param("warehouseId") UUID warehouseId,
      @Param("rentalItemId") UUID rentalItemId);
}
