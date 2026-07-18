package dev.buhanzaz.rwms.taskboard.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class TaskSyncAuthorizer {
  private static final String REQUIRED_SCOPE = "task-board.task-sync";
  private static final String MAINTENANCE_SERVICE = "maintenance-service";
  private static final String LOGISTICS_SCOPE = "task-board.logistics";
  private static final String LOGISTICS_SERVICE = "logistics-service";

  public String requireTaskSync(Jwt jwt) {
    String clientId = jwt == null ? null : jwt.getClaimAsString("client_id");
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || clientId == null
        || !clientId.equals(jwt.getSubject())
        || !MAINTENANCE_SERVICE.equals(clientId)
        || !scopes(jwt).equals(List.of(REQUIRED_SCOPE))) {
      throw new AccessDeniedException(
          "A permitted service credential with exactly task-board.task-sync is required");
    }
    return clientId;
  }

  /** Stage 8 can create, read and cancel only its dedicated task surface. */
  public void requireLogisticsTaskAccess(Jwt jwt) {
    String clientId = jwt == null ? null : jwt.getClaimAsString("client_id");
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !LOGISTICS_SERVICE.equals(clientId)
        || !LOGISTICS_SERVICE.equals(jwt.getSubject())
        || !scopes(jwt).equals(List.of(LOGISTICS_SCOPE))) {
      throw new AccessDeniedException(
          "The logistics-service credential with exactly task-board.logistics is required");
    }
  }

  private static List<String> scopes(Jwt jwt) {
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) claim = jwt.getClaims().get("scp");
    if (claim instanceof String value) {
      return List.of(value.trim().split("\\s+"))
          .stream().filter(item -> !item.isBlank()).toList();
    }
    if (claim instanceof Collection<?> values) {
      return values.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .filter(value -> !value.isBlank())
          .toList();
    }
    return List.of();
  }
}
