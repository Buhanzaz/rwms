package dev.buhanzaz.rwms.auth.eventing;

import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AuthKafkaConsumers {

    private static final long[] BACKOFF_MILLIS = {0, 1_000, 2_000, 4_000};

    @Bean
    CommonErrorHandler authFailClosedConsumerErrorHandler() {
        return new CommonContainerStoppingErrorHandler();
    }

    @Bean
    Consumer<Message<byte[]>> authUserAuthorizationEvents(
            AuthInboxProcessor processor, AuthSanitizedDltPublisher dlt) {
        return message -> processWithBoundedRetry(
                message.getPayload(), AuthAggregateType.USER_AUTHORIZATION, processor, dlt);
    }

    @Bean
    Consumer<Message<byte[]>> authWorkerAccessEvents(
            AuthInboxProcessor processor, AuthSanitizedDltPublisher dlt) {
        return message -> processWithBoundedRetry(
                message.getPayload(), AuthAggregateType.WORKER_ACCESS, processor, dlt);
    }

    private static void processWithBoundedRetry(
            byte[] payload,
            AuthAggregateType aggregateType,
            AuthInboxProcessor processor,
            AuthSanitizedDltPublisher dlt) {
        for (int attempt = 0; attempt < BACKOFF_MILLIS.length; attempt++) {
            if (attempt > 0) {
                pause(BACKOFF_MILLIS[attempt]);
            }
            try {
                processor.process(payload, aggregateType);
                return;
            } catch (AuthEventValidationException exception) {
                dlt.publish(aggregateType, payload, "VALIDATION_REJECTED");
                return;
            } catch (RuntimeException exception) {
                if (attempt == BACKOFF_MILLIS.length - 1) {
                    dlt.publish(aggregateType, payload, "PROCESSING_FAILED");
                    return;
                }
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Auth Kafka retry was interrupted");
        }
    }
}
