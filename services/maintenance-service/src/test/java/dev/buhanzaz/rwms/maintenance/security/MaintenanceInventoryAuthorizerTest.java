package dev.buhanzaz.rwms.maintenance.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class MaintenanceInventoryAuthorizerTest {
  private final MaintenanceAuthorizer authorizer = new MaintenanceAuthorizer(
      new MockEnvironment().withProperty("spring.profiles.active", "test"), false);

  @Test
  void acceptsOnlyTheExactInventoryServiceIdentityAudienceAndSingleScope() {
    assertThatCode(() -> authorizer.requireInventoryService(token(
        "inventory-service", "inventory-service", "SERVICE",
        List.of("rwms-services"), "maintenance.inventory")))
        .doesNotThrowAnyException();

    for (Jwt rejected : List.of(
        token("other-service", "inventory-service", "SERVICE", List.of("rwms-services"),
            "maintenance.inventory"),
        token("inventory-service", "other-service", "SERVICE", List.of("rwms-services"),
            "maintenance.inventory"),
        token("inventory-service", "inventory-service", "USER", List.of("rwms-services"),
            "maintenance.inventory"),
        token("inventory-service", "inventory-service", "SERVICE", List.of("other"),
            "maintenance.inventory"),
        token("inventory-service", "inventory-service", "SERVICE",
            List.of("rwms-services", "other"), "maintenance.inventory"),
        token("inventory-service", "inventory-service", "SERVICE", List.of("rwms-services"),
            "maintenance.inventory asset.inventory"),
        token("inventory-service", "inventory-service", "SERVICE", List.of("rwms-services"),
            List.of("maintenance.inventory", 7)),
        token("inventory-service", "inventory-service", "SERVICE", List.of("rwms-services"),
            "rwms.write"))) {
      assertThatThrownBy(() -> authorizer.requireInventoryService(rejected))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private static Jwt token(
      String subject,
      String clientId,
      String principalType,
      List<String> audience,
      Object scope) {
    Instant now = Instant.parse("2026-07-17T12:00:00Z");
    return new Jwt(
        "token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "none"),
        Map.of(
            "sub", subject,
            "client_id", clientId,
            "principal_type", principalType,
            "aud", audience,
            "scope", scope));
  }
}
