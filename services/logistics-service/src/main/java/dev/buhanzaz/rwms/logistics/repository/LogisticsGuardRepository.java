package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsGuardRepository extends JpaRepository<LogisticsGuard, UUID> {
  Optional<LogisticsGuard> findByLine_Id(UUID lineId);
}
