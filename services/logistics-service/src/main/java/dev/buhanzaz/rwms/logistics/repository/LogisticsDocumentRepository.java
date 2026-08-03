package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogisticsDocumentRepository extends JpaRepository<LogisticsDocument, UUID> {
  java.util.Optional<LogisticsDocument> findByIdAndDocumentType(
      UUID id, LogisticsDocumentType documentType);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select document from LogisticsDocument document where document.id = :id")
  java.util.Optional<LogisticsDocument> findForUpdate(@Param("id") UUID id);

  List<LogisticsDocument> findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
      LogisticsDocumentType documentType, UUID rentalOrderId);

  List<LogisticsDocument>
      findAllByDocumentTypeAndRentalOrderIdAndRentalShipmentIdIsNotNullOrderByCreatedAtAscIdAsc(
          LogisticsDocumentType documentType, UUID rentalOrderId);

  List<LogisticsDocument> findAllByDocumentTypeAndRentalShipmentIdOrderByCreatedAtAscIdAsc(
      LogisticsDocumentType documentType, UUID rentalShipmentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select document
      from LogisticsDocument document
      where document.rentalOrderId = :rentalOrderId
      order by document.createdAt, document.id
      """)
  List<LogisticsDocument> findAllByRentalOrderIdForUpdate(@Param("rentalOrderId") UUID rentalOrderId);

  List<LogisticsDocument> findAllByDocumentTypeAndWarehouseIdOrderByCreatedAtDescIdDesc(
      LogisticsDocumentType documentType, UUID warehouseId);
}
