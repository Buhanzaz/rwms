package dev.buhanzaz.rwms.warehouse.eventing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Deliberately narrow authorization for reviewed recovery of a terminal warehouse outbox event.
 * It is separate from ordinary warehouse metadata administration: both global administrator
 * roles may perform this audited operational repair, but no warehouse-local role may.
 */
@Component
public final class WarehouseOutboxRecoveryAccessAuthorizer {
  private static final UUID DEVELOPMENT_SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private final boolean developmentPublicBypass;

  public WarehouseOutboxRecoveryAccessAuthorizer(
      Environment environment,
      @Value("${rwms.security.dev-auth-bypass:false}") boolean developmentAuthBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentPublicBypass =
        developmentAuthBypass && environment.matchesProfiles("dev") && !production;
  }

  public RecoveryPrincipal requireRecoveryAdministrator(Jwt jwt) {
    if (developmentPublicBypass) {
      return new RecoveryPrincipal(DEVELOPMENT_SUBJECT);
    }
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("USER principal is required");
    }
    if (!exactlyRwmsWrite(jwt)) {
      throw new AccessDeniedException("Exactly rwms.write scope is required");
    }
    String role = jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role) && !"WMS_ADMIN".equals(role)) {
      throw new AccessDeniedException("SYSTEM_ADMIN or WMS_ADMIN role is required");
    }
    if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
      throw new AccessDeniedException("USER token must contain a subject");
    }
    try {
      return new RecoveryPrincipal(UUID.fromString(jwt.getSubject()));
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID value");
    }
  }

  /** Signed identity used as the recovery audit actor. */
  public record RecoveryPrincipal(UUID subjectId) {}

  private static boolean exactlyRwmsWrite(Jwt jwt) {
    List<String> scopes = scopeValues(jwt);
    return scopes.size() == 1 && "rwms.write".equals(scopes.getFirst());
  }

  private static List<String> scopeValues(Jwt jwt) {
    Object scope = jwt.getClaims().get("scope");
    if (scope == null) scope = jwt.getClaims().get("scp");
    if (scope instanceof String value) {
      return List.of(value.trim().split("\\s+")).stream().filter(item -> !item.isEmpty()).toList();
    }
    if (scope instanceof Collection<?> collection) {
      List<String> values = new ArrayList<>();
      for (Object candidate : collection) {
        if (!(candidate instanceof String value) || value.isBlank()) return List.of();
        values.add(value);
      }
      return List.copyOf(values);
    }
    return List.of();
  }
}
