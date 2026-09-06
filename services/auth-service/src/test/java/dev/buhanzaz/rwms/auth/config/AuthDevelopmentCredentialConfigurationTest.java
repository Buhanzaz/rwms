package dev.buhanzaz.rwms.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class AuthDevelopmentCredentialConfigurationTest {

    @Test
    void developmentProfileAllowsSecurePublicIssuerConfigurationThroughExistingEnvironmentKeys()
            throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AUTH_ISSUER", "https://auth.example.test/auth")
                .withProperty("AUTH_DEV_DEFAULT_CREDENTIALS", "false")
                .withProperty("AUTH_SESSION_COOKIE_SECURE", "true")
                .withProperty("AUTH_SIGNING_KEY_STORE", "/run/secrets/auth.p12");
        environment.getPropertySources().addLast(configuration("application-dev.yaml"));

        assertThat(environment.getProperty("rwms.auth.issuer"))
                .isEqualTo("https://auth.example.test/auth");
        assertThat(environment.getProperty("rwms.auth.dev-default-credentials", Boolean.class)).isFalse();
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("rwms.auth.signing-key-store")).isEqualTo("/run/secrets/auth.p12");
    }

    @Test
    void everyProfileUsesExternalTaskBoardCredentialsAndAllowsAHigherRotationRevision() throws IOException {
        for (String file : new String[] {"application.yaml", "application-dev.yaml", "application-test.yaml"}) {
            PropertySource<?> source = configuration(file);
            String prefix = taskBoardPrefix(source);
            MockEnvironment environment = new MockEnvironment();
            environment.getPropertySources().addLast(source);

            assertThat(environment.getProperty(prefix + ".secret-environment"))
                    .isEqualTo("TASK_BOARD_CLIENT_SECRET");
            assertThat(environment.getProperty(prefix + ".development-secret")).isNull();
            assertThat(environment.getProperty(prefix + ".revision", Long.class)).isEqualTo(6L);

            environment.setProperty("TASK_BOARD_CLIENT_REVISION", "37");
            assertThat(environment.getProperty(prefix + ".revision", Long.class)).isEqualTo(37L);
        }
    }

    @Test
    void publicDevelopmentConfigurationDoesNotRetainAnEnabledMaintenanceFallback() throws IOException {
        PropertySource<?> source = configuration("application-dev.yaml");
        for (int index = 0; source.getProperty("rwms.auth.oauth.clients[" + index + "].client-id") != null; index++) {
            String prefix = "rwms.auth.oauth.clients[" + index + "]";
            assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        }
    }

    private PropertySource<?> configuration(String name) throws IOException {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource(name)).getFirst();
    }

    private String taskBoardPrefix(PropertySource<?> source) {
        for (int index = 0; index < 30; index++) {
            String prefix = "rwms.auth.oauth.clients[" + index + "]";
            if ("task-board-service".equals(source.getProperty(prefix + ".client-id"))) {
                return prefix;
            }
        }
        throw new AssertionError("Configured task-board OAuth client is missing");
    }
}
