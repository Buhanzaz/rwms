package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsReconciliationRequest;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsReconciliationRequestRepository
    extends JpaRepository<LogisticsReconciliationRequest, UUID> {}
