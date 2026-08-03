package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class AssetInvalidationHubTest {
  @Test
  void subscriptionStartsWithResyncAndPublishesOnlyToTheAffectedWarehouse() {
    List<CapturingEmitter> created = new ArrayList<>();
    AssetInvalidationHub hub =
        new AssetInvalidationHub(
            () -> {
              CapturingEmitter emitter = new CapturingEmitter();
              created.add(emitter);
              return emitter;
            });
    UUID firstWarehouseId = UUID.randomUUID();
    UUID secondWarehouseId = UUID.randomUUID();

    hub.subscribe(firstWarehouseId);
    hub.subscribe(secondWarehouseId);

    assertThat(created).hasSize(2);
    assertThat(created.get(0).events).singleElement().satisfies(
        event -> {
          assertThat(event.frame()).contains("event:warehouse-invalidation");
          assertThat(event.payload().warehouseId()).isEqualTo(firstWarehouseId);
          assertThat(event.payload().scope()).isEqualTo("RESYNC");
          assertThat(event.payload().changeType()).isNull();
        });
    assertThat(created.get(1).events).singleElement().satisfies(
        event -> assertThat(event.payload().warehouseId()).isEqualTo(secondWarehouseId));

    AssetInvalidationHub.AssetInvalidationEvent changed =
        new AssetInvalidationHub.AssetInvalidationEvent(
            UUID.randomUUID(),
            firstWarehouseId,
            "RENTAL_ITEMS_CHANGED",
            "asset.rental-item.status-changed.v1",
            AssetAggregateType.RENTAL_ITEM,
            UUID.randomUUID(),
            3,
            Instant.now());
    hub.publish(changed);

    assertThat(created.get(0).events).hasSize(2);
    assertThat(created.get(0).events.get(1).payload()).isEqualTo(changed);
    assertThat(created.get(1).events).hasSize(1);
  }

  @Test
  void globalInvalidationIsScopedForEverySubscriberAndSerializesChangeType() throws Exception {
    List<CapturingEmitter> created = new ArrayList<>();
    AssetInvalidationHub hub =
        new AssetInvalidationHub(
            () -> {
              CapturingEmitter emitter = new CapturingEmitter();
              created.add(emitter);
              return emitter;
            });
    UUID firstWarehouseId = UUID.randomUUID();
    UUID secondWarehouseId = UUID.randomUUID();
    hub.subscribe(firstWarehouseId);
    hub.subscribe(secondWarehouseId);
    AssetInvalidationHub.AssetInvalidationEvent catalogChanged =
        new AssetInvalidationHub.AssetInvalidationEvent(
            UUID.randomUUID(),
            null,
            "EQUIPMENT_CATALOG_CHANGED",
            "asset.equipment-catalog.changed.v1",
            AssetAggregateType.EQUIPMENT_CATALOG,
            UUID.randomUUID(),
            4,
            Instant.now());

    hub.publishGlobal(catalogChanged);

    assertThat(created.get(0).events.get(1).payload().warehouseId()).isEqualTo(firstWarehouseId);
    assertThat(created.get(1).events.get(1).payload().warehouseId()).isEqualTo(secondWarehouseId);
    assertThat(new ObjectMapper().writeValueAsString(created.get(0).events.get(1).payload()))
        .contains("\"changeType\":\"asset.equipment-catalog.changed.v1\"");
  }

  @Test
  void publishFillsMissingEventIdentityBeforeWritingSseFrame() {
    List<CapturingEmitter> created = new ArrayList<>();
    AssetInvalidationHub hub =
        new AssetInvalidationHub(
            () -> {
              CapturingEmitter emitter = new CapturingEmitter();
              created.add(emitter);
              return emitter;
            });
    UUID warehouseId = UUID.randomUUID();
    hub.subscribe(warehouseId);

    hub.publish(
        new AssetInvalidationHub.AssetInvalidationEvent(
            null,
            warehouseId,
            "RENTAL_ITEMS_CHANGED",
            "asset.rental-item.status-changed.v1",
            AssetAggregateType.RENTAL_ITEM,
            UUID.randomUUID(),
            1,
            null));

    assertThat(created.getFirst().events).hasSize(2);
    assertThat(created.getFirst().events.get(1).payload().eventId()).isNotNull();
    assertThat(created.getFirst().events.get(1).payload().occurredAt()).isNotNull();
  }

  private static final class CapturingEmitter extends SseEmitter {
    private final List<CapturedEvent> events = new ArrayList<>();

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      Set<DataWithMediaType> values = builder.build();
      String frame =
          values.stream()
              .map(DataWithMediaType::getData)
              .filter(String.class::isInstance)
              .map(String.class::cast)
              .reduce("", String::concat);
      AssetInvalidationHub.AssetInvalidationEvent payload =
          values.stream()
              .map(DataWithMediaType::getData)
              .filter(AssetInvalidationHub.AssetInvalidationEvent.class::isInstance)
              .map(AssetInvalidationHub.AssetInvalidationEvent.class::cast)
              .findFirst()
              .orElseThrow();
      events.add(new CapturedEvent(frame, payload));
    }
  }

  private record CapturedEvent(
      String frame, AssetInvalidationHub.AssetInvalidationEvent payload) {}
}
