package dev.buhanzaz.rwms.logistics.order.security;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Authorization boundary for rental-order operations, including role and warehouse access checks.
 */
@Component
public class OrderAuthorizer {
  private static final UUID DEVELOPMENT_SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-0000000000d8");
  private static final String RENTAL_MANAGER_ROLE = "RENTAL_MANAGER";
  private static final String RENTAL_MANAGER_SCOPE = "rental.manage";
  private static final String ADMIN_WEB_CLIENT_ID = "rwms-admin-web";
  private static final Set<String> INTERACTIVE_PROTOCOL_SCOPES =
      Set.of("openid", "profile", "offline_access");
  private static final Set<String> RENTAL_MANAGER_CLIENT_IDS =
      Set.of("rwms-rental-manager-web", "rwms-rental-manager-android");
  private static final Set<String> ROLES =
      Set.of(
          "SYSTEM_ADMIN",
          "WMS_ADMIN",
          "WAREHOUSE_MANAGER",
          RENTAL_MANAGER_ROLE,
          "VIEWER");

  private final boolean developmentBypass;

  public OrderAuthorizer(
      Environment environment,
      @Value("${rwms.logistics.security.dev-auth-bypass:false}") boolean configuredBypass) {
    boolean production = environment.matchesProfiles("prod", "production");
    developmentBypass =
        configuredBypass && environment.matchesProfiles("dev") && !production;
  }

  public OrderActor readActor(Jwt jwt) {
    return actor(jwt, false);
  }

  public OrderActor writeActor(Jwt jwt) {
    return actor(jwt, true);
  }

