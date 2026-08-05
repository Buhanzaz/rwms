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
  private static final String MAINTENANCE_CLIENT_ID = "maintenance-service";
  private static final String INVENTORY_CLIENT_ID = "inventory-service";
  private static final String LOGISTICS_CLIENT_ID = "logistics-service";
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

  public void requireHtmlImportCommit(Jwt jwt, UUID warehouseId) {
    requireEdit(jwt, warehouseId);
    if (developmentPublicBypass) return;
    String role = jwt == null ? null : jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role) && !"WMS_ADMIN".equals(role)) {
      throw new AccessDeniedException("HTML import commit requires an administrator role");
    }
  }

  /**
   * A correction changes recorded custody in two warehouse scopes. It is not
   * an operator movement permission: only global administrators can make the
   * exceptional correction after both warehouse MANAGE grants have passed.
   */
  public void requireAdministrativeCorrection(
      Jwt jwt, UUID sourceWarehouseId, UUID targetWarehouseId) {
    requireManage(jwt, sourceWarehouseId);
    requireManage(jwt, targetWarehouseId);
    if (developmentPublicBypass) return;
    String role = jwt == null ? null : jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role) && !"WMS_ADMIN".equals(role)) {
      throw new AccessDeniedException(
          "Administrative correction requires a SYSTEM_ADMIN or WMS_ADMIN role");
    }
  }

  /** Orders use the constrained internal API; only warehouse operators and admins may mutate arbitrary balances. */
  public void requireEquipmentMovement(Jwt jwt, UUID warehouseId) {
    requireManage(jwt, warehouseId);
    if (developmentPublicBypass) return;
    String role = jwt == null ? null : jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role)
        && !"WMS_ADMIN".equals(role)
        && !"WAREHOUSE_MANAGER".equals(role)) {
      throw new AccessDeniedException(
          "This role cannot perform arbitrary equipment movements");
    }
  }

  public void requireGlobalCatalogManagement(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUserScope(jwt, "rwms.write");
    String role = jwt == null ? null : jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role) && !"WMS_ADMIN".equals(role)) {
      throw new AccessDeniedException("Global catalog management role is required");
    }
  }

  /** Terminal outbox recovery is a global administrative operation, never a warehouse-local grant. */
  public void requireOutboxRecovery(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUserScope(jwt, "rwms.write");
    String role = jwt == null ? null : jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role) && !"WMS_ADMIN".equals(role)) {
      throw new AccessDeniedException("Outbox recovery requires an administrator role");
    }
  }

  public void requireGlobalCatalogRead(Jwt jwt) {
    requireUserScope(jwt, "rwms.read");
  }

  /** The Stage 6 credential can reach only the maintenance lease/fence controller. */
  public void requireMaintenanceAssetAccess(Jwt jwt) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !MAINTENANCE_CLIENT_ID.equals(jwt.getClaimAsString("client_id"))
        || !MAINTENANCE_CLIENT_ID.equals(jwt.getSubject())
        || !exactScope(jwt, "asset.maintenance")) {
      throw new AccessDeniedException(
          "The maintenance-service credential with exactly asset.maintenance is required");
    }
  }

  public UUID maintenanceSubjectId(Jwt jwt) {
    requireMaintenanceAssetAccess(jwt);
    return serviceSubjectId(MAINTENANCE_CLIENT_ID);
  }

  /** Stage 7 inventory may reach only its read/capture/source-create boundary. */
  public void requireInventoryAssetAccess(Jwt jwt) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !INVENTORY_CLIENT_ID.equals(jwt.getClaimAsString("client_id"))
        || !INVENTORY_CLIENT_ID.equals(jwt.getSubject())
        || !exactScope(jwt, "asset.inventory")) {
      throw new AccessDeniedException(
          "The inventory-service credential with exactly asset.inventory is required");
    }
  }

  public UUID inventorySubjectId(Jwt jwt) {
    requireInventoryAssetAccess(jwt);
    return serviceSubjectId(INVENTORY_CLIENT_ID);
  }

  /** Stage 8 may reach only the dedicated logistics lease/effect/hold surface. */
  public void requireLogisticsAssetAccess(Jwt jwt) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !LOGISTICS_CLIENT_ID.equals(jwt.getClaimAsString("client_id"))
        || !LOGISTICS_CLIENT_ID.equals(jwt.getSubject())
        || !exactScope(jwt, "asset.logistics")) {
      throw new AccessDeniedException(
          "The logistics-service credential with exactly asset.logistics is required");
    }
  }

  public UUID logisticsSubjectId(Jwt jwt) {
    requireLogisticsAssetAccess(jwt);
    return serviceSubjectId(LOGISTICS_CLIENT_ID);
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

  private static UUID serviceSubjectId(String clientId) {
    return UUID.nameUUIDFromBytes(("service:" + clientId).getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> scopes(Jwt jwt) {
    if (jwt == null) return List.of();
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) claim = jwt.getClaims().get("scp");
    if (claim instanceof String value) {
      return List.of(value.trim().split("\\s+")).stream().filter(item -> !item.isBlank()).toList();
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
