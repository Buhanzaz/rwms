package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairComplexitySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairComplexitySettingsRepository
    extends JpaRepository<RepairComplexitySettings, UUID> {}
