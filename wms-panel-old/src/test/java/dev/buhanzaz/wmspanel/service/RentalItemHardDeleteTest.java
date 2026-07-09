package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoLink;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoType;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntryType;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlanGenerationStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskLine;
import dev.buhanzaz.wmspanel.entity.TaskAssignment;
import dev.buhanzaz.wmspanel.entity.TaskAssignmentStatus;
import dev.buhanzaz.wmspanel.entity.TaskTimeEvent;
import dev.buhanzaz.wmspanel.entity.TaskTimeEventType;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class RentalItemHardDeleteTest {

    @Autowired
    DataManager dataManager;

    @Autowired
    EntityStates entityStates;

    private final List<Object> entitiesToRemove = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = entitiesToRemove.size() - 1; i >= 0; i--) {
            removeIfPresent(entitiesToRemove.get(i));
        }
        entitiesToRemove.clear();
    }

    @Test
    void deletingRentalItemHardDeletesRepairBoardDependenciesAndFreesNumber() {
        Warehouse warehouse = createWarehouse();
        RentalCategory category = createCategory();
        RentalItem rentalItem = createRentalItem(warehouse, category, "HD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        WorkQueue queue = createQueue(warehouse);

        RepairEstimate estimate = createEstimate(warehouse, rentalItem);
        RepairEstimateLine estimateLine = createEstimateLine(estimate);
        RepairProcess repairProcess = createRepairProcess(warehouse, rentalItem, estimate);
        BoardTask boardTask = createBoardTask(warehouse, rentalItem, repairProcess);
        QueueEntry queueEntry = createQueueEntry(boardTask, queue);
        TaskAssignment assignment = createTaskAssignment(queueEntry);
        TaskTimeEvent timeEvent = createTimeEvent(queueEntry);
        RepairEstimateTaskPlan taskPlan = createTaskPlan(estimate, estimateLine, repairProcess, queue, boardTask);
        RepairProcessTaskLine processTaskLine = createProcessTaskLine(taskPlan, estimateLine);
        RentalItemEvent event = createRentalItemEvent(warehouse, rentalItem, estimate, repairProcess, boardTask, queueEntry);
        RentalItemEventPhoto photo = createEventPhoto(event);
        BoardTaskPhotoLink photoLink = createPhotoLink(boardTask, photo);

        estimate.setLatestEvent(event);
        dataManager.save(estimate);

        dataManager.remove(rentalItem);
        entitiesToRemove.remove(rentalItem);

        assertThat(findById(RentalItem.class, rentalItem.getId())).isNull();
        assertThat(findById(RepairEstimate.class, estimate.getId())).isNull();
        assertThat(findById(RepairEstimateLine.class, estimateLine.getId())).isNull();
        assertThat(findById(RepairProcess.class, repairProcess.getId())).isNull();
        assertThat(findById(BoardTask.class, boardTask.getId())).isNull();
        assertThat(findById(QueueEntry.class, queueEntry.getId())).isNull();
        assertThat(findById(TaskAssignment.class, assignment.getId())).isNull();
        assertThat(findById(TaskTimeEvent.class, timeEvent.getId())).isNull();
        assertThat(findById(RepairEstimateTaskPlan.class, taskPlan.getId())).isNull();
        assertThat(findById(RepairProcessTaskLine.class, processTaskLine.getId())).isNull();
        assertThat(findById(RentalItemEvent.class, event.getId())).isNull();
        assertThat(findById(RentalItemEventPhoto.class, photo.getId())).isNull();
        assertThat(findById(BoardTaskPhotoLink.class, photoLink.getId())).isNull();

        RentalItem recreated = dataManager.create(RentalItem.class);
        recreated.setWarehouse(warehouse);
        recreated.setCategory(category);
        recreated.setNumber(rentalItem.getNumber());
        recreated.setStatus("READY");
        RentalItem savedAgain = dataManager.save(recreated);
        entitiesToRemove.add(savedAgain);

        assertThat(savedAgain.getNumber()).isEqualTo(rentalItem.getNumber());
    }

    private <T> T findById(Class<T> entityClass, Object id) {
        return dataManager.load(entityClass).id(id).optional().orElse(null);
    }

    private Warehouse createWarehouse() {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Hard delete warehouse " + UUID.randomUUID());
        warehouse.setCode("WH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        warehouse.setCity("Test City");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        Warehouse saved = dataManager.save(warehouse);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalCategory createCategory() {
        RentalCategory category = dataManager.create(RentalCategory.class);
        category.setName("Hard delete category " + UUID.randomUUID());
        category.setCode("CAT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        category.setActive(true);
        RentalCategory saved = dataManager.save(category);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalItem createRentalItem(Warehouse warehouse, RentalCategory category, String number) {
        RentalItem rentalItem = dataManager.create(RentalItem.class);
        rentalItem.setWarehouse(warehouse);
        rentalItem.setCategory(category);
        rentalItem.setNumber(number);
        rentalItem.setStatus("READY");
        RentalItem saved = dataManager.save(rentalItem);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkQueue createQueue(Warehouse warehouse) {
        WorkQueue queue = dataManager.create(WorkQueue.class);
        queue.setWarehouse(warehouse);
        queue.setCode("QUEUE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        queue.setName("Hard delete queue " + UUID.randomUUID());
        queue.setSortOrder(10);
        queue.setActive(true);
        queue.setCollapsed(false);
        queue.setHidden(false);
        queue.setSinkQueue(false);
        queue.setQueueKind(WorkQueueKind.REPAIR);
        WorkQueue saved = dataManager.save(queue);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairEstimate createEstimate(Warehouse warehouse, RentalItem rentalItem) {
        RepairEstimate estimate = dataManager.create(RepairEstimate.class);
        estimate.setWarehouse(warehouse);
        estimate.setRentalItem(rentalItem);
        estimate.setCabinNumber(rentalItem.getNumber());
        estimate.setSourceParty("Тест");
        estimate.setStatus(RepairEstimateStatus.COMPLETED);
        RepairEstimate saved = dataManager.save(estimate);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairEstimateLine createEstimateLine(RepairEstimate estimate) {
        RepairEstimateLine line = dataManager.create(RepairEstimateLine.class);
        line.setEstimate(estimate);
        line.setDescription("Проверка hard delete");
        line.setUnit("шт");
        line.setQuantity(1);
        line.setUnitPrice(BigDecimal.TEN);
        line.setLineTotal(BigDecimal.TEN);
        line.setRowOrder(0);
        RepairEstimateLine saved = dataManager.save(line);
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

    private BoardTask createBoardTask(Warehouse warehouse, RentalItem rentalItem, RepairProcess repairProcess) {
        BoardTask task = dataManager.create(BoardTask.class);
        task.setWarehouse(warehouse);
        task.setRentalItem(rentalItem);
        task.setRepairProcess(repairProcess);
        task.setTitle("Hard delete board task");
        task.setUnitNumber(rentalItem.getNumber());
        task.setStatus(dev.buhanzaz.wmspanel.entity.BoardTaskStatus.ACTIVE);
        BoardTask saved = dataManager.save(task);
        entitiesToRemove.add(saved);
        return saved;
    }

    private QueueEntry createQueueEntry(BoardTask boardTask, WorkQueue queue) {
        QueueEntry entry = dataManager.create(QueueEntry.class);
        entry.setTask(boardTask);
        entry.setQueue(queue);
        entry.setRouteIndex(0);
        entry.setPosition(0);
        entry.setEntryType(QueueEntryType.REAL);
        entry.setStatus(QueueEntryStatus.WAITING);
        QueueEntry saved = dataManager.save(entry);
        entitiesToRemove.add(saved);
        return saved;
    }

    private TaskAssignment createTaskAssignment(QueueEntry queueEntry) {
        TaskAssignment assignment = dataManager.create(TaskAssignment.class);
        assignment.setQueueEntry(queueEntry);
        assignment.setAssignedAt(OffsetDateTime.now());
        assignment.setStatus(TaskAssignmentStatus.ACTIVE);
        TaskAssignment saved = dataManager.save(assignment);
        entitiesToRemove.add(saved);
        return saved;
    }

    private TaskTimeEvent createTimeEvent(QueueEntry queueEntry) {
        TaskTimeEvent event = dataManager.create(TaskTimeEvent.class);
        event.setQueueEntry(queueEntry);
        event.setEventType(TaskTimeEventType.STARTED);
        event.setReason("hard-delete-test");
        event.setCreatedAt(OffsetDateTime.now());
        TaskTimeEvent saved = dataManager.save(event);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairEstimateTaskPlan createTaskPlan(RepairEstimate estimate,
                                                  RepairEstimateLine estimateLine,
                                                  RepairProcess repairProcess,
                                                  WorkQueue queue,
                                                  BoardTask boardTask) {
        RepairEstimateTaskPlan taskPlan = dataManager.create(RepairEstimateTaskPlan.class);
        taskPlan.setEstimate(estimate);
        taskPlan.setEstimateLine(estimateLine);
        taskPlan.setRepairProcess(repairProcess);
        taskPlan.setQueue(queue);
        taskPlan.setGeneratedBoardTask(boardTask);
        taskPlan.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.GENERATED);
        taskPlan.setSortOrder(0);
        taskPlan.setActive(true);
        RepairEstimateTaskPlan saved = dataManager.save(taskPlan);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairProcessTaskLine createProcessTaskLine(RepairEstimateTaskPlan taskPlan, RepairEstimateLine estimateLine) {
        RepairProcessTaskLine line = dataManager.create(RepairProcessTaskLine.class);
        line.setTaskPlan(taskPlan);
        line.setEstimateLine(estimateLine);
        line.setLineType(estimateLine.getLineType());
        line.setSortOrder(0);
        line.setPrimaryWorkLine(true);
        RepairProcessTaskLine saved = dataManager.save(line);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalItemEvent createRentalItemEvent(Warehouse warehouse,
                                                  RentalItem rentalItem,
                                                  RepairEstimate estimate,
                                                  RepairProcess repairProcess,
                                                  BoardTask boardTask,
                                                  QueueEntry queueEntry) {
        RentalItemEvent event = dataManager.create(RentalItemEvent.class);
        event.setWarehouse(warehouse);
        event.setRentalItem(rentalItem);
        event.setEventType(RentalItemEventType.MANUAL);
        event.setTitle("Hard delete event");
        event.setEventDate(OffsetDateTime.now());
        event.setEstimate(estimate);
        event.setRepairProcess(repairProcess);
        event.setBoardTask(boardTask);
        event.setQueueEntry(queueEntry);
        RentalItemEvent saved = dataManager.save(event);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalItemEventPhoto createEventPhoto(RentalItemEvent event) {
        RentalItemEventPhoto photo = dataManager.create(RentalItemEventPhoto.class);
        photo.setEvent(event);
        photo.setStoragePath("test/" + UUID.randomUUID() + ".jpg");
        photo.setOriginalFileName("photo.jpg");
        photo.setContentType("image/jpeg");
        photo.setSizeBytes(1L);
        photo.setSortOrder(0);
        RentalItemEventPhoto saved = dataManager.save(photo);
        entitiesToRemove.add(saved);
        return saved;
    }

    private BoardTaskPhotoLink createPhotoLink(BoardTask boardTask, RentalItemEventPhoto photo) {
        BoardTaskPhotoLink link = dataManager.create(BoardTaskPhotoLink.class);
        link.setBoardTask(boardTask);
        link.setPhoto(photo);
        link.setPhotoType(BoardTaskPhotoType.WORK);
        link.setSortOrder(0);
        BoardTaskPhotoLink saved = dataManager.save(link);
        entitiesToRemove.add(saved);
        return saved;
    }

    private void removeIfPresent(Object entity) {
        Object entityId = entity == null ? null : EntityValues.getId(entity);
        if (entity == null || entityStates.isDetached(entity) && entityId == null) {
            return;
        }
        try {
            Object reloaded = dataManager.load(entity.getClass())
                    .id(entityId)
                    .optional()
                    .orElse(null);
            if (reloaded != null) {
                dataManager.remove(reloaded);
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup; cascade tests intentionally remove parts of the graph mid-test.
        }
    }
}
