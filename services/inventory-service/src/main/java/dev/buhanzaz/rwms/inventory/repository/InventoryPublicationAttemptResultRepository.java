package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttemptResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryPublicationAttemptResultRepository
    extends JpaRepository<InventoryPublicationAttemptResult, UUID> {}
