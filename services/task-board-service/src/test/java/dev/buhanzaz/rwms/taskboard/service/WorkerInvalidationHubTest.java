package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerInvalidationEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
