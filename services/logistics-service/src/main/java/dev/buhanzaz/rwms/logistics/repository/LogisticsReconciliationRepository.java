package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliation;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationState;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Reconciliation Repository; it does not own cross-service workflow decisions.
 */
public interface LogisticsReconciliationRepository
    extends JpaRepository<LogisticsReconciliation, UUID> {
  Optional<LogisticsReconciliation> findByDocument_IdAndLine_IdAndState(
      UUID documentId, UUID lineId, LogisticsReconciliationState state);
}
