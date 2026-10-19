package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventorySourceIsolationRepair;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Service-local immutable administrative repair receipts, never shared with another database. */
public interface InventorySourceIsolationRepairRepository
    extends JpaRepository<InventorySourceIsolationRepair, UUID> {}
