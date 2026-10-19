package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairCapacitySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for RepairCapacitySettings; business transitions remain in the owning service. */
public interface RepairCapacitySettingsRepository
    extends JpaRepository<RepairCapacitySettings, UUID> {}
