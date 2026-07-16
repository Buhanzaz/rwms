package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessKind;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerGroupMember;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.entity.EntityValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class RepairReworkServiceTest {

    @Autowired
    DataManager dataManager;

    @Autowired
    EntityStates entityStates;

    @Autowired
    RepairReworkService repairReworkService;

    @Autowired
    RepairProcessService repairProcessService;

    private final List<Object> entitiesToRemove = new ArrayList<>();
    private final List<UUID> taskIdsToClean = new ArrayList<>();
    private final List<UUID> processIdsToClean = new ArrayList<>();

    @AfterEach
    void tearDown() {
        cleanupGeneratedReworkGraph();
        for (int i = entitiesToRemove.size() - 1; i >= 0; i--) {
            Object entity = entitiesToRemove.get(i);
            if (entity != null) {
                removeFresh(entity);
            }
        }
        entitiesToRemove.clear();
        taskIdsToClean.clear();
        processIdsToClean.clear();
    }

    @Test
    void createReworkCreatesChildProcessAndDistinctBoardTask() {
        Warehouse warehouse = createWarehouse();
        RentalCategory category = createCategory();
        RentalItem rentalItem = createRentalItem(warehouse, category);
        RepairEstimate estimate = createEstimate(warehouse, rentalItem);
        RepairProcess sourceProcess = createSourceProcess(warehouse, rentalItem, estimate);
        WorkQueue queue = createQueue(warehouse);
        WorkerClass workerClass = createWorkerClass();
        WorkerGroup workerGroup = createWorkerGroup(warehouse, workerClass);
        Worker worker = createWorker(warehouse);
        createMembership(workerGroup, worker);

        RepairReworkService.CreateReworkResult result = repairReworkService.createRework(
                new RepairReworkService.CreateReworkCommand(
                        sourceProcess.getId(),
                        queue.getId(),
                        workerGroup.getId(),
                        worker.getId(),
                        "Нужно устранить замечания после приемки"));
        taskIdsToClean.add(result.boardTask().getId());
        processIdsToClean.add(result.process().getId());

        RepairProcess savedSource = dataManager.load(RepairProcess.class).id(sourceProcess.getId()).one();
        RepairProcess reworkProcess = dataManager.load(RepairProcess.class)
                .id(result.process().getId())
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("sourceProcess", "_base")
                        .add("requestedWorkerGroup", "_base")
                        .add("requestedWorker", "_base"))
                .one();
        BoardTask reworkTask = dataManager.load(BoardTask.class)
                .id(result.boardTask().getId())
                .fetchPlan("_base")
                .one();
        List<QueueEntry> entries = repairProcessService.loadTaskEntries(reworkTask.getId());

        assertThat(savedSource.getStatus()).isEqualTo(RepairProcessStatus.REWORK);
        assertThat(repairProcessService.loadAfterRepairProcesses(List.of(warehouse)))
                .extracting(RepairProcess::getId)
                .contains(sourceProcess.getId())
                .doesNotContain(reworkProcess.getId());
        assertThat(reworkProcess.getEstimate()).isNull();
        assertThat(reworkProcess.getProcessKind()).isEqualTo(RepairProcessKind.REWORK);
        assertThat(reworkProcess.getSourceProcess()).isNotNull();
        assertThat(reworkProcess.getSourceProcess().getId()).isEqualTo(sourceProcess.getId());
        assertThat(reworkProcess.getRequestedWorkerGroup()).isNotNull();
        assertThat(reworkProcess.getRequestedWorkerGroup().getId()).isEqualTo(workerGroup.getId());
        assertThat(reworkProcess.getRequestedWorker()).isNotNull();
        assertThat(reworkProcess.getRequestedWorker().getId()).isEqualTo(worker.getId());
        assertThat(reworkTask.getTaskKind()).isEqualTo(RepairProcessTaskKind.REWORK);
        assertThat(reworkTask.getRepairProcess()).isNotNull();
        assertThat(reworkTask.getRepairProcess().getId()).isEqualTo(reworkProcess.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getStatus()).isEqualTo(QueueEntryStatus.WAITING);
    }

    @Test
    void acceptingCompletedReworkAlsoAcceptsSourceProcess() {
        Warehouse warehouse = createWarehouse();
        RentalCategory category = createCategory();
        RentalItem rentalItem = createRentalItem(warehouse, category);
        RepairEstimate estimate = createEstimate(warehouse, rentalItem);
        RepairProcess sourceProcess = createSourceProcess(warehouse, rentalItem, estimate);
        WorkQueue queue = createQueue(warehouse);

        RepairReworkService.CreateReworkResult result = repairReworkService.createRework(
                new RepairReworkService.CreateReworkCommand(
                        sourceProcess.getId(),
                        queue.getId(),
                        null,
                        null,
                        "Повторная проверка качества"));
        taskIdsToClean.add(result.boardTask().getId());
        processIdsToClean.add(result.process().getId());

        BoardTask task = dataManager.load(BoardTask.class).id(result.boardTask().getId()).one();
        task.setStatus(BoardTaskStatus.DONE);
        dataManager.save(task);

        repairProcessService.syncProcessStatus(result.process());
        repairProcessService.acceptProcess(result.process().getId(), "Принято после доработки");

        RepairProcess acceptedRework = dataManager.load(RepairProcess.class).id(result.process().getId()).one();
        RepairProcess acceptedSource = dataManager.load(RepairProcess.class).id(sourceProcess.getId()).one();

        assertThat(acceptedRework.getStatus()).isEqualTo(RepairProcessStatus.ACCEPTED);
        assertThat(acceptedSource.getStatus()).isEqualTo(RepairProcessStatus.ACCEPTED);
        assertThat(acceptedSource.getAcceptanceComment()).isEqualTo("Принято после доработки");
    }

    private Warehouse createWarehouse() {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Rework warehouse " + UUID.randomUUID());
        warehouse.setCode("RW-" + UUID.randomUUID().toString().substring(0, 8));
        warehouse.setCity("Moscow");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        Warehouse saved = dataManager.save(warehouse);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalCategory createCategory() {
        RentalCategory category = dataManager.create(RentalCategory.class);
        category.setName("Rework category " + UUID.randomUUID());
        category.setCode("RW-CAT-" + UUID.randomUUID().toString().substring(0, 8));
        category.setActive(true);
        RentalCategory saved = dataManager.save(category);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalItem createRentalItem(Warehouse warehouse, RentalCategory category) {
        RentalItem item = dataManager.create(RentalItem.class);
        item.setWarehouse(warehouse);
        item.setCategory(category);
        item.setNumber("RW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        item.setStatus("WAITING_REPAIR_CHECK");
        RentalItem saved = dataManager.save(item);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairEstimate createEstimate(Warehouse warehouse, RentalItem rentalItem) {
        RepairEstimate estimate = dataManager.create(RepairEstimate.class);
        estimate.setWarehouse(warehouse);
        estimate.setRentalItem(rentalItem);
        estimate.setCabinNumber(rentalItem.getNumber());
        RepairEstimate saved = dataManager.save(estimate);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairProcess createSourceProcess(Warehouse warehouse, RentalItem rentalItem, RepairEstimate estimate) {
        RepairProcess process = dataManager.create(RepairProcess.class);
        process.setWarehouse(warehouse);
        process.setRentalItem(rentalItem);
        process.setEstimate(estimate);
        process.setStatus(RepairProcessStatus.AFTER_REPAIR);
        process.setProcessKind(RepairProcessKind.ESTIMATE_REPAIR);
        RepairProcess saved = dataManager.save(process);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkQueue createQueue(Warehouse warehouse) {
        WorkQueue queue = dataManager.create(WorkQueue.class);
        queue.setWarehouse(warehouse);
        queue.setCode("RW-Q-" + UUID.randomUUID().toString().substring(0, 8));
        queue.setName("Rework queue " + UUID.randomUUID());
        queue.setQueueKind(WorkQueueKind.REPAIR);
        queue.setActive(true);
        WorkQueue saved = dataManager.save(queue);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerClass createWorkerClass() {
        WorkerClass workerClass = dataManager.create(WorkerClass.class);
        workerClass.setCode("RW-WC-" + UUID.randomUUID().toString().substring(0, 8));
        workerClass.setName("Rework class " + UUID.randomUUID());
        workerClass.setActive(true);
        WorkerClass saved = dataManager.save(workerClass);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerGroup createWorkerGroup(Warehouse warehouse, WorkerClass workerClass) {
        WorkerGroup group = dataManager.create(WorkerGroup.class);
        group.setWarehouse(warehouse);
        group.setWorkerClass(workerClass);
        group.setName("Rework group " + UUID.randomUUID());
        group.setActive(true);
        WorkerGroup saved = dataManager.save(group);
        entitiesToRemove.add(saved);
        return saved;
    }

    private Worker createWorker(Warehouse warehouse) {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(warehouse);
        worker.setDisplayName("Rework worker " + UUID.randomUUID());
        worker.setActive(true);
        Worker saved = dataManager.save(worker);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerGroupMember createMembership(WorkerGroup workerGroup, Worker worker) {
        WorkerGroupMember membership = dataManager.create(WorkerGroupMember.class);
        membership.setWorkerGroup(workerGroup);
        membership.setWorker(worker);
        membership.setActive(true);
        WorkerGroupMember saved = dataManager.save(membership);
        entitiesToRemove.add(saved);
        return saved;
    }

    private void cleanupGeneratedReworkGraph() {
        if (!taskIdsToClean.isEmpty()) {
            List<QueueEntry> entries = dataManager.load(QueueEntry.class)
                    .query("select e from QueueEntry e where e.task.id in :taskIds")
                    .parameter("taskIds", taskIdsToClean)
                    .list();
            entries.forEach(dataManager::remove);

            List<BoardTask> tasks = dataManager.load(BoardTask.class)
                    .query("select e from BoardTask e where e.id in :taskIds")
                    .parameter("taskIds", taskIdsToClean)
                    .list();
            tasks.forEach(dataManager::remove);
        }
        if (!processIdsToClean.isEmpty()) {
            List<RepairProcess> processes = dataManager.load(RepairProcess.class)
                    .query("select e from RepairProcess e where e.id in :processIds")
                    .parameter("processIds", processIdsToClean)
                    .list();
            processes.forEach(dataManager::remove);
        }
    }

    private void removeFresh(Object entity) {
        if (entityStates.isNew(entity)) {
            return;
        }
        Object id = EntityValues.getId(entity);
        if (id == null) {
            dataManager.remove(entity);
            return;
        }
        Object current = dataManager.load(entity.getClass())
                .id(id)
                .optional()
                .orElse(null);
        if (current != null) {
            dataManager.remove(current);
        }
    }
}
