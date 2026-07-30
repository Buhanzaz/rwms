package dev.buhanzaz.rwms.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

class AuthorizationServerConfigurationTest {

    private final AuthorizationServerConfiguration configuration = new AuthorizationServerConfiguration();

    @Test
    void rejectsWorkerForPanelAndUserForWorkerClient() {
        var clients = new OAuthClientProperties(List.of(publicClient("rwms-panel", PrincipalType.USER),
                publicClient("rwms-worker-android", PrincipalType.WORKER)));
        assertThatThrownBy(() -> configuration.validateClientPrincipal(
                        "rwms-panel", PrincipalType.WORKER, clients))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("rwms-panel");
        assertThatThrownBy(() -> configuration.validateClientPrincipal(
                        "rwms-worker-android", PrincipalType.USER, clients))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("rwms-worker-android");
        assertThatThrownBy(() -> configuration.validateClientPrincipal(
                        "unconfigured-client", PrincipalType.USER, clients))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("unconfigured-client");
    }

    @Test
    void productionRequiresExternalSigningKey() {
        var properties = new AuthProperties(
                "https://auth.example.test",
                "https://panel.example.test",
                "https://panel.example.test/auth/callback",
                "https://panel.example.test/",
                "https://worker.example.test",
                "https://worker.example.test/oauth/callback",
                "https://worker.example.test/logout",
                false,
                "admin",
                "long-production-password",
                "",
                "",
                "rwms-auth");

        assertThatThrownBy(() -> configuration.jwkSource(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_SIGNING_KEY_STORE");
    }

    @Test
    void productionRejectsHttpTransportConfiguration() {
        var properties = new AuthProperties(
                "http://localhost:9000",
                "http://localhost:8080",
                "http://localhost:8080/auth/callback",
                "http://localhost:8080/",
                "http://localhost:8082",
                "http://localhost:8082/auth/callback",
                "http://localhost:8082/",
                false,
                "admin",
                "long-production-password",
                "auth.p12",
                "password",
                "rwms-auth");

        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        properties, productionKafkaEnvironment(), productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_ISSUER");
    }

    @Test
    void productionProfileRejectsDevelopmentDefaults() {
        var properties = propertiesWithDevelopmentDefaults();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        properties, environment, productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev или test");
    }

    @Test
    void productionProfileTakesPrecedenceOverMixedDevelopmentProfiles() {
        List.of(
                        new String[] {"production", "test"},
                        new String[] {"prod", "dev"})
                .forEach(profiles -> {
                    var environment = new MockEnvironment();
                    environment.setActiveProfiles(profiles);

                    assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                                    propertiesWithDevelopmentDefaults(), environment, productionKafkaProperties())
                            .afterPropertiesSet())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("dev или test");
                });
    }

