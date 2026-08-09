package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsEquipmentHoldReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Equipment Hold Reference Repository; it does not own cross-service workflow decisions.
 */
public interface LogisticsEquipmentHoldReferenceRepository
    extends JpaRepository<LogisticsEquipmentHoldReference, UUID> {
  List<LogisticsEquipmentHoldReference> findAllByDocument_IdOrderByCreatedAtAsc(UUID documentId);

  List<LogisticsEquipmentHoldReference> findAllByLine_IdOrderByCreatedAtAsc(UUID lineId);

  Optional<LogisticsEquipmentHoldReference> findByLine_IdAndEquipmentId(UUID lineId, UUID equipmentId);

  Optional<LogisticsEquipmentHoldReference> findByHoldId(UUID holdId);
}
