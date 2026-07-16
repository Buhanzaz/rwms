package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerGroupMember;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class WorkerManagementServiceTest {

    @Autowired
    WorkerManagementService workerManagementService;

    @Autowired
    DataManager dataManager;

    @Autowired
    Messages messages;

    private final List<Object> entitiesToRemove = new ArrayList<>();

    @AfterEach
    void tearDown() {
        List<UUID> groupIds = entitiesToRemove.stream()
                .filter(WorkerGroup.class::isInstance)
                .map(WorkerGroup.class::cast)
                .map(WorkerGroup::getId)
                .toList();
        if (!groupIds.isEmpty()) {
            dataManager.load(WorkerGroupMember.class)
                    .query("select e from WorkerGroupMember e where e.workerGroup.id in :groupIds")
                    .parameter("groupIds", groupIds)
                    .list()
                    .forEach(dataManager::remove);
        }
        for (int i = entitiesToRemove.size() - 1; i >= 0; i--) {
            dataManager.remove(entitiesToRemove.get(i));
        }
        entitiesToRemove.clear();
    }

    @Test
    void validateWorkerBuildsDisplayNameFromNamePartsBeforeSave() {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(firstWarehouse());
        worker.setDisplayName("   ");
        worker.setLastName(" Иванов ");
        worker.setFirstName(" Петр ");
        worker.setMiddleName(" Сергеевич ");
        worker.setActive(true);

        workerManagementService.validateWorker(worker);
        Worker savedWorker = dataManager.save(worker);
        entitiesToRemove.add(savedWorker);

        assertThat(savedWorker.getDisplayName()).isEqualTo("Иванов Петр Сергеевич");
        assertThat(savedWorker.getLastName()).isEqualTo("Иванов");
        assertThat(savedWorker.getFirstName()).isEqualTo("Петр");
        assertThat(savedWorker.getMiddleName()).isEqualTo("Сергеевич");
    }

    @Test
    void validateWorkerOverridesLegacyDisplayNameWithNameParts() {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(firstWarehouse());
        worker.setDisplayName("Старое имя");
        worker.setLastName(" Сидоров ");
        worker.setFirstName(" Иван ");
        worker.setMiddleName(" Петрович ");
        worker.setActive(true);

        workerManagementService.validateWorker(worker);
        Worker savedWorker = dataManager.save(worker);
        entitiesToRemove.add(savedWorker);

        assertThat(savedWorker.getDisplayName()).isEqualTo("Сидоров Иван Петрович");
    }

    @Test
    void validateWorkerKeepsLegacyDisplayNameWhenNamePartsMissing() {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(firstWarehouse());
        worker.setDisplayName("Legacy worker");
        worker.setLastName("   ");
        worker.setFirstName(null);
        worker.setMiddleName("   ");
        worker.setActive(true);

        workerManagementService.validateWorker(worker);
        Worker savedWorker = dataManager.save(worker);
        entitiesToRemove.add(savedWorker);

        assertThat(savedWorker.getDisplayName()).isEqualTo("Legacy worker");
    }

    @Test
    void validateWorkerRejectsBlankDisplayNameWhenNamePartsMissing() {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(firstWarehouse());
        worker.setDisplayName("   ");
        worker.setLastName("   ");
        worker.setFirstName(null);
        worker.setMiddleName("   ");
        worker.setActive(true);

        assertThatThrownBy(() -> workerManagementService.validateWorker(worker))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(messages.getMessage("validation.workerDisplayNameRequired"));
    }

    @Test
    void validateWorkerGroupRequiresWarehouse() {
        WorkerClass workerClass = firstWorkerClass();
        WorkerGroup group = dataManager.create(WorkerGroup.class);
        group.setWorkerClass(workerClass);
        group.setName("Группа без склада " + UUID.randomUUID());
        group.setActive(true);

        assertThatThrownBy(() -> workerManagementService.validateWorkerGroup(group))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(messages.getMessage("validation.workerGroupRequired"));
    }

    @Test
    void accessibleWarehousesAreAvailableForAdminAuthentication() {
        assertThat(workerManagementService.accessibleWarehouses()).isNotEmpty();
        assertThat(workerManagementService.accessibleWarehouseIds()).isNotEmpty();
    }

    @Test
    void syncWorkerGroupsStoresMultipleMemberships() {
        Warehouse warehouse = firstWarehouse();
        WorkerClass workerClass = firstWorkerClass();
        Worker worker = newWorker(warehouse, "Рабочий для групп " + UUID.randomUUID());
        WorkerGroup firstGroup = newWorkerGroup(warehouse, workerClass, "Группа 1 " + UUID.randomUUID());
        WorkerGroup secondGroup = newWorkerGroup(warehouse, workerClass, "Группа 2 " + UUID.randomUUID());

        workerManagementService.syncWorkerGroups(worker, Set.of(firstGroup, secondGroup));

        assertThat(workerManagementService.loadGroupIdsForWorker(worker.getId()))
                .containsExactlyInAnyOrder(firstGroup.getId(), secondGroup.getId());

        workerManagementService.syncWorkerGroups(worker, Set.of(secondGroup));

        assertThat(workerManagementService.loadGroupIdsForWorker(worker.getId()))
                .containsExactly(secondGroup.getId());
    }

    @Test
    void loadAvailableGroupsForWorkerReturnsFreshlyCreatedGroupFromSameWarehouse() {
        Warehouse warehouse = firstWarehouse();
        WorkerClass workerClass = firstWorkerClass();
        Worker worker = newWorker(warehouse, "Рабочий склада " + UUID.randomUUID());
        WorkerGroup group = newWorkerGroup(warehouse, workerClass, "Новая группа " + UUID.randomUUID());

        assertThat(workerManagementService.loadAvailableGroupsForWorker(worker))
                .extracting(WorkerGroup::getId)
                .contains(group.getId());
    }

    @Test
    void syncWorkerGroupsRejectsGroupFromAnotherWarehouse() {
        Warehouse firstWarehouse = firstWarehouse();
        Warehouse secondWarehouse = newWarehouse("Другой склад " + UUID.randomUUID());
        WorkerClass workerClass = firstWorkerClass();
        Worker worker = newWorker(firstWarehouse, "Рабочий своего склада " + UUID.randomUUID());
        WorkerGroup foreignGroup = newWorkerGroup(secondWarehouse, workerClass, "Чужая группа " + UUID.randomUUID());

        assertThatThrownBy(() -> workerManagementService.syncWorkerGroups(worker, Set.of(foreignGroup)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(messages.getMessage("validation.workerGroupWarehouseMustMatchWorkerWarehouse"));
    }

    private Worker newWorker(Warehouse warehouse, String displayName) {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(warehouse);
        worker.setLastName(displayName);
        worker.setActive(true);
        Worker savedWorker = dataManager.save(worker);
        entitiesToRemove.add(savedWorker);
        return savedWorker;
    }

    private WorkerGroup newWorkerGroup(Warehouse warehouse, WorkerClass workerClass, String name) {
        WorkerGroup group = dataManager.create(WorkerGroup.class);
        group.setWarehouse(warehouse);
        group.setWorkerClass(workerClass);
        group.setName(name);
        group.setActive(true);
        WorkerGroup savedGroup = dataManager.save(group);
        entitiesToRemove.add(savedGroup);
        return savedGroup;
    }

    private Warehouse newWarehouse(String name) {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName(name);
        warehouse.setCode("WH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        warehouse.setCity("Test City");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        Warehouse savedWarehouse = dataManager.save(warehouse);
        entitiesToRemove.add(savedWarehouse);
        return savedWarehouse;
    }

    private Warehouse firstWarehouse() {
        return dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where e.active = true order by coalesce(e.sortOrder, 999999), e.name")
                .list()
                .stream()
                .findFirst()
                .orElseThrow();
    }

    private WorkerClass firstWorkerClass() {
        return workerManagementService.loadActiveWorkerClasses().stream()
                .findFirst()
                .orElseThrow();
    }
}
