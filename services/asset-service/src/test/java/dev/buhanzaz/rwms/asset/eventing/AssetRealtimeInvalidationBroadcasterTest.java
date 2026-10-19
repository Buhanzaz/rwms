package dev.buhanzaz.rwms.asset.eventing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AssetRealtimeInvalidationBroadcasterTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AssetInvalidationHub hub = mock(AssetInvalidationHub.class);
  private final AssetRealtimeInvalidationBroadcaster broadcaster =
      new AssetRealtimeInvalidationBroadcaster(
          mapper, new AssetEventPayloadPolicy(mapper), hub);

  @Test
  void broadcastsOneCommittedMovementToBothWarehouseScopes() throws Exception {
    UUID movementId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("movementId", movementId.toString());
    payload.put("equipmentId", UUID.randomUUID().toString());
    payload.put("sourceBalanceId", UUID.randomUUID().toString());
    payload.put("targetBalanceId", UUID.randomUUID().toString());
    payload.put("quantity", 2);
    payload.put("movementKind", "WAREHOUSE_TO_WAREHOUSE");
    payload.put("equipmentCategory", "FURNITURE");
    payload.put("sourceWarehouseId", sourceWarehouseId.toString());
    payload.put("sourceRentalItemId", null);
    payload.put("sourceLocationKind", "STOCK");
    payload.put("targetWarehouseId", targetWarehouseId.toString());
    payload.put("targetRentalItemId", null);
    payload.put("targetLocationKind", "STOCK");

    broadcaster.broadcast(
        envelope(
            movementId,
            AssetAggregateType.EQUIPMENT_MOVEMENT,
            AssetEventType.EQUIPMENT_TRANSFERRED,
            payload),
        AssetAggregateType.EQUIPMENT_MOVEMENT);

    ArgumentCaptor<AssetInvalidationHub.AssetInvalidationEvent> events =
        ArgumentCaptor.forClass(AssetInvalidationHub.AssetInvalidationEvent.class);
    verify(hub, times(2)).publish(events.capture());
    org.assertj.core.api.Assertions.assertThat(
            events.getAllValues().stream()
                .map(AssetInvalidationHub.AssetInvalidationEvent::warehouseId))
        .containsExactlyInAnyOrder(sourceWarehouseId, targetWarehouseId);
    verify(hub, never()).publishGlobal(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void warehouseChangeUsesGlobalRefreshBecauseTheFactHasNoSourceWarehouse() throws Exception {
    UUID rentalItemId = UUID.randomUUID();
    Map<String, Object> payload =
        Map.of(
            "rentalItemId", rentalItemId.toString(),
            "warehouseId", UUID.randomUUID().toString(),
            "status", "FREE",
            "numberSha256", "0".repeat(64));

    broadcaster.broadcast(
        envelope(
            rentalItemId,
            AssetAggregateType.RENTAL_ITEM,
            AssetEventType.RENTAL_ITEM_WAREHOUSE_CHANGED,
            payload),
        AssetAggregateType.RENTAL_ITEM);

    verify(hub).publishGlobal(org.mockito.ArgumentMatchers.any());
    verify(hub, never()).publish(org.mockito.ArgumentMatchers.any());
  }

  private byte[] envelope(
      UUID aggregateId,
      AssetAggregateType aggregateType,
      AssetEventType eventType,
      Map<String, Object> payload)
      throws Exception {
    Instant now = Instant.now();
    return mapper.writeValueAsBytes(
        new DomainEventEnvelopeV2<>(
            2,
            UUID.randomUUID(),
            eventType.value(),
            1,
            now,
            now,
            "asset-service",
            aggregateType.name(),
            aggregateId.toString(),
            1,
            new CorrelationContext(UUID.randomUUID(), null),
            null,
            payload));
  }
}
