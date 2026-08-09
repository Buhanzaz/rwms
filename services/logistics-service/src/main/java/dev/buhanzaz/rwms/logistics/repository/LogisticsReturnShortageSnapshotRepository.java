package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsReturnShortageSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Return Shortage Snapshot Repository; it does not own cross-service workflow decisions.
 */
public interface LogisticsReturnShortageSnapshotRepository
    extends JpaRepository<LogisticsReturnShortageSnapshot, UUID> {
  Optional<LogisticsReturnShortageSnapshot> findByLine_Id(UUID lineId);
}
