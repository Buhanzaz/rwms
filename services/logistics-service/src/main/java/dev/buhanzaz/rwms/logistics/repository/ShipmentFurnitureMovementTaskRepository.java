package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Shipment Furniture Movement Task Repository;
 * it does not own cross-service workflow decisions.
 */
public interface ShipmentFurnitureMovementTaskRepository
    extends JpaRepository<ShipmentFurnitureMovementTask, UUID> {
  List<ShipmentFurnitureMovementTask> findAllByDocument_IdOrderByUnitNumberAsc(UUID documentId);

  List<ShipmentFurnitureMovementTask> findAllByDocument_IdInOrderByDocument_IdAscUnitNumberAsc(
      java.util.Collection<UUID> documentIds);

  Optional<ShipmentFurnitureMovementTask> findByDocument_IdAndRentalItemId(
      UUID documentId, UUID rentalItemId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select link
      from ShipmentFurnitureMovementTask link
      where link.document.id = :documentId
        and link.rentalItemId = :rentalItemId
      """)
  Optional<ShipmentFurnitureMovementTask> findByDocumentAndRentalItemForUpdate(
      @Param("documentId") UUID documentId, @Param("rentalItemId") UUID rentalItemId);

  boolean existsByDocument_Id(UUID documentId);

  @Query(
      """
      select (count(link) > 0)
      from ShipmentFurnitureMovementTask link
      where link.document.id = :documentId
        and (
          link.equipmentMovementTaskId is not null
          or (
            link.replacementIdempotencyKey is not null
            and link.replacementCompletedAt is null
            and link.replacementRejectedAt is null
          )
        )
      """)
  boolean existsOperationalByDocumentId(@Param("documentId") UUID documentId);

  /** True while an atomic replacement checkpoint owns any still-old cabin of the order. */
  boolean
      existsByOrder_IdAndOldRentalItemIdInAndReplacementCompletedAtIsNullAndReplacementRejectedAtIsNull(
          UUID orderId, java.util.Collection<UUID> oldRentalItemIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select link
      from ShipmentFurnitureMovementTask link
      where link.order.id = :orderId
        and link.document is null
        and link.rentalItemId in :rentalItemIds
        and link.equipmentMovementTaskId is not null
        and link.replacementCompletedAt is not null
        and link.replacementRejectedAt is null
      order by link.replacementCompletedAt, link.id
      """)
  List<ShipmentFurnitureMovementTask> findAttachableReplacementMovementsForUpdate(
      @Param("orderId") UUID orderId,
      @Param("rentalItemIds") java.util.Collection<UUID> rentalItemIds);

  Optional<ShipmentFurnitureMovementTask> findByOrder_IdAndReplacementIdempotencyKey(
      UUID orderId, UUID replacementIdempotencyKey);

  List<ShipmentFurnitureMovementTask>
      findAllByOrder_IdAndReplacementBatchIdempotencyKeyOrderByReplacementPairIndexAsc(
          UUID orderId, UUID replacementBatchIdempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select link
      from ShipmentFurnitureMovementTask link
      where link.order.id = :orderId
        and link.replacementBatchIdempotencyKey = :batchIdempotencyKey
      order by link.replacementPairIndex
      """)
  List<ShipmentFurnitureMovementTask> findReplacementBatchForUpdate(
      @Param("orderId") UUID orderId, @Param("batchIdempotencyKey") UUID batchIdempotencyKey);

  Optional<ShipmentFurnitureMovementTask> findByEquipmentMovementTaskId(
      UUID equipmentMovementTaskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select link from ShipmentFurnitureMovementTask link where link.id = :id")
  Optional<ShipmentFurnitureMovementTask> findForUpdate(@Param("id") UUID id);

  @Query(
      """
      select link.id
      from ShipmentFurnitureMovementTask link
      where link.replacementIdempotencyKey is not null
        and link.replacementCompletedAt is null
        and link.replacementRejectedAt is null
      order by link.createdAt, link.id
      """)
  List<UUID> findPendingReplacementIds();

  boolean
      existsByOrder_IdAndReplacementIdempotencyKeyIsNotNullAndReplacementCompletedAtIsNullAndReplacementRejectedAtIsNull(
          UUID orderId);
}
