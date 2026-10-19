package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeCharge;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Owns durable customer fee quotes and their exact linked booking-mutation result. */
public interface CustomerBookingChangeChargeRepository
    extends JpaRepository<CustomerBookingChangeCharge, UUID> {
  Optional<CustomerBookingChangeCharge> findByCustomerSubjectIdAndIdempotencyKey(
      UUID subjectId, UUID key);

  Optional<CustomerBookingChangeCharge> findByMutationId(UUID mutationId);

  List<CustomerBookingChangeCharge> findAllByMutationIdIn(Collection<UUID> mutationIds);

  List<CustomerBookingChangeCharge> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId);

  Optional<CustomerBookingChangeCharge> findByWaivedByAndWaiverKey(UUID actorId, UUID key);

  @Query(
      """
      select charge from CustomerBookingChangeCharge charge
      join CustomerRentalSession session on session.bookingId = charge.bookingId
      join RentalOrder rentalOrder on rentalOrder.id = charge.orderId
      where charge.warehouseId in :warehouses and rentalOrder.warehouseId = charge.warehouseId
        and session.state = dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState.BOOKED
        and session.version = charge.bookingVersion and session.deliverySlotId = charge.oldSlotId
        and charge.applicationState = dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeApplicationState.OFFERED
        and charge.settlement in (dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement.PAYMENT_REQUIRED,
          dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement.POLICY_UNCONFIGURED)
        and charge.expiresAt > :now
      order by charge.createdAt, charge.id
      """)
  List<CustomerBookingChangeCharge> findPendingWaivers(
      @Param("warehouses") Collection<UUID> warehouses,
      @Param("now") OffsetDateTime now,
      Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select charge from CustomerBookingChangeCharge charge where charge.id = :id")
  Optional<CustomerBookingChangeCharge> findForUpdate(@Param("id") UUID id);
}
