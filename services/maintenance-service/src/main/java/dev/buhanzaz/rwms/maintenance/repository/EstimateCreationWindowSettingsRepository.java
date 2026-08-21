package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.EstimateCreationWindowSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for the maintenance-owned estimate creation window. */
public interface EstimateCreationWindowSettingsRepository
    extends JpaRepository<EstimateCreationWindowSettings, UUID> {}
