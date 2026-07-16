package dev.buhanzaz.rwms.auth.eventing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cloud.stream.binder.kafka.KafkaListenerContainerCustomizer;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;

final class AuthKafkaConsumerLifecycleRegistry implements KafkaListenerContainerCustomizer {

    private static final String CONSUMER_GROUP = "auth-shadow-v1";

    private final Map<AuthAggregateType, AbstractMessageListenerContainer<?, ?>> containers =
            new ConcurrentHashMap<>();

    @Override
    public void configure(
            AbstractMessageListenerContainer<?, ?> container,
            String destinationName,
            String group) {
        if (!CONSUMER_GROUP.equals(group)) {
            return;
        }
        for (AuthAggregateType aggregateType : AuthAggregateType.values()) {
            if (aggregateType.topic().equals(destinationName)) {
                containers.put(aggregateType, container);
                return;
            }
        }
    }

    boolean isRegistered(AuthAggregateType aggregateType) {
        return containers.containsKey(aggregateType);
    }

    boolean isRunning(AuthAggregateType aggregateType) {
        AbstractMessageListenerContainer<?, ?> container = containers.get(aggregateType);
        return container != null && container.isRunning();
    }
}
