package dev.buhanzaz.rwms.warehouse.security;

import dev.buhanzaz.rwms.warehouse.service.WarehouseOperationSource;
import dev.buhanzaz.rwms.warehouse.service.WarehouseLifecycleReadinessOwner;
import java.util.ArrayList;
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

/**
 * Enforces the API's deliberately narrow user and service-credential boundaries.
 *
 * <p>Public directory access requires a user principal, while every internal route checks an exact
 * service identity and single-purpose scope. For owner-attributed operations, the credential — not
 * the request body — determines the owner.
 */
@Component
public class WarehouseAuthorizer {
  private static final UUID DEVELOPMENT_SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final Set<String> INTERACTIVE_PROTOCOL_SCOPES =
      Set.of("openid", "profile", "offline_access");
  private static final String ADMIN_WEB_CLIENT_ID = "rwms-admin-web";
  private static final Set<String> RENTAL_MANAGER_CLIENT_IDS =
      Set.of("rwms-rental-manager-web", "rwms-rental-manager-android");
  private static final Set<String> RENTAL_STAFF_ROLES =
      Set.of("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER", "VIEWER");
  private final boolean developmentPublicBypass;

  /**
   * Creates the authorization boundary and constrains its development bypass.
   *
   * @param environment active environment used to constrain the development-only bypass
   * @param developmentAuthBypass configured development-only public access flag
   */
  public WarehouseAuthorizer(
      Environment environment,
      @Value("${rwms.security.dev-auth-bypass:false}") boolean developmentAuthBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentPublicBypass =
        developmentAuthBypass && environment.matchesProfiles("dev") && !production;
  }

  /**
   * Requires a user principal with directory-read authority.
   *
   * @param jwt authenticated caller
   */
  public void requireWarehouseDirectoryRead(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    if (isRentalManagerCredential(jwt)) {
      requireRentalManagerDirectoryRead(jwt);
      return;
    }
    if (scopes(jwt).contains("admin.manage")) {
      if (!hasAdministratorRole(jwt)) {
        throw new AccessDeniedException("Administrator role is required");
      }
      requireExactApplicationScope(jwt, "admin.manage");
      return;
    }
    requireScope(jwt, "warehouse.read");
  }

  /** Requires ordinary RWMS warehouse-read authority for one direct identity lookup. */
  public void requireWarehouseRead(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    requireScope(jwt, "warehouse.read");
  }

  /**
   * Requires a system or WMS administrator with mutation authority.
   *
   * @param jwt authenticated user caller
   */
  public void requireAdminWrite(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    if (!hasAdministratorRole(jwt)) {
      throw new AccessDeniedException("Administrator role is required");
    }
    if (scopes(jwt).contains("admin.manage")) {
      requireExactApplicationScope(jwt, "admin.manage");
      return;
    }
    requireScope(jwt, "rwms.write");
  }

  /**
   * Requires a system administrator with mutation authority.
   *
   * @param jwt authenticated caller
   */
  public void requireSystemAdminWrite(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireSystemAdmin(jwt);
    requireScope(jwt, "rwms.write");
  }

