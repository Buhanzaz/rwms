package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes process-local booking availability hints only after the database
 * transaction that changed presentation holds has committed successfully.
 * Reconnecting clients still receive the hub's RESYNC event and re-read the
 * authoritative projection, so these hints do not need a durable history.
 */
@Component
@RequiredArgsConstructor
public class RentalAvailabilityInvalidationPublisher {
  public static final String CHANGE_TYPE = "RENTAL_AVAILABILITY_CHANGED";

  private final AssetInvalidationHub invalidations;

  public void publishAfterCommit(UUID warehouseId, Collection<UUID> rentalItemIds) {
    UUID requiredWarehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    List<UUID> changedIds =
        List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(rentalItemIds, "rentalItemIds")));
    if (changedIds.isEmpty()) return;
    changedIds.forEach(rentalItemId -> Objects.requireNonNull(rentalItemId, "rentalItemId"));
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Rental availability invalidation requires transaction synchronization");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            changedIds.forEach(
                rentalItemId ->
                    invalidations.publish(
                        new AssetInvalidationHub.AssetInvalidationEvent(
                            UUID.randomUUID(),
                            requiredWarehouseId,
                            CHANGE_TYPE,
                            CHANGE_TYPE,
                            AssetAggregateType.RENTAL_ITEM,
                            rentalItemId,
                            0,
                            Instant.now())));
          }
        });
  }
}
