package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryPlanLogisticsEffect;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.time.OffsetDateTime;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanEffectState;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for generation-scoped completed-inventory logistics effects. */
public interface InventoryPlanLogisticsEffectRepository
    extends JpaRepository<InventoryPlanLogisticsEffect, UUID> {
  /** Locks one effect while a claimed remote attempt is settled. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select effect from InventoryPlanLogisticsEffect effect where effect.id = :id")
  Optional<InventoryPlanLogisticsEffect> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select effect from InventoryPlanLogisticsEffect effect where effect.inventoryId = :inventoryId and effect.finalPlanVersion = :finalPlanVersion and effect.outcomeReapplicationNo = :outcomeReapplicationNo")
  Optional<InventoryPlanLogisticsEffect> findForUpdate(
      @Param("inventoryId") UUID inventoryId,
      @Param("finalPlanVersion") long finalPlanVersion,
      @Param("outcomeReapplicationNo") long outcomeReapplicationNo);

  List<InventoryPlanLogisticsEffect>
      findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
          Collection<InventoryPlanEffectState> states, OffsetDateTime current);

  Optional<InventoryPlanLogisticsEffect>
      findFirstByInventoryIdAndFinalPlanVersionOrderByOutcomeReapplicationNoDesc(
          UUID inventoryId, long finalPlanVersion);
}
