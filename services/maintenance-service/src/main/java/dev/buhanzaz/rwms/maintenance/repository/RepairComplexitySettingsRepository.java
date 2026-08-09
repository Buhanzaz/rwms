package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairComplexitySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for RepairComplexitySettings; business transitions remain in the owning service. */
public interface RepairComplexitySettingsRepository
    extends JpaRepository<RepairComplexitySettings, UUID> {}
