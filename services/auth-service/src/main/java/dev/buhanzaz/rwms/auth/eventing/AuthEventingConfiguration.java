package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.JsonNode;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AuthOutboxProperties.class, RwmsKafkaProperties.class})
public class AuthEventingConfiguration {

    @Bean
    RwmsKafkaPayloadSafetyValidator authUserCreatedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_CREATED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authUserChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_CHANGED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authUserProfileChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_PROFILE_CHANGED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authUserPasswordChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_PASSWORD_CHANGED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authUserGrantsChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_GRANTS_CHANGED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authUserBootstrappedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_BOOTSTRAPPED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authWorkerConfiguredPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.WORKER_CONFIGURED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authWorkerPasswordResetPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.WORKER_PASSWORD_RESET, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authWorkerDisabledPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.WORKER_DISABLED, policy);
    }

    @Bean
    RwmsKafkaPayloadSafetyValidator authWorkerDeletedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.WORKER_DELETED, policy);
    }

    private static RwmsKafkaPayloadSafetyValidator validator(
            String eventType, AuthEventPayloadPolicy policy) {
        return new RwmsKafkaPayloadSafetyValidator() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public void validate(JsonNode payload) {
                policy.validateNode(eventType, payload);
            }
        };
    }
}
