package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Resolves installation-wide effective-dated work schedules. */
public interface KpiWorkScheduleRepository
    extends JpaRepository<KpiWorkScheduleRevision, UUID> {
  @Query(
      value =
          "select * from kpi_work_schedule where warehouse_id is null and scheduled order by effective_from",
      nativeQuery = true)
  List<KpiWorkScheduleRevision> findAllGlobalScheduledOrderByEffectiveFromAsc();
}
