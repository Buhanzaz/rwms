package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Document Line Repository; it does
 * not own cross-service workflow decisions.
 */
public interface LogisticsDocumentLineRepository
    extends JpaRepository<LogisticsDocumentLine, UUID> {
  List<LogisticsDocumentLine> findAllByDocument_IdOrderByLineNumber(UUID documentId);

  /** Reads ordered cabin membership for a bounded planner-created document set. */
  @Query(
      """
      select line
      from LogisticsDocumentLine line
      where line.document.id in :documentIds
      order by line.document.id, line.lineNumber, line.id
      """)
  List<LogisticsDocumentLine> findAllByDocumentIdIn(
      @Param("documentIds") Collection<UUID> documentIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select line
      from LogisticsDocumentLine line
      join fetch line.document document
      where line.assetId in :assetIds
      order by document.id, line.lineNumber, line.id
      """)
  List<LogisticsDocumentLine> findAllForUpdateByAssetIdIn(
      @Param("assetIds") Collection<UUID> assetIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select line
      from LogisticsDocumentLine line
      where line.document.id in :documentIds
      order by line.document.id, line.lineNumber, line.id
      """)
  List<LogisticsDocumentLine> findAllForUpdateByDocumentIdIn(
      @Param("documentIds") Collection<UUID> documentIds);

  /**
   * Detects a cabin already selected by a live logistics document.
   *
   * <p>Callers acquire the transaction-scoped cabin key before this read. Completed and cancelled
   * documents release the logistics-local selection; physical availability is still validated by
   * the asset owner.
   */
  @Query(
      """
      select case when count(line) > 0 then true else false end
      from LogisticsDocumentLine line
      join line.document document
      where line.assetId = :assetId
        and document.state not in (
          dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState.COMPLETED,
          dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState.CANCELLED)
      """)
  boolean existsActiveDocumentSelection(@Param("assetId") UUID assetId);

  @Query(
      """
      select line.assetId
      from LogisticsDocumentLine line
      join line.document document
      where document.documentType = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType.SHIPMENT
        and document.rentalOrderId = :orderId
        and document.state <> dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState.CANCELLED
        and line.inventorySupersededBy is null
        and line.assetId in :rentalItemIds
      """)
  List<UUID> findAssignedRentalShipmentAssetIds(
      @Param("orderId") UUID orderId, @Param("rentalItemIds") List<UUID> rentalItemIds);

  @Query(
      """
      select distinct line.assetId
      from LogisticsDocumentLine line
      join line.document document
      where document.documentType = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType.SHIPMENT
        and document.rentalOrderId = :orderId
        and document.state = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState.SHIPPED
        and line.inventorySupersededBy is null
      """)
  List<UUID> findShippedRentalOrderAssetIds(@Param("orderId") UUID orderId);
}
