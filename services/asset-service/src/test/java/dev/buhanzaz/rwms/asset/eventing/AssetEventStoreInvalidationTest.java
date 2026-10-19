package dev.buhanzaz.rwms.asset.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AssetEventStoreInvalidationTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AssetInvalidationHub invalidations = mock(AssetInvalidationHub.class);
  private final AssetEventStore store =
      new AssetEventStore(jdbc, null, null, null, null, invalidations);

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = AssetEventType.class,
      names = {
        "RENTAL_ITEM_CREATED",
        "RENTAL_ITEM_PASSPORT_CHANGED",
        "RENTAL_ITEM_STATUS_CHANGED",
        "RENTAL_ITEM_GENERAL_COMMENT_CHANGED"
      })
  void rentalItemInvalidationIsPublishedOnlyAfterCommitWithConcreteChangeType(
      AssetEventType eventType) {
    UUID eventId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    TransactionSynchronizationManager.initSynchronization();

    store.registerInvalidation(
        eventId,
        AssetAggregateType.RENTAL_ITEM,
        rentalItemId,
        2,
        eventType,
        Map.of("warehouseId", warehouseId.toString()),
        Map.of("warehouseId", warehouseId.toString()),
        OffsetDateTime.now(ZoneOffset.UTC));

    verifyNoInteractions(invalidations);
    List<TransactionSynchronization> synchronizations =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(synchronizations).hasSize(1);

    synchronizations.getFirst().afterCommit();

    ArgumentCaptor<AssetInvalidationHub.AssetInvalidationEvent> event =
        ArgumentCaptor.forClass(AssetInvalidationHub.AssetInvalidationEvent.class);
    verify(invalidations).publish(event.capture());
    assertThat(event.getValue().warehouseId()).isEqualTo(warehouseId);
    assertThat(event.getValue().scope()).isEqualTo("RENTAL_ITEMS_CHANGED");
    assertThat(event.getValue().changeType()).isEqualTo(eventType.value());
    assertThat(event.getValue().aggregateId()).isEqualTo(rentalItemId);
    assertThat(event.getValue().revision()).isEqualTo(2);
  }

  @Test
  void rolledBackTransactionDoesNotPublishInvalidation() {
    TransactionSynchronizationManager.initSynchronization();
    UUID warehouseId = UUID.randomUUID();

    store.registerInvalidation(
        UUID.randomUUID(),
        AssetAggregateType.EQUIPMENT_BALANCE,
        UUID.randomUUID(),
        1,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        Map.of("warehouseId", warehouseId.toString()),
        Map.of("warehouseId", warehouseId.toString()),
        OffsetDateTime.now(ZoneOffset.UTC));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    verify(invalidations, never()).publish(org.mockito.ArgumentMatchers.any());
    verify(invalidations, never()).publishGlobal(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void warehouseMoveInvalidatesBothOriginAndDestination() {
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    AssetInvalidationHub scopedInvalidations = mock(AssetInvalidationHub.class);
    AssetEventStore scopedStore =
        new AssetEventStore(
            new PreviousWarehouseJdbcTemplate(sourceWarehouseId),
            null,
            null,
            null,
            null,
            scopedInvalidations);

    scopedStore.registerInvalidation(
        UUID.randomUUID(),
        AssetAggregateType.RENTAL_ITEM,
        rentalItemId,
        5,
        AssetEventType.RENTAL_ITEM_WAREHOUSE_CHANGED,
        Map.of("warehouseId", destinationWarehouseId.toString()),
        Map.of("warehouseId", destinationWarehouseId.toString()),
        OffsetDateTime.now(ZoneOffset.UTC));

    ArgumentCaptor<AssetInvalidationHub.AssetInvalidationEvent> events =
        ArgumentCaptor.forClass(AssetInvalidationHub.AssetInvalidationEvent.class);
    verify(scopedInvalidations, org.mockito.Mockito.times(2)).publish(events.capture());
    assertThat(events.getAllValues())
        .extracting(AssetInvalidationHub.AssetInvalidationEvent::warehouseId)
        .containsExactlyInAnyOrder(sourceWarehouseId, destinationWarehouseId);
    assertThat(events.getAllValues())
        .extracting(AssetInvalidationHub.AssetInvalidationEvent::changeType)
        .containsOnly("asset.rental-item.warehouse-changed.v1");
  }

  @Test
  void nestedUserJsonCannotExpandWarehouseScope() {
    UUID warehouseId = UUID.randomUUID();
    UUID unrelatedWarehouseId = UUID.randomUUID();
    AssetInvalidationHub scopedInvalidations = mock(AssetInvalidationHub.class);
    AssetEventStore scopedStore =
        new AssetEventStore(
            jdbc, null, null, null, null, scopedInvalidations);

    scopedStore.registerInvalidation(
        UUID.randomUUID(),
        AssetAggregateType.RENTAL_ITEM,
        UUID.randomUUID(),
        1,
        AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED,
        Map.of("warehouseId", warehouseId.toString()),
        Map.of(
            "warehouseId",
            warehouseId.toString(),
            "passport",
            Map.of("warehouseId", unrelatedWarehouseId.toString())),
        OffsetDateTime.now(ZoneOffset.UTC));

    ArgumentCaptor<AssetInvalidationHub.AssetInvalidationEvent> event =
        ArgumentCaptor.forClass(AssetInvalidationHub.AssetInvalidationEvent.class);
    verify(scopedInvalidations).publish(event.capture());
    assertThat(event.getValue().warehouseId()).isEqualTo(warehouseId);
  }

  @Test
  void globalCatalogChangeUsesGlobalFanoutAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();
    UUID eventId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    store.registerInvalidation(
        eventId,
        AssetAggregateType.EQUIPMENT_CATALOG,
        catalogId,
        2,
        AssetEventType.EQUIPMENT_CATALOG_CHANGED,
        Map.of("equipmentId", catalogId.toString()),
        Map.of("equipmentId", catalogId.toString()),
        OffsetDateTime.now(ZoneOffset.UTC));

    verifyNoInteractions(invalidations);
    TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();

    ArgumentCaptor<AssetInvalidationHub.AssetInvalidationEvent> event =
        ArgumentCaptor.forClass(AssetInvalidationHub.AssetInvalidationEvent.class);
    verify(invalidations).publishGlobal(event.capture());
    assertThat(event.getValue().warehouseId()).isNull();
    assertThat(event.getValue().scope()).isEqualTo("EQUIPMENT_CATALOG_CHANGED");
    assertThat(event.getValue().changeType()).isEqualTo("asset.equipment-catalog.changed.v1");
  }

  private static final class PreviousWarehouseJdbcTemplate extends JdbcTemplate {
    private final UUID warehouseId;

    private PreviousWarehouseJdbcTemplate(UUID warehouseId) {
      this.warehouseId = warehouseId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      assertThat(sql).contains("aggregate_snapshot");
      return (List<T>) List.of(warehouseId.toString());
    }
  }
}
