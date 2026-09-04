package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Document Repository; it does not
 * own cross-service workflow decisions.
 */
public interface LogisticsDocumentRepository extends JpaRepository<LogisticsDocument, UUID> {
  java.util.Optional<LogisticsDocument> findByIdAndDocumentType(
      UUID id, LogisticsDocumentType documentType);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select document from LogisticsDocument document where document.id = :id")
  java.util.Optional<LogisticsDocument> findForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select document from LogisticsDocument document where document.id in :ids order by"
          + " document.id")
  List<LogisticsDocument> findAllForUpdateByIdIn(@Param("ids") Collection<UUID> ids);

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
  List<LogisticsDocument> findAllByRentalOrderIdForUpdate(
      @Param("rentalOrderId") UUID rentalOrderId);

  /** Reads one deterministic, bounded warehouse document page for the public list endpoints. */
  Page<LogisticsDocument> findAllByDocumentTypeAndWarehouseIdOrderByCreatedAtDescIdDesc(
      LogisticsDocumentType documentType, UUID warehouseId, Pageable pageable);

  /** Reads one exact warehouse-local business day for returns and shipments. */
  Page<LogisticsDocument>
      findAllByDocumentTypeAndWarehouseIdAndScheduledDateOrderByCreatedAtDescIdDesc(
          LogisticsDocumentType documentType,
          UUID warehouseId,
          LocalDate scheduledDate,
          Pageable pageable);

  /** Reads transfers touching the selected warehouse in either direction on one exact day. */
  @Query(
      """
      select document
      from LogisticsDocument document
      where document.documentType = :documentType
        and document.scheduledDate = :scheduledDate
        and (
          document.warehouseId = :warehouseId
          or document.destinationWarehouseId = :warehouseId
        )
      order by document.createdAt desc, document.id desc
      """)
  Page<LogisticsDocument> findTransferPageForWarehouseAndScheduledDate(
      @Param("documentType") LogisticsDocumentType documentType,
      @Param("warehouseId") UUID warehouseId,
      @Param("scheduledDate") LocalDate scheduledDate,
      Pageable pageable);

  /** Reads only planner-created documents for one warehouse-local planning date. */
  List<LogisticsDocument>
      findAllByDocumentTypeAndWarehouseIdAndScheduledDateAndRequestedBySubjectIdOrderByCreatedAtAscIdAsc(
          LogisticsDocumentType documentType,
          UUID warehouseId,
          LocalDate scheduledDate,
          UUID requestedBySubjectId);

  /** Finds already-created historical facts for an exact plan reassertion without N+1 reads. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select document
      from LogisticsDocument document
      where document.inventorySourceId = :inventoryId
        and document.inventorySourceFinalPlanVersion = :finalPlanVersion
      order by document.inventorySourceFindingId, document.id
      """)
  List<LogisticsDocument> findAllInventorySourceDocumentsForUpdate(
      @Param("inventoryId") UUID inventoryId,
      @Param("finalPlanVersion") long finalPlanVersion);
}
