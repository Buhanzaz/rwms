package dev.buhanzaz.rwms.auth.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class AuthKafkaOutputChannelsConfiguration {

    @Bean(name = "rwms.auth.user-authorization.v1")
    MessageChannel authUserAuthorizationOutputChannel() {
        return new DirectWithAttributesChannel();
    }

    @Bean(name = "rwms.auth.worker-access.v1")
    MessageChannel authWorkerAccessOutputChannel() {
        return new DirectWithAttributesChannel();
    }

    @Bean(name = "rwms.auth.user-authorization.v1.auth-shadow-v1.dlt")
    MessageChannel authUserAuthorizationDltOutputChannel() {
        return new DirectWithAttributesChannel();
    }

    @Bean(name = "rwms.auth.worker-access.v1.auth-shadow-v1.dlt")
    MessageChannel authWorkerAccessDltOutputChannel() {
        return new DirectWithAttributesChannel();
    }
}
