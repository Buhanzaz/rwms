package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WarehouseKpiSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseKpiSettingsRepository
    extends JpaRepository<WarehouseKpiSettings, UUID> {
  Optional<WarehouseKpiSettings> findByWarehouseId(UUID warehouseId);
}
