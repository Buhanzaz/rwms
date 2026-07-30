package dev.buhanzaz.rwms.analytics.repository;

import dev.buhanzaz.rwms.analytics.domain.GroupKpiDayEvidence;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GroupKpiDayEvidenceRepository
    extends JpaRepository<GroupKpiDayEvidence, UUID> {
  List<GroupKpiDayEvidence>
      findAllByWarehouseIdAndLocalDateBetweenOrderByWorkerGroupIdAscLocalDateAsc(
          UUID warehouseId, LocalDate start, LocalDate end);

  @Query(
      """
      select min(e.dataAvailableFrom)
      from GroupKpiDayEvidence e
      where e.warehouseId = :warehouseId
      """)
  Optional<LocalDate> findDataAvailableFrom(UUID warehouseId);

  @Query(
      """
      select max(e.localDate)
      from GroupKpiDayEvidence e
      where e.warehouseId = :warehouseId
      """)
  Optional<LocalDate> findLatestEvidenceDate(UUID warehouseId);
}
