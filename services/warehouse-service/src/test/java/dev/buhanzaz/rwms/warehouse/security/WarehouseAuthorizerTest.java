package dev.buhanzaz.rwms.warehouse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class WarehouseAuthorizerTest {
  @Test
  void onlySystemAdminWithWriteScopeMayMutate() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireSystemAdminWrite(jwt("USER", "rwms.write", "SYSTEM_ADMIN", null));
    assertThatThrownBy(
            () -> authorizer.requireSystemAdminWrite(jwt("USER", "rwms.write", "WMS_ADMIN", null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireSystemAdminWrite(
                    jwt("USER", "warehouse.read", "SYSTEM_ADMIN", null)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void internalContractsRequireTheirExactServiceClientAndExactlyWarehouseRead() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireInternalAuthService(jwt("SERVICE", "warehouse.read", null, "auth-service"));
    for (Jwt invalid :
        new Jwt[] {
          jwt("SERVICE", "warehouse.read rwms.read", null, "auth-service"),
          jwt("SERVICE", "warehouse.read", null, "task-board-service"),
          jwt("USER", "warehouse.read", "SYSTEM_ADMIN", "auth-service")
        }) {
      assertThatThrownBy(() -> authorizer.requireInternalAuthService(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }

    authorizer.requireInternalAssetService(jwt("SERVICE", "warehouse.read", null, "asset-service"));
    assertThatThrownBy(
            () ->
                authorizer.requireInternalAssetService(
                    jwt("SERVICE", "warehouse.read", null, "auth-service")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void inventoryContractAlsoRequiresMatchingServiceSubject() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireInternalInventoryService(
        jwt("SERVICE", "warehouse.read", null, "inventory-service", "inventory-service"));
    for (Jwt invalid :
        new Jwt[] {
          jwt(
              "SERVICE",
              "warehouse.read rwms.read",
              null,
              "inventory-service",
              "inventory-service"),
          jwt("SERVICE", "asset.inventory", null, "inventory-service", "inventory-service"),
          jwt("SERVICE", "", null, "inventory-service", "inventory-service"),
          jwt("SERVICE", "warehouse.read", null, "asset-service", "inventory-service"),
          jwt("SERVICE", "warehouse.read", null, "inventory-service", "other-service"),
          jwt("USER", "warehouse.read", "SYSTEM_ADMIN", "inventory-service", "inventory-service")
        }) {
      assertThatThrownBy(() -> authorizer.requireInternalInventoryService(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void logisticsContractRequiresMatchingServiceIdentityAndExactLogisticsScope() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireInternalLogisticsService(
        jwt("SERVICE", "warehouse.logistics", null, "logistics-service", "logistics-service"));
    for (Jwt invalid :
        new Jwt[] {
          jwt(
              "SERVICE",
              "warehouse.logistics warehouse.read",
              null,
              "logistics-service",
              "logistics-service"),
          jwt("SERVICE", "warehouse.read", null, "logistics-service", "logistics-service"),
          jwt("SERVICE", "warehouse.logistics", null, "asset-service", "logistics-service"),
          jwt("SERVICE", "warehouse.logistics", null, "logistics-service", "other-service"),
          jwt("USER", "warehouse.logistics", "SYSTEM_ADMIN", "logistics-service", "logistics-service")
        }) {
      assertThatThrownBy(() -> authorizer.requireInternalLogisticsService(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void developmentBypassDoesNotOpenTheInternalContract() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(environment, true);

    assertThat(authorizer.isSystemAdmin(null)).isTrue();
    assertThat(authorizer.subjectId(null))
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000d1"));
    assertThatThrownBy(() -> authorizer.requireInternalAuthService(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalInventoryService(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalLogisticsService(null))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt jwt(String principalType, String scope, String globalRole, String clientId) {
    return jwt(principalType, scope, globalRole, clientId, UUID.randomUUID().toString());
  }

  private static Jwt jwt(
      String principalType, String scope, String globalRole, String clientId, String subject) {
    Map<String, Object> claims =
        new java.util.LinkedHashMap<>(
            Map.of("sub", subject, "principal_type", principalType, "scope", scope));
    if (globalRole != null) claims.put("global_role", globalRole);
    if (clientId != null) claims.put("client_id", clientId);
    return new Jwt(
        "token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
  }
}
