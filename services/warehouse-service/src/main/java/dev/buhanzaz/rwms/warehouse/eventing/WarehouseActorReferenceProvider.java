package dev.buhanzaz.rwms.warehouse.eventing;

import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class WarehouseActorReferenceProvider {
  public OpaqueActorReference current() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken
        || !(authentication instanceof JwtAuthenticationToken jwt)) return null;
    String subject = jwt.getToken().getSubject();
    String principalType = jwt.getToken().getClaimAsString("principal_type");
    if (subject == null || principalType == null) return null;
    try {
      UUID.fromString(subject);
      return new OpaqueActorReference(subject, principalType, null);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
