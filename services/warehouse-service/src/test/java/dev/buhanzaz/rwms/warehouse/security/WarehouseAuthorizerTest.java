package dev.buhanzaz.rwms.warehouse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.warehouse.service.WarehouseOperationSource;
import dev.buhanzaz.rwms.warehouse.service.WarehouseLifecycleReadinessOwner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class WarehouseAuthorizerTest {
  @Test
  void dedicatedRentalManagerClientsMayReadTheCompanyWarehouseDirectory() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    for (String clientId :
        List.of("rwms-rental-manager-web", "rwms-rental-manager-android")) {
      Jwt manager =
          jwt(
              "USER",
              "openid profile offline_access rental.manage",
              "RENTAL_MANAGER",
              clientId);
      authorizer.requireWarehouseDirectoryRead(manager);
      assertThatThrownBy(() -> authorizer.requireWarehouseRead(manager))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void rentalManagerDirectoryReadRequiresTheExactSignedCredentialBoundary() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    for (Jwt invalid :
        new Jwt[] {
          jwt("USER", "rental.manage", "RENTAL_MANAGER", "other-client"),
          jwt("USER", "rental.manage", "VIEWER", "rwms-rental-manager-web"),
          jwt("USER", "warehouse.read", "RENTAL_MANAGER", "rwms-rental-manager-web"),
          jwt(
              "USER",
              "rental.manage warehouse.read",
              "RENTAL_MANAGER",
              "rwms-rental-manager-web"),
          withRentalAccess(
              jwt(
                  "USER",
                  "rental.manage",
                  "RENTAL_MANAGER",
                  "rwms-rental-manager-web"),
              false),
          withRentalAccess(
              jwt(
                  "USER",
                  "rental.manage",
                  "RENTAL_MANAGER",
                  "rwms-rental-manager-web"),
              null),
          jwt(
              "SERVICE",
              "rental.manage",
              "RENTAL_MANAGER",
              "rwms-rental-manager-web",
              "rwms-rental-manager-web")
        }) {
      assertThatThrownBy(() -> authorizer.requireWarehouseDirectoryRead(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }

    assertThatThrownBy(
            () ->
                authorizer.requireWarehouseDirectoryRead(
                    jwtWithoutCompany(
                        "USER",
                        "rental.manage",
                        "RENTAL_MANAGER",
                        "rwms-rental-manager-web")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("company_id");
  }

  @Test
  void rentalManagerDirectoryCredentialDoesNotOpenMutationsSupportOrInternalApis() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);
    Jwt manager =
        jwt(
            "USER",
            "rental.manage",
            "RENTAL_MANAGER",
            "rwms-rental-manager-web");
    UUID warehouseId = UUID.randomUUID();

    assertThatThrownBy(() -> authorizer.requireCompanyAdminWrite(manager))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireSystemAdmin(manager))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireWarehouseSupportRead(manager, warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireWarehouseSupportWrite(manager, warehouseId, List.of()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalAuthService(manager))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalInventoryService(manager))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalLogisticsService(manager))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void dedicatedAdministrationScopeAllowsCompanyDirectoryAndMutationsForAdministrators() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    for (String role : List.of("SYSTEM_ADMIN", "WMS_ADMIN")) {
      Jwt administrator =
          jwt("USER", "openid profile offline_access admin.manage", role, null);
      authorizer.requireWarehouseDirectoryRead(administrator);
      assertThatThrownBy(() -> authorizer.requireWarehouseRead(administrator))
          .isInstanceOf(AccessDeniedException.class);
      authorizer.requireCompanyAdminWrite(administrator);
    }

    Jwt mixedApplicationScopes =
        jwt("USER", "admin.manage rwms.write", "SYSTEM_ADMIN", null);
    assertThatThrownBy(() -> authorizer.requireWarehouseDirectoryRead(mixedApplicationScopes))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Exact application scope");
    assertThatThrownBy(() -> authorizer.requireCompanyAdminWrite(mixedApplicationScopes))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Exact application scope");
  }

  @Test
  void administrationApplicationMayAccessSupportLinksOnlyAtTheExactBoundary() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);
    UUID servedWarehouseId = UUID.randomUUID();
    List<UUID> supportWarehouseIds = List.of(UUID.randomUUID());

    for (String role : List.of("SYSTEM_ADMIN", "WMS_ADMIN")) {
      Jwt administrator =
          jwt(
              "USER",
              "openid profile offline_access admin.manage",
              role,
              "rwms-admin-web");
      authorizer.requireWarehouseSupportRead(administrator, servedWarehouseId);
      authorizer.requireWarehouseSupportWrite(administrator, servedWarehouseId, supportWarehouseIds);
    }

    for (Jwt invalid :
        new Jwt[] {
          jwt("USER", "admin.manage", "SYSTEM_ADMIN", "other-client"),
          jwt("USER", "admin.manage", "VIEWER", "rwms-admin-web"),
          jwt("USER", "admin.manage analytics.read", "SYSTEM_ADMIN", "rwms-admin-web"),
          jwt(
              "SERVICE",
              "admin.manage",
              "SYSTEM_ADMIN",
              "rwms-admin-web",
              "rwms-admin-web")
        }) {
      assertThatThrownBy(() -> authorizer.requireWarehouseSupportRead(invalid, servedWarehouseId))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  authorizer.requireWarehouseSupportWrite(
                      invalid, servedWarehouseId, supportWarehouseIds))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void dedicatedAdministrationScopeDoesNotElevateRentalManagerOrViewer() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    for (String role : List.of("RENTAL_MANAGER", "VIEWER")) {
      Jwt nonAdministrator = jwt("USER", "admin.manage", role, null);
      assertThatThrownBy(() -> authorizer.requireWarehouseDirectoryRead(nonAdministrator))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> authorizer.requireCompanyAdminWrite(nonAdministrator))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void dedicatedAdministrationScopeStillRequiresSignedCompanyBoundary() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);
    Jwt missingCompany =
        jwtWithoutCompany("USER", "admin.manage", "SYSTEM_ADMIN", null);

    assertThatThrownBy(() -> authorizer.requireWarehouseDirectoryRead(missingCompany))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("company_id");
    assertThatThrownBy(() -> authorizer.requireCompanyAdminWrite(missingCompany))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("company_id");
  }

  @Test
  void companyAdministratorsWithSignedCompanyAndWriteScopeMayMutate() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireCompanyAdminWrite(jwt("USER", "rwms.write", "SYSTEM_ADMIN", null));
    authorizer.requireCompanyAdminWrite(jwt("USER", "rwms.write", "WMS_ADMIN", null));
    assertThatThrownBy(
            () -> authorizer.requireCompanyAdminWrite(jwt("USER", "rwms.write", "VIEWER", null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireCompanyAdminWrite(
                    jwt("USER", "warehouse.read", "SYSTEM_ADMIN", null)))
        .isInstanceOf(AccessDeniedException.class);

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
  void publicCompanyBoundaryRequiresAValidSignedCompanyClaim() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);
    UUID companyId = UUID.randomUUID();
    Jwt valid = jwt("USER", "warehouse.read", "WMS_ADMIN", null, UUID.randomUUID().toString(), companyId);

    assertThat(authorizer.companyId(valid)).isEqualTo(companyId);
    authorizer.requireWarehouseRead(valid);
    assertThatThrownBy(
            () -> authorizer.requireWarehouseRead(
                jwtWithoutCompany("USER", "warehouse.read", "WMS_ADMIN", null)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("company_id");
    assertThatThrownBy(
            () -> authorizer.companyId(
                jwtWithCompanyClaim("USER", "warehouse.read", "WMS_ADMIN", null, "not-a-uuid")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("UUID");
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
  void taskBoardIdentityContractRequiresMatchingServiceAndItsSinglePurposeScope() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireInternalTaskBoardIdentityReader(
        jwt(
            "SERVICE",
            "warehouse.identity.read",
            null,
            "task-board-service",
            "task-board-service"));
    for (Jwt invalid :
        new Jwt[] {
          jwt(
              "SERVICE",
              "warehouse.identity.read warehouse.read",
              null,
              "task-board-service",
              "task-board-service"),
          jwt(
              "SERVICE",
              "warehouse.read",
              null,
              "task-board-service",
              "task-board-service"),
          jwt(
              "SERVICE",
              "warehouse.identity.read",
              null,
              "logistics-service",
              "task-board-service"),
          jwt(
              "USER",
              "warehouse.identity.read",
              null,
              "task-board-service",
              "task-board-service")
        }) {
      assertThatThrownBy(() -> authorizer.requireInternalTaskBoardIdentityReader(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void timezoneHistoryAllowsEveryCalendarOwnerButOperationMarksStayNarrow() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireInternalTimeZoneReader(
        jwt("SERVICE", "warehouse.timezone.read", null, "asset-service", "asset-service"));
    authorizer.requireInternalTimeZoneReader(
        jwt(
            "SERVICE",
            "warehouse.timezone.read",
            null,
            "task-board-service",
            "task-board-service"));
    assertThatThrownBy(
            () ->
                authorizer.requireInternalOperationMarker(
                    jwt(
                        "SERVICE",
                        "warehouse.operation.mark",
                        null,
                        "task-board-service",
                        "task-board-service")))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            authorizer.requireInternalOperationMarker(
                jwt(
                    "SERVICE",
                    "warehouse.operation.mark",
                    null,
                    "maintenance-service",
                    "maintenance-service")))
        .isEqualTo(WarehouseOperationSource.MAINTENANCE);
    for (Jwt invalid :
        new Jwt[] {
          jwt(
              "SERVICE",
              "warehouse.timezone.read warehouse.read",
              null,
              "asset-service",
              "asset-service"),
          jwt("SERVICE", "warehouse.timezone.read", null, "auth-service", "auth-service"),
          jwt("SERVICE", "warehouse.timezone.read", null, "asset-service", "other-service"),
          jwt("USER", "warehouse.timezone.read", null, "asset-service", "asset-service")
        }) {
      assertThatThrownBy(() -> authorizer.requireInternalTimeZoneReader(invalid))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> authorizer.requireInternalOperationMarker(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void lifecycleContractsRequireAnExactRecognizedResourceOwnerScope() {
    WarehouseAuthorizer authorizer = new WarehouseAuthorizer(new MockEnvironment(), false);

    authorizer.requireInternalLifecycleAdmissionReader(
        jwt("SERVICE", "warehouse.lifecycle.read", null, "asset-service", "asset-service"));
    assertThat(
            authorizer.requireInternalLifecycleWorkReader(
                jwt(
                    "SERVICE",
                    "warehouse.lifecycle.read",
                    null,
                    "maintenance-service",
                    "maintenance-service")))
        .isEqualTo(WarehouseLifecycleReadinessOwner.MAINTENANCE);
    assertThat(
            authorizer.requireInternalLifecycleReadinessConfirmer(
                jwt(
                    "SERVICE",
                    "warehouse.lifecycle.confirm",
                    null,
                    "task-board-service",
                    "task-board-service")))
        .isEqualTo(WarehouseLifecycleReadinessOwner.TASK_BOARD);
    for (Jwt invalid :
        new Jwt[] {
          jwt(
              "SERVICE",
              "warehouse.lifecycle.read warehouse.read",
              null,
              "asset-service",
              "asset-service"),
          jwt(
              "SERVICE",
              "warehouse.lifecycle.confirm",
              null,
              "auth-service",
              "auth-service"),
          jwt(
              "SERVICE",
              "warehouse.lifecycle.confirm",
              null,
              "asset-service",
              "other-service"),
          jwt(
              "USER",
              "warehouse.lifecycle.read",
              null,
              "asset-service",
              "asset-service")
        }) {
      assertThatThrownBy(() -> authorizer.requireInternalLifecycleAdmissionReader(invalid))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> authorizer.requireInternalLifecycleWorkReader(invalid))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> authorizer.requireInternalLifecycleReadinessConfirmer(invalid))
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
    assertThat(authorizer.companyId(null))
        .isEqualTo(UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48"));
    assertThatThrownBy(() -> authorizer.requireInternalAuthService(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalInventoryService(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalLogisticsService(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalTaskBoardIdentityReader(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalTimeZoneReader(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalOperationMarker(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalLifecycleAdmissionReader(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalLifecycleWorkReader(null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireInternalLifecycleReadinessConfirmer(null))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt jwt(String principalType, String scope, String globalRole, String clientId) {
    return jwt(principalType, scope, globalRole, clientId, UUID.randomUUID().toString());
  }

  private static Jwt jwt(
      String principalType, String scope, String globalRole, String clientId, String subject) {
    return jwt(
        principalType,
        scope,
        globalRole,
        clientId,
        subject,
        UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48"));
  }

  private static Jwt jwt(
      String principalType,
      String scope,
      String globalRole,
      String clientId,
      String subject,
      UUID companyId) {
    Map<String, Object> claims =
        new java.util.LinkedHashMap<>(
            Map.of("sub", subject, "principal_type", principalType, "scope", scope));
    if (globalRole != null) claims.put("global_role", globalRole);
    if (clientId != null) claims.put("client_id", clientId);
    if ("USER".equals(principalType)) {
      claims.put("company_id", companyId.toString());
      claims.put("rentalAccess", true);
    }
    return new Jwt(
        "token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
  }

  private static Jwt jwtWithoutCompany(
      String principalType, String scope, String globalRole, String clientId) {
    Map<String, Object> claims =
        new java.util.LinkedHashMap<>(Map.of(
            "sub", UUID.randomUUID().toString(),
            "principal_type", principalType,
            "scope", scope));
    if (globalRole != null) claims.put("global_role", globalRole);
    if (clientId != null) claims.put("client_id", clientId);
    return new Jwt(
        "token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
  }

  private static Jwt withRentalAccess(Jwt source, Boolean rentalAccess) {
    Map<String, Object> claims = new java.util.LinkedHashMap<>(source.getClaims());
    if (rentalAccess == null) {
      claims.remove("rentalAccess");
    } else {
      claims.put("rentalAccess", rentalAccess);
    }
    return new Jwt(
        source.getTokenValue(),
        source.getIssuedAt(),
        source.getExpiresAt(),
        source.getHeaders(),
        claims);
  }

  private static Jwt jwtWithCompanyClaim(
      String principalType,
      String scope,
      String globalRole,
      String clientId,
      String companyId) {
    Map<String, Object> claims =
        new java.util.LinkedHashMap<>(Map.of(
            "sub", UUID.randomUUID().toString(),
            "principal_type", principalType,
            "scope", scope,
            "company_id", companyId));
    if (globalRole != null) claims.put("global_role", globalRole);
    if (clientId != null) claims.put("client_id", clientId);
    return new Jwt(
        "token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
  }
}
