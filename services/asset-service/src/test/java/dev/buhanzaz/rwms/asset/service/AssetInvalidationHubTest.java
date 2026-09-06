package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  void replacementSubscriptionSurvivesConcurrentLastSubscriberCleanup() throws Exception {
    List<CapturingEmitter> created = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch replacementFactoryCalled = new CountDownLatch(1);
    AtomicInteger emitterCount = new AtomicInteger();
    AssetInvalidationHub hub =
        new AssetInvalidationHub(
            () -> {
              CapturingEmitter emitter = new CapturingEmitter();
              if (emitterCount.incrementAndGet() == 2) replacementFactoryCalled.countDown();
              created.add(emitter);
              return emitter;
            });
    UUID warehouseId = UUID.randomUUID();
    CapturingEmitter initial = (CapturingEmitter) hub.subscribe(warehouseId);
    LastRemovalBarrierSet guardedSet = new LastRemovalBarrierSet(initial);
    emitterMap(hub).put(warehouseId, guardedSet);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> cleanup = executor.submit(initial::completeSubscription);
      await(guardedSet.emptyObserved);
      Future<SseEmitter> replacement = executor.submit(() -> hub.subscribe(warehouseId));
      await(replacementFactoryCalled);
      assertThat(guardedSet.replacementAdded.await(200, TimeUnit.MILLISECONDS)).isFalse();

      guardedSet.releaseCleanup.countDown();
      replacement.get(5, TimeUnit.SECONDS);
      cleanup.get(5, TimeUnit.SECONDS);
    } finally {
      guardedSet.releaseCleanup.countDown();
      executor.shutdownNow();
    }

    hub.publish(
        new AssetInvalidationHub.AssetInvalidationEvent(
            UUID.randomUUID(),
            warehouseId,
            "RENTAL_ITEMS_CHANGED",
            "asset.rental-item.status-changed.v1",
            AssetAggregateType.RENTAL_ITEM,
            UUID.randomUUID(),
            1,
            Instant.now()));

    assertThat(created.get(1).events).hasSize(2);
    assertThat(created.get(1).events.getLast().payload().scope()).isEqualTo("RENTAL_ITEMS_CHANGED");
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, Set<SseEmitter>> emitterMap(AssetInvalidationHub hub) throws Exception {
    Field field = AssetInvalidationHub.class.getDeclaredField("emitters");
    field.setAccessible(true);
    return (Map<UUID, Set<SseEmitter>>) field.get(hub);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  private static final class LastRemovalBarrierSet extends CopyOnWriteArraySet<SseEmitter> {
    private final CountDownLatch emptyObserved = new CountDownLatch(1);
    private final CountDownLatch releaseCleanup = new CountDownLatch(1);
    private final CountDownLatch replacementAdded = new CountDownLatch(1);
    private final AtomicBoolean blockFirstEmptyCheck = new AtomicBoolean(true);
    private volatile boolean observeReplacement;

    LastRemovalBarrierSet(SseEmitter initial) {
      super.add(initial);
      observeReplacement = true;
    }

    @Override
    public boolean add(SseEmitter emitter) {
      boolean added = super.add(emitter);
      if (added && observeReplacement) replacementAdded.countDown();
      return added;
    }

    @Override
    public boolean isEmpty() {
      boolean empty = super.isEmpty();
      if (empty && blockFirstEmptyCheck.compareAndSet(true, false)) {
        emptyObserved.countDown();
        try {
          if (!releaseCleanup.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to release last-subscriber cleanup");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AssertionError(exception);
        }
      }
      return empty;
    }
  }

  private static final class CapturingEmitter extends SseEmitter {
    private final List<CapturedEvent> events = new ArrayList<>();
    private Runnable completion;

    @Override
    public void onCompletion(Runnable callback) {
      completion = callback;
    }

    void completeSubscription() {
      if (completion == null) throw new IllegalStateException("Subscription callback was not set");
      completion.run();
    }

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
