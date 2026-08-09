package dev.buhanzaz.rwms.analytics.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Enforces JWT principal, scope and warehouse-view rules before analytics evidence is queried. */
@Component
public final class AnalyticsAuthorizer {
  private final boolean developmentBypass;

  public AnalyticsAuthorizer(
      Environment environment,
      @Value("${rwms.analytics.security.dev-auth-bypass:false}") boolean configuredBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentBypass = configuredBypass && environment.matchesProfiles("dev") && !production;
  }

  /**
   * Requires a USER principal with {@code rwms.read} and either an administrative role or a
   * warehouse claim granting at least VIEW; malformed claims grant no access.
   */
  public void requireWarehouseView(Jwt jwt, UUID warehouseId) {
    if (developmentBypass) return;
    if (jwt == null
        || warehouseId == null
        || !"USER".equals(jwt.getClaimAsString("principal_type"))
        || !scopes(jwt).contains("rwms.read")) {
      throw forbidden();
    }
    String role = jwt.getClaimAsString("global_role");
    if ("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role)) return;
    Object claim = jwt.getClaims().get("warehouse_access");
    if (claim instanceof Collection<?> entries) {
      for (Object candidate : entries) {
        if (!(candidate instanceof Map<?, ?> access)) continue;
        Object id = access.get("warehouseId");
        Object level = access.get("level");
        if (warehouseId.toString().equals(id) && permitsView(level)) return;
      }
    }
    throw forbidden();
  }

  private static boolean permitsView(Object level) {
    if (!(level instanceof String text)) return false;
    return "VIEW".equals(text) || "EDIT".equals(text) || "MANAGE".equals(text);
  }

  private static List<String> scopes(Jwt jwt) {
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) claim = jwt.getClaims().get("scp");
    if (claim instanceof String text) {
      return java.util.Arrays.stream(text.trim().split("\\s+"))
          .filter(value -> !value.isBlank())
          .toList();
    }
    if (claim instanceof Collection<?> values) {
      return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return List.of();
  }

  private static AccessDeniedException forbidden() {
    return new AccessDeniedException("Analytics warehouse VIEW access is forbidden");
  }
}
