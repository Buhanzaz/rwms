package dev.buhanzaz.rwms.assistant.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Builds the signed manager boundary for public assistant operations. */
@Component
public class AssistantAuthorizer {
  private static final Set<String> MANAGER_CLIENT_IDS =
      Set.of("rwms-rental-manager-web", "rwms-rental-manager-android");
  private static final Set<String> INTERACTIVE_PROTOCOL_SCOPES =
      Set.of("openid", "profile", "offline_access");

  /**
   * Accepts only the dedicated rental-manager applications. Panel, admin, customer, service and
   * mixed-scope tokens fail closed even when they carry a rentalAccess claim.
   */
  public UUID requireRentalManager(Jwt jwt) {
    if (jwt == null
        || !"USER".equals(jwt.getClaimAsString("principal_type"))
        || !"RENTAL_MANAGER".equals(jwt.getClaimAsString("global_role"))
        || !MANAGER_CLIENT_IDS.contains(jwt.getClaimAsString("client_id"))
        || !rentalAccess(jwt)) {
      throw new AccessDeniedException("Dedicated rental manager access is required");
    }
    Set<String> scopes = scopes(jwt);
    Set<String> applicationScopes = new LinkedHashSet<>(scopes);
    applicationScopes.removeAll(INTERACTIVE_PROTOCOL_SCOPES);
    if (!applicationScopes.equals(Set.of("rental.manage"))) {
      throw new AccessDeniedException("Dedicated rental manager scope is required");
    }
    return requiredUuid(jwt.getSubject(), "Bearer subject must be a UUID");
  }

  private static UUID requiredUuid(String value, String message) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new AccessDeniedException(message);
    }
  }

  private static Set<String> scopes(Jwt jwt) {
    Set<String> values = new LinkedHashSet<>();
    Object claim = jwt.getClaims().get("scope");
    if (claim instanceof String text) {
      for (String value : text.split("\\s+")) {
        if (!value.isBlank()) values.add(value);
      }
    } else if (claim instanceof Collection<?> collection) {
      collection.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .filter(value -> !value.isBlank())
          .forEach(values::add);
    }
    Object scp = jwt.getClaims().get("scp");
    if (scp instanceof Collection<?> collection) {
      collection.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .filter(value -> !value.isBlank())
          .forEach(values::add);
    } else if (scp instanceof String text) {
      for (String value : text.split("\\s+")) {
        if (!value.isBlank()) values.add(value);
      }
    }
    return Set.copyOf(values);
  }

  private static boolean rentalAccess(Jwt jwt) {
    Object value = jwt.getClaims().get("rentalAccess");
    if (value instanceof Boolean flag) return flag;
    return value instanceof String text && Boolean.parseBoolean(text);
  }
}
