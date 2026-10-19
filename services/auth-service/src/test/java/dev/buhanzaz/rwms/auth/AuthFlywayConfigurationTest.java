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
        assertThat(source.getProperty("spring.datasource.url")).isEqualTo("${AUTH_DB_URL:}");
        assertThat(source.getProperty("spring.datasource.username")).isEqualTo("${AUTH_DB_USERNAME:}");
        assertThat(source.getProperty("spring.datasource.password")).isEqualTo("${AUTH_DB_PASSWORD:}");
    }

    @Test
    void developmentNeverLetsHibernateMutateTheSchema() throws IOException {
        PropertySource<?> source = load("application-dev.yaml");

        assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(source.getProperty("spring.datasource.url"))
                .isEqualTo("${AUTH_DB_URL:jdbc:postgresql://127.0.0.1:5433/rwms_auth}");
        assertThat(source.getProperty("spring.datasource.username"))
                .isEqualTo("${AUTH_DB_USERNAME:rwms_auth}");
        assertThat(source.getProperty("spring.datasource.password"))
                .isEqualTo("${AUTH_DB_PASSWORD:rwms_auth}");
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
        int index = clientIndex(source, "maintenance-service");
        String prefix = "rwms.auth.oauth.clients[" + index + "]";

        assertThat(source.getProperty(prefix + ".enabled"))
                .isEqualTo("${MAINTENANCE_CLIENT_ENABLED:false}");
        assertThat(source.getProperty(prefix + ".revision"))
                .isEqualTo("${MAINTENANCE_CLIENT_REVISION:5}");
        assertThat(source.getProperty(prefix + ".authentication-methods[0]"))
                .isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".grant-types[0]"))
                .isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".scopes[0]"))
                .isEqualTo("asset.maintenance");
        assertThat(source.getProperty(prefix + ".scopes[1]"))
                .isEqualTo("task-board.task-sync");
        assertThat(source.getProperty(prefix + ".scopes[2]"))
                .isEqualTo("queue-registry.write");
        assertThat(source.getProperty(prefix + ".scopes[3]"))
                .isEqualTo("media.maintenance");
        assertThat(source.getProperty(prefix + ".scopes[4]"))
                .isEqualTo("logistics.maintenance");
        assertThat(source.getProperty(prefix + ".scopes[5]"))
                .isEqualTo("warehouse.timezone.read");
        assertThat(source.getProperty(prefix + ".scopes[6]"))
                .isEqualTo("warehouse.operation.mark");
        assertThat(source.getProperty(prefix + ".scopes[7]"))
                .isEqualTo("warehouse.lifecycle.read");
        assertThat(source.getProperty(prefix + ".scopes[8]"))
                .isEqualTo("warehouse.lifecycle.confirm");
        assertThat(source.getProperty(prefix + ".scopes[9]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
    }

    @Test
    void driverClientIsSeparatedFromWorkerAppInEveryInteractiveProfile() throws IOException {
        assertDriverClient(load("application.yaml"));
        assertDriverClient(load("application-dev.yaml"));
        assertDriverClient(load("application-test.yaml"));
        assertDriverClient(load("application-warehouse-client.yaml"));
    }

    @Test
    void taskBoardClientHasOnlyWorkerAndWarehouseIdentityScopes() throws IOException {
        assertTaskBoardClient(load("application.yaml"), "${TASK_BOARD_CLIENT_REVISION:7}");
        assertTaskBoardClient(load("application-dev.yaml"), "${TASK_BOARD_CLIENT_REVISION:7}");
        assertTaskBoardClient(load("application-test.yaml"), "${TASK_BOARD_CLIENT_REVISION:7}");
        assertTaskBoardClient(load("application-warehouse-client.yaml"), "${TASK_BOARD_CLIENT_REVISION:7}");
    }

    @Test
    void assetClientIsDisabledAndExactInBaseDevelopmentTestAndFocusedProfiles() throws IOException {
        assertAssetClient(
                load("application.yaml"),
                "${ASSET_WAREHOUSE_CLIENT_ENABLED:false}",
                "${ASSET_WAREHOUSE_CLIENT_REVISION:5}");
        assertAssetClient(
                load("application-dev.yaml"),
                "${ASSET_WAREHOUSE_CLIENT_ENABLED:false}",
                "${ASSET_WAREHOUSE_CLIENT_REVISION:5}");
        assertAssetClient(load("application-test.yaml"), false, 5);
        assertAssetClient(
                load("application-asset-client.yaml"),
                "${ASSET_WAREHOUSE_CLIENT_ENABLED:false}",
                "${ASSET_WAREHOUSE_CLIENT_REVISION:5}");
    }

    @Test
    void inventoryClientIsDisabledAndExactInBaseDevelopmentAndFocusedTestProfiles() throws IOException {
        assertInventoryClient(load("application.yaml"), "${INVENTORY_CLIENT_REVISION:7}");
        assertInventoryClient(load("application-dev.yaml"), "${INVENTORY_CLIENT_REVISION:7}");
        assertInventoryClient(load("application-inventory-client.yaml"), 7);
    }

    @Test
    void logisticsClientIsDisabledAndExactInBaseDevelopmentAndFocusedTestProfiles() throws IOException {
        assertLogisticsClient(load("application.yaml"), "${LOGISTICS_CLIENT_REVISION:7}");
        assertLogisticsClient(load("application-dev.yaml"), "${LOGISTICS_CLIENT_REVISION:7}");
        assertLogisticsClient(load("application-logistics-client.yaml"), 7);
    }

    @Test
    void logisticsPlannerClientIsDisabledAndExactInEveryConfiguredProfile() throws IOException {
        assertLogisticsPlannerClient(load("application.yaml"), "${LOGISTICS_PLANNER_CLIENT_REVISION:1}");
        assertLogisticsPlannerClient(load("application-dev.yaml"), "${LOGISTICS_PLANNER_CLIENT_REVISION:1}");
        assertLogisticsPlannerClient(load("application-planner-client.yaml"), 1);
    }

    @Test
    void dedicatedManagerAndAdminClientsStayLeastPrivilegeInInteractiveProfiles() throws IOException {
        assertDedicatedInteractiveClients(load("application.yaml"));
        assertDedicatedInteractiveClients(load("application-dev.yaml"));
        assertDedicatedInteractiveClients(load("application-test.yaml"));
    }

    private void assertDedicatedInteractiveClients(PropertySource<?> source) {
        assertCadClient(source);
        assertDedicatedInteractiveClient(
                source,
                "rwms-rental-manager-web",
                "rental.manage",
                "/manager/auth/callback");
        assertDedicatedInteractiveClient(
                source,
                "rwms-rental-manager-android",
                "rental.manage",
                "/auth/rental-manager/callback");
        assertDedicatedInteractiveClient(
                source,
                "rwms-admin-web",
                "admin.manage",
                "/admin/auth/callback");
    }

    private void assertCadClient(PropertySource<?> source) {
        int index = clientIndex(source, "rwms-cad");
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".enabled")).isEqualTo(true);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]")).isEqualTo("none");
        assertThat(source.getProperty(prefix + ".authentication-methods[1]")).isNull();
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("authorization_code");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isEqualTo("refresh_token");
        assertThat(source.getProperty(prefix + ".grant-types[2]")).isNull();
        assertThat(source.getProperty(prefix + ".scopes[0]")).isEqualTo("openid");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isEqualTo("profile");
        assertThat(source.getProperty(prefix + ".scopes[2]")).isEqualTo("offline_access");
        assertThat(source.getProperty(prefix + ".scopes[3]")).isEqualTo("cad.project");
        assertThat(source.getProperty(prefix + ".scopes[4]")).isNull();
        assertThat(source.getProperty(prefix + ".require-proof-key")).isEqualTo(true);
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isEqualTo("USER");
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".reuse-refresh-tokens")).isEqualTo(false);
        assertThat(String.valueOf(source.getProperty(prefix + ".redirect-uris[0]")))
                .contains("/cabin-cad/auth/callback");
        assertThat(source.getProperty(prefix + ".redirect-uris[1]")).isNull();
        assertThat(String.valueOf(source.getProperty(prefix + ".post-logout-redirect-uris[0]")))
                .contains("/cabin-cad/");
        assertThat(source.getProperty(prefix + ".post-logout-redirect-uris[1]")).isNull();
    }

    private void assertDedicatedInteractiveClient(
            PropertySource<?> source,
            String clientId,
            String applicationScope,
            String callbackPath) {
        int index = clientIndex(source, clientId);
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".enabled")).isEqualTo(true);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]")).isEqualTo("none");
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("authorization_code");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isEqualTo("refresh_token");
        assertThat(source.getProperty(prefix + ".scopes[0]")).isEqualTo("openid");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isEqualTo("profile");
        assertThat(source.getProperty(prefix + ".scopes[2]")).isEqualTo("offline_access");
        assertThat(source.getProperty(prefix + ".scopes[3]")).isEqualTo(applicationScope);
        assertThat(source.getProperty(prefix + ".scopes[4]")).isNull();
        assertThat(source.getProperty(prefix + ".require-proof-key")).isEqualTo(true);
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isEqualTo("USER");
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(String.valueOf(source.getProperty(prefix + ".redirect-uris[0]")))
                .contains(callbackPath);
    }

    private void assertAssetClient(
            PropertySource<?> source, Object expectedEnabled, Object expectedRevision) {
        int index = clientIndex(source, "asset-service");
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
        assertThat(source.getProperty(prefix + ".scopes[5]")).isEqualTo("media.asset");
        assertThat(source.getProperty(prefix + ".scopes[6]")).isEqualTo("media.asset-import");
        assertThat(source.getProperty(prefix + ".scopes[7]")).isNull();
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

    private void assertTaskBoardClient(PropertySource<?> source, Object expectedRevision) {
        int index = clientIndex(source, "task-board-service");
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".client-id")).isEqualTo("task-board-service");
        assertThat(source.getProperty(prefix + ".secret-environment")).isEqualTo("TASK_BOARD_CLIENT_SECRET");
        assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        assertThat(source.getProperty(prefix + ".revision")).isEqualTo(expectedRevision);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]"))
                .isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".scopes[0]"))
                .isEqualTo("worker-credentials.manage");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isEqualTo("media.task-board");
        assertThat(source.getProperty(prefix + ".scopes[2]"))
                .isEqualTo("warehouse.identity.read");
        assertThat(source.getProperty(prefix + ".scopes[3]"))
                .isEqualTo("warehouse.timezone.read");
        assertThat(source.getProperty(prefix + ".scopes[4]"))
                .isEqualTo("warehouse.lifecycle.read");
        assertThat(source.getProperty(prefix + ".scopes[5]"))
                .isEqualTo("warehouse.lifecycle.confirm");
        assertThat(source.getProperty(prefix + ".scopes[6]"))
                .isEqualTo("maintenance.task-requirements");
        assertThat(source.getProperty(prefix + ".scopes[7]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
    }

    private void assertInventoryClient(PropertySource<?> source, Object expectedRevision) {
        int index = clientIndex(source, "inventory-service");
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
        assertThat(source.getProperty(prefix + ".scopes[6]")).isEqualTo("logistics.inventory");
        assertThat(source.getProperty(prefix + ".scopes[7]")).isEqualTo("media.inventory");
        assertThat(source.getProperty(prefix + ".scopes[8]"))
                .isEqualTo("task-board.inventory-calendar.read");
        assertThat(source.getProperty(prefix + ".scopes[9]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".audiences[1]")).isNull();
        assertThat(source.getProperty(prefix + ".secret-environment")).isEqualTo("INVENTORY_CLIENT_SECRET");
        assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        assertThat(source.getProperty(prefix + ".redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".post-logout-redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-origins[0]")).isNull();
    }

    private void assertLogisticsClient(PropertySource<?> source, Object expectedRevision) {
        int index = clientIndex(source, "logistics-service");
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
        assertThat(source.getProperty(prefix + ".scopes[7]")).isEqualTo("task-board.driver-shifts.plan");
        assertThat(source.getProperty(prefix + ".scopes[8]")).isEqualTo("maintenance.logistics");
        assertThat(source.getProperty(prefix + ".scopes[9]")).isEqualTo("media.logistics");
        assertThat(source.getProperty(prefix + ".scopes[10]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".audiences[1]")).isNull();
        assertThat(source.getProperty(prefix + ".secret-environment")).isEqualTo("LOGISTICS_CLIENT_SECRET");
        assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        assertThat(source.getProperty(prefix + ".redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".post-logout-redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-origins[0]")).isNull();
    }

    private void assertLogisticsPlannerClient(PropertySource<?> source, Object expectedRevision) {
        int index = clientIndex(source, "logistics-planner");
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".client-id")).isEqualTo("logistics-planner");
        assertThat(source.getProperty(prefix + ".enabled"))
                .isEqualTo("${LOGISTICS_PLANNER_CLIENT_ENABLED:false}");
        assertThat(source.getProperty(prefix + ".revision")).isEqualTo(expectedRevision);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]"))
                .isEqualTo("client_secret_basic");
        assertThat(source.getProperty(prefix + ".authentication-methods[1]")).isNull();
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("client_credentials");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isNull();
        assertThat(source.getProperty(prefix + ".scopes[0]")).isEqualTo("logistics.planning");
        assertThat(source.getProperty(prefix + ".scopes[1]")).isNull();
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
        assertThat(source.getProperty(prefix + ".audiences[1]")).isNull();
        assertThat(source.getProperty(prefix + ".secret-environment"))
                .isEqualTo("LOGISTICS_PLANNER_CLIENT_SECRET");
        assertThat(source.getProperty(prefix + ".development-secret")).isNull();
        assertThat(source.getProperty(prefix + ".redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".post-logout-redirect-uris[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isNull();
        assertThat(source.getProperty(prefix + ".allowed-origins[0]")).isNull();
    }

    private void assertDriverClient(PropertySource<?> source) {
        int index = clientIndex(source, "rwms-driver-android");
        String prefix = "rwms.auth.oauth.clients[" + index + "]";
        assertThat(source.getProperty(prefix + ".enabled")).isEqualTo(true);
        assertThat(source.getProperty(prefix + ".authentication-methods[0]")).isEqualTo("none");
        assertThat(source.getProperty(prefix + ".grant-types[0]")).isEqualTo("authorization_code");
        assertThat(source.getProperty(prefix + ".grant-types[1]")).isEqualTo("refresh_token");
        assertThat(source.getProperty(prefix + ".scopes[3]")).isEqualTo("driver.tasks");
        assertThat(source.getProperty(prefix + ".scopes[4]")).isNull();
        assertThat(source.getProperty(prefix + ".require-proof-key")).isEqualTo(true);
        assertThat(source.getProperty(prefix + ".allowed-principal-types[0]")).isEqualTo("WORKER");
        assertThat(source.getProperty(prefix + ".audiences[0]")).isEqualTo("rwms-services");
    }

    private int clientIndex(PropertySource<?> source, String clientId) {
        for (int index = 0; index < 32; index++) {
            if (clientId.equals(source.getProperty("rwms.auth.oauth.clients[" + index + "].client-id"))) {
                return index;
            }
        }
        throw new AssertionError("OAuth client is missing: " + clientId);
    }

    private PropertySource<?> load(String resource) throws IOException {
        return loader.load(resource, new ClassPathResource(resource)).getFirst();
    }
}
