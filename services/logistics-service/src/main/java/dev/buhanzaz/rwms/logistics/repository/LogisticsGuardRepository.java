package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Guard Repository; it does not own cross-service workflow decisions.
 */
public interface LogisticsGuardRepository extends JpaRepository<LogisticsGuard, UUID> {
  Optional<LogisticsGuard> findByLine_Id(UUID lineId);
}
