package dev.buhanzaz.rwms.auth.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

/**
 * Declares the named Spring Cloud Stream producer channels used by auth eventing.
 *
 * <p>Bindings are created explicitly after singleton initialization so the fixed authoritative
 * and sanitized-DLT destinations are available only when Kafka support is enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class AuthKafkaOutputChannelsConfiguration {

    /**
     * Creates the producer channel for user-authorization facts.
     *
     * @return the unbound named output channel
     */
    @Bean(name = "rwms.auth.user-authorization.v1")
    MessageChannel authUserAuthorizationOutputChannel() {
        return new DirectWithAttributesChannel();
    }

    /**
     * Creates the producer channel for worker-access facts.
     *
     * @return the unbound named output channel
     */
    @Bean(name = "rwms.auth.worker-access.v1")
    MessageChannel authWorkerAccessOutputChannel() {
        return new DirectWithAttributesChannel();
    }

    /**
     * Creates the producer channel for sanitized user-authorization rejection metadata.
     *
     * @return the unbound named DLT output channel
     */
    @Bean(name = "rwms.auth.user-authorization.v1.auth-shadow-v1.dlt")
    MessageChannel authUserAuthorizationDltOutputChannel() {
        return new DirectWithAttributesChannel();
    }

    /**
     * Creates the producer channel for sanitized worker-access rejection metadata.
     *
     * @return the unbound named DLT output channel
     */
    @Bean(name = "rwms.auth.worker-access.v1.auth-shadow-v1.dlt")
    MessageChannel authWorkerAccessDltOutputChannel() {
        return new DirectWithAttributesChannel();
    }
}
