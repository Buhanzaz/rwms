package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Document Line Repository; it does not own cross-service workflow decisions.
 */
public interface LogisticsDocumentLineRepository extends JpaRepository<LogisticsDocumentLine, UUID> {
  List<LogisticsDocumentLine> findAllByDocument_IdOrderByLineNumber(UUID documentId);

  @Query(
      """
      select line.assetId
      from LogisticsDocumentLine line
      join line.document document
      where document.documentType = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType.SHIPMENT
        and document.rentalOrderId = :orderId
        and document.state <> dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState.CANCELLED
        and line.assetId in :rentalItemIds
      """)
  List<UUID> findAssignedRentalShipmentAssetIds(
      @Param("orderId") UUID orderId,
      @Param("rentalItemIds") List<UUID> rentalItemIds);

  @Query(
      """
      select distinct line.assetId
      from LogisticsDocumentLine line
      join line.document document
      where document.documentType = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType.SHIPMENT
        and document.rentalOrderId = :orderId
        and document.state = dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState.SHIPPED
      """)
  List<UUID> findShippedRentalOrderAssetIds(@Param("orderId") UUID orderId);
}
