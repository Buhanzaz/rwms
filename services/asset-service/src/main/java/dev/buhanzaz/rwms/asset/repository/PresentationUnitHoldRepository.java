package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local presentation unit hold persistence.
 */
public interface PresentationUnitHoldRepository
    extends JpaRepository<PresentationUnitHold, UUID> {
  List<PresentationUnitHold> findAllByPresentationIdAndStateOrderByCreatedAtAscIdAsc(
      UUID presentationId, PresentationUnitHoldState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select hold
      from PresentationUnitHold hold
      where hold.presentationId = :presentationId
        and hold.state = :state
      order by hold.rentalItemId, hold.id
      """)
  List<PresentationUnitHold> findAllActiveForUpdate(
      @Param("presentationId") UUID presentationId,
      @Param("state") PresentationUnitHoldState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select hold
      from PresentationUnitHold hold
      where hold.rentalItemId = :rentalItemId
        and hold.state = :state
      """)
  Optional<PresentationUnitHold> findActiveByRentalItemForUpdate(
      @Param("rentalItemId") UUID rentalItemId,
      @Param("state") PresentationUnitHoldState state);

  @Query(
      """
      select hold
      from PresentationUnitHold hold
      where hold.rentalItemId in :rentalItemIds
        and hold.state = :state
        and hold.expiresAt > :now
      """)
  List<PresentationUnitHold> findAllLiveByRentalItemIdIn(
      @Param("rentalItemIds") Collection<UUID> rentalItemIds,
      @Param("state") PresentationUnitHoldState state,
      @Param("now") OffsetDateTime now);

  @Modifying
  @Query(
      """
      update PresentationUnitHold hold
      set hold.state = dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState.EXPIRED,
          hold.endedAt = :now,
          hold.updatedAt = :now
      where hold.state = dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState.ACTIVE
        and hold.expiresAt <= :now
      """)
  int expireDue(@Param("now") OffsetDateTime now);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
