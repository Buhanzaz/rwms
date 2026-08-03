package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RentalAvailabilityInvalidationPublisherTest {
  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void publishesScopedCabinInvalidationsOnlyAfterCommit() {
    AssetInvalidationHub hub = mock(AssetInvalidationHub.class);
    RentalAvailabilityInvalidationPublisher publisher =
        new RentalAvailabilityInvalidationPublisher(hub);
    UUID warehouseId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    TransactionSynchronizationManager.initSynchronization();

    publisher.publishAfterCommit(
        warehouseId, List.of(firstId, secondId, firstId));

    verifyNoInteractions(hub);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AssetInvalidationHub.AssetInvalidationEvent> events =
        ArgumentCaptor.forClass(AssetInvalidationHub.AssetInvalidationEvent.class);
    verify(hub, org.mockito.Mockito.times(2)).publish(events.capture());
    assertThat(events.getAllValues())
        .allSatisfy(
            event -> {
              assertThat(event.warehouseId()).isEqualTo(warehouseId);
              assertThat(event.scope())
                  .isEqualTo(RentalAvailabilityInvalidationPublisher.CHANGE_TYPE);
              assertThat(event.changeType())
                  .isEqualTo(RentalAvailabilityInvalidationPublisher.CHANGE_TYPE);
              assertThat(event.revision()).isZero();
            });
    assertThat(events.getAllValues())
        .extracting(AssetInvalidationHub.AssetInvalidationEvent::aggregateId)
        .containsExactly(firstId, secondId);
  }

  @Test
  void rollbackNeverPublishesAnAvailabilityHint() {
    AssetInvalidationHub hub = mock(AssetInvalidationHub.class);
    RentalAvailabilityInvalidationPublisher publisher =
        new RentalAvailabilityInvalidationPublisher(hub);
    TransactionSynchronizationManager.initSynchronization();

    publisher.publishAfterCommit(UUID.randomUUID(), List.of(UUID.randomUUID()));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            synchronization ->
                synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK));

    verify(hub, never()).publish(org.mockito.ArgumentMatchers.any());
  }
}
