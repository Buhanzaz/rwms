package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryCaptureRelease;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryCaptureReleaseRepository
    extends JpaRepository<InventoryCaptureRelease, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select value from InventoryCaptureRelease value where value.operationId = :operationId")
  Optional<InventoryCaptureRelease> findByIdForUpdate(
      @Param("operationId") UUID operationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from InventoryCaptureRelease value
       where (value.state = 'PENDING' and value.nextAttemptAt <= :now)
          or (value.state = 'IN_FLIGHT' and value.leaseUntil <= :now)
       order by value.createdAt, value.operationId
      """)
  List<InventoryCaptureRelease> findClaimableForUpdate(
      @Param("now") OffsetDateTime now, Pageable pageable);
}
