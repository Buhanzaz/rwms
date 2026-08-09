package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryValidationSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory validation snapshot persistence.
 */
public interface InventoryValidationSnapshotRepository
    extends JpaRepository<InventoryValidationSnapshot, UUID> {}
