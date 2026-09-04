package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists offered, held and confirmed delivery windows used by the capacity planner. */
public interface CustomerDeliverySlotRepository
    extends JpaRepository<CustomerDeliverySlot, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select slot from CustomerDeliverySlot slot where slot.id = :id")
  Optional<CustomerDeliverySlot> findByIdForUpdate(@Param("id") UUID id);

  @Query(
      """
      select slot from CustomerDeliverySlot slot
      where slot.warehouseId = :warehouseId
        and slot.deliveryDate = :date
        and (slot.state = :confirmed
          or slot.state = :checkoutPending
          or (slot.state = :held and slot.expiresAt > :now))
      order by slot.windowStart asc, slot.createdAt asc, slot.id asc
      """)
  List<CustomerDeliverySlot> findCapacityWorkload(
      @Param("warehouseId") UUID warehouseId,
      @Param("date") LocalDate date,
      @Param("confirmed") CustomerDeliverySlotState confirmed,
      @Param("checkoutPending") CustomerDeliverySlotState checkoutPending,
      @Param("held") CustomerDeliverySlotState held,
      @Param("now") OffsetDateTime now);

  /** Locks every current capacity row used by the final hold fingerprint. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select slot from CustomerDeliverySlot slot
      where slot.warehouseId = :warehouseId
        and slot.deliveryDate = :date
        and (slot.state = :confirmed
          or slot.state = :checkoutPending
          or (slot.state = :held and slot.expiresAt > :now))
      order by slot.windowStart asc, slot.createdAt asc, slot.id asc
      """)
  List<CustomerDeliverySlot> findCapacityWorkloadForUpdate(
      @Param("warehouseId") UUID warehouseId,
      @Param("date") LocalDate date,
      @Param("confirmed") CustomerDeliverySlotState confirmed,
      @Param("checkoutPending") CustomerDeliverySlotState checkoutPending,
      @Param("held") CustomerDeliverySlotState held,
      @Param("now") OffsetDateTime now);

  List<CustomerDeliverySlot> findAllByInquiryIdAndStateInOrderByCreatedAtDesc(
      UUID inquiryId, Collection<CustomerDeliverySlotState> states);

  /** Locks held rows that will be superseded by a newly accepted slot. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select slot from CustomerDeliverySlot slot
      where slot.inquiryId = :inquiryId
        and slot.state = :state
      order by slot.createdAt desc, slot.id desc
      """)
  List<CustomerDeliverySlot> findHeldForUpdate(
      @Param("inquiryId") UUID inquiryId,
      @Param("state") CustomerDeliverySlotState state);

  Optional<CustomerDeliverySlot> findByOrderIdAndState(
      UUID orderId, CustomerDeliverySlotState state);

  List<CustomerDeliverySlot> findAllByOrderIdInAndState(
      Collection<UUID> orderIds, CustomerDeliverySlotState state);
}
