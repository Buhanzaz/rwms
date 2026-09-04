package dev.buhanzaz.rwms.taskboard.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Guards inventory-service's narrow read of the effective object work calendar. */
@Component
public class InventoryCalendarAuthorizer {
  static final String REQUIRED_SCOPE = "task-board.inventory-calendar.read";
  static final String INVENTORY_SERVICE = "inventory-service";

  /** Requires the exact inventory-service client credential and its sole calendar-read scope. */
  public void requireCalendarRead(Jwt jwt) {
    String clientId = jwt == null ? null : jwt.getClaimAsString("client_id");
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !INVENTORY_SERVICE.equals(clientId)
        || !INVENTORY_SERVICE.equals(jwt.getSubject())
        || !scopes(jwt).equals(List.of(REQUIRED_SCOPE))) {
      throw new AccessDeniedException(
          "The inventory-service credential with exactly task-board.inventory-calendar.read is required");
    }
  }

  private static List<String> scopes(Jwt jwt) {
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) claim = jwt.getClaims().get("scp");
    if (claim instanceof String value) {
      return List.of(value.trim().split("\\s+")).stream().filter(item -> !item.isBlank()).toList();
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
