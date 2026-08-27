package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerInvalidationEvent;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds warehouse-, native-surface- and worker-scoped SSE connections and sends invalidation
 * signals.
 *
 * <p>An event never grants data access or transports a complete task projection. The worker app
 * must reload the authorized feed after receiving it, which preserves authorization and avoids
 * stale task details in a long-lived stream.
 */
@Component
public class WorkerInvalidationHub {
  private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
  private final Map<SubscriptionKey, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
  private final Supplier<SseEmitter> emitterFactory;

  /** Creates production emitters with the bounded native-stream timeout. */
  public WorkerInvalidationHub() {
    this(() -> new SseEmitter(STREAM_TIMEOUT_MILLIS));
  }

  /** Supplies capturing emitters for focused surface-isolation tests. */
  WorkerInvalidationHub(Supplier<SseEmitter> emitterFactory) {
    this.emitterFactory = emitterFactory;
  }

  /** Opens one bounded native-surface stream and immediately emits the current feed revision. */
  public SseEmitter subscribe(
      UUID warehouseId, MobileTaskSurface surface, UUID workerId, long revision) {
    SubscriptionKey key = new SubscriptionKey(warehouseId, surface, workerId);
    SseEmitter emitter = emitterFactory.get();
    emitters.computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
    emitter.onCompletion(() -> remove(key, emitter));
    emitter.onTimeout(() -> remove(key, emitter));
    emitter.onError(ignored -> remove(key, emitter));
    send(
        key,
        emitter,
        new WorkerInvalidationEvent(
            UUID.randomUUID(),
            revision,
            "FEED_CHANGED",
            null,
            OffsetDateTime.now(ZoneOffset.UTC)));
    return emitter;
  }

  /** Broadcasts an action invalidation without leaking its entry ID to the other native surface. */
  public void actionApplied(
      UUID warehouseId,
      MobileTaskSurface actorSurface,
      UUID actorWorkerId,
      UUID entryId,
      long revision) {
    actionApplied(warehouseId, actorSurface, actorWorkerId, entryId, revision, Set.of());
  }

  /** Broadcasts an action and exposes JOIN identity only to eligible WorkerApp streams. */
  public void actionApplied(
      UUID warehouseId,
      MobileTaskSurface actorSurface,
      UUID actorWorkerId,
      UUID entryId,
      long revision,
      Set<UUID> joinWorkerIds) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    emitters.forEach(
        (key, workerEmitters) -> {
          if (!key.warehouseId().equals(warehouseId)) return;
          boolean actor =
              key.surface() == actorSurface && key.workerId().equals(actorWorkerId);
          boolean joinAvailable =
              key.surface() == MobileTaskSurface.WORKER
                  && !key.workerId().equals(actorWorkerId)
                  && joinWorkerIds.contains(key.workerId());
          WorkerInvalidationEvent event =
              new WorkerInvalidationEvent(
                  UUID.randomUUID(),
                  revision,
                  actor
                      ? "ENTRY_CHANGED"
                      : joinAvailable ? "TASK_JOIN_AVAILABLE" : "FEED_CHANGED",
                  actor || joinAvailable ? entryId : null,
                  now);
          workerEmitters.forEach(emitter -> send(key, emitter, event));
        });
  }

  /** Broadcasts a projection-only feed invalidation without exposing an entry identity. */
  public void feedChanged(UUID warehouseId, long revision) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    emitters.forEach(
        (key, workerEmitters) -> {
          if (!key.warehouseId().equals(warehouseId)) return;
          WorkerInvalidationEvent event =
              new WorkerInvalidationEvent(
                  UUID.randomUUID(), revision, "FEED_CHANGED", null, now);
          workerEmitters.forEach(emitter -> send(key, emitter, event));
        });
  }

  /**
   * Announces a newly available current-day task only to workers whose
   * current qualifications or group memberships match the queue. The event
   * remains an invalidation: the app must fetch the authorized feed before it
   * can display any task data.
   */
  public void taskAvailable(
      UUID warehouseId,
      MobileTaskSurface surface,
      Set<UUID> audienceWorkerIds,
      UUID entryId,
      long revision,
      boolean urgent) {
    if (audienceWorkerIds.isEmpty()) return;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String type = urgent ? "URGENT_TASK" : "NEW_TASK";
    audienceWorkerIds.forEach(
        workerId -> {
          SubscriptionKey key = new SubscriptionKey(warehouseId, surface, workerId);
          Set<SseEmitter> workerEmitters = emitters.get(key);
          if (workerEmitters == null) return;
          WorkerInvalidationEvent event =
              new WorkerInvalidationEvent(
                  UUID.randomUUID(), revision, type, entryId, now);
          workerEmitters.forEach(emitter -> send(key, emitter, event));
        });
  }

  @Scheduled(fixedDelayString = "PT15S")
  void keepAlive() {
    emitters.forEach(
        (key, workerEmitters) ->
            workerEmitters.forEach(
                emitter -> {
                  try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                  } catch (IOException | IllegalStateException exception) {
                    remove(key, emitter);
                  }
                }));
  }

  private void send(
      SubscriptionKey key, SseEmitter emitter, WorkerInvalidationEvent event) {
    try {
      emitter.send(
          SseEmitter.event()
              .id(event.eventId().toString())
              .name("worker-invalidation")
              .data(event));
    } catch (IOException | IllegalStateException exception) {
      remove(key, emitter);
    }
  }

  private void remove(SubscriptionKey key, SseEmitter emitter) {
    Set<SseEmitter> workerEmitters = emitters.get(key);
    if (workerEmitters == null) return;
    workerEmitters.remove(emitter);
    if (workerEmitters.isEmpty()) emitters.remove(key, workerEmitters);
  }

  /** Exact warehouse, native capability and authenticated worker owning one SSE subscription set. */
  private record SubscriptionKey(
      UUID warehouseId, MobileTaskSurface surface, UUID workerId) {}
}
