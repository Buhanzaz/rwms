package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacityJob;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads anonymous simulator delivery jobs for one warehouse-local capacity calculation. */
public interface ScenarioCapacityJobRepository extends JpaRepository<ScenarioCapacityJob, UUID> {
  @Query(
      """
      select job from ScenarioCapacityJob job
      where job.snapshot.warehouseId = :warehouseId
        and job.deliveryDate = :date
      order by job.windowStart asc, job.sourceJobId asc
      """)
  List<ScenarioCapacityJob> findCapacityWorkload(
      @Param("warehouseId") UUID warehouseId, @Param("date") LocalDate date);
}
