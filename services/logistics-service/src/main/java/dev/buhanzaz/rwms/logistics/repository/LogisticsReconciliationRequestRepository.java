package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationRequest;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Reconciliation Request Repository; it does not own cross-service workflow decisions.
 */
public interface LogisticsReconciliationRequestRepository
    extends JpaRepository<LogisticsReconciliationRequest, UUID> {}
