package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepairStageRepository extends JpaRepository<RepairStage, UUID> {
  List<RepairStage> findAllByRepairIdOrderByStageNo(UUID repairId);
  Optional<RepairStage> findByExternalQueueEntryId(UUID externalQueueEntryId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from RepairStage value where value.externalQueueEntryId = :entryId")
  Optional<RepairStage> findByExternalQueueEntryIdForUpdate(@Param("entryId") UUID entryId);
  long countByRepairIdAndStateNotIn(UUID repairId, Collection<RepairStageState> states);
  void deleteAllByRepairId(UUID repairId);
}
