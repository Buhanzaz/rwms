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

        assertThat(source.getProperty("rwms.auth.oauth.clients[6].client-id"))
                .isEqualTo("maintenance-service");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].enabled"))
                .isEqualTo("${MAINTENANCE_CLIENT_ENABLED:false}");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].revision"))
                .isEqualTo("${MAINTENANCE_CLIENT_REVISION:5}");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].authentication-methods[0]"))
                .isEqualTo("client_secret_basic");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].grant-types[0]"))
                .isEqualTo("client_credentials");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[0]"))
                .isEqualTo("asset.maintenance");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[1]"))
                .isEqualTo("task-board.task-sync");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[2]"))
                .isEqualTo("queue-registry.write");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[3]"))
                .isEqualTo("media.maintenance");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[4]"))
                .isEqualTo("logistics.maintenance");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[5]"))
                .isEqualTo("warehouse.timezone.read");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[6]"))
                .isEqualTo("warehouse.operation.mark");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[7]"))
                .isEqualTo("warehouse.lifecycle.read");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[8]"))
                .isEqualTo("warehouse.lifecycle.confirm");
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].scopes[9]"))
                .isNull();
        assertThat(source.getProperty("rwms.auth.oauth.clients[6].audiences[0]"))
                .isEqualTo("rwms-services");
    }

    @Test
    void taskBoardClientHasOnlyWorkerAndWarehouseReadinessScopes() throws IOException {
        assertTaskBoardClient(load("application.yaml"), 3, 3);
        assertTaskBoardClient(load("application-dev.yaml"), 3, 3);
        assertTaskBoardClient(load("application-test.yaml"), 3, 3);
        assertTaskBoardClient(load("application-warehouse-client.yaml"), 3, 3);
    }

    @Test
    void assetClientIsDisabledAndExactInBaseDevelopmentTestAndFocusedProfiles() throws IOException {
        assertAssetClient(
                load("application.yaml"),
                5,
                "${ASSET_WAREHOUSE_CLIENT_ENABLED:false}",
                "${ASSET_WAREHOUSE_CLIENT_REVISION:4}");
        assertAssetClient(
                load("application-dev.yaml"),
                5,
                "${ASSET_WAREHOUSE_CLIENT_ENABLED:false}",
                "${ASSET_WAREHOUSE_CLIENT_REVISION:4}");
        assertAssetClient(load("application-test.yaml"), 5, false, 4);
        assertAssetClient(
                load("application-asset-client.yaml"),
                0,
                "${ASSET_WAREHOUSE_CLIENT_ENABLED:false}",
                "${ASSET_WAREHOUSE_CLIENT_REVISION:4}");
    }

    @Test
    void inventoryClientIsDisabledAndExactInBaseDevelopmentAndFocusedTestProfiles() throws IOException {
        assertInventoryClient(load("application.yaml"), 7, "${INVENTORY_CLIENT_REVISION:4}");
        assertInventoryClient(load("application-dev.yaml"), 7, "${INVENTORY_CLIENT_REVISION:4}");
        assertInventoryClient(load("application-inventory-client.yaml"), 0, 4);
    }

    @Test
    void logisticsClientIsDisabledAndExactInBaseDevelopmentAndFocusedTestProfiles() throws IOException {
        assertLogisticsClient(load("application.yaml"), 8, "${LOGISTICS_CLIENT_REVISION:6}");
        assertLogisticsClient(load("application-dev.yaml"), 8, "${LOGISTICS_CLIENT_REVISION:6}");
        assertLogisticsClient(load("application-logistics-client.yaml"), 0, 6);
    }

    private void assertAssetClient(
            PropertySource<?> source, int index, Object expectedEnabled, Object expectedRevision) {
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".client-id")).isEqualTo("asset-service");
        assertThat(source.getProperty(prefix + ".enabled")).isEqualTo(expectedEnabled);
        assertThat(source.getProperty(prefix + ".revision")).isEqualTo(expectedRevision);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]")).isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".authentication-methods[1]")).isNull();
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isNull();
        assertThat(source.getProperty(prefix + ".scopes[0]")).isEqualTo("warehouse.read");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isEqualTo("warehouse.timezone.read");
        assertThat(source.getProperty(prefix + ".scopes[2]")).isEqualTo("warehouse.operation.mark");
        assertThat(source.getProperty(prefix + ".scopes[3]")).isEqualTo("warehouse.lifecycle.read");
        assertThat(source.getProperty(prefix + ".scopes[4]")).isEqualTo("warehouse.lifecycle.confirm");
        assertThat(source.getProperty(prefix + ".scopes[5]")).isEqualTo("media.asset-import");
        assertThat(source.getProperty(prefix + ".scopes[6]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".audiences[1]")).isNull();
        assertThat(source.getProperty(prefix + ".secret-environment"))
                .isEqualTo("ASSET_WAREHOUSE_CLIENT_SECRET");
        assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        assertThat(source.getProperty(prefix + ".redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".post-logout-redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-origins[0]")).isNull();
    }

    private void assertTaskBoardClient(
            PropertySource<?> source, int index, Object expectedRevision) {
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".client-id")).isEqualTo("task-board-service");
        assertThat(source.getProperty(prefix + ".revision")).isEqualTo(expectedRevision);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]"))
                .isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".scopes[0]"))
                .isEqualTo("worker-credentials.manage");
        assertThat(source.getProperty(prefix + ".scopes[1]"))
                .isEqualTo("warehouse.timezone.read");
        assertThat(source.getProperty(prefix + ".scopes[2]"))
                .isEqualTo("warehouse.lifecycle.read");
        assertThat(source.getProperty(prefix + ".scopes[3]"))
                .isEqualTo("warehouse.lifecycle.confirm");
        assertThat(source.getProperty(prefix + ".scopes[4]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
    }

    private void assertInventoryClient(PropertySource<?> source, int index, Object expectedRevision) {
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".client-id")).isEqualTo("inventory-service");
        assertThat(source.getProperty(prefix + ".enabled")).isEqualTo("${INVENTORY_CLIENT_ENABLED:false}");
        assertThat(source.getProperty(prefix + ".revision")).isEqualTo(expectedRevision);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]")).isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".authentication-methods[1]")).isNull();
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isNull();
        assertThat(source.getProperty(prefix + ".scopes[0]")).isEqualTo("warehouse.timezone.read");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isEqualTo("warehouse.operation.mark");
        assertThat(source.getProperty(prefix + ".scopes[2]")).isEqualTo("warehouse.lifecycle.read");
        assertThat(source.getProperty(prefix + ".scopes[3]")).isEqualTo("warehouse.lifecycle.confirm");
        assertThat(source.getProperty(prefix + ".scopes[4]")).isEqualTo("asset.inventory");
        assertThat(source.getProperty(prefix + ".scopes[5]")).isEqualTo("maintenance.inventory");
        assertThat(source.getProperty(prefix + ".scopes[6]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".audiences[1]")).isNull();
        assertThat(source.getProperty(prefix + ".secret-environment")).isEqualTo("INVENTORY_CLIENT_SECRET");
        assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        assertThat(source.getProperty(prefix + ".redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".post-logout-redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-origins[0]")).isNull();
    }

    private void assertLogisticsClient(PropertySource<?> source, int index, Object expectedRevision) {
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".client-id")).isEqualTo("logistics-service");
        assertThat(source.getProperty(prefix + ".enabled")).isEqualTo("${LOGISTICS_CLIENT_ENABLED:false}");
        assertThat(source.getProperty(prefix + ".revision")).isEqualTo(expectedRevision);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]")).isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".authentication-methods[1]")).isNull();
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isNull();
        assertThat(source.getProperty(prefix + ".scopes[0]")).isEqualTo("warehouse.logistics");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isEqualTo("warehouse.timezone.read");
        assertThat(source.getProperty(prefix + ".scopes[2]")).isEqualTo("warehouse.operation.mark");
        assertThat(source.getProperty(prefix + ".scopes[3]")).isEqualTo("warehouse.lifecycle.read");
        assertThat(source.getProperty(prefix + ".scopes[4]")).isEqualTo("warehouse.lifecycle.confirm");
        assertThat(source.getProperty(prefix + ".scopes[5]")).isEqualTo("asset.logistics");
        assertThat(source.getProperty(prefix + ".scopes[6]")).isEqualTo("task-board.logistics");
        assertThat(source.getProperty(prefix + ".scopes[7]")).isEqualTo("maintenance.logistics");
        assertThat(source.getProperty(prefix + ".scopes[8]")).isEqualTo("media.logistics");
        assertThat(source.getProperty(prefix + ".scopes[9]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".audiences[1]")).isNull();
        assertThat(source.getProperty(prefix + ".secret-environment")).isEqualTo("LOGISTICS_CLIENT_SECRET");
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
