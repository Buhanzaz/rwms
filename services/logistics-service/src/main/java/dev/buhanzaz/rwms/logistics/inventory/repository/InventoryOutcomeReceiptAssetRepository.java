package dev.buhanzaz.rwms.logistics.inventory.repository;

import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeReceiptAsset;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for immutable receipt asset rows. */
public interface InventoryOutcomeReceiptAssetRepository
    extends JpaRepository<InventoryOutcomeReceiptAsset, UUID> {
  List<InventoryOutcomeReceiptAsset> findAllByReceiptIdOrderByAssetIdAsc(UUID receiptId);
}
