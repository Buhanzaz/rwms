package dev.buhanzaz.rwms.logistics.customer.security;

import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Fail-closed CustomerApp authorization boundary. Customer tokens never inherit manager scopes or
 * JWT warehouse grants; a facade grants only the warehouse selected for its own inquiry.
 */
@Component
public class CustomerAuthorizer {
  private static final String CUSTOMER_CLIENT_ID = "rwms-customer-android";
  private static final String CUSTOMER_SCOPE = "customer.rental";

  /** Validates that a JWT was issued to the dedicated native customer client. */
  public CustomerIdentity identity(Jwt jwt) {
    if (jwt == null || !"USER".equals(jwt.getClaimAsString("principal_type"))) {
      throw denied("USER principal is required");
    }
    if (!"CUSTOMER".equals(jwt.getClaimAsString("global_role"))) {
      throw denied("CUSTOMER role is required");
    }
    if (!scopes(jwt).contains(CUSTOMER_SCOPE)) {
      throw denied("Customer rental scope is required");
    }
    String clientId = jwt.getClaimAsString("client_id");
    if (clientId == null) clientId = jwt.getClaimAsString("azp");
    if (!CUSTOMER_CLIENT_ID.equals(clientId)) {
      throw denied("Dedicated CustomerApp client is required");
    }
    UUID subjectId;
    try {
      String subject = jwt.getSubject();
      if (subject == null) throw denied("Customer subject must be a UUID");
      subjectId = UUID.fromString(subject);
    } catch (RuntimeException exception) {
      throw denied("Customer subject must be a UUID");
    }
    String username = jwt.getClaimAsString("preferred_username");
    if (username == null || username.isBlank()) username = subjectId.toString();
    return new CustomerIdentity(subjectId, username.trim());
  }

  /** Builds a least-privilege order actor for one selected customer warehouse. */
  public OrderActor orderActor(CustomerIdentity identity, UUID warehouseId) {
    Set<UUID> warehouses = warehouseId == null ? Set.of() : Set.of(warehouseId);
    return new OrderActor(
        identity.subjectId(),
        "CUSTOMER",
        identity.username(),
        warehouses,
        warehouses,
        false,
        false,
        true,
        true);
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

  private static AccessDeniedException denied(String message) {
    return new AccessDeniedException(message);
  }
}
