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

    @Test
    void inventoryClientIsDisabledAndExactInBaseDevelopmentAndFocusedTestProfiles() throws IOException {
        assertInventoryClient(load("application.yaml"), 6);
        assertInventoryClient(load("application-dev.yaml"), 6);
        assertInventoryClient(load("application-inventory-client.yaml"), 0);
    }

    private void assertInventoryClient(PropertySource<?> source, int index) {
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".client-id")).isEqualTo("inventory-service");
        assertThat(source.getProperty(prefix + ".enabled")).isEqualTo("${INVENTORY_CLIENT_ENABLED:false}");
        assertThat(source.getProperty(prefix + ".authentication-methods[0]")).isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".authentication-methods[1]")).isNull();
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isNull();
        assertThat(source.getProperty(prefix + ".scopes[0]")).isEqualTo("warehouse.read");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isEqualTo("asset.inventory");
        assertThat(source.getProperty(prefix + ".scopes[2]")).isEqualTo("maintenance.inventory");
        assertThat(source.getProperty(prefix + ".scopes[3]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".audiences[1]")).isNull();
        assertThat(source.getProperty(prefix + ".secret-environment")).isEqualTo("INVENTORY_CLIENT_SECRET");
        assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        assertThat(source.getProperty(prefix + ".redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".post-logout-redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-origins[0]")).isNull();
    }

    private PropertySource<?> load(String resource) throws IOException {
        return loader.load(resource, new ClassPathResource(resource)).getFirst();
    }
}
