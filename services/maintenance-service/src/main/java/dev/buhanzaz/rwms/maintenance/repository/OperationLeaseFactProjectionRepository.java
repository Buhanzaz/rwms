package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.OperationLeaseFactProjection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for OperationLeaseFactProjection; business transitions remain in the owning service. */
public interface OperationLeaseFactProjectionRepository
    extends JpaRepository<OperationLeaseFactProjection, UUID> {}