  /**
   * Requires a warehouse manager or administrator who can view the served warehouse.
   *
   * @param jwt authenticated user
   * @param servedWarehouseId warehouse whose support collection is read
   */
  public void requireWarehouseSupportRead(Jwt jwt, UUID servedWarehouseId) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    if (isAdministrationApplication(jwt)) return;
    requireScope(jwt, "warehouse.read");
    requireWarehouseManagementRole(jwt);
    requireWarehouseGrant(jwt, servedWarehouseId, 0);
  }

  /**
   * Requires MANAGE grants for the served warehouse and every resource-providing warehouse.
   *
   * <p>Checking both ends prevents a local manager from volunteering another warehouse's drivers,
   * vehicles or inventory without authority over that source.
   *
   * @param jwt authenticated user
   * @param servedWarehouseId warehouse whose support collection changes
   * @param supportWarehouseIds resource-providing warehouses in the replacement
   */
  public void requireWarehouseSupportWrite(
      Jwt jwt, UUID servedWarehouseId, Collection<UUID> supportWarehouseIds) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    if (isAdministrationApplication(jwt)) return;
    requireScope(jwt, "rwms.write");
    requireWarehouseManagementRole(jwt);
    requireWarehouseGrant(jwt, servedWarehouseId, 2);
    Set.copyOf(supportWarehouseIds).forEach(id -> requireWarehouseGrant(jwt, id, 2));
  }

  /**
   * Requires a user principal whose global role is exactly {@code SYSTEM_ADMIN}.
   *
   * @param jwt authenticated caller
   */
  public void requireSystemAdmin(Jwt jwt) {
    if (developmentPublicBypass) return;
    requireUser(jwt);
    if (!"SYSTEM_ADMIN".equals(jwt.getClaimAsString("global_role"))) {
      throw new AccessDeniedException("SYSTEM_ADMIN role is required");
    }
  }

  /**
   * Tests whether the caller has the system-administrator role.
   *
   * @param jwt authenticated caller, if any
   * @return whether the caller is a system administrator
   */
  public boolean isSystemAdmin(Jwt jwt) {
    return developmentPublicBypass
        || (jwt != null
            && "USER".equals(jwt.getClaimAsString("principal_type"))
            && "SYSTEM_ADMIN".equals(jwt.getClaimAsString("global_role")));
  }

  /**
   * Returns the UUID user identity used to scope create idempotency records.
   *
   * @param jwt authenticated user caller
   * @return caller UUID
   */
  public UUID subjectId(Jwt jwt) {
    if (developmentPublicBypass) return DEVELOPMENT_SUBJECT;
    requireUser(jwt);
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
  }

  /**
   * Requires the narrow internal credential issued to {@code auth-service}.
   *
   * @param jwt authenticated service caller
   */
  public void requireInternalAuthService(Jwt jwt) {
    requireInternalWarehouseReader(jwt, "auth-service");
  }

  /**
   * A separate private registry path keeps asset-service from using auth-service's endpoint.
   *
   * @param jwt authenticated asset-service caller
   */
  public void requireInternalAssetService(Jwt jwt) {
    requireInternalWarehouseReader(jwt, "asset-service");
  }

  /** Requires task-board-service and its single-purpose warehouse identity scope. */
  public void requireInternalTaskBoardIdentityReader(Jwt jwt) {
    String clientId = "task-board-service";
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !clientId.equals(jwt.getClaimAsString("client_id"))
        || !clientId.equals(jwt.getSubject())
        || !exactlyScope(jwt, "warehouse.identity.read")) {
      throw new AccessDeniedException(
          "Only task-board-service with exactly warehouse.identity.read may use this endpoint");
    }
  }

  /**
   * Inventory receives only active warehouse identity, version, and canonical timezone.
   *
   * @param jwt authenticated inventory-service caller
   */
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

  /**
   * Logistics receives only an exact warehouse identity for origin/destination validation.
   *
   * @param jwt authenticated logistics-service caller
   */
  public void requireInternalLogisticsService(Jwt jwt) {
    String clientId = "logistics-service";
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !clientId.equals(jwt.getClaimAsString("client_id"))
        || !clientId.equals(jwt.getSubject())
        || !exactlyScope(jwt, "warehouse.logistics")) {
      throw new AccessDeniedException(
          "Only logistics-service with matching subject and exactly warehouse.logistics may use this"
              + " endpoint");
    }
  }

  /**
   * Operation owners resolve a historical timezone only through this explicit as-of contract.
   * A broad warehouse.read token cannot be repurposed for it.
   *
   * @param jwt authenticated lifecycle-owner caller
   */
  public void requireInternalTimeZoneReader(Jwt jwt) {
    requireKnownLifecycleOwner(jwt, "warehouse.timezone.read");
  }

  /**
   * The authenticated client determines the durable operation source. Request bodies cannot
   * choose a source and therefore cannot impersonate another owning workflow.
   *
   * @param jwt authenticated operation-owner caller
   * @return operation source inferred from the client identity
   */
  public WarehouseOperationSource requireInternalOperationMarker(Jwt jwt) {
    return requireKnownOperationOwner(jwt, "warehouse.operation.mark");
  }

  /**
   * Resource owners ask this narrow boundary whether one directional operation is admitted.
   *
   * @param jwt authenticated lifecycle-owner caller
   */
  public void requireInternalLifecycleAdmissionReader(Jwt jwt) {
    requireKnownLifecycleOwner(jwt, "warehouse.lifecycle.read");
  }

  /**
   * A durable pull backlog lets every owner reconcile even when an event was missed or it owns no
   * live records for the warehouse.
   *
   * @param jwt authenticated lifecycle-owner caller
   * @return lifecycle owner inferred from the client identity
   */
  public WarehouseLifecycleReadinessOwner requireInternalLifecycleWorkReader(Jwt jwt) {
    return requireKnownLifecycleOwner(jwt, "warehouse.lifecycle.read");
  }

  /**
   * Lifecycle readiness is attributed to the authenticated resource owner, never a request body.
   *
   * @param jwt authenticated lifecycle-owner caller
   * @return lifecycle owner inferred from the client identity
   */
  public WarehouseLifecycleReadinessOwner requireInternalLifecycleReadinessConfirmer(Jwt jwt) {
    return requireKnownLifecycleOwner(jwt, "warehouse.lifecycle.confirm");
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

  private WarehouseOperationSource requireKnownOperationOwner(Jwt jwt, String scope) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !exactlyScope(jwt, scope)) {
      throw new AccessDeniedException("A recognized operation owner with the exact scope is required");
    }
    String clientId = jwt.getClaimAsString("client_id");
    String subject = jwt.getSubject();
    if (clientId == null || !clientId.equals(subject)) {
      throw new AccessDeniedException("Operation owner client_id and subject must match");
    }
    try {
      return WarehouseOperationSource.requireClientId(clientId);
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("Only a recognized operation owner may use this endpoint");
    }
  }

  private WarehouseLifecycleReadinessOwner requireKnownLifecycleOwner(Jwt jwt, String scope) {
    if (jwt == null
        || !"SERVICE".equals(jwt.getClaimAsString("principal_type"))
        || !exactlyScope(jwt, scope)) {
      throw new AccessDeniedException("A recognized lifecycle owner with the exact scope is required");
    }
    String clientId = jwt.getClaimAsString("client_id");
    String subject = jwt.getSubject();
    if (clientId == null || !clientId.equals(subject)) {
      throw new AccessDeniedException("Lifecycle owner client_id and subject must match");
    }
    try {
      return WarehouseLifecycleReadinessOwner.requireClientId(clientId);
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("Only a recognized lifecycle owner may use this endpoint");
    }
  }

  private void requireUser(Jwt jwt) {
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("USER principal is required");
    }
  }

  private static void requireWarehouseManagementRole(Jwt jwt) {
    String role = jwt.getClaimAsString("global_role");
    if (!"SYSTEM_ADMIN".equals(role)
        && !"WMS_ADMIN".equals(role)
        && !"WAREHOUSE_MANAGER".equals(role)) {
      throw new AccessDeniedException("Warehouse management role is required");
    }
  }

  private static boolean hasAdministratorRole(Jwt jwt) {
    String role = jwt.getClaimAsString("global_role");
    return "SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role);
  }

  private boolean isAdministrationApplication(Jwt jwt) {
    return jwt != null
        && "USER".equals(jwt.getClaimAsString("principal_type"))
        && ADMIN_WEB_CLIENT_ID.equals(jwt.getClaimAsString("client_id"))
        && hasAdministratorRole(jwt)
        && hasExactApplicationScope(jwt, "admin.manage");
  }

  private static boolean isRentalManagerCredential(Jwt jwt) {
    return isRentalManagerClient(jwt)
        || "RENTAL_MANAGER".equals(jwt.getClaimAsString("global_role"))
        || scopes(jwt).contains("rental.manage");
  }

  private void requireRentalManagerDirectoryRead(Jwt jwt) {
    if (!isRentalManagerClient(jwt)) {
      throw new AccessDeniedException("Dedicated rental-manager client is required");
    }
    if (!RENTAL_STAFF_ROLES.contains(jwt.getClaimAsString("global_role"))) {
      throw new AccessDeniedException("Recognized staff role is required");
    }
    if (!rentalAccess(jwt)) {
      throw new AccessDeniedException("Rental access is required");
    }
    requireExactApplicationScope(jwt, "rental.manage");
  }

  private static boolean isRentalManagerClient(Jwt jwt) {
    String clientId = jwt.getClaimAsString("client_id");
    return clientId != null && RENTAL_MANAGER_CLIENT_IDS.contains(clientId);
  }

  private static boolean rentalAccess(Jwt jwt) {
    Object value = jwt.getClaims().get("rentalAccess");
    if (value instanceof Boolean flag) return flag;
    return value instanceof String text && Boolean.parseBoolean(text);
  }

  private static void requireWarehouseGrant(Jwt jwt, UUID warehouseId, int requiredRank) {
    String role = jwt.getClaimAsString("global_role");
    if ("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role)) return;
    Object claim = jwt.getClaims().get("warehouse_access");
    if (claim instanceof Collection<?> entries) {
      for (Object candidate : entries) {
        if (!(candidate instanceof Map<?, ?> access)) continue;
        if (!warehouseId.toString().equals(access.get("warehouseId"))) continue;
        Object level = access.get("level");
        if (level instanceof String value && warehouseAccessRank(value) >= requiredRank) return;
      }
    }
    throw new AccessDeniedException("Insufficient warehouse access");
  }

  private static int warehouseAccessRank(String value) {
    return switch (value) {
      case "VIEW" -> 0;
      case "EDIT" -> 1;
      case "MANAGE" -> 2;
      default -> -1;
    };
  }

  private void requireScope(Jwt jwt, String required) {
    if (!scopes(jwt).contains(required)) {
      throw new AccessDeniedException("Required scope is missing");
    }
  }

  private void requireExactApplicationScope(Jwt jwt, String required) {
    if (!hasExactApplicationScope(jwt, required)) {
      throw new AccessDeniedException("Exact application scope is required");
    }
  }

  private static boolean hasExactApplicationScope(Jwt jwt, String required) {
    List<String> applicationScopes =
        scopes(jwt).stream()
            .filter(scope -> !INTERACTIVE_PROTOCOL_SCOPES.contains(scope))
            .toList();
    return applicationScopes.size() == 1 && required.equals(applicationScopes.getFirst());
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
