package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacityCommandReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores immutable successful-command results for delayed capacity retry safety. */
public interface ScenarioCapacityCommandReceiptRepository
    extends JpaRepository<ScenarioCapacityCommandReceipt, UUID> {
  /** Finds the canonical first result for one already accepted scenario generation and revision. */
  Optional<ScenarioCapacityCommandReceipt>
      findFirstByWarehouseIdAndSourceScenarioIdAndSourceGenerationAndSourceRevisionOrderByCreatedAtAscIdempotencyKeyAsc(
          UUID warehouseId,
          UUID sourceScenarioId,
          long sourceGeneration,
          String sourceRevision);
}
