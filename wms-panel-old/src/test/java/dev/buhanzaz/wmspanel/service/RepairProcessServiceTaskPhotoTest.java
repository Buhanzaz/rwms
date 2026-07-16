package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoLink;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoType;
import dev.buhanzaz.wmspanel.entity.BoardTaskStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntryType;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.TaskAssignment;
import dev.buhanzaz.wmspanel.entity.TaskAssignmentStatus;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class RepairProcessServiceTaskPhotoTest {

    @Autowired
    DataManager dataManager;

    @Autowired
    RepairProcessService repairProcessService;

    private final List<Object> entitiesToRemove = new ArrayList<>();
    private final List<UUID> taskIdsToClean = new ArrayList<>();

    @AfterEach
    void tearDown() {
        cleanupTaskPhotoGraph();
        for (int i = entitiesToRemove.size() - 1; i >= 0; i--) {
            Object entity = entitiesToRemove.get(i);
            if (entity != null) {
                dataManager.remove(entity);
            }
        }
        entitiesToRemove.clear();
        taskIdsToClean.clear();
    }

    @Test
    void attachTaskPhotosCreatesHistoryEventAndBoardTaskLinks() {
        Warehouse warehouse = createWarehouse();
        RentalCategory category = createCategory();
        RentalItem rentalItem = createRentalItem(warehouse, category);
        RepairEstimate estimate = createEstimate(warehouse, rentalItem);
        RepairProcess process = createRepairProcess(warehouse, rentalItem, estimate);
        BoardTask task = createBoardTask(warehouse, rentalItem, process);
        WorkQueue queue = createQueue(warehouse);
        QueueEntry queueEntry = createQueueEntry(task, queue);
        WorkerClass workerClass = createWorkerClass();
        WorkerGroup workerGroup = createWorkerGroup(warehouse, workerClass);
        Worker worker = createWorker(warehouse);
        createTaskAssignment(queueEntry, workerGroup, worker);

        int saved = repairProcessService.attachTaskPhotos(
                task.getId(),
                queueEntry.getId(),
                BoardTaskPhotoType.AFTER,
                "Подтвердили выполнение подзадачи",
                List.of(new RepairProcessService.RepairTaskUploadedPhoto(
                        "task-finish.jpg",
                        "image/jpeg",
                        new ByteArrayInputStream(new byte[] {1, 2, 3, 4})
                )));

        assertThat(saved).isEqualTo(1);

        List<RentalItemEvent> events = dataManager.load(RentalItemEvent.class)
                .query("select e from RentalItemEvent e where e.boardTask.id = :taskId order by e.createdDate desc")
                .parameter("taskId", task.getId())
                .list();
        assertThat(events).hasSize(1);
        RentalItemEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(RentalItemEventType.MANUAL);
        assertThat(event.getRepairProcess()).isNotNull();
        assertThat(event.getQueueEntry()).isNotNull();
        assertThat(event.getWorkerGroup()).isNotNull();
        assertThat(event.getWorker()).isNotNull();
        assertThat(event.getComment()).isEqualTo("Подтвердили выполнение подзадачи");
        assertThat(event.getSource()).isEqualTo("QUEUE_WORK_BOARD");

        List<RentalItemEventPhoto> photos = dataManager.load(RentalItemEventPhoto.class)
                .query("select e from RentalItemEventPhoto e where e.event.id = :eventId")
                .parameter("eventId", event.getId())
                .list();
        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).getStoragePath()).isNotBlank();

        List<BoardTaskPhotoLink> links = dataManager.load(BoardTaskPhotoLink.class)
                .query("select e from BoardTaskPhotoLink e where e.boardTask.id = :taskId")
                .parameter("taskId", task.getId())
                .list();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getPhotoType()).isEqualTo(BoardTaskPhotoType.AFTER);
        assertThat(links.get(0).getPhoto()).isNotNull();
        assertThat(links.get(0).getPhoto().getId()).isEqualTo(photos.get(0).getId());
    }

    private Warehouse createWarehouse() {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Фото склад " + UUID.randomUUID());
        warehouse.setCode("PHOTO-" + UUID.randomUUID().toString().substring(0, 8));
        warehouse.setCity("Moscow");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        Warehouse saved = dataManager.save(warehouse);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalCategory createCategory() {
        RentalCategory category = dataManager.create(RentalCategory.class);
        category.setName("Категория фото " + UUID.randomUUID());
        category.setCode("PHOTO-CAT-" + UUID.randomUUID().toString().substring(0, 8));
        category.setActive(true);
        RentalCategory saved = dataManager.save(category);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalItem createRentalItem(Warehouse warehouse, RentalCategory category) {
        RentalItem item = dataManager.create(RentalItem.class);
        item.setWarehouse(warehouse);
        item.setCategory(category);
        item.setNumber("PH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        item.setStatus("IN_REPAIR");
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

    private RepairProcess createRepairProcess(Warehouse warehouse, RentalItem rentalItem, RepairEstimate estimate) {
        RepairProcess process = dataManager.create(RepairProcess.class);
        process.setWarehouse(warehouse);
        process.setRentalItem(rentalItem);
        process.setEstimate(estimate);
        process.setStatus(RepairProcessStatus.ACTIVE);
        RepairProcess saved = dataManager.save(process);
        entitiesToRemove.add(saved);
        return saved;
    }

    private BoardTask createBoardTask(Warehouse warehouse, RentalItem rentalItem, RepairProcess process) {
        BoardTask task = dataManager.create(BoardTask.class);
        task.setWarehouse(warehouse);
        task.setRentalItem(rentalItem);
        task.setRepairProcess(process);
        task.setTitle("Фото подзадача " + UUID.randomUUID());
        task.setUnitNumber(rentalItem.getNumber());
        task.setStatus(BoardTaskStatus.ACTIVE);
        task.setTaskKind(RepairProcessTaskKind.REPAIR_WORK);
        BoardTask saved = dataManager.save(task);
        entitiesToRemove.add(saved);
        taskIdsToClean.add(saved.getId());
        return saved;
    }

    private WorkQueue createQueue(Warehouse warehouse) {
        WorkQueue queue = dataManager.create(WorkQueue.class);
        queue.setWarehouse(warehouse);
        queue.setCode("PHOTO-Q-" + UUID.randomUUID().toString().substring(0, 8));
        queue.setName("Фото очередь " + UUID.randomUUID());
        queue.setQueueKind(WorkQueueKind.REPAIR);
        queue.setActive(true);
        WorkQueue saved = dataManager.save(queue);
        entitiesToRemove.add(saved);
        return saved;
    }

    private QueueEntry createQueueEntry(BoardTask task, WorkQueue queue) {
        QueueEntry entry = dataManager.create(QueueEntry.class);
        entry.setTask(task);
        entry.setQueue(queue);
        entry.setEntryType(QueueEntryType.REAL);
        entry.setStatus(QueueEntryStatus.IN_PROGRESS);
        entry.setRouteIndex(0);
        entry.setPosition(0);
        QueueEntry saved = dataManager.save(entry);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerClass createWorkerClass() {
        WorkerClass workerClass = dataManager.create(WorkerClass.class);
        workerClass.setCode("PHOTO-WC-" + UUID.randomUUID().toString().substring(0, 8));
        workerClass.setName("Фото класс " + UUID.randomUUID());
        workerClass.setActive(true);
        WorkerClass saved = dataManager.save(workerClass);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerGroup createWorkerGroup(Warehouse warehouse, WorkerClass workerClass) {
        WorkerGroup group = dataManager.create(WorkerGroup.class);
        group.setWarehouse(warehouse);
        group.setWorkerClass(workerClass);
        group.setName("Фото бригада " + UUID.randomUUID());
        group.setActive(true);
        WorkerGroup saved = dataManager.save(group);
        entitiesToRemove.add(saved);
        return saved;
    }

    private Worker createWorker(Warehouse warehouse) {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(warehouse);
        worker.setDisplayName("Фото мастер " + UUID.randomUUID());
        worker.setActive(true);
        Worker saved = dataManager.save(worker);
        entitiesToRemove.add(saved);
        return saved;
    }

    private TaskAssignment createTaskAssignment(QueueEntry queueEntry, WorkerGroup workerGroup, Worker worker) {
        TaskAssignment assignment = dataManager.create(TaskAssignment.class);
        assignment.setQueueEntry(queueEntry);
        assignment.setWorkerGroup(workerGroup);
        assignment.setWorker(worker);
        assignment.setAssignedAt(OffsetDateTime.now());
        assignment.setStartedAt(OffsetDateTime.now());
        assignment.setStatus(TaskAssignmentStatus.ACTIVE);
        TaskAssignment saved = dataManager.save(assignment);
        entitiesToRemove.add(saved);
        return saved;
    }

    private void cleanupTaskPhotoGraph() {
        if (taskIdsToClean.isEmpty()) {
            return;
        }
        List<BoardTaskPhotoLink> links = dataManager.load(BoardTaskPhotoLink.class)
                .query("select e from BoardTaskPhotoLink e where e.boardTask.id in :taskIds")
                .parameter("taskIds", taskIdsToClean)
                .list();
        links.forEach(dataManager::remove);

        List<RentalItemEventPhoto> photos = dataManager.load(RentalItemEventPhoto.class)
                .query("select e from RentalItemEventPhoto e where e.event.boardTask.id in :taskIds")
                .parameter("taskIds", taskIdsToClean)
                .list();
        photos.forEach(dataManager::remove);

        List<RentalItemEvent> events = dataManager.load(RentalItemEvent.class)
                .query("select e from RentalItemEvent e where e.boardTask.id in :taskIds")
                .parameter("taskIds", taskIdsToClean)
                .list();
        events.forEach(dataManager::remove);
    }
}
