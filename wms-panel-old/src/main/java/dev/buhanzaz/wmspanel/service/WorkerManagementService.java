package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerClassAssignment;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerGroupMember;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlans;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkerManagementService {

    private static final UUID EMPTY_WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DataManager dataManager;
    private final FetchPlans fetchPlans;
    private final Messages messages;
    private final CurrentAuthentication currentAuthentication;
    private final WarehouseAccessService warehouseAccessService;

    public WorkerManagementService(DataManager dataManager,
                                   FetchPlans fetchPlans,
                                   Messages messages,
                                   CurrentAuthentication currentAuthentication,
                                   WarehouseAccessService warehouseAccessService) {
        this.dataManager = dataManager;
        this.fetchPlans = fetchPlans;
        this.messages = messages;
        this.currentAuthentication = currentAuthentication;
        this.warehouseAccessService = warehouseAccessService;
    }

    public List<UUID> accessibleWarehouseIds() {
        List<Warehouse> warehouses = warehouseAccessService.getAvailableWarehousesForCurrentUser();
        if (warehouses.isEmpty()) {
            return List.of(EMPTY_WAREHOUSE_ID);
        }
        return warehouses.stream().map(Warehouse::getId).toList();
    }

    public List<Warehouse> accessibleWarehouses() {
        return warehouseAccessService.getAvailableWarehousesForCurrentUser();
    }

    public List<WorkerClass> loadActiveWorkerClasses() {
        return dataManager.load(WorkerClass.class)
                .query("select e from WorkerClass e where e.active = true order by e.sortOrder, e.name")
                .fetchPlan("_base")
                .list();
    }

    public List<Worker> loadVisibleWorkers() {
        return dataManager.load(Worker.class)
                .query("""
                        select e from Worker e
                        where e.warehouse.id in :warehouseIds
                        order by e.warehouse.sortOrder, e.warehouse.name, e.lastName, e.firstName, e.middleName, e.id
                        """)
                .parameter("warehouseIds", accessibleWarehouseIds())
                .fetchPlan(workerFetchPlan())
                .list();
    }

    public List<WorkerGroup> loadVisibleWorkerGroups() {
        return dataManager.load(WorkerGroup.class)
                .query("""
                        select e from WorkerGroup e
                        where e.warehouse.id in :warehouseIds
                        order by e.warehouse.sortOrder, e.warehouse.name, e.workerClass.sortOrder, e.name
                        """)
                .parameter("warehouseIds", accessibleWarehouseIds())
                .fetchPlan(workerGroupFetchPlan())
                .list();
    }

    public List<WorkerGroup> loadAvailableGroupsForWorker(Worker worker) {
        if (worker == null || worker.getWarehouse() == null || worker.getWarehouse().getId() == null) {
            return List.of();
        }
        return dataManager.load(WorkerGroup.class)
                .query("""
                        select e from WorkerGroup e
                        where (e.active = true or e.active is null)
                          and e.warehouse.id = :warehouseId
                        order by e.workerClass.sortOrder, e.workerClass.name, e.name
                        """)
                .parameter("warehouseId", worker.getWarehouse().getId())
                .fetchPlan(workerGroupFetchPlan())
                .list();
    }

    public String loadWorkerGroupLabel(UUID workerId) {
        if (workerId == null) {
            return "";
        }
        return dataManager.load(WorkerGroupMember.class)
                .query("""
                        select e from WorkerGroupMember e
                        where e.worker.id = :workerId
                          and (e.active = true or e.active is null)
                        order by e.workerGroup.workerClass.sortOrder, e.workerGroup.workerClass.name, e.workerGroup.name
                        """)
                .parameter("workerId", workerId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("workerGroup", nested -> nested
                                .addFetchPlan("_base")
                                .add("workerClass", "_base")))
                .list()
                .stream()
                .map(WorkerGroupMember::getWorkerGroup)
                .filter(Objects::nonNull)
                .map(WorkerGroup::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public Set<UUID> loadGroupIdsForWorker(UUID workerId) {
        if (workerId == null) {
            return Set.of();
        }
        return dataManager.load(WorkerGroupMember.class)
                .query("""
                        select e from WorkerGroupMember e
                        where e.worker.id = :workerId
                          and e.active = true
                        """)
                .parameter("workerId", workerId)
                .list()
                .stream()
                .map(WorkerGroupMember::getWorkerGroup)
                .filter(Objects::nonNull)
                .map(WorkerGroup::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public List<WorkerClassAssignment> loadVisibleAssignments() {
        return dataManager.load(WorkerClassAssignment.class)
                .query("""
                        select e from WorkerClassAssignment e
                        where e.worker.warehouse.id in :warehouseIds
                        order by e.worker.warehouse.sortOrder, e.worker.warehouse.name, e.worker.displayName, e.workerClass.sortOrder, e.workerClass.name
                        """)
                .parameter("warehouseIds", accessibleWarehouseIds())
                .fetchPlan(workerClassAssignmentFetchPlan())
                .list();
    }

    public void validateWorker(Worker worker) {
        normalizeWorker(worker);
        if (worker == null || worker.getDisplayName() == null) {
            throw new IllegalArgumentException(messages.getMessage("validation.workerDisplayNameRequired"));
        }
        Warehouse warehouse = requireWarehouse(worker == null ? null : worker.getWarehouse(), "validation.workerWarehouseRequired");
        requireWarehouseManagementAccess(warehouse);
    }

    public void validateWorkerClass(WorkerClass workerClass) {
        requireGlobalWorkerSettingsAccess();
    }

    public void validateWorkerGroup(WorkerGroup workerGroup) {
        normalizeWorkerGroup(workerGroup);
        if (workerGroup == null || workerGroup.getWorkerClass() == null || workerGroup.getWarehouse() == null) {
            throw new IllegalArgumentException(messages.getMessage("validation.workerGroupRequired"));
        }
        requireWarehouseManagementAccess(requireWarehouse(workerGroup.getWarehouse(), "validation.workerWarehouseRequired"));
    }

    public void validateWorkerClassAssignment(WorkerClassAssignment assignment) {
        if (assignment == null || assignment.getWorker() == null || assignment.getWorkerClass() == null) {
            throw new IllegalArgumentException(messages.getMessage("validation.workerClassAssignmentRequired"));
        }
        requireWarehouseManagementAccess(requireWarehouse(assignment.getWorker().getWarehouse(), "validation.workerWarehouseRequired"));
    }

    public void syncWorkerGroups(Worker worker, Set<WorkerGroup> selectedGroups) {
        if (worker == null || worker.getId() == null) {
            return;
        }
        Warehouse workerWarehouse = requireWarehouse(worker.getWarehouse(), "validation.workerWarehouseRequired");
        requireWarehouseManagementAccess(workerWarehouse);

        Set<UUID> selectedIds = (selectedGroups == null ? Set.<WorkerGroup>of() : selectedGroups).stream()
                .filter(Objects::nonNull)
                .map(group -> dataManager.load(WorkerGroup.class).id(group.getId()).fetchPlan(workerGroupFetchPlan()).one())
                .peek(group -> validateWorkerGroupMembershipTarget(workerWarehouse, group))
                .map(WorkerGroup::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<WorkerGroupMember> existingMemberships = dataManager.load(WorkerGroupMember.class)
                .query("""
                        select e from WorkerGroupMember e
                        where e.worker.id = :workerId
                        """)
                .parameter("workerId", worker.getId())
                .list();

        Map<UUID, WorkerGroupMember> existingByGroupId = existingMemberships.stream()
                .filter(member -> member.getWorkerGroup() != null && member.getWorkerGroup().getId() != null)
                .collect(java.util.stream.Collectors.toMap(member -> member.getWorkerGroup().getId(), member -> member, (left, right) -> left));

        for (UUID selectedId : selectedIds) {
            WorkerGroupMember membership = existingByGroupId.remove(selectedId);
            if (membership == null) {
                membership = dataManager.create(WorkerGroupMember.class);
                membership.setWorkerGroup(dataManager.getReference(WorkerGroup.class, selectedId));
                membership.setWorker(worker);
                membership.setActive(true);
                dataManager.save(membership);
            } else if (!Boolean.TRUE.equals(membership.getActive())) {
                membership.setActive(true);
                dataManager.save(membership);
            }
        }

        for (WorkerGroupMember membership : existingByGroupId.values()) {
            dataManager.remove(membership);
        }
    }

    public boolean canManageWarehouse(Warehouse warehouse) {
        User user = currentUser();
        if (warehouseAccessService.isSystemOrWmsAdmin(user)) {
            return true;
        }
        if (user == null || user.getGlobalRole() != dev.buhanzaz.wmspanel.entity.UserGlobalRole.WAREHOUSE_MANAGER) {
            return false;
        }
        return warehouseAccessService.canEditWarehouseItems(user, warehouse);
    }

    public boolean canManageGlobalSettings() {
        return warehouseAccessService.isSystemOrWmsAdmin(currentUser());
    }

    public boolean canManageAnyAccessibleWarehouse() {
        return accessibleWarehouses().stream().anyMatch(this::canManageWarehouse);
    }

    private void requireWarehouseManagementAccess(Warehouse warehouse) {
        if (!canManageWarehouse(warehouse)) {
            throw new IllegalArgumentException(messages.getMessage("validation.workerWarehouseForbidden"));
        }
    }

    private void requireGlobalWorkerSettingsAccess() {
        if (!canManageGlobalSettings()) {
            throw new IllegalArgumentException(messages.getMessage("validation.globalWorkerSettingsForbidden"));
        }
    }

    private void normalizeWorker(Worker worker) {
        if (worker == null) {
            return;
        }

        String legacyDisplayName = trimToNull(worker.getDisplayName());
        worker.setFirstName(trimToNull(worker.getFirstName()));
        worker.setLastName(trimToNull(worker.getLastName()));
        worker.setMiddleName(trimToNull(worker.getMiddleName()));
        String fullName = buildWorkerDisplayName(worker);
        worker.setDisplayName(fullName != null ? fullName : legacyDisplayName);
    }

    private void normalizeWorkerGroup(WorkerGroup workerGroup) {
        if (workerGroup == null) {
            return;
        }
        workerGroup.setName(trimToNull(workerGroup.getName()));
        workerGroup.setDescription(trimToNull(workerGroup.getDescription()));
        if (workerGroup.getActive() == null) {
            workerGroup.setActive(true);
        }
    }

    private String buildWorkerDisplayName(Worker worker) {
        StringBuilder builder = new StringBuilder();
        appendNamePart(builder, worker.getLastName());
        appendNamePart(builder, worker.getFirstName());
        appendNamePart(builder, worker.getMiddleName());
        return builder.length() == 0 ? null : builder.toString();
    }

    private void appendNamePart(StringBuilder builder, String value) {
        if (value == null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Warehouse requireWarehouse(Warehouse warehouse, String messageKey) {
        if (warehouse == null || warehouse.getId() == null) {
            throw new IllegalArgumentException(messages.getMessage(messageKey));
        }
        return dataManager.load(Warehouse.class).id(warehouse.getId()).fetchPlan("_base").one();
    }

    private void validateWorkerGroupMembershipTarget(Warehouse workerWarehouse, WorkerGroup workerGroup) {
        validateWorkerGroup(workerGroup);
        Warehouse groupWarehouse = requireWarehouse(workerGroup.getWarehouse(), "validation.workerWarehouseRequired");
        if (!Objects.equals(workerWarehouse.getId(), groupWarehouse.getId())) {
            throw new IllegalArgumentException(messages.getMessage("validation.workerGroupWarehouseMustMatchWorkerWarehouse"));
        }
    }

    private User currentUser() {
        String username = warehouseAccessService.username();
        if (username == null) {
            return null;
        }
        return dataManager.load(User.class)
                .query("select e from wmspanel_User e where e.username = :username")
                .parameter("username", username)
                .optional()
                .orElse(null);
    }

    private FetchPlan workerFetchPlan() {
        return fetchPlans.builder(Worker.class)
                .addFetchPlan("_base")
                .add("warehouse", "_base")
                .build();
    }

    private FetchPlan workerClassAssignmentFetchPlan() {
        return fetchPlans.builder(WorkerClassAssignment.class)
                .addFetchPlan("_base")
                .add("worker", builder1 -> builder1
                        .addFetchPlan("_base")
                        .add("warehouse", "_base"))
                .add("workerClass", "_base")
                .build();
    }

    private FetchPlan workerGroupFetchPlan() {
        return fetchPlans.builder(WorkerGroup.class)
                .addFetchPlan("_base")
                .add("warehouse", "_base")
                .add("workerClass", "_base")
                .build();
    }
}
