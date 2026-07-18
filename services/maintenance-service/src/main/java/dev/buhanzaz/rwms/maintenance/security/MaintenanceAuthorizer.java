package dev.buhanzaz.rwms.maintenance.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceAuthorizer {
  private static final UUID DEV_SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d6");
  private final boolean developmentPublicBypass;

  public MaintenanceAuthorizer(
      Environment environment,
      @Value("${rwms.maintenance.security.dev-auth-bypass:false}") boolean developmentAuthBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentPublicBypass = developmentAuthBypass && environment.matchesProfiles("dev") && !production;
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

  public void requireInventoryService(Jwt jwt) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !"inventory-service".equals(jwt.getSubject())
        || !"inventory-service".equals(jwt.getClaimAsString("client_id"))
        || jwt.getAudience().size() != 1
        || !jwt.getAudience().contains("rwms-services")) {
      throw new AccessDeniedException("Exact inventory-service principal is required");
    }
    List<String> granted = scopes(jwt);
    if (granted.size() != 1 || !"maintenance.inventory".equals(granted.getFirst())) {
      throw new AccessDeniedException("Exact maintenance.inventory scope is required");
    }
  }

  public void requireLogisticsService(Jwt jwt) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !"logistics-service".equals(jwt.getSubject())
        || !"logistics-service".equals(jwt.getClaimAsString("client_id"))
        || jwt.getAudience().size() != 1
        || !jwt.getAudience().contains("rwms-services")) {
      throw new AccessDeniedException("Exact logistics-service principal is required");
    }
    List<String> granted = scopes(jwt);
    if (granted.size() != 1 || !"maintenance.logistics".equals(granted.getFirst())) {
      throw new AccessDeniedException("Exact maintenance.logistics scope is required");
    }
  }

  public UUID subjectId(Jwt jwt) {
    if (developmentPublicBypass) return DEV_SUBJECT;
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("USER principal is required");
    }
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
  }

  private void requireUserScope(Jwt jwt, String scope) {
    if (developmentPublicBypass) return;
    if (jwt == null
        || !"USER".equals(jwt.getClaimAsString("principal_type"))
        || !scopes(jwt).contains(scope)) {
      throw new AccessDeniedException("Required USER scope is missing");
    }
  }

  private void requireWarehouse(Jwt jwt, UUID warehouseId, AccessLevel required) {
    if (developmentPublicBypass) return;
    String role = jwt.getClaimAsString("global_role");
    if ("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role)) return;
    Object claim = jwt.getClaims().get("warehouse_access");
    if (claim instanceof Collection<?> entries) {
      for (Object candidate : entries) {
        if (!(candidate instanceof Map<?, ?> access)) continue;
        Object id = access.get("warehouseId");
        Object level = access.get("level");
        if (warehouseId.toString().equals(id) && level instanceof String value) {
          try {
            if (AccessLevel.valueOf(value).ordinal() >= required.ordinal()) return;
          } catch (IllegalArgumentException ignored) {
            // Malformed claims never grant authority.
          }
        }
      }
    }
    throw new AccessDeniedException("Insufficient warehouse access");
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
      if (values.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
        return List.of();
      }
      return values.stream().map(String.class::cast).toList();
    }
    return List.of();
  }
}
