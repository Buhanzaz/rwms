package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogisticsDocumentRepository extends JpaRepository<LogisticsDocument, UUID> {
  Optional<LogisticsDocument> findByIdAndDocumentType(UUID id, LogisticsDocumentType documentType);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select document from LogisticsDocument document where document.id = :id")
  Optional<LogisticsDocument> findForUpdate(@Param("id") UUID id);

  Optional<LogisticsDocument> findByDocumentTypeAndRentalOrderId(
      LogisticsDocumentType documentType, UUID rentalOrderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select document
      from LogisticsDocument document
      where document.documentType = :documentType
        and document.rentalOrderId = :rentalOrderId
      """)
  Optional<LogisticsDocument> findByDocumentTypeAndRentalOrderIdForUpdate(
      @Param("documentType") LogisticsDocumentType documentType,
      @Param("rentalOrderId") UUID rentalOrderId);

  List<LogisticsDocument> findAllByDocumentTypeAndWarehouseIdOrderByCreatedAtDescIdDesc(
      LogisticsDocumentType documentType, UUID warehouseId);
}
