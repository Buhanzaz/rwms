package dev.buhanzaz.rwms.taskboard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class TaskSyncAuthorizerTest {
  private final TaskSyncAuthorizer authorizer = new TaskSyncAuthorizer();

  @Test
  void acceptsMaintenanceWithItsExactScope() {
    assertThat(authorizer.requireTaskSync(serviceJwt("maintenance-service", "task-board.task-sync")))
        .isEqualTo("maintenance-service");
  }

  @Test
  void acceptsLogisticsWithItsExactScope() {
    assertThat(authorizer.requireTaskSync(serviceJwt("logistics-service", "task-board.logistics")))
        .isEqualTo("logistics-service");
  }

  @Test
  void rejectsCrossedOrAdditionalScopes() {
    assertThatThrownBy(
            () ->
                authorizer.requireTaskSync(
                    serviceJwt("logistics-service", "task-board.task-sync")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.requireTaskSync(
                    serviceJwt(
                        "logistics-service",
                        List.of("task-board.logistics", "task-board.task-sync"))))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt serviceJwt(String clientId, String scope) {
    return serviceJwt(clientId, List.of(scope));
  }

  private static Jwt serviceJwt(String clientId, List<String> scopes) {
    Instant now = Instant.now();
    return new Jwt(
        "token",
        now,
        now.plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub", clientId,
            "client_id", clientId,
            "principal_type", "SERVICE",
            "scope", scopes));
  }
}
