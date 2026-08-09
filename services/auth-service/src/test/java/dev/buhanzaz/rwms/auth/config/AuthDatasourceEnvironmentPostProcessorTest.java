package dev.buhanzaz.rwms.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** Verifies that auth datasource credentials fail closed before Spring can initialize persistence. */
class AuthDatasourceEnvironmentPostProcessorTest {

    private static final String PRODUCTION_URL = "jdbc:postgresql://auth-db.internal:5432/rwms_auth";
    private static final String PRODUCTION_USERNAME = "rwms_auth_runtime";
    private static final String PRODUCTION_PASSWORD = "deployment-secret";

    private final AuthDatasourceEnvironmentPostProcessor processor =
            new AuthDatasourceEnvironmentPostProcessor();

    /** Rejects each absent, blank, development-default, or loopback datasource setting independently. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeDatasourceConfigurations")
    void productionRejectsEveryMissingOrDevelopmentDatasourceSetting(UnsafeDatasourceCase testCase) {
        MockEnvironment environment = productionEnvironment(testCase.property(), testCase.value());

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                        environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(testCase.environmentName());
    }

    /** Accepts an explicit non-loopback PostgreSQL endpoint and deployment-specific credentials. */
    @Test
    void productionAcceptsDeploymentDatasourceSettings() {
        assertThatCode(() -> processor.postProcessEnvironment(
                        productionEnvironment(null, null), new SpringApplication(Object.class)))
                .doesNotThrowAnyException();
    }

    /** Keeps the repository's local database bootstrap available only through dev and test profiles. */
    @ParameterizedTest
    @MethodSource("developmentProfiles")
    void developmentProfilesAcceptLocalDatasourceDefaults(String profile) {
        MockEnvironment environment = localEnvironment(profile);

        assertThatCode(() -> processor.postProcessEnvironment(
                        environment, new SpringApplication(Object.class)))
                .doesNotThrowAnyException();
    }

    /** Gives a production profile precedence when a development profile is accidentally active too. */
    @Test
    void productionProfileRejectsLocalDefaultsEvenWhenTestIsAlsoActive() {
        MockEnvironment environment = localEnvironment("production", "test");

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                        environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_DB_URL");
    }

    /** Proves the registered processor aborts before any application-context initializer can run. */
    @Test
    void registeredProcessorRejectsUnsafeDatabaseBeforeApplicationContextInitialization() {
        AtomicBoolean contextInitializationStarted = new AtomicBoolean();
        SpringApplication application = new SpringApplication(Object.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> contextInitializationStarted.set(true));

        assertThatThrownBy(() -> {
                    try (ConfigurableApplicationContext ignored = application.run(
                            "--spring.profiles.active=production",
                            "--spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/rwms_auth",
                            "--spring.datasource.username=" + PRODUCTION_USERNAME,
                            "--spring.datasource.password=" + PRODUCTION_PASSWORD)) {
                        // The environment guard must fail before this context exists.
                    }
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_DB_URL")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(PRODUCTION_PASSWORD));
        assertThat(contextInitializationStarted).isFalse();
    }

    /** Ensures malformed connection strings cannot leak embedded credentials through startup errors. */
    @Test
    void rejectionDoesNotExposeDatasourceValues() {
        String embeddedSecret = "private-database-secret";
        MockEnvironment environment = productionEnvironment(
                AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                "jdbc:postgresql://operator:" + embeddedSecret + "@localhost:5432/rwms_auth");

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_DB_URL")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(embeddedSecret));
    }

    private static Stream<Arguments> unsafeDatasourceConfigurations() {
        return Stream.of(
                unsafe("missing URL", AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL, null, "AUTH_DB_URL"),
                unsafe("blank URL", AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL, " ", "AUTH_DB_URL"),
                unsafe(
                        "localhost URL",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                        "jdbc:postgresql://localhost:5433/rwms_auth",
                        "AUTH_DB_URL"),
                unsafe(
                        "IPv4 loopback URL",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                        "jdbc:postgresql://127.42.0.1:5433/rwms_auth",
                        "AUTH_DB_URL"),
                unsafe(
                        "integer-encoded IPv4 loopback URL",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                        "jdbc:postgresql://2130706433:5433/rwms_auth",
                        "AUTH_DB_URL"),
                unsafe(
                        "IPv6 loopback URL",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                        "jdbc:postgresql://[::1]:5433/rwms_auth",
                        "AUTH_DB_URL"),
                unsafe(
                        "IPv4-mapped IPv6 loopback URL",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                        "jdbc:postgresql://[::ffff:127.0.0.1]:5433/rwms_auth",
                        "AUTH_DB_URL"),
                unsafe(
                        "wildcard URL",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                        "jdbc:postgresql://0.0.0.0:5433/rwms_auth",
                        "AUTH_DB_URL"),
                unsafe(
                        "missing username",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_USERNAME,
                        null,
                        "AUTH_DB_USERNAME"),
                unsafe(
                        "blank username",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_USERNAME,
                        " ",
                        "AUTH_DB_USERNAME"),
                unsafe(
                        "development username",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_USERNAME,
                        "rwms_auth",
                        "AUTH_DB_USERNAME"),
                unsafe(
                        "missing password",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_PASSWORD,
                        null,
                        "AUTH_DB_PASSWORD"),
                unsafe(
                        "blank password",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_PASSWORD,
                        " ",
                        "AUTH_DB_PASSWORD"),
                unsafe(
                        "development password",
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_PASSWORD,
                        "rwms_auth",
                        "AUTH_DB_PASSWORD"));
    }

    private static Stream<String> developmentProfiles() {
        return Stream.of("dev", "test");
    }

    private static Arguments unsafe(String name, String property, String value, String environmentName) {
        return Arguments.of(new UnsafeDatasourceCase(name, property, value, environmentName));
    }

    private MockEnvironment productionEnvironment(String overriddenProperty, String overriddenValue) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        Map.of(
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL, PRODUCTION_URL,
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_USERNAME, PRODUCTION_USERNAME,
                        AuthDatasourceEnvironmentPostProcessor.DATASOURCE_PASSWORD, PRODUCTION_PASSWORD)
                .forEach((property, value) -> {
                    if (!property.equals(overriddenProperty)) {
                        environment.setProperty(property, value);
                    }
                });
        if (overriddenProperty != null && overriddenValue != null) {
            environment.setProperty(overriddenProperty, overriddenValue);
        }
        return environment;
    }

    private MockEnvironment localEnvironment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty(
                AuthDatasourceEnvironmentPostProcessor.DATASOURCE_URL,
                "jdbc:postgresql://127.0.0.1:5433/rwms_auth");
        environment.setProperty(AuthDatasourceEnvironmentPostProcessor.DATASOURCE_USERNAME, "rwms_auth");
        environment.setProperty(AuthDatasourceEnvironmentPostProcessor.DATASOURCE_PASSWORD, "rwms_auth");
        return environment;
    }

    /** One independently unsafe datasource setting and the non-secret variable name expected in errors. */
    private record UnsafeDatasourceCase(String name, String property, String value, String environmentName) {

        /** Uses the human-readable case name in parameterized-test output. */
        @Override
        public String toString() {
            return name;
        }
    }
}
