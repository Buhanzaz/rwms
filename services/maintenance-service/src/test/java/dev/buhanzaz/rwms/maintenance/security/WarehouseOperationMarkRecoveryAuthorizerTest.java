package dev.buhanzaz.rwms.maintenance.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class WarehouseOperationMarkRecoveryAuthorizerTest {
  private final MaintenanceAuthorizer authorizer = new MaintenanceAuthorizer(
      new MockEnvironment().withProperty("spring.profiles.active", "test"), false);

  @Test
  void requiresWriteScopeAndAnAdministratorRole() {
    UUID warehouseId = UUID.randomUUID();
    assertThatCode(
            () ->
                authorizer.requireWarehouseOperationRecoveryAdministrator(
                    token("rwms.write", "WMS_ADMIN"), warehouseId))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                authorizer.requireWarehouseOperationRecoveryAdministrator(
                    token("rwms.write", "SYSTEM_ADMIN"), warehouseId))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                authorizer.requireFurnitureEquipmentLinkAdministrator(
                    token("rwms.write", "WMS_ADMIN"), warehouseId))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseOperationRecoveryAdministrator(
                    token("rwms.read", "WMS_ADMIN"), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseOperationRecoveryAdministrator(
                    token("rwms.write", "WAREHOUSE_MANAGER"), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireFurnitureEquipmentLinkAdministrator(
                    token("rwms.write", "WAREHOUSE_MANAGER"), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt token(String scope, String role) {
    Instant now = Instant.parse("2026-08-05T00:00:00Z");
    return new Jwt(
        "token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "none"),
        Map.of(
            "sub", UUID.randomUUID().toString(),
            "principal_type", "USER",
            "aud", List.of("rwms-api"),
            "scope", scope,
            "global_role", role));
  }
}
