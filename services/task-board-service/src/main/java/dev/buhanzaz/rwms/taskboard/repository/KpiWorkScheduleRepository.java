package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Resolves current, pending, and effective-dated warehouse work schedules. */
public interface KpiWorkScheduleRepository
    extends JpaRepository<KpiWorkScheduleRevision, UUID> {
  List<KpiWorkScheduleRevision>
      findAllByWarehouseIdAndScheduledTrueOrderByEffectiveFromAsc(UUID warehouseId);
}
