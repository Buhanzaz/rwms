package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttemptResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory publication attempt result persistence.
 */
public interface InventoryPublicationAttemptResultRepository
    extends JpaRepository<InventoryPublicationAttemptResult, UUID> {}
