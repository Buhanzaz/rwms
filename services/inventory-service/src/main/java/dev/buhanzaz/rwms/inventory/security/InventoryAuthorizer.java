package dev.buhanzaz.rwms.inventory.security;

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

@Component
public class InventoryAuthorizer {
  private static final UUID DEV_SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d7");
  private final boolean developmentBypass;

  public InventoryAuthorizer(
      Environment environment,
      @Value("${rwms.inventory.security.dev-auth-bypass:false}") boolean configuredBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentBypass = configuredBypass && environment.matchesProfiles("dev") && !production;
  }

  public void requireRead(Jwt jwt, UUID warehouseId) {
    requireUserScope(jwt, "rwms.read");
    requireWarehouse(jwt, warehouseId, AccessLevel.VIEW);
  }

  public void requireEdit(Jwt jwt, UUID warehouseId) {
    requireUserScope(jwt, "rwms.write");
    requireWarehouse(jwt, warehouseId, AccessLevel.EDIT);
  }

  public void requireManage(Jwt jwt, UUID warehouseId) {
    requireUserScope(jwt, "rwms.write");
    requireWarehouse(jwt, warehouseId, AccessLevel.MANAGE);
  }

  public WarehouseScope readScope(Jwt jwt) {
    return scope(jwt, "rwms.read", AccessLevel.VIEW);
  }

  public WarehouseScope editScope(Jwt jwt) {
    return scope(jwt, "rwms.write", AccessLevel.EDIT);
  }

  public WarehouseScope manageScope(Jwt jwt) {
    return scope(jwt, "rwms.write", AccessLevel.MANAGE);
  }

  public UUID subjectId(Jwt jwt) {
    if (developmentBypass) return DEV_SUBJECT;
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("USER principal is required");
    }
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
  }

  public String profileRevision(Jwt jwt) {
    if (developmentBypass || jwt == null) return null;
    Object claim = jwt.getClaims().get("profile_revision");
    return claim instanceof String value ? value : null;
  }

  public String displayName(Jwt jwt) {
    if (developmentBypass) return "Development user";
    subjectId(jwt);
    String preferredUsername = jwt.getClaimAsString("preferred_username");
    if (preferredUsername != null && !preferredUsername.isBlank()) {
      return preferredUsername.trim();
    }
    return jwt.getSubject();
  }

  private void requireUserScope(Jwt jwt, String requiredScope) {
    if (developmentBypass) return;
    if (jwt == null
        || !"USER".equals(jwt.getClaimAsString("principal_type"))
        || !scopes(jwt).contains(requiredScope)) {
      throw new AccessDeniedException("Required USER scope is missing");
    }
  }

  private void requireWarehouse(Jwt jwt, UUID warehouseId, AccessLevel required) {
    WarehouseScope scope = warehouseScope(jwt, required);
    if (scope.unrestricted() || scope.warehouseIds().contains(warehouseId)) return;
    throw new AccessDeniedException("Insufficient warehouse access");
  }

  private WarehouseScope scope(Jwt jwt, String requiredScope, AccessLevel required) {
    requireUserScope(jwt, requiredScope);
    return warehouseScope(jwt, required);
  }

  private WarehouseScope warehouseScope(Jwt jwt, AccessLevel required) {
    if (developmentBypass) return new WarehouseScope(true, Set.of());
    String role = jwt.getClaimAsString("global_role");
    if ("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role)) {
      return new WarehouseScope(true, Set.of());
    }
    java.util.LinkedHashSet<UUID> warehouseIds = new java.util.LinkedHashSet<>();
    Object claim = jwt.getClaims().get("warehouse_access");
    if (claim instanceof Collection<?> entries) {
      for (Object candidate : entries) {
        if (!(candidate instanceof Map<?, ?> access)) continue;
        Object id = access.get("warehouseId");
        Object level = access.get("level");
        if (id instanceof String warehouse && level instanceof String value) {
          try {
            if (AccessLevel.valueOf(value).ordinal() >= required.ordinal()) {
              warehouseIds.add(UUID.fromString(warehouse));
            }
          } catch (IllegalArgumentException ignored) {
            // A malformed claim never grants authority.
          }
        }
      }
    }
    return new WarehouseScope(false, Set.copyOf(warehouseIds));
  }

  private static List<String> scopes(Jwt jwt) {
    if (jwt == null) return List.of();
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) claim = jwt.getClaims().get("scp");
    if (claim instanceof String value) {
      return java.util.Arrays.stream(value.trim().split("\\s+"))
          .filter(item -> !item.isBlank())
          .toList();
    }
    if (claim instanceof Collection<?> values) {
      return values.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .filter(value -> !value.isBlank())
          .toList();
    }
    return List.of();
  }

  private enum AccessLevel {
    VIEW,
    EDIT,
    MANAGE
  }

  public record WarehouseScope(boolean unrestricted, Set<UUID> warehouseIds) {}
}
