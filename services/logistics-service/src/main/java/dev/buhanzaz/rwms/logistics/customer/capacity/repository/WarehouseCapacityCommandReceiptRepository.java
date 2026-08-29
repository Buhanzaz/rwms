package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityCommandReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores immutable successful-command results for delayed capacity retry safety. */
public interface WarehouseCapacityCommandReceiptRepository
    extends JpaRepository<WarehouseCapacityCommandReceipt, UUID> {
  /** Finds the canonical first result for one already accepted warehouse generation and revision. */
  Optional<WarehouseCapacityCommandReceipt>
      findFirstByWarehouseIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
          UUID warehouseId,
          long sourceGeneration,
          String sourceRevision);
}
