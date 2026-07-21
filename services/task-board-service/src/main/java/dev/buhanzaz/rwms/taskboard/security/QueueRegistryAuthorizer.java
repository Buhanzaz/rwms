package dev.buhanzaz.rwms.taskboard.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class QueueRegistryAuthorizer {
  private static final String MAINTENANCE_SERVICE = "maintenance-service";
  private static final String REQUIRED_SCOPE = "queue-registry.write";
  private static final Set<String> APPROVED_MAINTENANCE_SCOPES =
      Set.of(
          "asset.maintenance",
          "task-board.task-sync",
          REQUIRED_SCOPE,
          "media.maintenance");

  private final Set<String> allowedClients;

  public QueueRegistryAuthorizer(
      @Value("${rwms.security.queue-registry-client-ids:maintenance-service}")
          List<String> allowedClients) {
    this.allowedClients =
        allowedClients.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    if (!this.allowedClients.equals(Set.of(MAINTENANCE_SERVICE))) {
      throw new IllegalStateException(
          "queue-registry-client-ids must contain only maintenance-service");
    }
  }

  public void requireAccess(Jwt jwt) {
    String clientId = jwt == null ? null : jwt.getClaimAsString("client_id");
    List<String> scopes = jwt == null ? List.of() : scopes(jwt);
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !MAINTENANCE_SERVICE.equals(clientId)
        || !clientId.equals(jwt.getSubject())
        || !allowedClients.contains(clientId)
        || !scopes.contains(REQUIRED_SCOPE)
        || !APPROVED_MAINTENANCE_SCOPES.containsAll(scopes)) {
      throw new AccessDeniedException(
          "The maintenance-service credential with queue-registry.write is required");
    }
  }

  private static List<String> scopes(Jwt jwt) {
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) {
      claim = jwt.getClaims().get("scp");
    }
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
