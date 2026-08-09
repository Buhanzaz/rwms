package dev.buhanzaz.rwms.taskboard.security;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Enforces public user and worker-token boundaries for task-board resources.
 *
 * <p>Warehouse access is checked against immutable JWT claims locally. A worker token can act only
 * in its own warehouse; a user token must hold the requested access level unless it has an
 * administration role. The development bypass is constrained to the {@code dev} profile.
 */
@Component
public class WarehouseAccessAuthorizer {
  private final boolean developmentAuthBypass;

  public WarehouseAccessAuthorizer(
      Environment environment,
      @Value("${rwms.security.dev-auth-bypass:false}") boolean developmentAuthBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    this.developmentAuthBypass =
        developmentAuthBypass && environment.matchesProfiles("dev") && !production;
  }

  /** Requires a user principal carrying the supplied public scope. */
  public void requireUserScope(Jwt jwt, String scope) {
    if (developmentAuthBypass) return;
    if (!"USER".equals(jwt.getClaimAsString("principal_type")) || !hasScope(jwt, scope))
      throw new AccessDeniedException("Required USER scope is missing");
  }

  /** Requires task read/write authority for a user or a worker token. */
  public void requireTaskScope(Jwt jwt, boolean write) {
    if (developmentAuthBypass) return;
    String type = jwt.getClaimAsString("principal_type");
    String scope = "WORKER".equals(type) ? "worker.tasks" : write ? "rwms.write" : "rwms.read";
    if (!("USER".equals(type) || "WORKER".equals(type)) || !hasScope(jwt, scope))
      throw new AccessDeniedException("Required task scope is missing");
  }

  /**
   * Requires the requested warehouse access level.
   *
   * <p>When workers are allowed, their {@code warehouse_id} JWT claim is the only accepted
   * warehouse binding; request data cannot substitute it.
   */
  public void requireWarehouse(
      Jwt jwt, UUID warehouseId, AccessLevel required, boolean allowWorker) {
    if (developmentAuthBypass) return;
    String type = jwt.getClaimAsString("principal_type");
    if ("WORKER".equals(type)) {
      if (allowWorker && warehouseId.toString().equals(jwt.getClaimAsString("warehouse_id")))
        return;
      throw new AccessDeniedException("Worker token is not allowed for this operation");
    }
    if (!"USER".equals(type)) throw new AccessDeniedException("Unsupported principal type");
    String role = jwt.getClaimAsString("global_role");
    if ("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role)) return;
    Object accessesClaim = jwt.getClaims().get("warehouse_access");
    if (accessesClaim instanceof List<?> accesses)
      for (Object candidate : accesses) {
        if (!(candidate instanceof java.util.Map<?, ?> access)) continue;
        Object claimedWarehouse = access.get("warehouseId");
        Object claimedLevel = access.get("level");
        if (claimedWarehouse instanceof String warehouse
            && claimedLevel instanceof String level
            && warehouseId.toString().equals(warehouse)) {
          try {
            if (AccessLevel.valueOf(level).ordinal()
                >= required.ordinal()) return;
          } catch (IllegalArgumentException ignored) {
          }
        }
      }
    throw new AccessDeniedException("Insufficient warehouse access");
  }

  /** Requires a global system or WMS administrator for catalog administration. */
  public void requireGlobalManagement(Jwt jwt) {
    if (developmentAuthBypass) return;
    String role = jwt.getClaimAsString("global_role");
    if (!"USER".equals(jwt.getClaimAsString("principal_type"))
        || !("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role)))
      throw new AccessDeniedException("Global management role required");
  }

  /** Returns a worker identity only for a valid worker token; user callers have no worker identity. */
  public UUID workerId(Jwt jwt) {
    if (developmentAuthBypass) return null;
    String type = jwt.getClaimAsString("principal_type");
    if ("USER".equals(type)) return null;
    if (!"WORKER".equals(type)) throw new AccessDeniedException("Unsupported principal type");
    String value = jwt.getClaimAsString("worker_id");
    if (value == null || value.isBlank())
      throw new AccessDeniedException("worker_id claim is required");
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new AccessDeniedException("Invalid worker_id claim");
    }
  }

  private boolean hasScope(Jwt jwt, String required) {
    Object claim = jwt.getClaims().get("scope");
    if (claim instanceof String value) return List.of(value.split(" ")).contains(required);
    if (claim instanceof List<?> values)
      return values.stream().map(String::valueOf).anyMatch(required::equals);
    Object scp = jwt.getClaims().get("scp");
    return scp instanceof List<?> values
        && values.stream().map(String::valueOf).anyMatch(required::equals);
  }
}
