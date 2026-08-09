package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Builds the sanitized actor reference allowed in task-board integration facts. */
@Component
public class TaskBoardActorReferenceProvider {
  public OpaqueActorReference current() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken
        || !(authentication instanceof JwtAuthenticationToken jwt)) return null;
    String subject = jwt.getToken().getSubject();
    String type = jwt.getToken().getClaimAsString("principal_type");
    if (subject == null || type == null) return null;
    try {
      UUID.fromString(subject);
      return new OpaqueActorReference(subject, type, null);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
