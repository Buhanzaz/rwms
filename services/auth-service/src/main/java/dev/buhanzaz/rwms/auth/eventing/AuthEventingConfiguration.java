package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.JsonNode;

/**
 * Wires auth eventing infrastructure and registers one payload validator per public fact type.
 *
 * <p>The shared Kafka layer resolves validators by event type before publication. Keeping these
 * beans explicit makes every supported auth fact pass the same strict payload policy while
 * leaving business transitions in their owning application services.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({AuthOutboxProperties.class, RwmsKafkaProperties.class})
public class AuthEventingConfiguration {

    /**
     * Registers validation for initial user-authorization facts.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#USER_CREATED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authUserCreatedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_CREATED, policy);
    }

    /**
     * Registers validation for changed user-authorization facts.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#USER_CHANGED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authUserChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_CHANGED, policy);
    }

    /**
     * Registers validation for user-profile-change authorization snapshots.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#USER_PROFILE_CHANGED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authUserProfileChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_PROFILE_CHANGED, policy);
    }

    /**
     * Registers validation for password-change authorization snapshots.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#USER_PASSWORD_CHANGED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authUserPasswordChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_PASSWORD_CHANGED, policy);
    }

    /**
     * Registers validation for warehouse-grant-change user snapshots.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#USER_GRANTS_CHANGED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authUserGrantsChangedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_GRANTS_CHANGED, policy);
    }

    /**
     * Registers validation for bootstrapped user-authorization facts.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#USER_BOOTSTRAPPED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authUserBootstrappedPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.USER_BOOTSTRAPPED, policy);
    }

    /**
     * Registers validation for configured worker-access facts.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#WORKER_CONFIGURED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authWorkerConfiguredPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.WORKER_CONFIGURED, policy);
    }

    /**
     * Registers validation for worker password-reset access snapshots.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#WORKER_PASSWORD_RESET}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authWorkerPasswordResetPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.WORKER_PASSWORD_RESET, policy);
    }

    /**
     * Registers validation for disabled worker-access facts.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#WORKER_DISABLED}
     */
    @Bean
    RwmsKafkaPayloadSafetyValidator authWorkerDisabledPayloadValidator(AuthEventPayloadPolicy policy) {
        return validator(AuthEventTypes.WORKER_DISABLED, policy);
    }

    /**
     * Registers validation for terminal worker-deletion facts.
     *
     * @param policy auth payload policy
     * @return validator bound to {@link AuthEventTypes#WORKER_DELETED}
     */
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
