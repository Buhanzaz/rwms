package dev.buhanzaz.rwms.auth.config;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fails application startup when the authentication boundary has an unsafe production configuration.
 *
 * <p>The checks deliberately run before normal application initialization. They prevent development
 * credentials, insecure public OAuth endpoints, invalid Android App Links, and an incomplete Kafka
 * outbox/consumer cutover from reaching an environment where the service is authoritative. Datasource
 * settings are also checked by {@link AuthDatasourceEnvironmentPostProcessor} before the application
 * context can create a {@code DataSource}.</p>
 */
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthProductionSafetyValidator implements InitializingBean {

    private final AuthProperties properties;
    private final Environment environment;
    private final RwmsKafkaProperties kafkaProperties;

    /**
     * Validates the active profile and all security-sensitive deployment invariants.
     *
     * <p>Development defaults are permitted only for {@code dev} and {@code test}; every other
     * profile must use HTTPS public endpoints and the declared production Kafka configuration.</p>
     */
    @Override
    public void afterPropertiesSet() {
        boolean productionProfile = environment.acceptsProfiles(Profiles.of("prod", "production"));
        boolean developmentProfile = environment.acceptsProfiles(Profiles.of("dev", "test"));
        if (properties.devDefaultCredentials()) {
            if (productionProfile || !developmentProfile) {
                throw new IllegalStateException(
                        "AUTH_DEV_DEFAULT_CREDENTIALS разрешён только с активным профилем dev или test");
            }
            return;
        }
        if (productionProfile || !developmentProfile) {
            requireKafkaCutover();
        }
        List.of(
                        new Endpoint("AUTH_ISSUER", properties.issuer()),
                        new Endpoint("PANEL_ORIGIN", properties.panelOrigin()),
                        new Endpoint("PANEL_REDIRECT_URI", properties.panelRedirectUri()),
                        new Endpoint("PANEL_POST_LOGOUT_REDIRECT_URI", properties.panelPostLogoutRedirectUri()),
                        new Endpoint("WORKER_ORIGIN", properties.workerOrigin()),
                        new Endpoint("WORKER_REDIRECT_URI", properties.workerRedirectUri()),
                        new Endpoint("WORKER_POST_LOGOUT_REDIRECT_URI", properties.workerPostLogoutRedirectUri()),
                        new Endpoint("DRIVER_ORIGIN", properties.driverOrigin()),
                        new Endpoint("DRIVER_REDIRECT_URI", properties.driverRedirectUri()),
                        new Endpoint("DRIVER_POST_LOGOUT_REDIRECT_URI", properties.driverPostLogoutRedirectUri()))
                .forEach(this::requireHttps);
        requireWorkerAppLinks();
        requireDriverAppLinks();
    }

    private void requireKafkaCutover() {
        if (!kafkaProperties.enabled()) {
            throw new IllegalStateException("AUTH_KAFKA_ENABLED должен быть true вне dev/test");
        }
        Set<String> required = Set.of(
                "rwms.auth.user-authorization.v1", "rwms.auth.worker-access.v1");
        if (!Set.copyOf(kafkaProperties.destinations()).equals(required)) {
            throw new IllegalStateException("Auth Kafka destinations должны точно соответствовать контракту F4A");
        }
        String brokers = environment.getProperty("spring.cloud.stream.kafka.binder.brokers");
        if (brokers == null || brokers.isBlank()) {
            throw new IllegalStateException("AUTH_KAFKA_BROKERS обязателен вне dev/test");
        }
        if (environment.getProperty(
                "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true)) {
            throw new IllegalStateException("Kafka topic auto-creation запрещён вне local dev");
        }
        if (!environment.getProperty(
                "spring.cloud.stream.kafka.default.producer.sync", Boolean.class, false)) {
            throw new IllegalStateException("Auth Kafka producer должен ждать broker acknowledgement");
        }
        int requestTimeout = requiredPositiveInteger(
                "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms");
        int deliveryTimeout = requiredPositiveInteger(
                "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms");
        int maxBlock = requiredPositiveInteger(
                "spring.cloud.stream.kafka.binder.configuration.max.block.ms");
        int rebootstrap = requiredPositiveInteger(
                "spring.cloud.stream.kafka.binder.configuration.metadata.recovery.rebootstrap.trigger.ms");
        Duration leaseDuration = DurationStyle.detectAndParse(environment.getProperty(
                "rwms.auth.eventing.outbox.lease-duration", "30s"));
        if (rebootstrap != 1_000) {
            throw new IllegalStateException("Auth Kafka producer recovery должен быть 1s");
        }
        long worstCasePublishWait = (long) maxBlock + deliveryTimeout;
        if (deliveryTimeout < requestTimeout
                || Duration.ofMillis(worstCasePublishWait).compareTo(leaseDuration) >= 0) {
            throw new IllegalStateException("Auth Kafka publish timeout должен быть меньше outbox lease");
        }
        String functions = environment.getProperty("spring.cloud.function.definition", "");
        if (!Set.of(functions.split(";")).equals(
                Set.of("authUserAuthorizationEvents", "authWorkerAccessEvents"))) {
            throw new IllegalStateException("Auth Kafka consumer functions должны точно соответствовать F4A");
        }
        requireConsumerBinding(
                "authUserAuthorizationEvents-in-0", "rwms.auth.user-authorization.v1");
        requireConsumerBinding("authWorkerAccessEvents-in-0", "rwms.auth.worker-access.v1");
    }

    private int requiredPositiveInteger(String property) {
        int value = environment.getProperty(property, Integer.class, -1);
        if (value <= 0) {
            throw new IllegalStateException("Auth Kafka timeout configuration обязательна");
        }
        return value;
    }

    private void requireConsumerBinding(String binding, String destination) {
        String prefix = "spring.cloud.stream.bindings." + binding;
        if (!destination.equals(environment.getProperty(prefix + ".destination"))) {
            throw new IllegalStateException("Auth Kafka input destination не соответствует контракту F4A");
        }
        if (!"auth-shadow-v1".equals(environment.getProperty(prefix + ".group"))) {
            throw new IllegalStateException("Auth Kafka consumer group должен быть auth-shadow-v1");
        }
        if (environment.getProperty(prefix + ".consumer.max-attempts", Integer.class, -1) != 1) {
            throw new IllegalStateException("Binder retry должен быть отключён в пользу bounded auth retry");
        }
        String kafkaPrefix = "spring.cloud.stream.kafka.bindings." + binding + ".consumer";
        if (environment.getProperty(kafkaPrefix + ".enable-dlq", Boolean.class, true)) {
            throw new IllegalStateException("Raw binder DLT запрещён для auth events");
        }
        if (!"authFailClosedConsumerErrorHandler".equals(
                environment.getProperty(kafkaPrefix + ".common-error-handler-bean-name"))) {
            throw new IllegalStateException("Auth Kafka consumer обязан использовать fail-closed handler");
        }
    }

    private void requireHttps(Endpoint endpoint) {
        try {
            URI uri = URI.create(endpoint.value());
            boolean unsafeIssuerShape = "AUTH_ISSUER".equals(endpoint.environmentName())
                    && (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || unsafeIssuerShape) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException(endpoint.environmentName() + " должен быть абсолютным HTTPS URI", exception);
        }
    }

    private void requireWorkerAppLinks() {
        URI origin = URI.create(properties.workerOrigin());
        URI redirect = URI.create(properties.workerRedirectUri());
        URI postLogout = URI.create(properties.workerPostLogoutRedirectUri());
        if (!sameOrigin(origin, redirect) || !sameOrigin(origin, postLogout)) {
            throw new IllegalStateException(
                    "WORKER_REDIRECT_URI and WORKER_POST_LOGOUT_REDIRECT_URI must use WORKER_ORIGIN");
        }
        if (redirect.getPath() == null
                || redirect.getPath().isBlank()
                || "/".equals(redirect.getPath())
                || redirect.getQuery() != null) {
            throw new IllegalStateException(
                    "WORKER_REDIRECT_URI must be a dedicated query-free Android App Link");
        }
    }

    private void requireDriverAppLinks() {
        URI origin = URI.create(properties.driverOrigin());
        URI redirect = URI.create(properties.driverRedirectUri());
        URI postLogout = URI.create(properties.driverPostLogoutRedirectUri());
        if (!sameOrigin(origin, redirect) || !sameOrigin(origin, postLogout)) {
            throw new IllegalStateException(
                    "DRIVER_REDIRECT_URI and DRIVER_POST_LOGOUT_REDIRECT_URI must use DRIVER_ORIGIN");
        }
        if (redirect.getPath() == null
                || redirect.getPath().isBlank()
                || "/".equals(redirect.getPath())
                || redirect.getQuery() != null) {
            throw new IllegalStateException(
                    "DRIVER_REDIRECT_URI must be a dedicated query-free Android callback");
        }
    }

    private boolean sameOrigin(URI expected, URI actual) {
        int expectedPort = expected.getPort() < 0 ? 443 : expected.getPort();
        int actualPort = actual.getPort() < 0 ? 443 : actual.getPort();
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && expectedPort == actualPort;
    }

    private record Endpoint(String environmentName, String value) {
    }
}
