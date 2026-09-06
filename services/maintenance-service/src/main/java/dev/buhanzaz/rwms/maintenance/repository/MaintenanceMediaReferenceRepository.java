package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReferenceId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for MaintenanceMediaReference; business transitions remain in the owning service. */
public interface MaintenanceMediaReferenceRepository
    extends JpaRepository<MaintenanceMediaReference, MaintenanceMediaReferenceId> {
  List<MaintenanceMediaReference> findAllByAggregateTypeAndAggregateIdOrderByMediaId(
      String aggregateType, UUID aggregateId);
  List<MaintenanceMediaReference>
      findAllByAggregateTypeAndAggregateIdInOrderByAggregateIdAscMediaId(
          String aggregateType, Collection<UUID> aggregateIds);
  void deleteAllByAggregateTypeAndAggregateId(String aggregateType, UUID aggregateId);
}
