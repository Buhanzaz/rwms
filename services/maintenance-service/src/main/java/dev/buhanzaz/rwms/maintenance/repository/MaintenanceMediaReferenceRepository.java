package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReferenceId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceMediaReferenceRepository
    extends JpaRepository<MaintenanceMediaReference, MaintenanceMediaReferenceId> {
  List<MaintenanceMediaReference> findAllByAggregateTypeAndAggregateIdOrderByMediaId(
      String aggregateType, UUID aggregateId);
  void deleteAllByAggregateTypeAndAggregateId(String aggregateType, UUID aggregateId);
}