  public void requireVisible(OrderActor actor, RentalOrder order) {
    if (!isVisible(actor, order)) {
      throw new OrderProblemException(
          HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Заказ не найден");
    }
  }

  public void requireMutable(OrderActor actor, RentalOrder order) {
    requireVisible(actor, order);
    if (!actor.writeScope()) {
      throw new AccessDeniedException("Required USER scope is missing");
    }
    if (order.getStatus() != RentalOrderStatus.DRAFT) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT, "ORDER_NOT_EDITABLE", "Заказ больше нельзя редактировать");
    }
    if (order.getWarehouseId() != null && !canEditWarehouse(actor, order.getWarehouseId())) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
  }

  /**
   * Checks the actor-side part of a booking edit. The lifecycle check for a
   * saved booking's shipment and furniture task is performed under a document
   * lock by {@code RentalOrderService}.
   */
  public void requireEditable(OrderActor actor, RentalOrder order) {
    requireVisible(actor, order);
    if (!actor.writeScope()) {
      throw new AccessDeniedException("Required USER scope is missing");
    }
    if (order.getStatus() != RentalOrderStatus.DRAFT
        && order.getStatus() != RentalOrderStatus.SAVED) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT, "ORDER_NOT_EDITABLE", "Заказ больше нельзя редактировать");
    }
    if (order.getWarehouseId() != null && !canEditWarehouse(actor, order.getWarehouseId())) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
  }

  /** Actor/warehouse authorization for selecting a partial shipment batch. */
  public void requireRentalShipmentCreation(OrderActor actor, RentalOrder order) {
    requireVisible(actor, order);
    if (!actor.writeScope()) {
      throw new AccessDeniedException("Required USER scope is missing");
    }
    if (order.getStatus() != RentalOrderStatus.SAVED) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT, "ORDER_NOT_SHIPPABLE", "Отгрузка доступна только для сохранённого заказа");
    }
    if (!RentalOrderPaymentState.allowsFulfillment(order.getPaymentState())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_PAYMENT_REQUIRED",
          "Сначала подтвердите оплату бытовок и мебели");
    }
    if (order.getWarehouseId() != null && !canEditWarehouse(actor, order.getWarehouseId())) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
  }

  /** Actor/warehouse authorization for extending cabins that are already on rent. */
  public void requireRentalTermExtension(OrderActor actor, RentalOrder order) {
    requireVisible(actor, order);
    if (!actor.writeScope()) {
      throw new AccessDeniedException("Required USER scope is missing");
    }
    if (order.getStatus() != RentalOrderStatus.SAVED
        && order.getStatus() != RentalOrderStatus.FULFILLED) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "ORDER_RENTAL_NOT_STARTED",
          "Продление доступно только для отгруженной бытовки");
    }
    if (order.getWarehouseId() != null && !canEditWarehouse(actor, order.getWarehouseId())) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
  }

  public void requireWarehouseRead(OrderActor actor, UUID warehouseId) {
    if (!canReadWarehouse(actor, warehouseId)) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
  }

  public void requireWarehouseEdit(OrderActor actor, UUID warehouseId) {
    if (!actor.writeScope() || !canEditWarehouse(actor, warehouseId)) {
      throw new AccessDeniedException("Insufficient warehouse access");
    }
  }

  public void requireManagerAssignment(OrderActor actor, UUID managerId) {
    if (!actor.subjectId().equals(managerId) && !actor.canViewOtherManagers()) {
      throw new AccessDeniedException("A manager cannot assign an order to another user");
    }
  }

  public boolean isVisible(OrderActor actor, RentalOrder order) {
    if (actor.globalAdministrator()) return true;
    if (actor.localAdministrator()) {
      return order.getWarehouseId() == null
          ? actor.subjectId().equals(order.getManagerId())
          : actor.readableWarehouses().contains(order.getWarehouseId());
    }
    return actor.subjectId().equals(order.getManagerId());
  }

  public boolean canEdit(OrderActor actor, RentalOrder order) {
    return actor.writeScope()
        && isVisible(actor, order)
        && (order.getStatus() == RentalOrderStatus.DRAFT
            || order.getStatus() == RentalOrderStatus.SAVED)
        && (order.getWarehouseId() == null
            || canEditWarehouse(actor, order.getWarehouseId()));
  }

  /**
   * Pure read-side mirror of rental-term extension authorization for an order projection.
   *
   * <p>The command still checks that each selected cabin has actually shipped. This affordance
   * deliberately reports only whether the actor can submit the extension command for a SAVED or
   * FULFILLED order without making the client reconstruct warehouse or role rules.
   */
  public boolean canExtendRentalTerms(OrderActor actor, RentalOrder order) {
    return actor.writeScope()
        && isVisible(actor, order)
        && (order.getStatus() == RentalOrderStatus.SAVED
            || order.getStatus() == RentalOrderStatus.FULFILLED)
        && (order.getWarehouseId() == null
            || canEditWarehouse(actor, order.getWarehouseId()));
  }

  public boolean canReadWarehouse(OrderActor actor, UUID warehouseId) {
    return warehouseId != null
        && (actor.globalAdministrator() || actor.readableWarehouses().contains(warehouseId));
  }

  public boolean canEditWarehouse(OrderActor actor, UUID warehouseId) {
    return warehouseId != null
        && (actor.globalAdministrator() || actor.editableWarehouses().contains(warehouseId));
  }

  private OrderActor actor(Jwt jwt, boolean requireWrite) {
    if (developmentBypass) {
      return new OrderActor(
          DEVELOPMENT_SUBJECT,
          "SYSTEM_ADMIN",
          "development-admin",
          Set.of(),
          Set.of(),
          true,
          false,
          true,
          true);
    }
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("USER principal is required");
    }
    Set<String> scopes = scopes(jwt);
    String subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
    UUID subjectId;
    try {
      subjectId = UUID.fromString(subject);
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
    String role = jwt.getClaimAsString("global_role");
    if (role == null || !ROLES.contains(role)) {
      throw new AccessDeniedException("Recognized USER role is required");
    }
    String clientId = jwt.getClaimAsString("client_id");
    boolean rentalManagerClient =
        clientId != null && RENTAL_MANAGER_CLIENT_IDS.contains(clientId);
    boolean rentalManager = RENTAL_MANAGER_ROLE.equals(role);
    boolean administrationClient = isAdministrationClient(clientId, role, scopes);
    if (rentalManagerClient && !rentalManager) {
      throw new AccessDeniedException("Dedicated rental manager client requires RENTAL_MANAGER role");
    }
    if (rentalManager) {
      Set<String> applicationScopes = new HashSet<>(scopes);
      applicationScopes.removeAll(INTERACTIVE_PROTOCOL_SCOPES);
      if (!rentalManagerClient || !applicationScopes.equals(Set.of(RENTAL_MANAGER_SCOPE))) {
        throw new AccessDeniedException("Dedicated rental manager client and scope are required");
      }
    } else if (!administrationClient) {
      String requiredScope = requireWrite ? "rwms.write" : "rwms.read";
      if (!scopes.contains(requiredScope)) {
        throw new AccessDeniedException("Required USER scope is missing");
      }
    }
    WarehouseGrants grants = grants(jwt);
    String displayName = jwt.getClaimAsString("preferred_username");
    if (displayName == null || displayName.isBlank()) displayName = subjectId.toString();
    boolean global = "SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role);
    boolean local = "WAREHOUSE_MANAGER".equals(role);
    Object rentalAccessClaim = jwt.getClaims().get("rentalAccess");
    boolean rentalAccess =
        rentalAccessClaim instanceof Boolean booleanValue
            ? booleanValue
            : rentalAccessClaim instanceof String stringValue
                && Boolean.parseBoolean(stringValue);
    if (!rentalAccess) {
      throw new AccessDeniedException("Rental access is required");
    }
    return new OrderActor(
        subjectId,
        role,
        displayName.trim(),
        Set.copyOf(grants.readable()),
        Set.copyOf(grants.editable()),
        global,
        local,
        rentalManager
            ? scopes.contains(RENTAL_MANAGER_SCOPE)
            : administrationClient || scopes.contains("rwms.write"),
        true);
  }

  /** The isolated administration client may act only for the two global administration roles. */
  private static boolean isAdministrationClient(String clientId, String role, Set<String> scopes) {
    if (!ADMIN_WEB_CLIENT_ID.equals(clientId)
        || !("SYSTEM_ADMIN".equals(role) || "WMS_ADMIN".equals(role))) {
      return false;
    }
    Set<String> applicationScopes = new HashSet<>(scopes);
    applicationScopes.removeAll(INTERACTIVE_PROTOCOL_SCOPES);
    return applicationScopes.equals(Set.of("admin.manage"));
  }

  private static WarehouseGrants grants(Jwt jwt) {
    Set<UUID> readable = new HashSet<>();
    Set<UUID> editable = new HashSet<>();
    Object claim = jwt.getClaims().get("warehouse_access");
    if (claim instanceof Collection<?> entries) {
      for (Object entry : entries) {
        if (!(entry instanceof Map<?, ?> access)) continue;
        Object warehouse = access.get("warehouseId");
        Object level = access.get("level");
        if (!(warehouse instanceof String id) || !(level instanceof String value)) continue;
        try {
          UUID warehouseId = UUID.fromString(id);
          AccessLevel accessLevel = AccessLevel.valueOf(value);
          readable.add(warehouseId);
          if (accessLevel.ordinal() >= AccessLevel.EDIT.ordinal()) editable.add(warehouseId);
        } catch (IllegalArgumentException ignored) {
          // Malformed claims never grant authority.
        }
      }
    }
    return new WarehouseGrants(readable, editable);
  }

  private static Set<String> scopes(Jwt jwt) {
    Object claim = jwt.getClaims().get("scope");
    if (claim == null) claim = jwt.getClaims().get("scp");
    if (claim instanceof String value) {
      return Set.copyOf(
          Arrays.stream(value.trim().split("\\s+"))
              .filter(item -> !item.isBlank())
              .toList());
    }
    if (claim instanceof Collection<?> values) {
      return Set.copyOf(
          values.stream()
              .filter(String.class::isInstance)
              .map(String.class::cast)
              .filter(value -> !value.isBlank())
              .toList());
    }
    return Set.of();
  }

  private record WarehouseGrants(Set<UUID> readable, Set<UUID> editable) {}

  private enum AccessLevel {
    VIEW,
    EDIT,
    MANAGE
  }
}
