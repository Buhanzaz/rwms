package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class AuthFlywayConfigurationTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void baseConfigurationEnforcesFlywayAndJpaValidation() throws IOException {
        PropertySource<?> source = load("application.yaml");

        assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(true);
        assertThat(source.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(source.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
        assertThat(source.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
        assertThat(source.getProperty("spring.flyway.clean-disabled")).isEqualTo(true);
        assertThat(source.getProperty("spring.flyway.out-of-order")).isEqualTo(false);
        assertThat(source.getProperty("spring.flyway.validate-migration-naming")).isEqualTo(true);
        assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void developmentNeverLetsHibernateMutateTheSchema() throws IOException {
        PropertySource<?> source = load("application-dev.yaml");

        assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void testsUseFlywayBeforeJpaAndDisableLegacySqlInitialization() throws IOException {
        PropertySource<?> source = load("application-test.yaml");

        assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(true);
        assertThat(source.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
        assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(source.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(source.getProperty("spring.jpa.defer-datasource-initialization")).isNull();
    }

    @Test
    void maintenanceClientIsDisabledByDefaultAndHasOnlyApprovedPrivateScopes() throws IOException {
        PropertySource<?> source = load("application.yaml");

        assertThat(source.getProperty("rwms.auth.oauth.clients[5].client-id"))
                .isEqualTo("maintenance-service");
        assertThat(source.getProperty("rwms.auth.oauth.clients[5].enabled"))
                .isEqualTo("${MAINTENANCE_CLIENT_ENABLED:false}");
        assertThat(source.getProperty("rwms.auth.oauth.clients[5].authentication-methods[0]"))
                .isEqualTo("client_secret_basic");
        assertThat(source.getProperty("rwms.auth.oauth.clients[5].grant-types[0]"))
                .isEqualTo("client_credentials");
        assertThat(source.getProperty("rwms.auth.oauth.clients[5].scopes[0]"))
                .isEqualTo("asset.maintenance");
        assertThat(source.getProperty("rwms.auth.oauth.clients[5].scopes[1]"))
                .isEqualTo("task-board.task-sync");
        assertThat(source.getProperty("rwms.auth.oauth.clients[5].scopes[2]"))
                .isNull();
        assertThat(source.getProperty("rwms.auth.oauth.clients[5].audiences[0]"))
                .isEqualTo("rwms-services");
    }

    private PropertySource<?> load(String resource) throws IOException {
        return loader.load(resource, new ClassPathResource(resource)).getFirst();
    }
}
