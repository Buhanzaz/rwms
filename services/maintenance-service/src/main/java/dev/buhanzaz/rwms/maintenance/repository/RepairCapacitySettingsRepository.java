package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairCapacitySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairCapacitySettingsRepository
    extends JpaRepository<RepairCapacitySettings, UUID> {}
