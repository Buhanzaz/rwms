package dev.buhanzaz.rwms.logistics.order.security;

import java.util.Set;
import java.util.UUID;

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
  public OrderActor(
      UUID subjectId,
      String role,
      String displayName,
      Set<UUID> readableWarehouses,
      Set<UUID> editableWarehouses,
      boolean globalAdministrator,
      boolean localAdministrator,
      boolean writeScope) {
    this(
        subjectId,
        role,
        displayName,
        readableWarehouses,
        editableWarehouses,
        globalAdministrator,
        localAdministrator,
        writeScope,
        Set.of("SYSTEM_ADMIN", "WMS_ADMIN", "RENTAL_MANAGER").contains(role));
  }

  public boolean canViewOtherManagers() {
    return globalAdministrator || localAdministrator;
  }
}
