package dev.buhanzaz.rwms.logistics.order.security;

import java.util.Set;
import java.util.UUID;

/**
 * Normalized authenticated actor used to apply rental-order authorization and warehouse scope checks.
 */
public record OrderActor(
    UUID subjectId,
    String role,
    String displayName,
    Set<UUID> readableWarehouses,
    Set<UUID> editableWarehouses,
    boolean globalAdministrator,
    boolean localAdministrator,
    boolean writeScope,
    boolean rentalAccess) {
  public OrderActor {
    if (subjectId == null) {
      throw new IllegalArgumentException("Order actor subject is required");
    }
  }

  public boolean canViewOtherManagers() {
    return globalAdministrator || localAdministrator;
  }
}
