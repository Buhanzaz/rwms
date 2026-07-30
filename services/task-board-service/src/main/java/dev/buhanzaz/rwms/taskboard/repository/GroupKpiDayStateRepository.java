package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.GroupKpiDayState;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupKpiDayStateRepository extends JpaRepository<GroupKpiDayState, UUID> {
  Optional<GroupKpiDayState> findByWarehouseIdAndWorkerGroupIdAndLocalDate(
      UUID warehouseId, UUID workerGroupId, LocalDate localDate);

  Optional<GroupKpiDayState> findFirstByWarehouseIdAndWorkerGroupIdOrderByLocalDateDesc(
      UUID warehouseId, UUID workerGroupId);

  List<GroupKpiDayState> findAllByNextTransitionAtLessThanEqualOrderByNextTransitionAtAsc(
      OffsetDateTime at);
}
