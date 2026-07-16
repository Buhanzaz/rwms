package dev.buhanzaz.rwms.taskboard.eventing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

class TaskBoardKafkaConsumersTest {
  private final byte[] payload =
      "{\"pii\":\"canary@example.test\"}".getBytes(StandardCharsets.UTF_8);

  @Test
  void validationFailureHandsOffOnceToDurableSanitizedDlt() {
    TaskBoardInboxProcessor processor = mock(TaskBoardInboxProcessor.class);
    TaskBoardSanitizedDltPublisher dlt = mock(TaskBoardSanitizedDltPublisher.class);
    doThrow(new TaskBoardEventValidationException())
        .when(processor)
        .process(any(), eq(TaskBoardAggregateType.WORKER));
    var consumer = new TaskBoardKafkaConsumers().taskBoardWorkerEvents(processor, dlt);

    assertThatCode(() -> consumer.accept(MessageBuilder.withPayload(payload).build()))
        .doesNotThrowAnyException();

    verify(processor).process(payload, TaskBoardAggregateType.WORKER);
    verify(dlt).publish(TaskBoardAggregateType.WORKER, payload, "VALIDATION_REJECTED");
  }

  @Test
  void failedDurableDltEnqueuePreventsSourceOffsetCommit() {
    TaskBoardInboxProcessor processor = mock(TaskBoardInboxProcessor.class);
    TaskBoardSanitizedDltPublisher dlt = mock(TaskBoardSanitizedDltPublisher.class);
    doThrow(new TaskBoardEventValidationException())
        .when(processor)
        .process(any(), eq(TaskBoardAggregateType.WORKER));
    doThrow(new IllegalStateException("DLT database unavailable"))
        .when(dlt)
        .publish(TaskBoardAggregateType.WORKER, payload, "VALIDATION_REJECTED");
    var consumer = new TaskBoardKafkaConsumers().taskBoardWorkerEvents(processor, dlt);

    assertThatThrownBy(() -> consumer.accept(MessageBuilder.withPayload(payload).build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DLT database unavailable");
  }
}
