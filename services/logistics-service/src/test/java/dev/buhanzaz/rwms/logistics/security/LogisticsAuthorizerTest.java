package dev.buhanzaz.rwms.logistics.security;

import static org.assertj.core.api.Assertions.assertThat;
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

  private final LogisticsAuthorizer authorizer =
      new LogisticsAuthorizer(new MockEnvironment(), false);

  @Test
  void grantsEditAtTheExactWarehouseOnly() {
    Jwt jwt =
        userJwt(
            "rwms.write", List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "EDIT")));

    assertThatCode(() -> authorizer.requireEdit(jwt, WAREHOUSE)).doesNotThrowAnyException();
    assertThatThrownBy(() -> authorizer.requireEdit(jwt, DESTINATION))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Insufficient warehouse access");
  }

  @Test
  void isolatedAdministrationApplicationAllowsGlobalAdministratorsWithoutRwmsScopes() {
    Jwt administrator = administrationJwt("SYSTEM_ADMIN");

    assertThatCode(() -> authorizer.requireRead(administrator, WAREHOUSE))
        .doesNotThrowAnyException();
    assertThatCode(() -> authorizer.requireManage(administrator, WAREHOUSE))
        .doesNotThrowAnyException();
    assertThatCode(() -> authorizer.requireWarehouseOperationRecoveryAdministrator(administrator))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> authorizer.requireRead(administrationJwt("WAREHOUSE_MANAGER"), WAREHOUSE))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Required USER scope");
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

  @Test
  void grantsOnlyTheExactInventoryServiceIdentityAudienceAndScope() {
    Jwt exact =
        serviceJwt(
            "inventory-service",
            "inventory-service",
            List.of("logistics.inventory"),
            List.of("rwms-services"));

    assertThatCode(() -> authorizer.requireInventoryOutcome(exact)).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                authorizer.requireInventoryOutcome(
                    serviceJwt(
                        "inventory-service",
                        "inventory-service",
                        List.of("logistics.inventory", "rwms.write"),
                        List.of("rwms-services"))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireInventoryOutcome(
                    serviceJwt(
                        "inventory-service",
                        "other-service",
                        List.of("logistics.inventory"),
                        List.of("rwms-services"))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireInventoryOutcome(
                    serviceJwt(
                        "inventory-service",
                        "inventory-service",
                        List.of("logistics.inventory"),
                        List.of("rwms-services", "other-audience"))))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void grantsOnlyTheExactStandalonePlannerIdentityAudienceAndScope() {
    Jwt exact =
        serviceJwt(
            "logistics-planner",
            "logistics-planner",
            List.of("logistics.planning"),
            List.of("rwms-services"));

    assertThatCode(() -> authorizer.requirePlanningIntegration(exact)).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                authorizer.requirePlanningIntegration(
                    serviceJwt(
                        "logistics-planner",
                        "logistics-planner",
                        List.of("logistics.planning", "rwms.read"),
                        List.of("rwms-services"))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requirePlanningIntegration(
                    serviceJwt(
                        "logistics-planner",
                        "other-client",
                        List.of("logistics.planning"),
                        List.of("rwms-services"))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requirePlanningIntegration(
                    serviceJwt(
                        "logistics-planner",
                        "logistics-planner",
                        List.of("logistics.planning"),
                        List.of("rwms-services", "other-audience"))))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void warehouseOperationRecoveryRequiresWriteScopedGlobalAdministrator() {
    Jwt administrator =
        userJwt(
            "rwms.write",
            "WMS_ADMIN",
            List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "VIEW")));
    Jwt manager =
        userJwt(
            "rwms.write",
            "WAREHOUSE_MANAGER",
            List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "MANAGE")));

    assertThatCode(() -> authorizer.requireWarehouseOperationRecoveryAdministrator(administrator))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> authorizer.requireWarehouseOperationRecoveryAdministrator(manager))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Global administrator");
  }

  @Test
  void exactAssignedDriverUsesWorkerClaimAndDriverScopeInsteadOfSessionSubject() {
    UUID unrelatedSessionSubject = UUID.randomUUID();
    Jwt driver = workerJwt(unrelatedSessionSubject, SUBJECT, "driver.tasks");

    assertThat(authorizer.isExactAssignedDriver(driver, "ASSIGNED_DRIVER", SUBJECT)).isTrue();
    assertThat(authorizer.isExactAssignedDriver(driver, "WAREHOUSE_DRIVERS", SUBJECT)).isFalse();
    assertThat(authorizer.isExactAssignedDriver(driver, "UNASSIGNED", SUBJECT)).isFalse();
    assertThat(authorizer.isExactAssignedDriver(driver, "ASSIGNED_DRIVER", DESTINATION)).isFalse();
  }

  @Test
  void warehouseDriverClaimRequiresExactDriverAppScopeWorkerAndWarehouse() {
    Jwt driver = workerJwt(UUID.randomUUID(), SUBJECT, "driver.tasks");

    assertThat(authorizer.requireWarehouseDriver(driver, WAREHOUSE)).isEqualTo(SUBJECT);
    assertThat(authorizer.isWarehouseDriver(driver, WAREHOUSE)).isTrue();
    assertThat(authorizer.isWarehouseDriver(driver, DESTINATION)).isFalse();
    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseDriver(
                    workerJwt(UUID.randomUUID(), SUBJECT, "worker.tasks"), WAREHOUSE))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseDriver(
                    workerJwt(UUID.randomUUID(), SUBJECT, "driver.tasks worker.tasks"), WAREHOUSE))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseDriver(
                    workerJwt(UUID.randomUUID(), "not-a-uuid", "driver.tasks"), WAREHOUSE))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("worker_id");
  }

  @Test
  void workerAppScopeAndUserSubjectCannotReadAssignedDriverDetail() {
    Jwt workerApp = workerJwt(UUID.randomUUID(), SUBJECT, "worker.tasks");
    Jwt formerUserShape =
        userJwt(
            "worker.tasks", List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "VIEW")));

    assertThat(authorizer.isExactAssignedDriver(workerApp, "ASSIGNED_DRIVER", SUBJECT)).isFalse();
    assertThat(
            authorizer.isExactAssignedDriver(
                workerJwt(UUID.randomUUID(), SUBJECT, "worker.tasks driver.tasks"),
                "ASSIGNED_DRIVER",
                SUBJECT))
        .isFalse();
    assertThat(authorizer.isExactAssignedDriver(formerUserShape, "ASSIGNED_DRIVER", SUBJECT))
        .isFalse();
    assertThat(
            authorizer.isExactAssignedDriver(
                workerJwt(UUID.randomUUID(), "not-a-uuid", "driver.tasks"),
                "ASSIGNED_DRIVER",
                SUBJECT))
        .isFalse();
  }

  private static Jwt userJwt(String scope, List<Map<String, String>> warehouseAccess) {
    return userJwt(scope, null, warehouseAccess);
  }

  private static Jwt userJwt(
      String scope, String globalRole, List<Map<String, String>> warehouseAccess) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(SUBJECT.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("principal_type", "USER")
            .claim("scope", scope)
            .claim("warehouse_access", warehouseAccess);
    if (globalRole != null) builder.claim("global_role", globalRole);
    return builder.build();
  }

  private static Jwt administrationJwt(String globalRole) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(SUBJECT.toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", "USER")
        .claim("client_id", "rwms-admin-web")
        .claim("global_role", globalRole)
        .claim("scope", "openid profile offline_access admin.manage")
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

  private static Jwt workerJwt(UUID sessionSubject, Object workerId, String scope) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(sessionSubject.toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", "WORKER")
        .claim("worker_id", String.valueOf(workerId))
        .claim("warehouse_id", WAREHOUSE.toString())
        .claim("scope", scope)
        .build();
  }
}
