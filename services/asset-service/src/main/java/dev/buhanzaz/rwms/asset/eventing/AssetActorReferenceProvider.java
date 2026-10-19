package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Extracts an opaque actor reference for asset events only when the current JWT has a valid UUID
 * subject. Missing, anonymous or malformed authentication deliberately produces no actor record.
 */
@Component
public class AssetActorReferenceProvider {
  public OpaqueActorReference current() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken
        || !(authentication instanceof JwtAuthenticationToken jwt)) return null;
    String subject = jwt.getToken().getSubject();
    String principalType = jwt.getToken().getClaimAsString("principal_type");
    try {
      UUID.fromString(subject);
      return new OpaqueActorReference(subject, principalType, null);
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return null;
    }
  }
}
