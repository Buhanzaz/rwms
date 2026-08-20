package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Rental Order Unit Term Repository; it does
 * not own cross-service workflow decisions.
 */
public interface RentalOrderUnitTermRepository extends JpaRepository<RentalOrderUnitTerm, UUID> {
  @Query(
      "select term from RentalOrderUnitTerm term where term.order.id=:orderId and"
          + " term.inventorySupersededBy is null order by term.rentalItemId")
  List<RentalOrderUnitTerm> findAllByOrder_IdOrderByRentalItemIdAsc(@Param("orderId") UUID orderId);

  @Query(
      "select term from RentalOrderUnitTerm term where term.order.id=:orderId and term.rentalItemId"
          + " in :rentalItemIds and term.inventorySupersededBy is null order by term.rentalItemId")
  List<RentalOrderUnitTerm> findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(
      @Param("orderId") UUID orderId, @Param("rentalItemIds") Collection<UUID> rentalItemIds);

  @Query(
      "select term from RentalOrderUnitTerm term where term.rentalShipmentId=:rentalShipmentId and"
          + " term.inventorySupersededBy is null order by term.rentalItemId")
  List<RentalOrderUnitTerm> findAllByRentalShipmentIdOrderByRentalItemIdAsc(
      @Param("rentalShipmentId") UUID rentalShipmentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select term
      from RentalOrderUnitTerm term
      join fetch term.order orders
      where term.rentalItemId in :assetIds
      order by orders.id, term.rentalItemId, term.id
      """)
  List<RentalOrderUnitTerm> findAllForUpdateByRentalItemIdIn(
      @Param("assetIds") Collection<UUID> assetIds);

  @Query(
      "select count(term) from RentalOrderUnitTerm term where term.order.id=:orderId and"
          + " term.inventorySupersededBy is null")
  long countActiveByOrderId(@Param("orderId") UUID orderId);

  void deleteAllByOrder_IdAndRentalItemId(UUID orderId, UUID rentalItemId);
}
