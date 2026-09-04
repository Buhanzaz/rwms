package dev.buhanzaz.rwms.asset.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.domain.AssetCompanyDefaults;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class AssetAuthorizerTest {
  private final UUID warehouseId = UUID.randomUUID();

  @Test
  void appliesWarehouseViewEditManageAndGlobalCatalogRules() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);
    Jwt manager = user("rwms.read rwms.write", "WAREHOUSE_MANAGER", "MANAGE");

    authorizer.requireRead(manager, warehouseId);
    authorizer.requireEdit(manager, warehouseId);
    authorizer.requireManage(manager, warehouseId);
    assertThatThrownBy(() -> authorizer.requireGlobalCatalogManagement(manager))
        .isInstanceOf(AccessDeniedException.class);

    authorizer.requireGlobalCatalogManagement(user("rwms.read rwms.write", "WMS_ADMIN", "VIEW"));
    assertThatThrownBy(() -> authorizer.requireEdit(user("rwms.read", "VIEWER", "VIEW"), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void isolatedAdministrationApplicationAllowsOnlyGlobalAdministratorsWithoutRwmsScopes() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);

    Jwt administrator = administrationApplication("SYSTEM_ADMIN");
    authorizer.requireRead(administrator, warehouseId);
    authorizer.requireManage(administrator, warehouseId);
    authorizer.requireGlobalCatalogManagement(administrator);

    assertThatThrownBy(
            () -> authorizer.requireRead(administrationApplication("WAREHOUSE_MANAGER"), warehouseId))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Required USER scope");
  }

  @Test
  void genericEquipmentMovementRejectsRentalManagerAndViewerDespiteManageGrant() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);

    assertThatThrownBy(
            () ->
                authorizer.requireEquipmentMovement(
                    user("rwms.read rwms.write", "RENTAL_MANAGER", "MANAGE"), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireEquipmentMovement(
                    user("rwms.read rwms.write", "VIEWER", "MANAGE"), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    authorizer.requireEquipmentMovement(
        user("rwms.read rwms.write", "WAREHOUSE_MANAGER", "MANAGE"), warehouseId);
  }

  @Test
  void htmlImportCommitRequiresWriteScopeAndAnAdministratorRole() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);

    authorizer.requireHtmlImportCommit(
        user("rwms.read rwms.write", "WMS_ADMIN", "EDIT"), warehouseId);
    authorizer.requireHtmlImportCommit(
        user("rwms.read rwms.write", "SYSTEM_ADMIN", "MANAGE"), warehouseId);

    assertThatThrownBy(
            () ->
                authorizer.requireHtmlImportCommit(
                    user("rwms.read rwms.write", "WAREHOUSE_MANAGER", "MANAGE"),
                    warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireHtmlImportCommit(
                    user("rwms.read", "WMS_ADMIN", "EDIT"), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void outboxRecoveryRequiresUserWriteScopeAndAnAdministratorOrTheExistingDevelopmentBypass() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);

    authorizer.requireOutboxRecovery(user("rwms.read rwms.write", "SYSTEM_ADMIN", "VIEW"));
    authorizer.requireOutboxRecovery(user("rwms.read rwms.write", "WMS_ADMIN", "VIEW"));
    assertThatThrownBy(() -> authorizer.requireOutboxRecovery(
        user("rwms.read rwms.write", "WAREHOUSE_MANAGER", "MANAGE")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireOutboxRecovery(
        user("rwms.read", "SYSTEM_ADMIN", "VIEW")))
        .isInstanceOf(AccessDeniedException.class);

    MockEnvironment development = new MockEnvironment();
    development.setActiveProfiles("dev");
    AssetAuthorizer bypass = new AssetAuthorizer(development, true);
    bypass.requireOutboxRecovery(null);
    assertThat(bypass.subjectId(null)).isNotNull();
    assertThat(bypass.companyId(null)).isEqualTo(AssetCompanyDefaults.INITIAL_COMPANY_ID);
  }

  @Test
  void companyFenceRequiresAValidSignedUserClaim() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);
    UUID companyId = UUID.randomUUID();
    Jwt valid =
        jwt(
            Map.of(
                "sub",
                UUID.randomUUID().toString(),
                "principal_type",
                "USER",
                "company_id",
                companyId.toString()));

    assertThat(authorizer.companyId(valid)).isEqualTo(companyId);
    assertThatThrownBy(
            () ->
                authorizer.companyId(
                    jwt(
                        Map.of(
                            "sub",
                            UUID.randomUUID().toString(),
                            "principal_type",
                            "USER"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("company claim");
    assertThatThrownBy(
            () ->
                authorizer.companyId(
                    jwt(
                        Map.of(
                            "sub",
                            UUID.randomUUID().toString(),
                            "principal_type",
                            "USER",
                            "company_id",
                            "not-a-uuid"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("UUID");
    assertThatThrownBy(
            () -> authorizer.companyId(service("asset.inventory", "inventory-service")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("USER");
  }

  @Test
  void bindsAssetMaintenanceToTheExactMaintenanceClientAndScopeOnly() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);
    Jwt valid = service("asset.maintenance", "maintenance-service");

    authorizer.requireMaintenanceAssetAccess(valid);
    assertThat(authorizer.maintenanceSubjectId(valid)).isNotNull();
    assertThatThrownBy(() -> authorizer.requireMaintenanceAssetAccess(
        service("asset.maintenance asset.internal", "maintenance-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireMaintenanceAssetAccess(
        service("asset.maintenance", "inventory-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireMaintenanceAssetAccess(
        user("asset.maintenance", "SYSTEM_ADMIN", "MANAGE")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void bindsAssetInventoryToTheExactInventoryClientAndScopeOnly() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);
    Jwt valid = service("asset.inventory", "inventory-service");

    authorizer.requireInventoryAssetAccess(valid);
    assertThat(authorizer.inventorySubjectId(valid)).isNotNull();
    assertThatThrownBy(() -> authorizer.requireInventoryAssetAccess(
        service("asset.inventory asset.internal", "inventory-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInventoryAssetAccess(jwt(Map.of(
        "sub", "inventory-service",
        "principal_type", "SERVICE",
        "scope", java.util.List.of("asset.inventory", 7),
        "client_id", "inventory-service"))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInventoryAssetAccess(
        service("asset.inventory", "maintenance-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInventoryAssetAccess(
        service("asset.inventory", "inventory-service", "maintenance-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInventoryAssetAccess(
        user("asset.inventory", "SYSTEM_ADMIN", "MANAGE")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void bindsAssetLogisticsToTheExactLogisticsClientAndScopeOnly() {
    AssetAuthorizer authorizer = new AssetAuthorizer(new MockEnvironment(), false);
    Jwt valid = service("asset.logistics", "logistics-service");

    authorizer.requireLogisticsAssetAccess(valid);
    assertThat(authorizer.logisticsSubjectId(valid)).isNotNull();
    assertThatThrownBy(() -> authorizer.requireLogisticsAssetAccess(
        service("asset.logistics asset.internal", "logistics-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireLogisticsAssetAccess(
        service("asset.logistics", "inventory-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireLogisticsAssetAccess(
        service("asset.logistics", "logistics-service", "another-subject")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireLogisticsAssetAccess(
        user("asset.logistics", "SYSTEM_ADMIN", "MANAGE")))
        .isInstanceOf(AccessDeniedException.class);
  }

  private Jwt user(String scope, String globalRole, String level) {
    return jwt(Map.of(
        "sub", UUID.randomUUID().toString(),
        "principal_type", "USER",
        "scope", scope,
        "global_role", globalRole,
        "company_id", AssetCompanyDefaults.INITIAL_COMPANY_ID.toString(),
        "warehouse_access", java.util.List.of(Map.of("warehouseId", warehouseId.toString(), "level", level))));
  }

  private static Jwt administrationApplication(String globalRole) {
    return jwt(
        Map.of(
            "sub", UUID.randomUUID().toString(),
            "principal_type", "USER",
            "client_id", "rwms-admin-web",
            "global_role", globalRole,
            "scope", "openid profile offline_access admin.manage"));
  }

  private static Jwt service(String scope, String clientId) {
    return service(scope, clientId, clientId);
  }

  private static Jwt service(String scope, String clientId, String subject) {
    return jwt(Map.of(
        "sub", subject,
        "principal_type", "SERVICE",
        "scope", scope,
        "client_id", clientId));
  }

  private static Jwt jwt(Map<String, Object> claims) {
    return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
  }
}
