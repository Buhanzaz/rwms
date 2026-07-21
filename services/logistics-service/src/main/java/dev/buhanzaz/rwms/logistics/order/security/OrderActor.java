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
    boolean writeScope) {
  public boolean canViewOtherManagers() {
    return globalAdministrator || localAdministrator;
  }
}
