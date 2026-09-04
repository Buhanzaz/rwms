package dev.buhanzaz.rwms.warehouse.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class WarehouseOutboxRecoveryAccessAuthorizerTest {
  private static final UUID COMPANY =
      UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48");

  @Test
  void acceptsOnlyExactUserWriteScopeAndApprovedGlobalAdministratorRoles() {
    WarehouseOutboxRecoveryAccessAuthorizer authorizer =
        new WarehouseOutboxRecoveryAccessAuthorizer(new MockEnvironment(), false);
    UUID systemAdmin = UUID.randomUUID();
    UUID wmsAdmin = UUID.randomUUID();

    assertThat(authorizer.requireRecoveryAdministrator(jwt(systemAdmin, "SYSTEM_ADMIN", "rwms.write")))
        .isEqualTo(
            new WarehouseOutboxRecoveryAccessAuthorizer.RecoveryPrincipal(systemAdmin, COMPANY));
    assertThat(authorizer.requireRecoveryAdministrator(jwt(wmsAdmin, "WMS_ADMIN", "rwms.write")))
        .isEqualTo(
            new WarehouseOutboxRecoveryAccessAuthorizer.RecoveryPrincipal(wmsAdmin, COMPANY));

    for (Jwt invalid :
        new Jwt[] {
          jwt(UUID.randomUUID(), "WAREHOUSE_MANAGER", "rwms.write"),
          jwt(UUID.randomUUID(), "SYSTEM_ADMIN", "rwms.write warehouse.read"),
          jwt(UUID.randomUUID(), "SYSTEM_ADMIN", "warehouse.read"),
          serviceJwt("SYSTEM_ADMIN", "rwms.write"),
          malformedSubjectJwt("SYSTEM_ADMIN", "rwms.write"),
          missingCompanyJwt("SYSTEM_ADMIN", "rwms.write"),
          malformedCompanyJwt("SYSTEM_ADMIN", "rwms.write")
        }) {
      assertThatThrownBy(() -> authorizer.requireRecoveryAdministrator(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void developmentBypassUsesTheDedicatedDeterministicReviewerOnlyInDev() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");
    WarehouseOutboxRecoveryAccessAuthorizer authorizer =
        new WarehouseOutboxRecoveryAccessAuthorizer(environment, true);

    assertThat(authorizer.requireRecoveryAdministrator(null))
        .isEqualTo(
            new WarehouseOutboxRecoveryAccessAuthorizer.RecoveryPrincipal(
                UUID.fromString("00000000-0000-0000-0000-0000000000d1"), COMPANY));
  }

  private static Jwt jwt(UUID subjectId, String role, String scope) {
    return token(subjectId.toString(), "USER", role, scope);
  }

  private static Jwt serviceJwt(String role, String scope) {
    return token(UUID.randomUUID().toString(), "SERVICE", role, scope);
  }

  private static Jwt malformedSubjectJwt(String role, String scope) {
    return token("not-a-uuid", "USER", role, scope);
  }

  private static Jwt missingCompanyJwt(String role, String scope) {
    return tokenWithoutCompany(UUID.randomUUID().toString(), "USER", role, scope, null);
  }

  private static Jwt malformedCompanyJwt(String role, String scope) {
    return tokenWithoutCompany(
        UUID.randomUUID().toString(), "USER", role, scope, "not-a-uuid");
  }

  private static Jwt token(String subject, String principalType, String role, String scope) {
    return tokenWithoutCompany(subject, principalType, role, scope, COMPANY.toString());
  }

  private static Jwt tokenWithoutCompany(
      String subject, String principalType, String role, String scope, String companyId) {
    Map<String, Object> claims = new java.util.HashMap<>();
    claims.put("sub", subject);
    claims.put("principal_type", principalType);
    claims.put("global_role", role);
    claims.put("scope", scope);
    if (companyId != null) claims.put("company_id", companyId);
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.copyOf(claims));
  }
}
