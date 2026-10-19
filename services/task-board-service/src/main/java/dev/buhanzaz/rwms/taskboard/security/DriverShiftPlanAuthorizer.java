package dev.buhanzaz.rwms.taskboard.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Enforces the exact logistics-service credential for daily shift plan projection writes. */
@Component
public class DriverShiftPlanAuthorizer {
  /** Rejects user tokens, identity mismatch, broad tokens and every non-logistics client. */
  public void requirePlanner(Jwt jwt) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !"logistics-service".equals(jwt.getSubject())
        || !"logistics-service".equals(jwt.getClaimAsString("client_id"))
        || !scopes(jwt).equals(List.of("task-board.driver-shifts.plan")))
      throw new AccessDeniedException(
          "Exact logistics driver-shift planner credential is required");
  }

  private List<String> scopes(Jwt jwt) {
    Object value = jwt.getClaims().get("scope");
    if (value == null) value = jwt.getClaims().get("scp");
    if (value instanceof String text)
      return java.util.Arrays.stream(text.trim().split("\\s+"))
          .filter(item -> !item.isBlank())
          .sorted()
          .toList();
    if (value instanceof Collection<?> collection)
      return collection.stream().map(String::valueOf).sorted().toList();
    return List.of();
  }
}
