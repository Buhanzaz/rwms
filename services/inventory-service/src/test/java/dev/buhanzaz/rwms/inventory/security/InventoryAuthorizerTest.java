package dev.buhanzaz.rwms.inventory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class InventoryAuthorizerTest {

  @Test
  void isolatedAdministrationApplicationGetsAnUnrestrictedInventoryScope() {
    InventoryAuthorizer authorizer = new InventoryAuthorizer(new MockEnvironment(), false);

    InventoryAuthorizer.WarehouseScope scope =
        authorizer.readScope(administrationToken("SYSTEM_ADMIN"));

    assertThat(scope.unrestricted()).isTrue();
    assertThatThrownBy(() -> authorizer.readScope(administrationToken("WAREHOUSE_MANAGER")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Required USER scope");
  }

  @Test
  void eventingRecoveryRequiresGlobalAdminWithTheApprovedScope() {
    InventoryAuthorizer authorizer = new InventoryAuthorizer(new MockEnvironment(), false);

    authorizer.requireEventingRecovery(administrationToken("SYSTEM_ADMIN"));
    authorizer.requireEventingRecovery(userToken("WMS_ADMIN", "rwms.write"));
    assertThatThrownBy(
            () -> authorizer.requireEventingRecovery(userToken("WAREHOUSE_MANAGER", "rwms.write")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SYSTEM_ADMIN or WMS_ADMIN");
    assertThatThrownBy(
            () -> authorizer.requireEventingRecovery(userToken("SYSTEM_ADMIN", "rwms.read")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("scope");
  }

  private static Jwt administrationToken(String globalRole) {
    Instant now = Instant.parse("2026-07-17T12:00:00Z");
    return new Jwt(
        "token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "none"),
        Map.of(
            "sub", UUID.randomUUID().toString(),
            "client_id", "rwms-admin-web",
            "principal_type", "USER",
            "global_role", globalRole,
            "scope", "openid profile offline_access admin.manage"));
  }

  private static Jwt userToken(String globalRole, String scope) {
    Instant now = Instant.parse("2026-07-17T12:00:00Z");
    return new Jwt(
        "token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "none"),
        Map.of(
            "sub", UUID.randomUUID().toString(),
            "client_id", "rwms-panel",
            "principal_type", "USER",
            "global_role", globalRole,
            "scope", scope));
  }
}
