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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class WorkerInvalidationHub {
  private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
  private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public SseEmitter subscribe(UUID workerId, long revision) {
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
    emitters.computeIfAbsent(workerId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
    emitter.onCompletion(() -> remove(workerId, emitter));
    emitter.onTimeout(() -> remove(workerId, emitter));
    emitter.onError(ignored -> remove(workerId, emitter));
    send(
        workerId,
        emitter,
        new WorkerInvalidationEvent(
            UUID.randomUUID(),
            revision,
            "FEED_CHANGED",
            null,
            OffsetDateTime.now(ZoneOffset.UTC)));
    return emitter;
  }

  public void actionApplied(UUID actorWorkerId, UUID entryId, long revision) {
    actionApplied(actorWorkerId, entryId, revision, Set.of());
  }

  public void actionApplied(
      UUID actorWorkerId,
      UUID entryId,
      long revision,
      Set<UUID> joinWorkerIds) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    emitters.forEach(
        (workerId, workerEmitters) -> {
          boolean actor = workerId.equals(actorWorkerId);
          boolean joinAvailable = !actor && joinWorkerIds.contains(workerId);
          WorkerInvalidationEvent event =
              new WorkerInvalidationEvent(
                  UUID.randomUUID(),
                  revision,
                  actor
                      ? "ENTRY_CHANGED"
                      : joinAvailable ? "TASK_JOIN_AVAILABLE" : "FEED_CHANGED",
                  actor || joinAvailable ? entryId : null,
                  now);
          workerEmitters.forEach(emitter -> send(workerId, emitter, event));
        });
  }

  /**
   * Announces a newly available current-day task only to workers whose
   * current qualifications or group memberships match the queue. The event
   * remains an invalidation: the app must fetch the authorized feed before it
   * can display any task data.
   */
  public void taskAvailable(
      Set<UUID> audienceWorkerIds, UUID entryId, long revision, boolean urgent) {
    if (audienceWorkerIds.isEmpty()) return;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String type = urgent ? "URGENT_TASK" : "NEW_TASK";
    audienceWorkerIds.forEach(
        workerId -> {
          Set<SseEmitter> workerEmitters = emitters.get(workerId);
          if (workerEmitters == null) return;
          WorkerInvalidationEvent event =
              new WorkerInvalidationEvent(
                  UUID.randomUUID(), revision, type, entryId, now);
          workerEmitters.forEach(emitter -> send(workerId, emitter, event));
        });
  }

  @Scheduled(fixedDelayString = "PT15S")
  void keepAlive() {
    emitters.forEach(
        (workerId, workerEmitters) ->
            workerEmitters.forEach(
                emitter -> {
                  try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                  } catch (IOException | IllegalStateException exception) {
                    remove(workerId, emitter);
                  }
                }));
  }

  private void send(UUID workerId, SseEmitter emitter, WorkerInvalidationEvent event) {
    try {
      emitter.send(
          SseEmitter.event()
              .id(event.eventId().toString())
              .name("worker-invalidation")
              .data(event));
    } catch (IOException | IllegalStateException exception) {
      remove(workerId, emitter);
    }
  }

  private void remove(UUID workerId, SseEmitter emitter) {
    Set<SseEmitter> workerEmitters = emitters.get(workerId);
    if (workerEmitters == null) return;
    workerEmitters.remove(emitter);
    if (workerEmitters.isEmpty()) emitters.remove(workerId, workerEmitters);
  }
}
