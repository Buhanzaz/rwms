package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Guard Repository; it does not own
 * cross-service workflow decisions.
 */
public interface LogisticsGuardRepository extends JpaRepository<LogisticsGuard, UUID> {
  Optional<LogisticsGuard> findByLine_Id(UUID lineId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select guard from LogisticsGuard guard where guard.line.id in :lineIds order by guard.id")
  List<LogisticsGuard> findAllForUpdateByLineIdIn(@Param("lineIds") Collection<UUID> lineIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select guard from LogisticsGuard guard where guard.id=:id")
  Optional<LogisticsGuard> findForUpdate(@Param("id") UUID id);
}
