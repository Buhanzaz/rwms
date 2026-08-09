package dev.buhanzaz.rwms.assistant.service;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Requires the rental-access claim and UUID subject before a conversation operation can use owner-scoped state. */
@Component
public class AssistantAuthorizer {
  /**
   * Converts a bearer token to the conversation owner only after its rental-access claim and UUID
   * subject are validated; malformed or absent claims fail closed.
   */
  public UUID requireRentalUser(Jwt jwt) {
    if (jwt == null || !rentalAccess(jwt)) {
      throw new AccessDeniedException("rentalAccess=true is required");
    }
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("Bearer subject must be a UUID");
    }
  }

  private static boolean rentalAccess(Jwt jwt) {
    Object value = jwt.getClaims().get("rentalAccess");
    if (value instanceof Boolean flag) return flag;
    return value instanceof String text && Boolean.parseBoolean(text);
  }
}
