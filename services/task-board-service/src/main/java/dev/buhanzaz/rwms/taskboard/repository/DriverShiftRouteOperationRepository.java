package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.DriverShiftRouteOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for the immutable ordered operations of one driver shift plan. */
public interface DriverShiftRouteOperationRepository
    extends JpaRepository<DriverShiftRouteOperation, UUID> {
  List<DriverShiftRouteOperation> findAllByDriverShiftPlanIdOrderBySequenceAsc(
      UUID driverShiftPlanId);

  long deleteAllByDriverShiftPlanId(UUID driverShiftPlanId);
}
