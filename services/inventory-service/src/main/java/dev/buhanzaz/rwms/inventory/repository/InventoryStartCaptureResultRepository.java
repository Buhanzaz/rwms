package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryStartCaptureResult;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryStartCaptureResultRepository
    extends JpaRepository<InventoryStartCaptureResult, InventoryStartCaptureResult.Key> {
  Optional<InventoryStartCaptureResult>
      findFirstByOperationIdAndOutcomeAndExpiresAtAfterOrderByTechnicalAttemptDesc(
          UUID operationId, String outcome, OffsetDateTime now);
}
