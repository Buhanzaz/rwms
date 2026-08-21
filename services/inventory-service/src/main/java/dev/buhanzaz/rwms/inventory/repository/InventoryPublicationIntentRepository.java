package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory publication intent persistence.
 */
public interface InventoryPublicationIntentRepository
    extends JpaRepository<InventoryPublicationIntent, UUID> {
  Optional<InventoryPublicationIntent> findByInventoryIdAndFindingId(
      UUID inventoryId, UUID findingId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select intent from InventoryPublicationIntent intent where intent.inventoryId = :inventoryId and intent.findingId = :findingId")
  Optional<InventoryPublicationIntent> findByInventoryIdAndFindingIdForUpdate(
      @Param("inventoryId") UUID inventoryId, @Param("findingId") UUID findingId);

  List<InventoryPublicationIntent> findAllByInventoryIdOrderByFindingId(UUID inventoryId);

  List<InventoryPublicationIntent> findAllByFindingIdInOrderByFindingId(Set<UUID> findingIds);

  List<InventoryPublicationIntent> findAllByInventoryIdInOrderByInventoryIdAscFindingIdAsc(
      Set<UUID> inventoryIds);

  @Query(
      "select intent.inventoryId as inventoryId, intent.state as state from InventoryPublicationIntent intent where intent.inventoryId in :inventoryIds order by intent.inventoryId, intent.findingId")
  List<InventoryPublicationStateProjection> findStatesByInventoryIds(
      @Param("inventoryIds") Set<UUID> inventoryIds);

  List<InventoryPublicationIntent> findTop20ByStateOrderByUpdatedAtAsc(PublicationState state);

  /** Selects one bounded due batch shared by new and transient publication work. */
  List<InventoryPublicationIntent>
      findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
          Set<PublicationState> states, OffsetDateTime dueAt);

  /** Lightweight history-row projection that never hydrates publication JSON evidence. */
  interface InventoryPublicationStateProjection {
    UUID getInventoryId();

    PublicationState getState();
  }
}
