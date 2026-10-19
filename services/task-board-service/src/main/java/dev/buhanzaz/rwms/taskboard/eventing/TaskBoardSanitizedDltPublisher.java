package dev.buhanzaz.rwms.taskboard.eventing;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reduces a rejected record to checksum/failure metadata and enqueues it for the exact sanitized DLT. */
@Component
@RequiredArgsConstructor
public class TaskBoardSanitizedDltPublisher {
  private final TaskBoardSanitizedDltStore store;
  private final TaskBoardEventingMetrics metrics;

  public void publish(TaskBoardAggregateType aggregateType, byte[] rejectedMessage, String failureCode) {
    String hash = TaskBoardEventStore.sha256(rejectedMessage);
    store.enqueue(
        aggregateType.sanitizedDltTopic(),
        hash,
        failureCode,
        Map.of("failureCode", failureCode, "messageSha256", hash,
            "recordedAt", Instant.now().toString()));
    metrics.sanitizedDltEnqueued();
  }
}
