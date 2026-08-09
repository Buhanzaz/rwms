package dev.buhanzaz.rwms.logistics.equipment.repository;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskOwnerType;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned equipment movement tasks and lines.
 */
public interface EquipmentMovementTaskRepository extends JpaRepository<EquipmentMovementTask, UUID> {
  Optional<EquipmentMovementTask> findByCreatedBySubjectIdAndIdempotencyKey(
      UUID createdBySubjectId, UUID idempotencyKey);

  Optional<EquipmentMovementTask> findByOwnerTypeAndOwnerId(
      EquipmentMovementTaskOwnerType ownerType, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from EquipmentMovementTask task where task.id = :id")
  Optional<EquipmentMovementTask> findForUpdate(@Param("id") UUID id);

  @Query(
      """
      select task.id
      from EquipmentMovementTask task
      where task.state in :states
        and (task.nextAttemptAt is null or task.nextAttemptAt <= :now)
      order by task.nextAttemptAt asc nulls first, task.id asc
      """)
  List<UUID> findDueIds(
      @Param("states") Collection<EquipmentMovementTaskState> states,
      @Param("now") OffsetDateTime now);

}
