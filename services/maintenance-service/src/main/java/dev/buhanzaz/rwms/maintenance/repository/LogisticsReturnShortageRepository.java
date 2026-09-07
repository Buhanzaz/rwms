package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortageId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for LogisticsReturnShortage; business transitions remain in the owning service. */
public interface LogisticsReturnShortageRepository
    extends JpaRepository<LogisticsReturnShortage, LogisticsReturnShortageId> {
  List<LogisticsReturnShortage> findAllById_ReturnIdAndWarehouseIdOrderById_LineId(
      UUID returnId, UUID warehouseId);

  Optional<LogisticsReturnShortage> findByEstimateId(UUID estimateId);
}
