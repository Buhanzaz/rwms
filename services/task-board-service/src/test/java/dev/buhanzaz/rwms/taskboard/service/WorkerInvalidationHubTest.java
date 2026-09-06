package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerInvalidationEvent;
import java.io.IOException;
import java.lang.reflect.Field;
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

/** Verifies that SSE signals cannot cross warehouse, WorkerApp or DriverApp boundaries. */
class WorkerInvalidationHubTest {

  @Test
  void entryIdentityAndJoinSignalsStayOnTheirAuthorizedNativeSurface() {
    List<CapturingEmitter> created = new ArrayList<>();
    WorkerInvalidationHub hub =
        new WorkerInvalidationHub(
            () -> {
              CapturingEmitter emitter = new CapturingEmitter();
              created.add(emitter);
              return emitter;
            });
    UUID actor = UUID.randomUUID();
    UUID slinger = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID warehouseOne = UUID.randomUUID();
    UUID warehouseTwo = UUID.randomUUID();

    hub.subscribe(warehouseOne, MobileTaskSurface.WORKER, actor, 10);
    hub.subscribe(warehouseOne, MobileTaskSurface.DRIVER, actor, 10);
    hub.subscribe(warehouseOne, MobileTaskSurface.WORKER, slinger, 10);
    hub.subscribe(warehouseOne, MobileTaskSurface.DRIVER, slinger, 10);
    hub.subscribe(warehouseTwo, MobileTaskSurface.WORKER, actor, 20);

    assertThat(created).hasSize(5);
    assertThat(created)
        .allSatisfy(
            emitter ->
                assertThat(emitter.events)
                    .singleElement()
                    .satisfies(
                        event -> {
                          assertThat(event.type()).isEqualTo("FEED_CHANGED");
                          assertThat(event.entryId()).isNull();
                        }));

    hub.actionApplied(
        warehouseOne, MobileTaskSurface.DRIVER, actor, entryId, 11, Set.of(slinger));

    assertEvent(created.get(0), "FEED_CHANGED", null);
    assertEvent(created.get(1), "ENTRY_CHANGED", entryId);
    assertEvent(created.get(2), "TASK_JOIN_AVAILABLE", entryId);
    assertEvent(created.get(3), "FEED_CHANGED", null);
    assertThat(created.get(4).events).hasSize(1);

    UUID driverEntryId = UUID.randomUUID();
    hub.taskAvailable(
        warehouseOne,
        MobileTaskSurface.DRIVER,
        Set.of(slinger),
        driverEntryId,
        12,
        false);

    assertThat(created.get(2).events).hasSize(2);
    assertEvent(created.get(3), "NEW_TASK", driverEntryId);

    UUID workerEntryId = UUID.randomUUID();
    hub.taskAvailable(
        warehouseOne,
        MobileTaskSurface.WORKER,
        Set.of(slinger),
        workerEntryId,
        13,
        true);

    assertEvent(created.get(2), "URGENT_TASK", workerEntryId);
    assertThat(created.get(3).events).hasSize(3);
    assertThat(created.get(4).events).hasSize(1);

    hub.feedChanged(warehouseTwo, 21);

    assertThat(created.get(0).events).hasSize(2);
    assertThat(created.get(1).events).hasSize(2);
    assertThat(created.get(2).events).hasSize(3);
    assertThat(created.get(3).events).hasSize(3);
    assertEvent(created.get(4), "FEED_CHANGED", null);
    assertThat(created.get(4).events.getLast().revision()).isEqualTo(21);
  }

  @Test
  void replacementSubscriptionSurvivesConcurrentLastSubscriberCleanup() throws Exception {
    List<CapturingEmitter> created = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch replacementFactoryCalled = new CountDownLatch(1);
    AtomicInteger emitterCount = new AtomicInteger();
    WorkerInvalidationHub hub =
        new WorkerInvalidationHub(
            () -> {
              CapturingEmitter emitter = new CapturingEmitter();
              if (emitterCount.incrementAndGet() == 2) replacementFactoryCalled.countDown();
              created.add(emitter);
              return emitter;
            });
    UUID warehouseId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    CapturingEmitter initial =
        (CapturingEmitter) hub.subscribe(warehouseId, MobileTaskSurface.WORKER, workerId, 1);
    Map<Object, Set<SseEmitter>> emitterMap = emitterMap(hub);
    Object key = emitterMap.keySet().iterator().next();
    LastRemovalBarrierSet guardedSet = new LastRemovalBarrierSet(initial);
    emitterMap.put(key, guardedSet);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> cleanup = executor.submit(initial::completeSubscription);
      await(guardedSet.emptyObserved);
      Future<SseEmitter> replacement =
          executor.submit(
              () -> hub.subscribe(warehouseId, MobileTaskSurface.WORKER, workerId, 2));
      await(replacementFactoryCalled);
      assertThat(guardedSet.replacementAdded.await(200, TimeUnit.MILLISECONDS)).isFalse();

      guardedSet.releaseCleanup.countDown();
      replacement.get(5, TimeUnit.SECONDS);
      cleanup.get(5, TimeUnit.SECONDS);
    } finally {
      guardedSet.releaseCleanup.countDown();
      executor.shutdownNow();
    }

    hub.feedChanged(warehouseId, 3);

    assertThat(created.get(1).events).hasSize(2);
    assertEvent(created.get(1), "FEED_CHANGED", null);
    assertThat(created.get(1).events.getLast().revision()).isEqualTo(3);
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Set<SseEmitter>> emitterMap(WorkerInvalidationHub hub) throws Exception {
    Field field = WorkerInvalidationHub.class.getDeclaredField("emitters");
    field.setAccessible(true);
    return (Map<Object, Set<SseEmitter>>) field.get(hub);
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

  private static void assertEvent(
      CapturingEmitter emitter, String type, UUID entryId) {
    assertThat(emitter.events.getLast())
        .satisfies(
            event -> {
              assertThat(event.type()).isEqualTo(type);
              assertThat(event.entryId()).isEqualTo(entryId);
            });
  }

  /** Captures structured SSE payloads without a servlet response. */
  private static final class CapturingEmitter extends SseEmitter {
    private final List<WorkerInvalidationEvent> events = new ArrayList<>();
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
      builder.build().stream()
          .map(DataWithMediaType::getData)
          .filter(WorkerInvalidationEvent.class::isInstance)
          .map(WorkerInvalidationEvent.class::cast)
          .findFirst()
          .ifPresent(events::add);
    }
  }
}
