package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryPlanningSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory planning settings persistence.
 */
public interface InventoryPlanningSettingsRepository
    extends JpaRepository<InventoryPlanningSettings, UUID> {}
