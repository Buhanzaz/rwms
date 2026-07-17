package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OperationLeaseState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperationLeaseRepository extends JpaRepository<OperationLease, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select lease from OperationLease lease where lease.id = :id")
  Optional<OperationLease> findByIdForUpdate(@Param("id") UUID id);

  @Query("select lease.rentalItemId from OperationLease lease where lease.id = :id")
  Optional<UUID> findRentalItemIdById(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select lease from OperationLease lease
      where lease.rentalItemId = :rentalItemId and lease.state = :state
      order by lease.fencingToken, lease.id
      """)
  List<OperationLease> findByRentalItemIdAndStateForUpdate(
      @Param("rentalItemId") UUID rentalItemId, @Param("state") OperationLeaseState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select lease from OperationLease lease
      where lease.rentalItemId = :rentalItemId
        and lease.state = :state
        and lease.expiresAt <= :expiredAt
      order by lease.fencingToken, lease.id
      """)
  List<OperationLease> findExpiredByRentalItemIdAndStateForUpdate(
      @Param("rentalItemId") UUID rentalItemId,
      @Param("state") OperationLeaseState state,
      @Param("expiredAt") OffsetDateTime expiredAt);

  @Query("select coalesce(max(lease.fencingToken), 0) from OperationLease lease where lease.rentalItemId = :rentalItemId")
  long maximumFencingToken(@Param("rentalItemId") UUID rentalItemId);
}
