package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.OperationLeaseFactProjection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLeaseFactProjectionRepository
    extends JpaRepository<OperationLeaseFactProjection, UUID> {}