    @Test
    void productionProfileStillRequiresKafkaWhenTestProfileIsAlsoActive() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production", "test");

        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), environment, disabledKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_KAFKA_ENABLED");
    }

    @Test
    void productionSafetyFailsDuringSingletonInitializationBeforeLifecycleStarts() {
        AtomicBoolean lifecycleStarted = new AtomicBoolean();
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("production");
        context.registerBean(AuthProperties.class, this::productionProperties);
        context.registerBean(RwmsKafkaProperties.class, this::disabledKafkaProperties);
        context.registerBean(AuthProductionSafetyValidator.class);
        context.registerBean("lifecycleProbe", SmartLifecycle.class, () -> new SmartLifecycle() {
            @Override
            public void start() {
                lifecycleStarted.set(true);
            }

            @Override
            public void stop() {
                lifecycleStarted.set(false);
            }

            @Override
            public boolean isRunning() {
                return lifecycleStarted.get();
            }
        });

        assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("AUTH_KAFKA_ENABLED");
        assertThat(lifecycleStarted).isFalse();
    }

    @Test
    void productionRejectsUnsafeIssuerShape() {
        List.of(
                        "https://user:password@auth.example.test/auth",
                        "https://auth.example.test/auth?tenant=rwms",
                        "https://auth.example.test/auth#issuer")
                .forEach(issuer -> {
                    var properties = new AuthProperties(
                            issuer,
                            "https://panel.example.test",
                            "https://panel.example.test/auth/callback",
                            "https://panel.example.test/",
                            "https://worker.example.test",
                            "https://worker.example.test/auth/callback",
                            "https://worker.example.test/",
                            false,
                            "admin",
                            "long-production-password",
                            "auth.p12",
                            "password",
                            "rwms-auth");

                    assertThatThrownBy(
                                    () -> new AuthProductionSafetyValidator(
                                                    properties,
                                                    productionKafkaEnvironment(),
                                                    productionKafkaProperties())
                                            .afterPropertiesSet())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("AUTH_ISSUER");
                });
    }

    @Test
    void productionRejectsKafkaInputDestinationAndGroupOverrides() {
        var wrongDestination = productionKafkaEnvironment();
        wrongDestination.setProperty(
                "spring.cloud.stream.bindings.authUserAuthorizationEvents-in-0.destination",
                "rwms.auth.forged.v1");
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), wrongDestination, productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("input destination");

        var wrongGroup = productionKafkaEnvironment();
        wrongGroup.setProperty(
                "spring.cloud.stream.bindings.authWorkerAccessEvents-in-0.group",
                "unsafe-shared-group");
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), wrongGroup, productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consumer group");
    }

    @Test
    void productionRejectsNonCanonicalDynamicKafkaDestinations() {
        var redirectedOutput = new RwmsKafkaProperties(
                true,
                List.of(
                        "rwms.auth.user-authorization.v1",
                        "rwms.auth.forged.v1"));
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), productionKafkaEnvironment(), redirectedOutput)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destinations");
    }

    @Test
    void productionRejectsKafkaRecoveryOrPublishTimeoutOutsideOutboxLease() {
        var slowRecovery = productionKafkaEnvironment();
        slowRecovery.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.metadata.recovery.rebootstrap.trigger.ms",
                "300000");
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), slowRecovery, productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery");

        var expiredLease = productionKafkaEnvironment();
        expiredLease.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms",
                "25000");
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), expiredLease, productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox lease");

        var combinedTimeoutExceedsLease = productionKafkaEnvironment();
        combinedTimeoutExceedsLease.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms",
                "20000");
        combinedTimeoutExceedsLease.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.max.block.ms",
                "20000");
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), combinedTimeoutExceedsLease, productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox lease");
    }

    @Test
    void productionAcceptsExactFailClosedKafkaCutoverConfiguration() {
        assertThatCode(() -> new AuthProductionSafetyValidator(
                        productionProperties(), productionKafkaEnvironment(), productionKafkaProperties())
                .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void productionRequiresWorkerRedirectsOnTheConfiguredAppLinkOrigin() {
        AuthProperties properties = new AuthProperties(
                "https://auth.example.test/auth",
                "https://panel.example.test",
                "https://panel.example.test/auth/callback",
                "https://panel.example.test/",
                "https://worker.example.test",
                "https://attacker.example.test/worker/oauth2redirect",
                "https://worker.example.test/",
                false,
                "admin",
                "long-production-password",
                "auth.p12",
                "password",
                "rwms-auth");

        assertThatThrownBy(
                () ->
                        new AuthProductionSafetyValidator(
                                        properties,
                                        productionKafkaEnvironment(),
                                        productionKafkaProperties())
                                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER_ORIGIN");
    }

    @Test
    void productionReadsTheRealIndexedYamlDestinationShapeThroughConfigurationBinding() {
        var source = new MapConfigurationPropertySource(Map.of(
                "rwms.platform.kafka.enabled", "true",
                "rwms.platform.kafka.destinations[0]", "rwms.auth.user-authorization.v1",
                "rwms.platform.kafka.destinations[1]", "rwms.auth.worker-access.v1"));
        RwmsKafkaProperties bound = new Binder(source)
                .bind("rwms.platform.kafka", Bindable.of(RwmsKafkaProperties.class))
                .orElseThrow(() -> new IllegalStateException("Kafka properties fixture did not bind"));

        assertThatCode(() -> new AuthProductionSafetyValidator(
                        productionProperties(), productionKafkaEnvironment(), bound)
                .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void productionRejectsRawBinderDltAndMissingFailClosedHandler() {
        var rawDlt = productionKafkaEnvironment();
        rawDlt.setProperty(
                "spring.cloud.stream.kafka.bindings.authUserAuthorizationEvents-in-0.consumer.enable-dlq",
                "true");
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), rawDlt, productionKafkaProperties())
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Raw binder DLT");

        var unsafeHandler = productionKafkaEnvironment();
        unsafeHandler.setProperty(
                "spring.cloud.stream.kafka.bindings.authWorkerAccessEvents-in-0.consumer.common-error-handler-bean-name",
                "defaultErrorHandler");
        assertThatThrownBy(() -> new AuthProductionSafetyValidator(
                        productionProperties(), unsafeHandler, productionKafkaProperties())
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed handler");
    }

    private MockEnvironment productionKafkaEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        environment.setProperty("rwms.platform.kafka.enabled", "true");
        environment.setProperty(
                "rwms.platform.kafka.destinations",
                "rwms.auth.user-authorization.v1,rwms.auth.worker-access.v1");
        environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "kafka.internal:9092");
        environment.setProperty("spring.cloud.stream.kafka.binder.auto-create-topics", "false");
        environment.setProperty("spring.cloud.stream.kafka.default.producer.sync", "true");
        environment.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms", "5000");
        environment.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms", "15000");
        environment.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.max.block.ms", "5000");
        environment.setProperty(
                "spring.cloud.stream.kafka.binder.configuration.metadata.recovery.rebootstrap.trigger.ms",
                "1000");
        environment.setProperty("rwms.auth.eventing.outbox.lease-duration", "30s");
        environment.setProperty(
                "spring.cloud.function.definition",
                "authUserAuthorizationEvents;authWorkerAccessEvents");
        configureBinding(
                environment,
                "authUserAuthorizationEvents-in-0",
                "rwms.auth.user-authorization.v1");
        configureBinding(
                environment,
                "authWorkerAccessEvents-in-0",
                "rwms.auth.worker-access.v1");
        return environment;
    }

    private void configureBinding(MockEnvironment environment, String binding, String destination) {
        String prefix = "spring.cloud.stream.bindings." + binding;
        environment.setProperty(prefix + ".destination", destination);
        environment.setProperty(prefix + ".group", "auth-shadow-v1");
        environment.setProperty(prefix + ".consumer.max-attempts", "1");
        String kafkaPrefix = "spring.cloud.stream.kafka.bindings." + binding + ".consumer";
        environment.setProperty(kafkaPrefix + ".enable-dlq", "false");
        environment.setProperty(
                kafkaPrefix + ".common-error-handler-bean-name",
                "authFailClosedConsumerErrorHandler");
    }

    private AuthProperties productionProperties() {
        return new AuthProperties(
                "https://auth.example.test/auth",
                "https://panel.example.test",
                "https://panel.example.test/auth/callback",
                "https://panel.example.test/",
                "https://worker.example.test",
                "https://worker.example.test/auth/callback",
                "https://worker.example.test/",
                false,
                "admin",
                "long-production-password",
                "auth.p12",
                "password",
                "rwms-auth");
    }

    private RwmsKafkaProperties productionKafkaProperties() {
        return new RwmsKafkaProperties(
                true,
                List.of(
                        "rwms.auth.user-authorization.v1",
                        "rwms.auth.worker-access.v1"));
    }

    private RwmsKafkaProperties disabledKafkaProperties() {
        return new RwmsKafkaProperties(false, List.of());
    }

    private OAuthClientProperties.Client publicClient(String clientId, PrincipalType principalType) {
        return new OAuthClientProperties.Client(
                clientId,
                clientId,
                true,
                1,
                Set.of("none"),
                Set.of("authorization_code"),
                Set.of("https://panel.example.test/callback"),
                Set.of("https://panel.example.test/"),
                Set.of("openid"),
                true,
                Set.of(principalType),
                Set.of("rwms-services"),
                Set.of("https://panel.example.test"),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                true,
                null,
                null,
                false);
    }

    private AuthProperties propertiesWithDevelopmentDefaults() {
        return new AuthProperties(
                "https://auth.example.test/auth",
                "https://panel.example.test",
                "https://panel.example.test/auth/callback",
                "https://panel.example.test/",
                "https://worker.example.test",
                "https://worker.example.test/auth/callback",
                "https://worker.example.test/",
                true,
                "admin",
                "admin",
                "",
                "",
                "rwms-auth");
    }
}
