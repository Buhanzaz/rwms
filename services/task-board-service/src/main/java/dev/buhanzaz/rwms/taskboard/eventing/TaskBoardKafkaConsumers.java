package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.messaging.Message;

/**
 * Declares task-board Kafka consumers with bounded processing retry and fail-closed container policy.
 *
 * <p>Validation and exhausted processing failures enqueue sanitized DLT metadata rather than raw
 * source records.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class TaskBoardKafkaConsumers {
  private static final long[] BACKOFF_MILLIS = {0, 1_000, 2_000, 4_000};

  @Bean
  CommonErrorHandler taskBoardFailClosedConsumerErrorHandler() {
    return new CommonContainerStoppingErrorHandler();
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardWorkerClassEvents(
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return consumer(TaskBoardAggregateType.WORKER_CLASS, processor, dlt);
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardWorkerEvents(
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return consumer(TaskBoardAggregateType.WORKER, processor, dlt);
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardWorkerGroupEvents(
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return consumer(TaskBoardAggregateType.WORKER_GROUP, processor, dlt);
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardWorkQueueEvents(
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return consumer(TaskBoardAggregateType.WORK_QUEUE, processor, dlt);
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardQueueUsageReferenceEvents(
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return consumer(TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, processor, dlt);
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardBoardTaskEvents(
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return consumer(TaskBoardAggregateType.BOARD_TASK, processor, dlt);
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardQueueEntryEvents(
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return consumer(TaskBoardAggregateType.QUEUE_ENTRY, processor, dlt);
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardMediaEvents(WorkerMediaEventProcessor processor) {
    return message -> processor.process(message.getPayload());
  }

  @Bean
  Consumer<Message<byte[]>> taskBoardWarehouseEvents(
      WarehouseMetadataEventProcessor processor) {
    return message -> processor.process(message.getPayload());
  }

  private Consumer<Message<byte[]>> consumer(TaskBoardAggregateType type,
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    return message -> processWithBoundedRetry(message.getPayload(), type, processor, dlt);
  }

  private void processWithBoundedRetry(byte[] payload, TaskBoardAggregateType type,
      TaskBoardInboxProcessor processor, TaskBoardSanitizedDltPublisher dlt) {
    for (int attempt = 0; attempt < BACKOFF_MILLIS.length; attempt++) {
      if (attempt > 0) pause(BACKOFF_MILLIS[attempt]);
      try {
        processor.process(payload, type);
        return;
      } catch (TaskBoardEventValidationException exception) {
        dlt.publish(type, payload, "VALIDATION_REJECTED");
        return;
      } catch (RuntimeException exception) {
        if (attempt == BACKOFF_MILLIS.length - 1) {
          dlt.publish(type, payload, "PROCESSING_FAILED");
          return;
        }
      }
    }
  }

  private void pause(long millis) {
    try { Thread.sleep(millis); }
    catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Task-board Kafka retry was interrupted");
    }
  }
}
