package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryStartCaptureAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryStartCaptureAttemptRepository
    extends JpaRepository<InventoryStartCaptureAttempt, InventoryStartCaptureAttempt.Key> {
  Optional<InventoryStartCaptureAttempt> findFirstByOperationIdOrderByTechnicalAttemptDesc(
      UUID operationId);
}
