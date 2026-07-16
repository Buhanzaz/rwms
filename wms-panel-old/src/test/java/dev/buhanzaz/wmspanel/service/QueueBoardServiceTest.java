package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.QueueEntryType;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.WorkQueueWorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerGroupMember;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.entity.EntityValues;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class QueueBoardServiceTest {

    @Autowired
    QueueBoardService queueBoardService;

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
    void createTaskCreatesRealFirstEntryAndShadowFutureEntries() {
        WorkerGroup group = firstWorkerGroup();
        RentalItem item = firstRentalItem();
        Warehouse warehouse = item.getWarehouse();
        WorkerClass workerClass = group.getWorkerClass();
        String suffix = UUID.randomUUID().toString();
        WorkQueue first = queueBoardService.createQueue(warehouse, "Тест маршрут 1 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));
        WorkQueue second = queueBoardService.createQueue(warehouse, "Тест маршрут 2 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));

        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                item,
                "Проверка маршрута",
                null,
                List.of(
                        new QueueBoardService.QueueTaskStep(first, "Первый этап", 10),
                        new QueueBoardService.QueueTaskStep(second, "Второй этап", 20)),
                null,
                null));

        QueueBoardService.BoardState state = queueBoardService.getBoardState(warehouse, true);
        QueueBoardService.EntryCard firstCard = onlyCard(state, first);
        QueueBoardService.EntryCard secondCard = onlyCard(state, second);

        assertThat(firstCard.entry().getEntryType()).isEqualTo(QueueEntryType.REAL);
        assertThat(firstCard.entry().getRouteIndex()).isZero();
        assertThat(secondCard.entry().getEntryType()).isEqualTo(QueueEntryType.SHADOW);
        assertThat(secondCard.entry().getRouteIndex()).isEqualTo(1);
    }

    @Test
    void boardStateDoesNotAutocreateQueuesForWarehouseWithoutQueues() {
        Warehouse warehouse = createWarehouse();

        QueueBoardService.BoardState state = queueBoardService.getBoardState(warehouse, true);
        List<WorkQueue> queues = queueBoardService.loadQueues(warehouse);

        assertThat(state.columns()).isEmpty();
        assertThat(queues).isEmpty();
    }

    @Test
    void completeCurrentTaskPromotesNextShadowToReal() {
        WorkerGroup group = firstWorkerGroup();
        RentalItem item = firstRentalItem();
        Warehouse warehouse = item.getWarehouse();
        WorkerClass workerClass = group.getWorkerClass();
        String suffix = UUID.randomUUID().toString();
        WorkQueue first = queueBoardService.createQueue(warehouse, "Тест завершение 1 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));
        WorkQueue second = queueBoardService.createQueue(warehouse, "Тест завершение 2 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));

        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                item,
                null,
                null,
                List.of(
                        new QueueBoardService.QueueTaskStep(first, "Первый этап", 10),
                        new QueueBoardService.QueueTaskStep(second, "Второй этап", 20)),
                null,
                null));

        queueBoardService.takeNextTask(first.getId(), group, null);
        queueBoardService.completeCurrentTask(first.getId());

        QueueBoardService.EntryCard secondCard = onlyCard(queueBoardService.getBoardState(warehouse, true), second);
        assertThat(secondCard.entry().getEntryType()).isEqualTo(QueueEntryType.REAL);
    }

    @Test
    void laterRepairProcessQueueIsShadowAndCannotBeTakenBeforePreviousQueueDone() {
        Warehouse warehouse = createWarehouse();
        WorkerClass workerClass = createWorkerClass();
        WorkerGroup group = createWorkerGroup(warehouse, workerClass);
        RentalItem item = createRentalItem(warehouse);
        RepairProcess process = createRepairProcess(warehouse, item);
        String suffix = UUID.randomUUID().toString();
        WorkQueue first = queueBoardService.createQueue(warehouse, "Тест порядок 1 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));
        WorkQueue second = queueBoardService.createQueue(warehouse, "Тест порядок 2 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));

        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                item,
                "Первый этап процесса",
                null,
                List.of(new QueueBoardService.QueueTaskStep(first, "Первый этап", 10)),
                process,
                null));
        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                item,
                "Второй этап процесса",
                null,
                List.of(new QueueBoardService.QueueTaskStep(second, "Второй этап", 20)),
                process,
                null));

        QueueBoardService.BoardState state = queueBoardService.getBoardState(warehouse, true);
        QueueBoardService.EntryCard secondCard = onlyCard(state, second);

        assertThat(secondCard.entry().getEntryType()).isEqualTo(QueueEntryType.REAL);
        assertThat(secondCard.effectiveEntryType()).isEqualTo(QueueEntryType.SHADOW);
        assertThatThrownBy(() -> queueBoardService.takeNextTask(second.getId(), group, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Сначала завершите предыдущую очередь");

        queueBoardService.takeNextTask(first.getId(), group, null);
        queueBoardService.completeCurrentTask(first.getId());

        assertThat(queueBoardService.takeNextTask(second.getId(), group, null))
                .contains("Задача взята в работу");
    }

    @Test
    void reorderedRepairProcessRouteControlsWhichQueueCanStartFirst() {
        Warehouse warehouse = createWarehouse();
        WorkerClass workerClass = createWorkerClass();
        WorkerGroup group = createWorkerGroup(warehouse, workerClass);
        RentalItem item = createRentalItem(warehouse);
        RepairProcess process = createRepairProcess(warehouse, item);
        String suffix = UUID.randomUUID().toString();
        WorkQueue first = queueBoardService.createQueue(warehouse, "Тест маршрут процесса 1 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));
        WorkQueue second = queueBoardService.createQueue(warehouse, "Тест маршрут процесса 2 " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));

        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                item,
                "Первый этап процесса",
                null,
                List.of(new QueueBoardService.QueueTaskStep(first, "Первый этап", 10)),
                process,
                null));
        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                item,
                "Второй этап процесса",
                null,
                List.of(new QueueBoardService.QueueTaskStep(second, "Второй этап", 20)),
                process,
                null));

        QueueBoardService.EntryCard firstCard = onlyCard(queueBoardService.getBoardState(warehouse, true), first);
        QueueBoardService.EntryCard secondCard = onlyCard(queueBoardService.getBoardState(warehouse, true), second);
        queueBoardService.reorderProcessRoute(process.getId(), List.of(secondCard.entry().getId(), firstCard.entry().getId()));

        QueueBoardService.BoardState state = queueBoardService.getBoardState(warehouse, true);
        QueueBoardService.EntryCard reorderedFirstCard = onlyCard(state, first);
        QueueBoardService.EntryCard reorderedSecondCard = onlyCard(state, second);

        assertThat(reorderedFirstCard.effectiveEntryType()).isEqualTo(QueueEntryType.SHADOW);
        assertThat(reorderedSecondCard.effectiveEntryType()).isEqualTo(QueueEntryType.REAL);
        assertThatThrownBy(() -> queueBoardService.takeNextTask(first.getId(), group, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Сначала завершите предыдущую очередь");
        assertThat(queueBoardService.takeNextTask(second.getId(), group, null))
                .contains("Задача взята в работу");
    }

    @Test
    void sinkQueueHidesFutureShadowEntries() {
        WorkerGroup group = firstWorkerGroup();
        RentalItem item = firstRentalItem();
        Warehouse warehouse = item.getWarehouse();
        WorkerClass workerClass = group.getWorkerClass();
        String suffix = UUID.randomUUID().toString();
        WorkQueue sink = queueBoardService.createQueue(warehouse, "Тест отстойник " + suffix, null, WorkQueueKind.HOLDING, 120, 2, true, Set.of(workerClass));
        WorkQueue afterSink = queueBoardService.createQueue(warehouse, "Тест после отстойника " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));

        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                item,
                null,
                null,
                List.of(
                        new QueueBoardService.QueueTaskStep(sink, "Ждать накопления", 10),
                        new QueueBoardService.QueueTaskStep(afterSink, "Следующий этап", 20)),
                null,
                null));

        QueueBoardService.BoardState state = queueBoardService.getBoardState(warehouse, true);

        assertThat(cardsForQueue(state, sink)).hasSize(1);
        assertThat(cardsForQueue(state, afterSink)).isEmpty();
    }

    @Test
    void syncQueuesCreatesOrUpdatesAQueueForEverySelectedWarehouse() {
        WorkerGroup group = firstWorkerGroup();
        WorkerClass workerClass = group.getWorkerClass();
        Warehouse firstWarehouse = createWarehouse();
        Warehouse secondWarehouse = createWarehouse();
        String code = "BULK_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        WorkQueue existing = dataManager.create(WorkQueue.class);
        existing.setWarehouse(firstWarehouse);
        existing.setCode(code);
        existing.setName("Старая очередь");
        existing.setSortOrder(10);
        existing.setActive(true);
        existing.setCollapsed(false);
        existing.setHidden(false);
        existing.setQueueKind(WorkQueueKind.REPAIR);
        dataManager.save(existing);
        entitiesToRemove.add(existing);

        queueBoardService.syncQueues(
                Set.of(firstWarehouse, secondWarehouse),
                code,
                "Синхронная очередь",
                "Описание",
                WorkQueueKind.HOLDING,
                90,
                7,
                true,
                Set.of(workerClass));

        List<WorkQueue> queues = dataManager.load(WorkQueue.class)
                .query("select e from WorkQueue e where e.code = :code order by e.warehouse.name")
                .parameter("code", code)
                .list();
        entitiesToRemove.addAll(queues);

        assertThat(queues).hasSize(2);
        assertThat(queues)
                .extracting(WorkQueue::getName)
                .containsOnly("Синхронная очередь");
        assertThat(queues)
                .allSatisfy(queue -> {
                    assertThat(queue.getQueueKind()).isEqualTo(WorkQueueKind.HOLDING);
                    assertThat(queue.getHoldingPeriodMinutes()).isEqualTo(90);
                    assertThat(queue.getNotificationThreshold()).isEqualTo(7);
                    assertThat(queue.getNotifyWhenThresholdReached()).isTrue();
                });
    }

    @Test
    void syncQueuesCreatesFreshQueueAfterHardDelete() {
        Warehouse warehouse = createWarehouse();
        WorkerClass workerClass = firstWorkerGroup().getWorkerClass();
        String code = "RESTORE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        WorkQueue queue = dataManager.create(WorkQueue.class);
        queue.setWarehouse(warehouse);
        queue.setCode(code);
        queue.setName("Удаленная очередь");
        queue.setSortOrder(10);
        queue.setActive(true);
        queue.setCollapsed(false);
        queue.setHidden(false);
        queue.setQueueKind(WorkQueueKind.REPAIR);
        WorkQueue savedQueue = dataManager.save(queue);

        dataManager.remove(savedQueue);
        entitiesToRemove.remove(savedQueue);

        queueBoardService.syncQueues(
                Set.of(warehouse),
                code,
                "Восстановленная очередь",
                "Описание",
                WorkQueueKind.REPAIR,
                null,
                null,
                false,
                Set.of(workerClass));

        List<WorkQueue> queues = dataManager.load(WorkQueue.class)
                .query("select e from WorkQueue e where e.warehouse = :warehouse and e.code = :code")
                .parameter("warehouse", warehouse)
                .parameter("code", code)
                .list();
        entitiesToRemove.addAll(queues);

        assertThat(queues).hasSize(1);
        assertThat(queues.get(0).getName()).isEqualTo("Восстановленная очередь");
    }

    @Test
    void queueKeepsBoundWorkerGroups() {
        WorkerGroup group = firstWorkerGroup();
        RentalItem item = firstRentalItem();
        Warehouse warehouse = item.getWarehouse();
        WorkerClass workerClass = group.getWorkerClass();
        String suffix = UUID.randomUUID().toString();
        WorkQueue queue = queueBoardService.createQueue(warehouse, "Тест группа " + suffix, null, WorkQueueKind.REPAIR, null, false, Set.of(workerClass));

        List<WorkQueueWorkerGroup> bindings = dataManager.load(WorkQueueWorkerGroup.class)
                .query("select e from WorkQueueWorkerGroup e where e.queue = :queue")
                .parameter("queue", queue)
                .list();

        assertThat(bindings)
                .extracting(binding -> binding.getWorkerClass().getId())
                .containsExactly(workerClass.getId());
    }

    @Test
    void updateQueueKeepsExistingWorkerClassBindingWithoutUniqueViolation() {
        WorkerGroup group = firstWorkerGroup();
        WorkerClass workerClass = group.getWorkerClass();
        Warehouse warehouse = group.getWarehouse();
        String suffix = UUID.randomUUID().toString();
        WorkQueue queue = queueBoardService.createQueue(
                warehouse,
                "Тест привязки " + suffix,
                null,
                WorkQueueKind.REPAIR,
                null,
                null,
                false,
                List.of(new QueueBoardService.QueueWorkerClassBinding(workerClass, false)));

        queueBoardService.updateQueue(
                queue,
                "Тест привязки обновлен " + suffix,
                null,
                WorkQueueKind.REPAIR,
                null,
                null,
                false,
                List.of(new QueueBoardService.QueueWorkerClassBinding(workerClass, true)));

        List<WorkQueueWorkerGroup> bindings = dataManager.load(WorkQueueWorkerGroup.class)
                .query("select e from WorkQueueWorkerGroup e where e.queue = :queue")
                .parameter("queue", queue)
                .list();

        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).getWorkerClass().getId()).isEqualTo(workerClass.getId());
        assertThat(bindings.get(0).getStopTaskOnTake()).isTrue();
    }

    @Test
    void saveQueueOrderKeepsHoldingQueuesAtTheEnd() {
        Warehouse warehouse = createWarehouse();
        WorkerClass workerClass = createWorkerClass();
        WorkQueue first = queueBoardService.createQueue(warehouse, "Порядок 1 " + UUID.randomUUID(), null,
                WorkQueueKind.REPAIR, null, false, Set.of(workerClass));
        WorkQueue second = queueBoardService.createQueue(warehouse, "Порядок 2 " + UUID.randomUUID(), null,
                WorkQueueKind.REPAIR, null, false, Set.of(workerClass));
        WorkQueue holding = queueBoardService.createQueue(warehouse, "Порядок накопительная " + UUID.randomUUID(), null,
                WorkQueueKind.HOLDING, 60, 3, true, Set.of(workerClass));

        queueBoardService.saveQueueOrder(warehouse, List.of(holding.getId(), second.getId(), first.getId()));

        List<WorkQueue> queues = queueBoardService.loadQueues(warehouse);
        assertThat(queues).extracting(WorkQueue::getId)
                .containsExactly(second.getId(), first.getId(), holding.getId());
        assertThat(queues.get(2).getQueueKind()).isEqualTo(WorkQueueKind.HOLDING);
    }

    @Test
    void stopTaskOnTakePausesCurrentTaskForSameWorker() {
        Warehouse warehouse = createWarehouse();
        WorkerClass workerClass = createWorkerClass();
        WorkerGroup group = createWorkerGroup(warehouse, workerClass);
        Worker worker = createWorker(warehouse, "Тестовый рабочий " + UUID.randomUUID());
        createWorkerGroupMember(group, worker);
        RentalItem firstItem = createRentalItem(warehouse);
        RentalItem secondItem = createRentalItem(warehouse);
        String suffix = UUID.randomUUID().toString();

        WorkQueue firstQueue = queueBoardService.createQueue(
                warehouse,
                "Первая очередь " + suffix,
                null,
                WorkQueueKind.REPAIR,
                null,
                null,
                false,
                List.of(new QueueBoardService.QueueWorkerClassBinding(workerClass, false)));
        WorkQueue secondQueue = queueBoardService.createQueue(
                warehouse,
                "Вторая очередь " + suffix,
                null,
                WorkQueueKind.REPAIR,
                null,
                null,
                false,
                List.of(new QueueBoardService.QueueWorkerClassBinding(workerClass, true)));

        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                firstItem,
                "Первая задача",
                null,
                List.of(new QueueBoardService.QueueTaskStep(firstQueue, "Этап 1", 10)),
                null,
                null));
        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                secondItem,
                "Вторая задача",
                null,
                List.of(new QueueBoardService.QueueTaskStep(secondQueue, "Этап 2", 10)),
                null,
                null));

        queueBoardService.takeNextTask(firstQueue.getId(), group, worker);
        queueBoardService.takeNextTask(secondQueue.getId(), group, worker);

        QueueBoardService.BoardState state = queueBoardService.getBoardState(warehouse, true);
        assertThat(onlyCard(state, firstQueue).entry().getStatus()).isEqualTo(dev.buhanzaz.wmspanel.entity.QueueEntryStatus.PAUSED);
        assertThat(onlyCard(state, secondQueue).entry().getStatus()).isEqualTo(dev.buhanzaz.wmspanel.entity.QueueEntryStatus.IN_PROGRESS);
    }

    @Test
    void finishingInterruptingTaskResumesPausedTaskForWorkerInAnotherGroup() {
        Warehouse warehouse = createWarehouse();
        WorkerClass repairClass = createWorkerClass();
        WorkerClass loaderClass = createWorkerClass();
        WorkerGroup repairGroup = createWorkerGroup(warehouse, repairClass);
        WorkerGroup loaderGroup = createWorkerGroup(warehouse, loaderClass);
        Worker worker = createWorker(warehouse, "Влад " + UUID.randomUUID());
        createWorkerGroupMember(repairGroup, worker);
        createWorkerGroupMember(loaderGroup, worker);
        RentalItem repairItem = createRentalItem(warehouse);
        RentalItem loadingItem = createRentalItem(warehouse);
        String suffix = UUID.randomUUID().toString();

        WorkQueue repairQueue = queueBoardService.createQueue(
                warehouse,
                "Ремонт " + suffix,
                null,
                WorkQueueKind.REPAIR,
                null,
                null,
                false,
                List.of(new QueueBoardService.QueueWorkerClassBinding(repairClass, false)));
        WorkQueue loadingQueue = queueBoardService.createQueue(
                warehouse,
                "Погрузка " + suffix,
                null,
                WorkQueueKind.MOVEMENT,
                null,
                null,
                false,
                List.of(new QueueBoardService.QueueWorkerClassBinding(loaderClass, true)));

        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                repairItem,
                "Замена двери",
                null,
                List.of(new QueueBoardService.QueueTaskStep(repairQueue, "Ремонт", 60)),
                null,
                null));
        queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                loadingItem,
                "Разгрузка машины",
                null,
                List.of(new QueueBoardService.QueueTaskStep(loadingQueue, "Разгрузка", 30)),
                null,
                null));

        queueBoardService.takeNextTask(repairQueue.getId(), repairGroup, null);
        queueBoardService.takeNextTask(loadingQueue.getId(), loaderGroup, null);

        assertThat(onlyCard(queueBoardService.getBoardState(warehouse, true), repairQueue).entry().getStatus())
                .isEqualTo(dev.buhanzaz.wmspanel.entity.QueueEntryStatus.PAUSED);

        queueBoardService.completeCurrentTask(loadingQueue.getId());

        assertThat(onlyCard(queueBoardService.getBoardState(warehouse, true), repairQueue).entry().getStatus())
                .isEqualTo(dev.buhanzaz.wmspanel.entity.QueueEntryStatus.IN_PROGRESS);
    }

    private WorkerGroup firstWorkerGroup() {
        return createWorkerGroup();
    }

    private RentalItem firstRentalItem() {
        return createRentalItem();
    }

    private WorkerGroup createWorkerGroup() {
        Warehouse warehouse = createWarehouse();
        WorkerClass workerClass = createWorkerClass();
        return createWorkerGroup(warehouse, workerClass);
    }

    private WorkerGroup createWorkerGroup(Warehouse warehouse, WorkerClass workerClass) {
        WorkerGroup group = dataManager.create(WorkerGroup.class);
        group.setWarehouse(warehouse);
        group.setWorkerClass(workerClass);
        group.setName("Test group " + UUID.randomUUID());
        group.setActive(true);
        WorkerGroup saved = dataManager.save(group);
        entitiesToRemove.add(saved);
        return saved;
    }

    private Worker createWorker(Warehouse warehouse, String displayName) {
        Worker worker = dataManager.create(Worker.class);
        worker.setWarehouse(warehouse);
        worker.setDisplayName(displayName);
        worker.setActive(true);
        Worker saved = dataManager.save(worker);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerGroupMember createWorkerGroupMember(WorkerGroup workerGroup, Worker worker) {
        WorkerGroupMember member = dataManager.create(WorkerGroupMember.class);
        member.setWorkerGroup(workerGroup);
        member.setWorker(worker);
        member.setActive(true);
        WorkerGroupMember saved = dataManager.save(member);
        entitiesToRemove.add(saved);
        return saved;
    }

    private WorkerClass createWorkerClass() {
        WorkerClass workerClass = dataManager.create(WorkerClass.class);
        workerClass.setCode("TEST_CLASS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        workerClass.setName("Test class " + UUID.randomUUID());
        workerClass.setActive(true);
        WorkerClass saved = dataManager.save(workerClass);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RentalItem createRentalItem() {
        Warehouse warehouse = createWarehouse();
        return createRentalItem(warehouse);
    }

    private RentalItem createRentalItem(Warehouse warehouse) {
        RentalCategory category = createCategory();
        RentalItem rentalItem = dataManager.create(RentalItem.class);
        rentalItem.setWarehouse(warehouse);
        rentalItem.setCategory(category);
        rentalItem.setNumber("TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rentalItem.setStatus("READY");
        RentalItem saved = dataManager.save(rentalItem);
        entitiesToRemove.add(saved);
        return saved;
    }

    private RepairProcess createRepairProcess(Warehouse warehouse, RentalItem rentalItem) {
        RepairEstimate estimate = dataManager.create(RepairEstimate.class);
        estimate.setWarehouse(warehouse);
        estimate.setRentalItem(rentalItem);
        estimate.setCabinNumber(rentalItem.getNumber());
        estimate.setSourceParty("Тест");
        estimate.setStatus(RepairEstimateStatus.COMPLETED);
        RepairEstimate savedEstimate = dataManager.save(estimate);

        RepairProcess process = dataManager.create(RepairProcess.class);
        process.setWarehouse(warehouse);
        process.setRentalItem(rentalItem);
        process.setEstimate(savedEstimate);
        RepairProcess savedProcess = dataManager.save(process);

        entitiesToRemove.add(savedProcess);
        entitiesToRemove.add(savedEstimate);
        return savedProcess;
    }

    private Warehouse createWarehouse() {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Test warehouse " + UUID.randomUUID());
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
        category.setName("Test category " + UUID.randomUUID());
        category.setCode("CAT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        category.setActive(true);
        RentalCategory saved = dataManager.save(category);
        entitiesToRemove.add(saved);
        return saved;
    }

    private QueueBoardService.EntryCard onlyCard(QueueBoardService.BoardState state, WorkQueue queue) {
        List<QueueBoardService.EntryCard> cards = cardsForQueue(state, queue);
        assertThat(cards).hasSize(1);
        return cards.get(0);
    }

    private List<QueueBoardService.EntryCard> cardsForQueue(QueueBoardService.BoardState state, WorkQueue queue) {
        return state.columns().stream()
                .filter(column -> column.queue().getId().equals(queue.getId()))
                .findFirst()
                .orElseThrow()
                .cards();
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
            // Test cleanup should be best-effort: some entities are already removed by the scenario itself.
        }
    }
}
