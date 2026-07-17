package dev.buhanzaz.rwms.warehouse.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class WarehouseAuthorizer {
  private static final UUID DEVELOPMENT_SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private final boolean developmentPublicBypass;

  public WarehouseAuthorizer(
      Environment environment,
      @Value("${rwms.security.dev-auth-bypass:false}") boolean developmentAuthBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentPublicBypass =
        developmentAuthBypass && environment.matchesProfiles("dev") && !production;
  }

  public void requireWarehouseRead(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    requireScope(jwt, "warehouse.read");
  }

  public void requireSystemAdminWrite(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireSystemAdmin(jwt);
    requireScope(jwt, "rwms.write");
  }

  public void requireSystemAdmin(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    if (!"SYSTEM_ADMIN".equals(jwt.getClaimAsString("global_role"))) {
      throw new AccessDeniedException("SYSTEM_ADMIN role is required");
    }
  }

  public boolean isSystemAdmin(Jwt jwt) {
    return developmentPublicBypass
        || (jwt != null
            && "USER".equals(jwt.getClaimAsString("principal_type"))
            && "SYSTEM_ADMIN".equals(jwt.getClaimAsString("global_role")));
  }

  public UUID subjectId(Jwt jwt) {
    if (developmentPublicBypass) return DEVELOPMENT_SUBJECT;
    requireUser(jwt);
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
  }

  public void requireInternalAuthService(Jwt jwt) {
    requireInternalWarehouseReader(jwt, "auth-service");
  }

  /** A separate private registry path keeps asset-service from using auth-service's endpoint. */
  public void requireInternalAssetService(Jwt jwt) {
    requireInternalWarehouseReader(jwt, "asset-service");
  }

  /** Inventory receives only active warehouse identity, version, and canonical timezone. */
  public void requireInternalInventoryService(Jwt jwt) {
    String clientId = "inventory-service";
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !clientId.equals(jwt.getClaimAsString("client_id"))
        || !clientId.equals(jwt.getSubject())
        || !exactlyWarehouseRead(jwt)) {
      throw new AccessDeniedException(
          "Only inventory-service with matching subject and exactly warehouse.read may use this"
              + " endpoint");
    }
  }

  private void requireInternalWarehouseReader(Jwt jwt, String clientId) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !clientId.equals(jwt.getClaimAsString("client_id"))
        || !exactlyWarehouseRead(jwt)) {
      throw new AccessDeniedException(
          "Only " + clientId + " with exactly warehouse.read may use this endpoint");
    }
  }

  private void requireUser(Jwt jwt) {
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("USER principal is required");
    }
  }

  private void requireScope(Jwt jwt, String required) {
    if (!scopes(jwt).contains(required)) {
      throw new AccessDeniedException("Required scope is missing");
    }
  }

  private boolean exactlyWarehouseRead(Jwt jwt) {
    return exactlyScope(jwt, "warehouse.read");
  }

  private boolean exactlyScope(Jwt jwt, String expectedScope) {
    List<String> values = scopeValues(jwt);
    return values.size() == 1 && expectedScope.equals(values.getFirst());
  }

  private static List<String> scopes(Jwt jwt) {
    return List.copyOf(scopeValues(jwt));
  }

  private static List<String> scopeValues(Jwt jwt) {
    if (jwt == null) return List.of();
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
