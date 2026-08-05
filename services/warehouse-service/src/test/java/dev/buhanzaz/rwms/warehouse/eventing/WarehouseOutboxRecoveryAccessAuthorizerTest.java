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
  @Test
  void acceptsOnlyExactUserWriteScopeAndApprovedGlobalAdministratorRoles() {
    WarehouseOutboxRecoveryAccessAuthorizer authorizer =
        new WarehouseOutboxRecoveryAccessAuthorizer(new MockEnvironment(), false);
    UUID systemAdmin = UUID.randomUUID();
    UUID wmsAdmin = UUID.randomUUID();

    assertThat(authorizer.requireRecoveryAdministrator(jwt(systemAdmin, "SYSTEM_ADMIN", "rwms.write")))
        .isEqualTo(systemAdmin);
    assertThat(authorizer.requireRecoveryAdministrator(jwt(wmsAdmin, "WMS_ADMIN", "rwms.write")))
        .isEqualTo(wmsAdmin);

    for (Jwt invalid :
        new Jwt[] {
          jwt(UUID.randomUUID(), "WAREHOUSE_MANAGER", "rwms.write"),
          jwt(UUID.randomUUID(), "SYSTEM_ADMIN", "rwms.write warehouse.read"),
          jwt(UUID.randomUUID(), "SYSTEM_ADMIN", "warehouse.read"),
          serviceJwt("SYSTEM_ADMIN", "rwms.write"),
          malformedSubjectJwt("SYSTEM_ADMIN", "rwms.write")
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
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000d1"));
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

  private static Jwt token(String subject, String principalType, String role, String scope) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub", subject,
            "principal_type", principalType,
            "global_role", role,
            "scope", scope));
  }
}
