package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsReturnShortageSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsReturnShortageSnapshotRepository
    extends JpaRepository<LogisticsReturnShortageSnapshot, UUID> {
  Optional<LogisticsReturnShortageSnapshot> findByLine_Id(UUID lineId);
}
