package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Local SSE fan-out fed both by same-instance commits and by the replica-specific Kafka broadcast
 * consumer. PostgreSQL facts/outbox remain durable; a reconnecting browser always performs a
 * scoped read because this channel intentionally has no replay cursor.
 */
@Service
public class AssetInvalidationHub {
  private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
  private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
  private final Supplier<SseEmitter> emitterFactory;

  public AssetInvalidationHub() {
    this(() -> new SseEmitter(STREAM_TIMEOUT_MILLIS));
  }

  AssetInvalidationHub(Supplier<SseEmitter> emitterFactory) {
    this.emitterFactory = emitterFactory;
  }

  public SseEmitter subscribe(UUID warehouseId) {
    SseEmitter emitter = emitterFactory.get();
    emitters.computeIfAbsent(warehouseId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
    Runnable remove = () -> remove(warehouseId, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(ignored -> remove.run());
    send(
        warehouseId,
        emitter,
        new AssetInvalidationEvent(
            UUID.randomUUID(), warehouseId, "RESYNC", null, null, null, 0, Instant.now()));
    return emitter;
  }

  public void publish(AssetInvalidationEvent event) {
    if (event == null || event.warehouseId() == null) return;
    event = normalize(event);
    Set<SseEmitter> warehouseEmitters = emitters.get(event.warehouseId());
    if (warehouseEmitters == null) return;
    for (SseEmitter emitter : Set.copyOf(warehouseEmitters)) {
      send(event.warehouseId(), emitter, event);
    }
  }

  public void publishGlobal(AssetInvalidationEvent event) {
    if (event == null) return;
    event = normalize(event);
    for (Map.Entry<UUID, Set<SseEmitter>> entry : emitters.entrySet()) {
      AssetInvalidationEvent scopedEvent =
          new AssetInvalidationEvent(
              event.eventId(),
              entry.getKey(),
              event.scope(),
              event.changeType(),
              event.aggregateType(),
              event.aggregateId(),
              event.revision(),
              event.occurredAt());
      for (SseEmitter emitter : Set.copyOf(entry.getValue())) {
        send(entry.getKey(), emitter, scopedEvent);
      }
    }
  }

  @Scheduled(fixedDelayString = "PT15S")
  void keepAlive() {
    for (Map.Entry<UUID, Set<SseEmitter>> entry : emitters.entrySet()) {
      for (SseEmitter emitter : Set.copyOf(entry.getValue())) {
        try {
          emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException exception) {
          emitter.completeWithError(exception);
          remove(entry.getKey(), emitter);
        }
      }
    }
  }

  private void send(UUID warehouseId, SseEmitter emitter, AssetInvalidationEvent event) {
    try {
      SseEmitter.SseEventBuilder builder =
          SseEmitter.event()
              .id(event.eventId().toString())
              .name("warehouse-invalidation")
              .data(event);
      emitter.send(builder);
    } catch (IOException | IllegalStateException exception) {
      emitter.completeWithError(exception);
      remove(warehouseId, emitter);
    }
  }

  private void remove(UUID warehouseId, SseEmitter emitter) {
    Set<SseEmitter> warehouseEmitters = emitters.get(warehouseId);
    if (warehouseEmitters == null) return;
    warehouseEmitters.remove(emitter);
    if (warehouseEmitters.isEmpty()) emitters.remove(warehouseId, warehouseEmitters);
  }

  private static AssetInvalidationEvent normalize(AssetInvalidationEvent event) {
    return new AssetInvalidationEvent(
        event.eventId() == null ? UUID.randomUUID() : event.eventId(),
        event.warehouseId(),
        event.scope(),
        event.changeType(),
        event.aggregateType(),
        event.aggregateId(),
        event.revision(),
        event.occurredAt() == null ? Instant.now() : event.occurredAt());
  }

  public record AssetInvalidationEvent(
      UUID eventId,
      UUID warehouseId,
      String scope,
      String changeType,
      AssetAggregateType aggregateType,
      UUID aggregateId,
      long revision,
      Instant occurredAt) {}
}
