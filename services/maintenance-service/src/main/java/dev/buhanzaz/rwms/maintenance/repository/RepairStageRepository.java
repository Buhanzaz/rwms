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

/** Spring Data persistence boundary for RepairStage; business transitions remain in the owning service. */
public interface RepairStageRepository extends JpaRepository<RepairStage, UUID> {
  List<RepairStage> findAllByRepairIdOrderByStageNo(UUID repairId);
  @Query(
      """
      select stage from RepairStage stage
       where stage.repairId in :repairIds
       order by stage.repairId, stage.stageNo, stage.id
      """)
  List<RepairStage> findAllByRepairIdInOrderByRepairIdAscStageNoAscIdAsc(
      @Param("repairIds") Collection<UUID> repairIds);
  Optional<RepairStage> findByRepairIdAndStageNo(UUID repairId, int stageNo);
  Optional<RepairStage> findByExternalQueueEntryId(UUID externalQueueEntryId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from RepairStage value where value.externalQueueEntryId = :entryId")
  Optional<RepairStage> findByExternalQueueEntryIdForUpdate(@Param("entryId") UUID entryId);
  long countByRepairIdAndStateNotIn(UUID repairId, Collection<RepairStageState> states);
  void deleteAllByRepairId(UUID repairId);
}
