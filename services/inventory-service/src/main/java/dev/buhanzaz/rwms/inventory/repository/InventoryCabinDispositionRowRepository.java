package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionCandidateKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionRow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for durable per-finding cabin disposition rows. */
public interface InventoryCabinDispositionRowRepository
    extends JpaRepository<InventoryCabinDispositionRow, InventoryCabinDispositionRow.Key> {
  List<InventoryCabinDispositionRow> findAllByInventoryIdOrderByFindingIdAsc(UUID inventoryId);

  List<InventoryCabinDispositionRow>
      findAllByInventoryIdAndCandidateKindOrderByFindingIdAsc(
          UUID inventoryId, InventoryCabinDispositionCandidateKind candidateKind);

  List<InventoryCabinDispositionRow>
      findAllByInventoryIdAndDispositionKindOrderByFindingIdAsc(
          UUID inventoryId, InventoryCabinDispositionKind dispositionKind);

  long deleteByInventoryId(UUID inventoryId);
}
