package dev.buhanzaz.rwms.auth.integration.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class WarehouseValidationPropertiesTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(tools.jackson.databind.ObjectMapper.class, () -> JsonMapper.builder().build())
            .withUserConfiguration(WarehouseValidationConfiguration.class);

    @Test
    void disabledModeCreatesNoOpWithoutUrlsOrSecret() {
        contexts.run(context -> {
            assertThat(context).hasSingleBean(WarehouseExistenceClient.class);
            assertThat(context.getBean(WarehouseExistenceClient.class))
                    .isInstanceOf(NoOpWarehouseExistenceClient.class);
        });
    }

    @Test
    void enabledModeFailsStartupWithoutCompleteSafeConfiguration() {
        contexts.withPropertyValues("rwms.auth.warehouse-validation.enabled=true")
                .run(context -> assertThat(context).hasFailed());

        assertThatThrownBy(() -> properties(
                        "http://warehouse-service:8083/path",
                        "http://auth-service:9000/not-token",
                        "secret",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3))
                .validateEnabledConfiguration())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledConfigurationRejectsUnsafeUrisAndUnboundedTimeoutsWithoutExposingSecret() {
        var properties = properties(
                "http://user@warehouse-service:8083",
                "http://auth-service:9000/oauth2/token?target=other",
                "do-not-log-me",
                Duration.ofSeconds(31),
                Duration.ofSeconds(3));

        assertThatThrownBy(properties::validateEnabledConfiguration)
                .isInstanceOf(IllegalStateException.class);
        assertThat(properties.toString()).doesNotContain("do-not-log-me");

        var valid = properties(
                        "http://warehouse-service:8083",
                        "http://auth-service:9000/oauth2/token",
                        "do-not-log-me",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3))
                .validateEnabledConfiguration();
        assertThat(valid.toString()).doesNotContain("do-not-log-me");
    }

    private WarehouseValidationProperties properties(
            String baseUrl,
            String tokenUri,
            String secret,
            Duration connectTimeout,
            Duration readTimeout) {
        return new WarehouseValidationProperties(
                true,
                baseUrl,
                tokenUri,
                "auth-service",
                secret,
                connectTimeout,
                readTimeout);
    }
}
