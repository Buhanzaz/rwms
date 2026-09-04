package dev.buhanzaz.rwms.taskboard.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class InventoryCalendarAuthorizerTest {
  private final InventoryCalendarAuthorizer authorizer = new InventoryCalendarAuthorizer();

  @Test
  void acceptsOnlyTheExactInventoryServiceCalendarCredential() {
    assertThatCode(
            () ->
                authorizer.requireCalendarRead(
                    jwt(
                        "SERVICE",
                        "inventory-service",
                        "inventory-service",
                        List.of("task-board.inventory-calendar.read"))))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongPrincipalClientSubjectAndAnyAdditionalScope() {
    for (Jwt token :
        List.of(
            jwt("USER", "inventory-service", "inventory-service", "task-board.inventory-calendar.read"),
            jwt("SERVICE", "other-service", "inventory-service", "task-board.inventory-calendar.read"),
            jwt("SERVICE", "inventory-service", "other-subject", "task-board.inventory-calendar.read"),
            jwt(
                "SERVICE",
                "inventory-service",
                "inventory-service",
                "task-board.inventory-calendar.read rwms.write"))) {
      assertThatThrownBy(() -> authorizer.requireCalendarRead(token))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private static Jwt jwt(String type, String clientId, String subject, Object scope) {
    return Jwt.withTokenValue("inventory-calendar-token")
        .header("alg", "none")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", type)
        .claim("client_id", clientId)
        .claim("scope", scope)
        .build();
  }
}
