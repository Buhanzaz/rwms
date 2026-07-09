package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.UserGlobalRole;
import dev.buhanzaz.wmspanel.entity.UserWarehouseAccess;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WarehouseAccessLevel;
import dev.buhanzaz.wmspanel.security.FullAccessRole;
import io.jmix.core.DataManager;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class WarehouseAccessService {

    private final DataManager dataManager;
    private final CurrentAuthentication currentAuthentication;

    public WarehouseAccessService(DataManager dataManager, CurrentAuthentication currentAuthentication) {
        this.dataManager = dataManager;
        this.currentAuthentication = currentAuthentication;
    }

    public List<Warehouse> getAvailableWarehousesForCurrentUser() {
        return getAvailableWarehousesForUser(resolveCurrentUser());
    }

    public List<Warehouse> getAvailableWarehousesForUser(User user) {
        if (hasGlobalWarehouseAdminAccess(user)) {
            return loadActiveWarehouses();
        }

        return activeAccesses(user).stream()
                .map(UserWarehouseAccess::getWarehouse)
                .filter(warehouse -> warehouse != null && Boolean.TRUE.equals(warehouse.getActive()))
                .distinct()
                .sorted(Comparator.comparing(Warehouse::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Warehouse::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    public List<Warehouse> availableWarehouses() {
        return getAvailableWarehousesForCurrentUser();
    }

    public Warehouse defaultWarehouse() {
        List<Warehouse> warehouses = getAvailableWarehousesForCurrentUser();
        return warehouses.isEmpty() ? null : warehouses.get(0);
    }

    public void checkAccess(Warehouse warehouse) {
        if (!canViewWarehouse(resolveCurrentUser(), warehouse)) {
            throw new IllegalArgumentException("Нет доступа к выбранному складу");
        }
    }

    public boolean canViewWarehouse(User user, Warehouse warehouse) {
        if (warehouse == null) {
            return false;
        }
        if (hasGlobalWarehouseAdminAccess(user)) {
            return true;
        }
        return hasAccessAtLeast(user, warehouse, WarehouseAccessLevel.VIEW);
    }

    public boolean canEditWarehouseItems(User user, Warehouse warehouse) {
        if (warehouse == null) {
            return false;
        }
        if (hasGlobalWarehouseAdminAccess(user)) {
            return true;
        }
        return hasAccessAtLeast(user, warehouse, WarehouseAccessLevel.EDIT);
    }

    public boolean canManageDictionaries(User user) {
        return hasGlobalWarehouseAdminAccess(user);
    }

    public boolean isSystemOrWmsAdmin(User user) {
        if (user != null) {
            UserGlobalRole globalRole = user.getGlobalRole();
            if (globalRole == UserGlobalRole.SYSTEM_ADMIN || globalRole == UserGlobalRole.WMS_ADMIN) {
                return true;
            }
        }
        return hasFullAccessAuthority();
    }

    private boolean hasFullAccessAuthority() {
        return currentAuthentication.getAuthentication() != null
                && currentAuthentication.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(FullAccessRole.CODE::equals);
    }

    private boolean hasGlobalWarehouseAdminAccess(User user) {
        return isSystemOrWmsAdmin(user);
    }

    public String username() {
        return currentAuthentication.getUser() == null ? "system" : currentAuthentication.getUser().getUsername();
    }

    private List<Warehouse> loadActiveWarehouses() {
        return dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where e.active = true order by coalesce(e.sortOrder, 999999), e.name")
                .list();
    }

    private List<UserWarehouseAccess> activeAccesses(User user) {
        if (isSystemOrWmsAdmin(user) || user == null || user.getId() == null) {
            return List.of();
        }
        return dataManager.load(UserWarehouseAccess.class)
                .query("""
                        select distinct e
                        from UserWarehouseAccess e
                        where e.active = true
                          and e.user.id = :userId
                        """)
                .parameter("userId", user.getId())
                .list();
    }

    private boolean hasAccessAtLeast(User user, Warehouse warehouse, WarehouseAccessLevel requiredLevel) {
        return activeAccesses(user).stream()
                .filter(access -> access.getWarehouse() != null && access.getWarehouse().getId().equals(warehouse.getId()))
                .map(access -> effectiveAccessLevel(user, access))
                .anyMatch(level -> accessRank(level) >= accessRank(requiredLevel));
    }

    private WarehouseAccessLevel effectiveAccessLevel(User user, UserWarehouseAccess access) {
        if (user != null && user.getGlobalRole() == UserGlobalRole.VIEWER) {
            return WarehouseAccessLevel.VIEW;
        }
        if (access.getAccessLevel() != null) {
            return access.getAccessLevel();
        }
        return WarehouseAccessLevel.VIEW;
    }

    private int accessRank(WarehouseAccessLevel accessLevel) {
        if (accessLevel == null) {
            return 0;
        }
        return switch (accessLevel) {
            case VIEW -> 1;
            case EDIT -> 2;
            case MANAGE -> 3;
        };
    }

    private User resolveCurrentUser() {
        String username = username();
        if (username == null) {
            return null;
        }
        return dataManager.load(User.class)
                .query("select e from wmspanel_User e where e.username = :username")
                .parameter("username", username)
                .optional()
                .orElse(null);
    }
}
