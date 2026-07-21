package dev.buhanzaz.rwms.taskboard.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class QueueRegistryAuthorizerTest {
  private final QueueRegistryAuthorizer access =
      new QueueRegistryAuthorizer(List.of("maintenance-service"));

  @Test
  void acceptsMaintenanceServiceWithRequiredScopeInStringOrListClaims() {
    assertThatCode(
            () ->
                access.requireAccess(
                    jwt(
                        "maintenance-service",
                        "maintenance-service",
                        "queue-registry.write")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                access.requireAccess(
                    jwt(
                        "maintenance-service",
                        "maintenance-service",
                        List.of("queue-registry.write", "media.maintenance"))))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingOrWrongScope() {
    for (Object scopes :
        List.of(
            "task-board.task-sync",
            List.of("media.maintenance"),
            List.of("queue-registry.write", "unrelated.scope"))) {
      assertThatThrownBy(
              () -> access.requireAccess(jwt("maintenance-service", "maintenance-service", scopes)))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void rejectsWrongClientSubjectOrPrincipalType() {
    assertThatThrownBy(
            () ->
                access.requireAccess(
                    jwt("inventory-service", "inventory-service", "queue-registry.write")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                access.requireAccess(
                    jwt("maintenance-service", "different-subject", "queue-registry.write")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                access.requireAccess(
                    jwt("maintenance-service", "maintenance-service", "queue-registry.write", "USER")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void refusesAQueueRegistryAllowlistBroaderThanMaintenanceService() {
    assertThatThrownBy(
            () ->
                new QueueRegistryAuthorizer(
                    List.of("maintenance-service", "inventory-service")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("only maintenance-service");
  }

  private Jwt jwt(String clientId, String subject, Object scopes) {
    return jwt(clientId, subject, scopes, "SERVICE");
  }

  private Jwt jwt(String clientId, String subject, Object scopes, String principalType) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", principalType)
        .claim("client_id", clientId)
        .claim("scope", scopes)
        .build();
  }
}
