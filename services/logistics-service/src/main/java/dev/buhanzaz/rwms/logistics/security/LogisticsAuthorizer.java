package dev.buhanzaz.rwms.logistics.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Authorization boundary for logistics document APIs, enforcing authenticated role and warehouse
 * scope.
 */
@Component
public class LogisticsAuthorizer {
  private static final UUID DEVELOPMENT_SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-0000000000d8");
  private final boolean developmentBypass;

  public LogisticsAuthorizer(
      Environment environment,
      @Value("${rwms.logistics.security.dev-auth-bypass:false}") boolean configuredBypass) {
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

  public void requireManageBoth(Jwt jwt, UUID originWarehouseId, UUID destinationWarehouseId) {
    requireUserScope(jwt, "rwms.write");
    requireWarehouse(jwt, originWarehouseId, AccessLevel.MANAGE);
    requireWarehouse(jwt, destinationWarehouseId, AccessLevel.MANAGE);
  }

  public void requireManage(Jwt jwt, UUID warehouseId) {
    requireUserScope(jwt, "rwms.write");
    requireWarehouse(jwt, warehouseId, AccessLevel.MANAGE);
  }

  /** Technical recovery changes retry state and therefore remains global-admin only. */
  public void requireWarehouseOperationRecoveryAdministrator(Jwt jwt) {
    requireUserScope(jwt, "rwms.write");
    if (developmentBypass) return;
    String role = jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role) && !"WMS_ADMIN".equals(role)) {
      throw new AccessDeniedException("Global administrator role is required");
    }
  }

  public void requireMaintenanceDriverTaskIntake(Jwt jwt) {
    requireMaintenanceServiceIntake(jwt);
  }

  public void requireMaintenanceEquipmentMovementIntake(Jwt jwt) {
    requireMaintenanceServiceIntake(jwt);
  }

  private void requireMaintenanceServiceIntake(Jwt jwt) {
    if (developmentBypass) return;
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !"maintenance-service".equals(jwt.getSubject())
        || !"maintenance-service".equals(jwt.getClaimAsString("client_id"))
        || !List.of("rwms-services").equals(audiences(jwt))
        || !List.of("logistics.maintenance").equals(scopes(jwt))) {
      throw new AccessDeniedException(
          "Exact maintenance-service logistics.maintenance token is required");
    }
  }

  public UUID subjectId(Jwt jwt) {
    if (developmentBypass) return DEVELOPMENT_SUBJECT;
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("USER principal is required");
    }
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
  }

  /**
   * Returns whether a WORKER carrying the DriverApp task scope has a {@code worker_id} equal to the
   * exact driver frozen on an assigned logistics task. The session subject is deliberately not a
   * worker identity, and this check grants no warehouse-wide visibility.
   */
  public boolean isExactAssignedDriver(Jwt jwt, String audienceMode, UUID plannedDriverWorkerId) {
    if (developmentBypass) return true;
    if (jwt == null
        || !"WORKER".equals(jwt.getClaimAsString("principal_type"))
        || !"ASSIGNED_DRIVER".equals(audienceMode)
        || plannedDriverWorkerId == null
        || !scopes(jwt).contains("driver.tasks")
        || scopes(jwt).contains("worker.tasks")) {
      return false;
    }
    Object workerClaim = jwt.getClaims().get("worker_id");
    if (!(workerClaim instanceof String workerId)) return false;
    try {
      return plannedDriverWorkerId.equals(UUID.fromString(workerId));
    } catch (IllegalArgumentException exception) {
      return false;
    }
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
    if (warehouseId == null) throw new AccessDeniedException("Warehouse scope is required");
    if (developmentBypass) return;
    String globalRole = jwt == null ? null : jwt.getClaimAsString("global_role");
    if ("SYSTEM_ADMIN".equals(globalRole) || "WMS_ADMIN".equals(globalRole)) return;
    Object claim = jwt == null ? null : jwt.getClaims().get("warehouse_access");
    if (claim instanceof Collection<?> entries) {
      for (Object entry : entries) {
        if (!(entry instanceof Map<?, ?> access)) continue;
        Object id = access.get("warehouseId");
        Object level = access.get("level");
        if (warehouseId.toString().equals(id) && level instanceof String value) {
          try {
            if (AccessLevel.valueOf(value).ordinal() >= required.ordinal()) return;
          } catch (IllegalArgumentException ignored) {
            // A malformed access claim never grants authority.
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
      return values.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .filter(value -> !value.isBlank())
          .toList();
    }
    return List.of();
  }

  private static List<String> audiences(Jwt jwt) {
    if (jwt == null) return List.of();
    List<String> audiences = jwt.getAudience();
    return audiences == null ? List.of() : audiences;
  }

  private enum AccessLevel {
    VIEW,
    EDIT,
    MANAGE
  }
}
