package dev.buhanzaz.rwms.analytics.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class AnalyticsAuthorizerTest {
  @Test
  void allowsUserWithReadScopeAndWarehouseView() {
    UUID warehouseId = UUID.randomUUID();
    AnalyticsAuthorizer authorizer = new AnalyticsAuthorizer(mock(Environment.class), false);
    Jwt jwt =
        jwt(
            Map.of(
                "principal_type", "USER",
                "scope", "rwms.read",
                "global_role", "WORKER",
                "warehouse_access",
                    List.of(Map.of("warehouseId", warehouseId.toString(), "level", "VIEW"))));

    assertThatCode(() -> authorizer.requireWarehouseView(jwt, warehouseId))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingScopeOrAnotherWarehouse() {
    UUID warehouseId = UUID.randomUUID();
    AnalyticsAuthorizer authorizer = new AnalyticsAuthorizer(mock(Environment.class), false);

    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseView(
                    jwt(Map.of("principal_type", "USER", "scope", "rwms.write")), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseView(
                    jwt(
                        Map.of(
                            "principal_type", "USER",
                            "scope", "rwms.read",
                            "global_role", "WORKER",
                            "warehouse_access",
                                List.of(
                                    Map.of(
                                        "warehouseId",
                                        UUID.randomUUID().toString(),
                                        "level",
                                        "MANAGE")))),
                    warehouseId))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt jwt(Map<String, Object> claims) {
    return new Jwt(
        "token",
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(60),
        Map.of("alg", "none"),
        claims);
  }
}
