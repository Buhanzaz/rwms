package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryCabinWriteOffIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinWriteOffIntentState;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanEffectState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for durable maintenance cabin write-off hand-offs. */
public interface InventoryCabinWriteOffIntentRepository
    extends JpaRepository<InventoryCabinWriteOffIntent, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select intent from InventoryCabinWriteOffIntent intent where intent.findingId = :findingId")
  Optional<InventoryCabinWriteOffIntent> findByFindingIdForUpdate(
      @Param("findingId") UUID findingId);

  List<InventoryCabinWriteOffIntent> findAllByInventoryIdOrderByFindingIdAsc(UUID inventoryId);

  /**
   * Selects only intents whose exact plan-level prerequisite is terminal, avoiding hot polling
   * while logistics is still pending.
   */
  @Query(
      """
      select intent
        from InventoryCabinWriteOffIntent intent, InventoryPlanLogisticsEffect effect
       where intent.inventoryId = effect.inventoryId
         and intent.finalPlanVersion = effect.finalPlanVersion
         and intent.outcomeReapplicationNo = effect.outcomeReapplicationNo
         and intent.state in :intentStates
         and intent.nextAttemptAt <= :current
         and effect.state in :effectStates
       order by intent.nextAttemptAt asc, intent.findingId asc
      """)
  List<InventoryCabinWriteOffIntent> findRecoverableAfterLogistics(
      @Param("intentStates") Collection<InventoryCabinWriteOffIntentState> intentStates,
      @Param("effectStates") Collection<InventoryPlanEffectState> effectStates,
      @Param("current") OffsetDateTime current,
      Pageable pageable);

  @Query(
      "select intent.inventoryId as inventoryId, intent.state as state from InventoryCabinWriteOffIntent intent where intent.inventoryId in :inventoryIds order by intent.inventoryId, intent.findingId")
  List<InventoryCabinWriteOffStateProjection> findStatesByInventoryIds(
      @Param("inventoryIds") Set<UUID> inventoryIds);

  /** Lightweight state row used by history projection without hydrating frozen JSON requests. */
  interface InventoryCabinWriteOffStateProjection {
    UUID getInventoryId();

    InventoryCabinWriteOffIntentState getState();
  }
}
