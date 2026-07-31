package dev.buhanzaz.rwms.logistics.security;

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

class LogisticsAuthorizerTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000202");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000203");

  private final LogisticsAuthorizer authorizer = new LogisticsAuthorizer(new MockEnvironment(), false);

  @Test
  void grantsEditAtTheExactWarehouseOnly() {
    Jwt jwt =
        userJwt(
            "rwms.write",
            List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "EDIT")));

    assertThatCode(() -> authorizer.requireEdit(jwt, WAREHOUSE)).doesNotThrowAnyException();
    assertThatThrownBy(() -> authorizer.requireEdit(jwt, DESTINATION))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Insufficient warehouse access");
  }

  @Test
  void transferManagementRequiresManageAtBothWarehouses() {
    Jwt jwt =
        userJwt(
            "rwms.write",
            List.of(
                Map.of("warehouseId", WAREHOUSE.toString(), "level", "MANAGE"),
                Map.of("warehouseId", DESTINATION.toString(), "level", "EDIT")));

    assertThatThrownBy(() -> authorizer.requireManageBoth(jwt, WAREHOUSE, DESTINATION))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Insufficient warehouse access");
  }

  @Test
  void rejectsServiceTokenAtPublicBoundary() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(SUBJECT.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("principal_type", "SERVICE")
            .claim("scope", "rwms.read")
            .build();

    assertThatThrownBy(() -> authorizer.requireRead(jwt, WAREHOUSE))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Required USER scope");
  }

  @Test
  void grantsOnlyExactMaintenanceServiceTokenAtDriverTaskIntake() {
    Jwt exact =
        serviceJwt(
            "maintenance-service",
            "maintenance-service",
            List.of("logistics.maintenance"),
            List.of("rwms-services"));

    assertThatCode(() -> authorizer.requireMaintenanceDriverTaskIntake(exact))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                authorizer.requireMaintenanceDriverTaskIntake(
                    serviceJwt(
                        "maintenance-service",
                        "maintenance-service",
                        List.of("logistics.maintenance", "rwms.write"),
                        List.of("rwms-services"))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireMaintenanceDriverTaskIntake(
                    serviceJwt(
                        "other-service",
                        "maintenance-service",
                        List.of("logistics.maintenance"),
                        List.of("rwms-services"))))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt userJwt(String scope, List<Map<String, String>> warehouseAccess) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(SUBJECT.toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", "USER")
        .claim("scope", scope)
        .claim("warehouse_access", warehouseAccess)
        .build();
  }

  private static Jwt serviceJwt(
      String subject, String clientId, List<String> scopes, List<String> audience) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .audience(audience)
        .claim("principal_type", "SERVICE")
        .claim("client_id", clientId)
        .claim("scope", scopes)
        .build();
  }
}
