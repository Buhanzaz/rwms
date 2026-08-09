package dev.buhanzaz.rwms.dossier.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Produces a fail-closed warehouse filter; it never authorizes rows after they are loaded. */
@Component
public final class DossierAuthorizer {
  private final boolean developmentBypass;

  public DossierAuthorizer(
      Environment environment,
      @Value("${rwms.dossier.security.dev-auth-bypass:false}") boolean configuredBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentBypass = configuredBypass && environment.matchesProfiles("dev") && !production;
  }

  /**
   * Translates JWT roles and warehouse claims into a SQL-ready read scope. Invalid claim entries
   * grant nothing, so a non-administrator without valid warehouses receives an empty scope.
   */
  public WarehouseScope requireReadScope(Jwt jwt) {
    if (developmentBypass) return new WarehouseScope(true, Set.of());
    if (jwt == null
        || !"USER".equals(jwt.getClaimAsString("principal_type"))
        || !scopes(jwt).contains("rwms.read")) {
      throw new AccessDeniedException("Dossier read access is forbidden");
    }
    String role = jwt.getClaimAsString("global_role");
    if ("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role)) {
      return new WarehouseScope(true, Set.of());
    }
    java.util.LinkedHashSet<UUID> warehouses = new java.util.LinkedHashSet<>();
    Object claim = jwt.getClaims().get("warehouse_access");
    if (claim instanceof Collection<?> entries) {
      for (Object candidate : entries) {
        if (!(candidate instanceof Map<?, ?> access)) continue;
        Object id = access.get("warehouseId");
        Object level = access.get("level");
        if (id instanceof String warehouseId && level instanceof String accessLevel) {
          try {
            if (AccessLevel.valueOf(accessLevel).ordinal() >= AccessLevel.VIEW.ordinal()) {
              warehouses.add(UUID.fromString(warehouseId));
            }
          } catch (IllegalArgumentException ignored) {
            // A malformed claim grants nothing.
          }
        }
      }
    }
    return new WarehouseScope(false, Set.copyOf(warehouses));
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

  private enum AccessLevel { VIEW, EDIT, MANAGE }

  public record WarehouseScope(boolean unrestricted, Set<UUID> warehouseIds) {
    public boolean allows(UUID warehouseId) {
      return unrestricted || warehouseIds.contains(warehouseId);
    }
  }
}
