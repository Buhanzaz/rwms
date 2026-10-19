package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

class AuthKafkaConsumersTest {

    private final byte[] payload = "{\"pii\":\"canary@example.test\"}".getBytes();

    @Test
    void exhaustedProcessingHandsOffOnceToDurableDltAndAcknowledgesSource() {
        AuthInboxProcessor processor = mock(AuthInboxProcessor.class);
        AuthSanitizedDltPublisher dlt = mock(AuthSanitizedDltPublisher.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(processor)
                .process(any(), eq(AuthAggregateType.USER_AUTHORIZATION));
        var consumer = new AuthKafkaConsumers().authUserAuthorizationEvents(processor, dlt);

        assertThatCode(() -> consumer.accept(MessageBuilder.withPayload(payload).build()))
                .doesNotThrowAnyException();

        verify(processor, times(4)).process(payload, AuthAggregateType.USER_AUTHORIZATION);
        verify(dlt).publish(AuthAggregateType.USER_AUTHORIZATION, payload, "PROCESSING_FAILED");
    }

    @Test
    void failedDurableDltEnqueuePropagatesSoSourceOffsetCannotCommit() {
        AuthInboxProcessor processor = mock(AuthInboxProcessor.class);
        AuthSanitizedDltPublisher dlt = mock(AuthSanitizedDltPublisher.class);
        doThrow(new AuthEventValidationException())
                .when(processor)
                .process(any(), eq(AuthAggregateType.USER_AUTHORIZATION));
        doThrow(new IllegalStateException("DLT database unavailable"))
                .when(dlt)
                .publish(AuthAggregateType.USER_AUTHORIZATION, payload, "VALIDATION_REJECTED");
        var consumer = new AuthKafkaConsumers().authUserAuthorizationEvents(processor, dlt);

        assertThatThrownBy(() -> consumer.accept(MessageBuilder.withPayload(payload).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DLT database unavailable");
    }
}
