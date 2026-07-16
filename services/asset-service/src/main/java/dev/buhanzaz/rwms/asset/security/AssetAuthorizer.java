package dev.buhanzaz.rwms.asset.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class AssetAuthorizer {
  private static final UUID DEV_SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
  private final boolean developmentPublicBypass;

  public AssetAuthorizer(
      Environment environment,
      @Value("${rwms.security.dev-auth-bypass:false}") boolean developmentAuthBypass) {
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

  public void requireGlobalCatalogManagement(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUserScope(jwt, "rwms.write");
    String role = jwt == null ? null : jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role) && !"WMS_ADMIN".equals(role)) {
      throw new AccessDeniedException("Global catalog management role is required");
    }
  }

  public void requireGlobalCatalogRead(Jwt jwt) {
    requireUserScope(jwt, "rwms.read");
  }

  /** Private Asset APIs are usable only by a service credential minted with one exact scope. */
  public void requireInternalAssetAccess(Jwt jwt) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || jwt.getClaimAsString("client_id") == null
        || jwt.getClaimAsString("client_id").isBlank()
        || !exactScope(jwt, "asset.internal")) {
      throw new AccessDeniedException("A service credential with exactly asset.internal is required");
    }
  }

  public UUID internalSubjectId(Jwt jwt) {
    requireInternalAssetAccess(jwt);
    return UUID.nameUUIDFromBytes(("service:" + jwt.getClaimAsString("client_id"))
        .getBytes(StandardCharsets.UTF_8));
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
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type")) || !scopes(jwt).contains(scope)) {
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
            // A malformed access level cannot grant access.
          }
        }
      }
    }
    throw new AccessDeniedException("Insufficient warehouse access");
  }

  private static boolean exactScope(Jwt jwt, String expected) {
    List<String> values = scopes(jwt);
    return values.size() == 1 && expected.equals(values.getFirst());
  }

  private static List<String> scopes(Jwt jwt) {
    if (jwt == null) return List.of();
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) claim = jwt.getClaims().get("scp");
    if (claim instanceof String value) {
      return List.of(value.trim().split("\\s+")).stream().filter(item -> !item.isBlank()).toList();
    }
    if (claim instanceof Collection<?> values) {
      return values.stream().filter(String.class::isInstance).map(String.class::cast).filter(value -> !value.isBlank()).toList();
    }
    return List.of();
  }
}
