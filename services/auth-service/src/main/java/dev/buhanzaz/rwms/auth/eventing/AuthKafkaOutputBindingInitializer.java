package dev.buhanzaz.rwms.auth.eventing;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.DependsOn;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

/**
 * Binds auth's fixed producer channels only after application singletons are ready.
 *
 * <p>It binds both authoritative fact and sanitized-DLT destinations for each aggregate family,
 * then releases them in reverse order at shutdown. This keeps broker lifecycle work outside the
 * transactional outbox writer.
 */
@Component
@DependsOn("authProductionSafetyValidator")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AuthKafkaOutputBindingInitializer implements SmartInitializingSingleton, DisposableBean {

    private final BindingService bindingService;
    private final ApplicationContext applicationContext;
    private final List<String> boundDestinations = new ArrayList<>();

    /** Binds all destinations declared by every supported auth aggregate family. */
    @Override
    public void afterSingletonsInstantiated() {
        for (AuthAggregateType aggregateType : AuthAggregateType.values()) {
            aggregateType.outputDestinations().forEach(this::bind);
        }
    }

    private void bind(String destination) {
        MessageChannel channel = applicationContext.getBean(destination, MessageChannel.class);
        bindingService.bindProducer(channel, destination);
        boundDestinations.add(destination);
    }

    /** Releases every producer binding acquired during application initialization. */
    @Override
    public void destroy() {
        for (int index = boundDestinations.size() - 1; index >= 0; index--) {
            bindingService.unbindProducers(null, boundDestinations.get(index));
        }
        boundDestinations.clear();
    }
}
