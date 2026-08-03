package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkQueueClassBindingRepository
    extends JpaRepository<WorkQueueClassBinding, UUID> {
  List<WorkQueueClassBinding> findAllByQueueId(UUID queueId);

  List<WorkQueueClassBinding> findAllByQueueIdOrderByBindingOrderAscIdAsc(UUID queueId);

  List<WorkQueueClassBinding> findAllByWorkerClassId(UUID workerClassId);

  boolean existsByWorkerClassId(UUID classId);

  Optional<WorkQueueClassBinding> findByQueueIdAndWorkerClassId(
      UUID queueId, UUID workerClassId);

  /**
   * This relation is global deliberately: a dedicated logistics class remains logistics even
   * while every driver queue is disabled. A class shared with any GENERAL queue is never marked,
   * which prevents a legacy driver placeholder from hiding ordinary repair workers.
   */
  @Query(
      """
      select distinct logisticsBinding.workerClass.id
      from WorkQueueClassBinding logisticsBinding
      where logisticsBinding.queue.definition.purpose = :logisticsPurpose
        and logisticsBinding.bindingOrder = 0
        and logisticsBinding.participationPolicy = :participationPolicy
        and not exists (
          select 1
          from WorkQueueClassBinding generalBinding
          where generalBinding.workerClass.id = logisticsBinding.workerClass.id
            and generalBinding.queue.definition.purpose = :generalPurpose
        )
      """)
  List<UUID> findDedicatedLogisticsPrimaryWorkerClassIds(
      @Param("logisticsPurpose") QueuePurpose logisticsPurpose,
      @Param("generalPurpose") QueuePurpose generalPurpose,
      @Param("participationPolicy") ParticipationPolicy participationPolicy);
}
