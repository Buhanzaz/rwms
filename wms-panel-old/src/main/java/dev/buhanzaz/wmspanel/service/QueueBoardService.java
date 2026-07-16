package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntryType;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.TaskAssignment;
import dev.buhanzaz.wmspanel.entity.TaskAssignmentStatus;
import dev.buhanzaz.wmspanel.entity.TaskTimeEvent;
import dev.buhanzaz.wmspanel.entity.TaskTimeEventType;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.WorkQueueWorkerGroup;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerGroupMember;
import dev.buhanzaz.wmspanel.entity.Worker;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlans;
import io.jmix.core.SaveContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class QueueBoardService {

    private final DataManager dataManager;
    private final FetchPlans fetchPlans;
    private final RepairProcessService repairProcessService;
    private final RentalItemEventService rentalItemEventService;

    public QueueBoardService(DataManager dataManager,
                             FetchPlans fetchPlans,
                             RepairProcessService repairProcessService,
                             RentalItemEventService rentalItemEventService) {
        this.dataManager = dataManager;
        this.fetchPlans = fetchPlans;
        this.repairProcessService = repairProcessService;
        this.rentalItemEventService = rentalItemEventService;
    }

    public BoardState getBoardState(Warehouse warehouse, boolean showShadow) {
        Warehouse boardWarehouse = requireWarehouse(warehouse);
        List<WorkQueue> queues = loadQueues(boardWarehouse);
        List<QueueEntry> entries = dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.status <> 'DONE'
                          and e.queue.warehouse = :warehouse
                        order by e.queue.sortOrder, e.position
                        """)
                .parameter("warehouse", boardWarehouse)
                .fetchPlan(queueEntryFetchPlan())
                .list();
        List<TaskAssignment> assignments = dataManager.load(TaskAssignment.class)
                .query("select e from TaskAssignment e where e.status <> 'DONE'")
                .fetchPlan(taskAssignmentFetchPlan())
                .list();

        Map<UUID, List<QueueEntry>> entriesByTaskId = entries.stream()
                .collect(Collectors.groupingBy(entry -> entry.getTask().getId(), LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<TaskAssignment>> assignmentsByEntryId = assignments.stream()
                .collect(Collectors.groupingBy(assignment -> assignment.getQueueEntry().getId(), LinkedHashMap::new, Collectors.toList()));
        Set<UUID> blockedEntryIds = blockedEntryIds(entries);

        List<QueueColumn> columns = new ArrayList<>();
        for (WorkQueue queue : queues) {
            if (Boolean.TRUE.equals(queue.getHidden())) {
                continue;
            }
            List<EntryCard> cards = entries.stream()
                    .filter(entry -> sameId(entry.getQueue(), queue))
                    .filter(entry -> shouldShowEntry(
                            entry,
                            entriesByTaskId.get(entry.getTask().getId()),
                            showShadow,
                            blockedEntryIds.contains(entry.getId())))
                    .sorted(Comparator.comparing(entry -> valueOrZero(entry.getPosition())))
                    .map(entry -> toCard(
                            entry,
                            entriesByTaskId.get(entry.getTask().getId()),
                            assignmentsByEntryId.get(entry.getId()),
                            blockedEntryIds.contains(entry.getId())))
                    .toList();
            columns.add(new QueueColumn(queue, cards, boundGroupNames(queue)));
        }

        return new BoardState(columns);
    }

    public List<Warehouse> loadActiveWarehouses() {
        return dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where e.active = true order by e.name")
                .fetchPlan("_base")
                .list();
    }

    public List<RentalItem> loadRentalItems(Warehouse warehouse) {
        Warehouse boardWarehouse = requireWarehouse(warehouse);
        return dataManager.load(RentalItem.class)
                .query("select e from RentalItem e where e.warehouse = :warehouse order by e.number")
                .parameter("warehouse", boardWarehouse)
                .fetchPlan(rentalItemFetchPlan())
                .list();
    }

    public List<WorkQueue> loadQueues(Warehouse warehouse) {
        Warehouse boardWarehouse = requireWarehouse(warehouse);
        return dataManager.load(WorkQueue.class)
                .query("select e from WorkQueue e where e.active = true and e.warehouse = :warehouse order by e.sortOrder, e.name")
                .parameter("warehouse", boardWarehouse)
                .fetchPlan(workQueueFetchPlan())
                .list();
    }

    public List<WorkQueue> loadAllQueues(Warehouse warehouse) {
        Warehouse boardWarehouse = requireWarehouse(warehouse);
        return dataManager.load(WorkQueue.class)
                .query("select e from WorkQueue e where e.warehouse = :warehouse order by e.sortOrder, e.name")
                .parameter("warehouse", boardWarehouse)
                .fetchPlan(workQueueFetchPlan())
                .list();
    }

    public List<WorkerClass> loadActiveWorkerClasses() {
        return dataManager.load(WorkerClass.class)
                .query("select e from WorkerClass e where e.active = true order by e.sortOrder, e.name")
                .fetchPlan("_base")
                .list();
    }

    public List<WorkerGroup> loadActiveWorkerGroups() {
        return dataManager.load(WorkerGroup.class)
                .query("select e from WorkerGroup e where e.active = true order by e.workerClass.sortOrder, e.name")
                .fetchPlan(workerGroupFetchPlan())
                .list();
    }

    public List<WorkerClass> loadWorkerClassesForQueue(WorkQueue queue) {
        if (queue == null || queue.getId() == null) {
            return List.of();
        }
        return dataManager.load(WorkQueueWorkerGroup.class)
                .query("select e from WorkQueueWorkerGroup e where e.queue = :queue order by e.workerClass.sortOrder, e.workerClass.name")
                .parameter("queue", queue)
                .fetchPlan(workQueueWorkerGroupFetchPlan())
                .list()
                .stream()
                .map(WorkQueueWorkerGroup::getWorkerClass)
                .toList();
    }

    public List<QueueWorkerClassBinding> loadBindingsForQueue(WorkQueue queue) {
        if (queue == null || queue.getId() == null) {
            return List.of();
        }
        return dataManager.load(WorkQueueWorkerGroup.class)
                .query("select e from WorkQueueWorkerGroup e where e.queue = :queue order by e.workerClass.sortOrder, e.workerClass.name")
                .parameter("queue", queue)
                .fetchPlan(workQueueWorkerGroupFetchPlan())
                .list()
                .stream()
                .map(binding -> new QueueWorkerClassBinding(
                        binding.getWorkerClass(),
                        Boolean.TRUE.equals(binding.getStopTaskOnTake())))
                .toList();
    }

    public List<WorkerGroup> loadWorkerGroupsForQueue(WorkQueue queue) {
        List<WorkerClass> classes = loadWorkerClassesForQueue(queue);
        if (classes.isEmpty()) {
            return List.of();
        }
        return dataManager.load(WorkerGroup.class)
                .query("""
                        select e from WorkerGroup e
                        where e.active = true
                          and e.workerClass in :classes
                          and e.warehouse = :warehouse
                        order by e.workerClass.sortOrder, e.name
                        """)
                .parameter("classes", classes)
                .parameter("warehouse", queue.getWarehouse())
                .fetchPlan(workerGroupFetchPlan())
                .list();
    }

    public List<Worker> loadActiveWorkers(Warehouse warehouse) {
        if (warehouse == null || warehouse.getId() == null) {
            return List.of();
        }
        return dataManager.load(Worker.class)
                .query("select e from Worker e where e.active = true and e.warehouse = :warehouse order by e.displayName")
                .parameter("warehouse", warehouse)
                .fetchPlan("_base")
                .list();
    }

    public List<Worker> loadActiveWorkersForGroup(WorkerGroup workerGroup) {
        if (workerGroup == null || workerGroup.getId() == null) {
            return List.of();
        }
        return dataManager.load(WorkerGroupMember.class)
                .query("""
                        select e from WorkerGroupMember e
                        where e.workerGroup = :workerGroup
                          and e.active = true
                          and e.worker is not null
                          and e.worker.active = true
                        order by e.worker.displayName
                        """)
                .parameter("workerGroup", workerGroup)
                .fetchPlan(workerGroupMemberFetchPlan())
                .list()
                .stream()
                .map(WorkerGroupMember::getWorker)
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public WorkQueue createQueue(Warehouse warehouse, String name, String description, WorkQueueKind queueKind, Integer threshold,
                                 boolean notifyWhenThresholdReached, Set<WorkerClass> workerClasses) {
        return createQueue(warehouse, name, description, queueKind, null, threshold, notifyWhenThresholdReached, workerClasses);
    }

    @Transactional
    public WorkQueue createQueue(Warehouse warehouse, String name, String description, WorkQueueKind queueKind, Integer holdingPeriodMinutes,
                                 Integer threshold, boolean notifyWhenThresholdReached, Set<WorkerClass> workerClasses) {
        return createQueue(warehouse, name, description, queueKind, holdingPeriodMinutes, threshold, notifyWhenThresholdReached,
                safeWorkerClasses(workerClasses).stream().map(workerClass -> new QueueWorkerClassBinding(workerClass, false)).toList());
    }

    @Transactional
    public WorkQueue createQueue(Warehouse warehouse, String name, String description, WorkQueueKind queueKind, Integer holdingPeriodMinutes,
                                 Integer threshold, boolean notifyWhenThresholdReached, List<QueueWorkerClassBinding> bindings) {
        Warehouse boardWarehouse = requireWarehouse(warehouse);
        WorkQueueKind kind = queueKind == null ? WorkQueueKind.REPAIR : queueKind;
        boolean sinkQueue = kind == WorkQueueKind.HOLDING;
        WorkQueue queue = dataManager.create(WorkQueue.class);
        queue.setWarehouse(boardWarehouse);
        queue.setCode(nextQueueCode(boardWarehouse, name));
        queue.setName(required(name, "Введите название очереди"));
        queue.setDescription(trimToNull(description));
        queue.setSortOrder(nextQueueSortOrder(boardWarehouse));
        queue.setActive(true);
        queue.setCollapsed(false);
        queue.setHidden(false);
        queue.setSinkQueue(sinkQueue);
        queue.setQueueKind(kind);
        queue.setNotificationThreshold(sinkQueue ? threshold : null);
        queue.setNotifyWhenThresholdReached(sinkQueue && notifyWhenThresholdReached);
        queue.setHoldingPeriodMinutes(sinkQueue ? holdingPeriodMinutes : null);

        saveQueueWithBindings(queue, bindings);
        return queue;
    }

    @Transactional
    public void updateQueue(WorkQueue queue, String name, String description, WorkQueueKind queueKind, Integer threshold,
                            boolean notifyWhenThresholdReached, Set<WorkerClass> workerClasses) {
        updateQueue(queue, name, description, queueKind, null, threshold, notifyWhenThresholdReached, workerClasses);
    }

    @Transactional
    public void updateQueue(WorkQueue queue, String name, String description, WorkQueueKind queueKind, Integer holdingPeriodMinutes,
                            Integer threshold, boolean notifyWhenThresholdReached, Set<WorkerClass> workerClasses) {
        updateQueue(queue, name, description, queueKind, holdingPeriodMinutes, threshold, notifyWhenThresholdReached,
                safeWorkerClasses(workerClasses).stream().map(workerClass -> new QueueWorkerClassBinding(workerClass, false)).toList());
    }

    @Transactional
    public void updateQueue(WorkQueue queue, String name, String description, WorkQueueKind queueKind, Integer holdingPeriodMinutes,
                            Integer threshold, boolean notifyWhenThresholdReached, List<QueueWorkerClassBinding> bindings) {
        WorkQueueKind kind = queueKind == null ? WorkQueueKind.REPAIR : queueKind;
        boolean sinkQueue = kind == WorkQueueKind.HOLDING;
        WorkQueue reloaded = reloadQueue(queue.getId());
        reloaded.setName(required(name, "Введите название очереди"));
        reloaded.setDescription(trimToNull(description));
        reloaded.setSinkQueue(sinkQueue);
        reloaded.setQueueKind(kind);
        reloaded.setNotificationThreshold(sinkQueue ? threshold : null);
        reloaded.setNotifyWhenThresholdReached(sinkQueue && notifyWhenThresholdReached);
        reloaded.setHoldingPeriodMinutes(sinkQueue ? holdingPeriodMinutes : null);

        saveQueueWithBindings(reloaded, bindings);
    }

    @Transactional
    public List<WorkQueue> syncQueues(Set<Warehouse> warehouses, String code, String name, String description,
                                      WorkQueueKind queueKind, Integer holdingPeriodMinutes, Integer threshold,
                                      boolean notifyWhenThresholdReached, Set<WorkerClass> workerClasses) {
        return syncQueues(warehouses, code, name, description, queueKind, holdingPeriodMinutes, threshold, notifyWhenThresholdReached,
                safeWorkerClasses(workerClasses).stream().map(workerClass -> new QueueWorkerClassBinding(workerClass, false)).toList());
    }

    @Transactional
    public List<WorkQueue> syncQueues(Set<Warehouse> warehouses, String code, String name, String description,
                                      WorkQueueKind queueKind, Integer holdingPeriodMinutes, Integer threshold,
                                      boolean notifyWhenThresholdReached, List<QueueWorkerClassBinding> bindings) {
        String normalizedCode = normalizeQueueCode(code);
        List<WorkQueue> savedQueues = new ArrayList<>();
        for (Warehouse warehouse : safeWarehouses(warehouses)) {
            Warehouse boardWarehouse = requireWarehouse(warehouse);
            WorkQueue queue = findExistingQueueByCode(boardWarehouse, normalizedCode)
                    .map(existing -> reloadQueue(existing.getId()))
                    .orElseGet(() -> {
                        WorkQueue created = dataManager.create(WorkQueue.class);
                        created.setWarehouse(boardWarehouse);
                        created.setCode(normalizedCode);
                        created.setSortOrder(nextQueueSortOrder(boardWarehouse));
                        created.setActive(true);
                        created.setCollapsed(false);
                        created.setHidden(false);
                        return created;
                    });
            savedQueues.add(configureQueue(queue, name, description, queueKind, holdingPeriodMinutes, threshold,
                    notifyWhenThresholdReached, bindings));
        }
        return savedQueues;
    }

    @Transactional
    public void setQueueCollapsed(UUID queueId, boolean collapsed) {
        WorkQueue queue = reloadQueue(queueId);
        queue.setCollapsed(collapsed);
        dataManager.save(queue);
    }

    @Transactional
    public void setQueueHidden(UUID queueId, boolean hidden) {
        WorkQueue queue = reloadQueue(queueId);
        queue.setHidden(hidden);
        dataManager.save(queue);
    }

    @Transactional
    public void showAllQueues(Warehouse warehouse) {
        loadAllQueues(warehouse).forEach(queue -> {
            queue.setHidden(false);
            dataManager.save(queue);
        });
    }

    @Transactional
    public void moveQueueLeft(UUID queueId) {
        moveQueue(queueId, -1);
    }

    @Transactional
    public void moveQueueRight(UUID queueId) {
        moveQueue(queueId, 1);
    }

    @Transactional
    public void saveQueueOrder(Warehouse warehouse, List<UUID> orderedQueueIds) {
        Warehouse boardWarehouse = requireWarehouse(warehouse);
        if (orderedQueueIds == null || orderedQueueIds.isEmpty()) {
            return;
        }
        Map<UUID, WorkQueue> queuesById = loadAllQueues(boardWarehouse).stream()
                .collect(Collectors.toMap(WorkQueue::getId, queue -> queue, (left, right) -> left, LinkedHashMap::new));
        List<WorkQueue> orderedQueues = new ArrayList<>();
        for (UUID queueId : orderedQueueIds) {
            WorkQueue queue = queuesById.remove(queueId);
            if (queue != null && !isSinkQueue(queue)) {
                orderedQueues.add(queue);
            }
        }
        queuesById.values().stream()
                .filter(queue -> !isSinkQueue(queue))
                .sorted(queueComparator())
                .forEach(orderedQueues::add);
        queuesById.values().stream()
                .filter(this::isSinkQueue)
                .sorted(queueComparator())
                .forEach(orderedQueues::add);

        SaveContext saveContext = new SaveContext();
        int sortOrder = 10;
        for (WorkQueue queue : orderedQueues) {
            queue.setSortOrder(sortOrder);
            sortOrder += 10;
            saveContext.saving(queue);
        }
        dataManager.save(saveContext);
    }

    @Transactional
    public BoardTask createTask(CreateTaskCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Не переданы данные задачи");
        }
        RentalItem selectedItem = command.rentalItem();
        if (selectedItem == null || selectedItem.getId() == null) {
            throw new IllegalArgumentException("Выберите бытовку из базы");
        }
        RentalItem rentalItem = dataManager.load(RentalItem.class).id(selectedItem.getId()).fetchPlan(rentalItemFetchPlan()).one();
        Warehouse warehouse = requireWarehouse(rentalItem.getWarehouse());
        List<QueueTaskStep> route = command.routeSteps().stream()
                .filter(step -> step.queue() != null)
                .toList();
        if (route.isEmpty()) {
            throw new IllegalArgumentException("Выберите хотя бы одну очередь выполнения");
        }

        BoardTask task = dataManager.create(BoardTask.class);
        task.setTitle("Бытовка " + rentalItem.getNumber());
        task.setUnitNumber(rentalItem.getNumber());
        task.setWarehouse(warehouse);
        task.setRentalItem(rentalItem);
        task.setRepairProcess(command.repairProcess());
        task.setTaskKind(command.taskKind() == null ? RepairProcessTaskKind.REPAIR_WORK : command.taskKind());
        task.setDescription(trimToNull(command.description()));
        task.setStatus(BoardTaskStatus.ACTIVE);
        task.setDeadlineAt(command.deadlineAt());
        task.setPlannedDurationMinutes(totalPlannedDurationMinutes(route));

        SaveContext saveContext = new SaveContext().saving(task);
        for (int i = 0; i < route.size(); i++) {
            QueueTaskStep step = route.get(i);
            QueueEntry entry = dataManager.create(QueueEntry.class);
            WorkQueue queue = reloadQueue(step.queue().getId());
            if (!sameWarehouse(queue.getWarehouse(), warehouse)) {
                throw new IllegalArgumentException("Маршрут должен быть в том же складе, что и бытовка");
            }
            entry.setTask(task);
            entry.setQueue(queue);
            entry.setRouteIndex(i);
            entry.setPosition(nextPosition(queue));
            entry.setEntryType(i == 0 ? QueueEntryType.REAL : QueueEntryType.SHADOW);
            entry.setStatus(QueueEntryStatus.WAITING);
            entry.setTaskText(trimToNull(step.taskText()));
            entry.setPlannedDurationMinutes(step.plannedDurationMinutes());
            entry.setActiveWorkSeconds(0L);
            saveContext.saving(entry);
        }
        dataManager.save(saveContext);
        return task;
    }

    @Transactional
    public String takeNextTask(UUID queueId, WorkerGroup workerGroup, Worker worker) {
        if (workerGroup == null && worker == null) {
            throw new IllegalArgumentException("Выберите рабочую группу или рабочего");
        }
        if (workerGroup != null && worker != null && !isWorkerMemberOfGroup(workerGroup, worker)) {
            throw new IllegalArgumentException("Выбранный рабочий не состоит в указанной рабочей группе");
        }
        WorkQueue queue = reloadQueue(queueId);
        QueueEntry entry = dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.queue = :queue
                          and e.entryType = 'REAL'
                          and e.status = 'WAITING'
                        order by e.position
                        """)
                .parameter("queue", queue)
                .fetchPlan(queueEntryFetchPlan())
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Нет доступных задач в этой очереди"));

        findBlockingPreviousEntry(entry).ifPresent(blocker -> {
            throw new IllegalArgumentException("Сначала завершите предыдущую очередь: " + blocker.getQueue().getName());
        });

        OffsetDateTime now = OffsetDateTime.now();
        SaveContext saveContext = new SaveContext();
        List<Worker> assignedWorkers = resolveAssignedWorkers(workerGroup, worker);
        if (shouldStopCurrentTaskOnTake(queue, workerGroup)) {
            pauseActiveEntriesForWorkers(assignedWorkers, entry, now, saveContext);
        }

        entry.setStatus(QueueEntryStatus.IN_PROGRESS);
        entry.setActiveStartedAt(now);
        entry.setPausedAt(null);

        if (assignedWorkers.isEmpty()) {
            TaskAssignment assignment = createAssignment(entry, workerGroup, null, now);
            saveContext.saving(assignment, timeEvent(entry, null, TaskTimeEventType.STARTED, "Задача взята в работу", now));
        } else {
            for (Worker assignedWorker : assignedWorkers) {
                TaskAssignment assignment = createAssignment(entry, workerGroup, assignedWorker, now);
                saveContext.saving(assignment, timeEvent(entry, assignedWorker, TaskTimeEventType.STARTED, "Задача взята в работу", now));
            }
        }

        saveContext.saving(entry);
        dataManager.save(saveContext);
        QueueEntry savedEntry = reloadEntry(entry.getId());
        Worker eventWorker = worker != null
                ? worker
                : assignedWorkers.isEmpty() ? null : assignedWorkers.get(0);
        rentalItemEventService.recordRepairTaskStarted(
                savedEntry.getTask().getRepairProcess(),
                savedEntry.getTask(),
                savedEntry,
                workerGroup,
                eventWorker);
        return "Задача взята в работу: " + savedEntry.getTask().getTitle();
    }

    @Transactional
    public List<String> completeCurrentTask(UUID queueId) {
        WorkQueue queue = reloadQueue(queueId);
        QueueEntry current = dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.queue = :queue
                          and e.entryType = 'REAL'
                          and e.status = 'IN_PROGRESS'
                        order by e.position
                        """)
                .parameter("queue", queue)
                .fetchPlan(queueEntryFetchPlan())
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("В этой очереди нет задачи в работе"));

        OffsetDateTime now = OffsetDateTime.now();
        SaveContext saveContext = new SaveContext();
        stopTimer(current, now);
        current.setStatus(QueueEntryStatus.DONE);
        current.setDoneAt(now);
        saveContext.saving(current);

        List<TaskAssignment> assignments = activeAssignments(current);
        for (TaskAssignment assignment : assignments) {
            assignment.setStatus(TaskAssignmentStatus.DONE);
            assignment.setFinishedAt(now);
            saveContext.saving(assignment, timeEvent(current, assignment.getWorker(), TaskTimeEventType.FINISHED, "Этап завершен", now));
        }
        if (shouldResumeInterruptedTasks(queue, assignments)) {
            resumePausedEntriesForWorkers(
                    assignments.stream()
                            .map(TaskAssignment::getWorker)
                            .filter(Objects::nonNull)
                            .toList(),
                    current,
                    now,
                    saveContext);
        }

        List<String> messages = new ArrayList<>();
        BoardTask task = current.getTask();
        applyMovementCompletionFlag(task, saveContext);

        Optional<QueueEntry> next = nextShadow(task);
        if (next.isPresent()) {
            QueueEntry nextEntry = next.get();
            nextEntry.setEntryType(QueueEntryType.REAL);
            nextEntry.setStatus(QueueEntryStatus.WAITING);
            saveContext.saving(nextEntry);
            messages.add("Теневая задача стала обычной: " + task.getTitle());
        } else {
            task.setStatus(BoardTaskStatus.DONE);
            task.setDoneAt(now);
            saveContext.saving(task);
        }
        dataManager.save(saveContext);
        QueueEntry savedCurrent = reloadEntry(current.getId());
        TaskAssignment primaryAssignment = assignments.isEmpty() ? null : assignments.get(0);
        rentalItemEventService.recordRepairTaskCompleted(
                savedCurrent.getTask().getRepairProcess(),
                savedCurrent.getTask(),
                savedCurrent,
                primaryAssignment == null ? null : primaryAssignment.getWorkerGroup(),
                primaryAssignment == null ? null : primaryAssignment.getWorker());
        normalizeQueuePositions(queue);
        RepairProcess process = savedCurrent.getTask().getRepairProcess();
        if (process != null) {
            boolean readyAfterRepair = repairProcessService.syncProcessStatus(process);
            if (readyAfterRepair) {
                rentalItemEventService.recordRepairReadyForCheck(process);
            }
        }
        messages.add("Задача завершена: " + savedCurrent.getTask().getTitle());
        return messages;
    }

    @Transactional
    public void pauseTask(UUID entryId, String reason) {
        QueueEntry entry = reloadEntry(entryId);
        if (entry.getStatus() != QueueEntryStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("Поставить на паузу можно только задачу в работе");
        }
        OffsetDateTime now = OffsetDateTime.now();
        SaveContext saveContext = new SaveContext();
        pauseEntry(entry, now, trimToNull(reason) == null ? "Пауза" : trimToNull(reason), saveContext);
        dataManager.save(saveContext);
    }

    @Transactional
    public void resumeTask(UUID entryId) {
        QueueEntry entry = reloadEntry(entryId);
        if (entry.getStatus() != QueueEntryStatus.PAUSED) {
            throw new IllegalArgumentException("Возобновить можно только задачу на паузе");
        }
        OffsetDateTime now = OffsetDateTime.now();
        entry.setStatus(QueueEntryStatus.IN_PROGRESS);
        entry.setActiveStartedAt(now);
        entry.setPausedAt(null);

        SaveContext saveContext = new SaveContext().saving(entry);
        for (TaskAssignment assignment : activeAssignments(entry)) {
            assignment.setStatus(TaskAssignmentStatus.ACTIVE);
            assignment.setPausedAt(null);
            saveContext.saving(assignment, timeEvent(entry, assignment.getWorker(), TaskTimeEventType.RESUMED, "Таймер возобновлен", now));
        }
        dataManager.save(saveContext);
    }

    @Transactional
    public void reorderEntriesInQueue(UUID queueId, List<UUID> orderedEntryIds) {
        WorkQueue queue = reloadQueue(queueId);
        Map<UUID, QueueEntry> entries = dataManager.load(QueueEntry.class)
                .query("select e from QueueEntry e where e.queue = :queue and e.status <> 'DONE'")
                .parameter("queue", queue)
                .fetchPlan(queueEntryFetchPlan())
                .list()
                .stream()
                .collect(Collectors.toMap(QueueEntry::getId, entry -> entry));

        SaveContext saveContext = new SaveContext();
        int position = 0;
        for (UUID entryId : orderedEntryIds) {
            QueueEntry entry = entries.remove(entryId);
            if (entry != null) {
                entry.setPosition(position++);
                saveContext.saving(entry);
            }
        }
        for (QueueEntry entry : entries.values().stream().sorted(Comparator.comparing(e -> valueOrZero(e.getPosition()))).toList()) {
            entry.setPosition(position++);
            saveContext.saving(entry);
        }
        dataManager.save(saveContext);
    }

    public List<QueueEntry> loadProcessRouteEntries(UUID repairProcessId) {
        if (repairProcessId == null) {
            return List.of();
        }
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(repairProcessId)
                .one();
        return dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.task.repairProcess = :process
                        order by e.routeIndex, e.queue.sortOrder, e.position
                        """)
                .parameter("process", process)
                .fetchPlan(queueEntryFetchPlan())
                .list();
    }

    @Transactional
    public void reorderProcessRoute(UUID repairProcessId, List<UUID> orderedEntryIds) {
        if (repairProcessId == null || orderedEntryIds == null) {
            return;
        }
        RepairProcess process = dataManager.load(RepairProcess.class)
                .id(repairProcessId)
                .one();
        Map<UUID, QueueEntry> entries = dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.task.repairProcess = :process
                        """)
                .parameter("process", process)
                .fetchPlan(queueEntryFetchPlan())
                .list()
                .stream()
                .collect(Collectors.toMap(QueueEntry::getId, entry -> entry));

        SaveContext saveContext = new SaveContext();
        int routeIndex = 10;
        for (UUID entryId : orderedEntryIds) {
            QueueEntry entry = entries.remove(entryId);
            if (entry != null) {
                entry.setRouteIndex(routeIndex);
                routeIndex += 10;
                saveContext.saving(entry);
            }
        }
        for (QueueEntry entry : entries.values().stream()
                .sorted(Comparator
                        .comparing((QueueEntry e) -> valueOrZero(e.getRouteIndex()))
                        .thenComparing(e -> e.getQueue() == null ? Integer.MAX_VALUE : valueOrZero(e.getQueue().getSortOrder()))
                        .thenComparing(e -> valueOrZero(e.getPosition())))
                .toList()) {
            entry.setRouteIndex(routeIndex);
            routeIndex += 10;
            saveContext.saving(entry);
        }
        dataManager.save(saveContext);
    }

    @Transactional
    public void moveEntryToQueue(UUID entryId, UUID targetQueueId, int targetIndex) {
        QueueEntry dragged = reloadEntry(entryId);
        if (dragged.getStatus() == QueueEntryStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("Задача уже в работе, ее нельзя переносить");
        }

        WorkQueue sourceQueue = dragged.getQueue();
        WorkQueue targetQueue = reloadQueue(targetQueueId);
        if (sameId(sourceQueue, targetQueue)) {
            List<UUID> orderedIds = dataManager.load(QueueEntry.class)
                    .query("select e from QueueEntry e where e.queue = :queue and e.status <> 'DONE' order by e.position")
                    .parameter("queue", targetQueue)
                    .fetchPlan("_base")
                    .list()
                    .stream()
                    .map(QueueEntry::getId)
                    .collect(Collectors.toCollection(ArrayList::new));
            orderedIds.remove(entryId);
            orderedIds.add(Math.max(0, Math.min(targetIndex, orderedIds.size())), entryId);
            reorderEntriesInQueue(targetQueueId, orderedIds);
            return;
        }

        Optional<QueueEntry> targetShadow = dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.task = :task
                          and e.queue = :queue
                          and e.entryType = 'SHADOW'
                          and e.status <> 'DONE'
                          and e <> :dragged
                        order by e.routeIndex
                        """)
                .parameter("task", dragged.getTask())
                .parameter("queue", targetQueue)
                .parameter("dragged", dragged)
                .fetchPlan(queueEntryFetchPlan())
                .optional();

        SaveContext saveContext = new SaveContext();
        if (dragged.getEntryType() == QueueEntryType.REAL && targetShadow.isPresent()) {
            QueueEntry shadow = targetShadow.get();
            int draggedRouteIndex = valueOrZero(dragged.getRouteIndex());
            dragged.setEntryType(QueueEntryType.SHADOW);
            dragged.setStatus(QueueEntryStatus.WAITING);
            dragged.setRouteIndex(valueOrZero(shadow.getRouteIndex()));
            shadow.setEntryType(QueueEntryType.REAL);
            shadow.setStatus(QueueEntryStatus.WAITING);
            shadow.setRouteIndex(draggedRouteIndex);
            saveContext.saving(dragged, shadow);
            insertEntryAtPosition(shadow, targetQueue, targetIndex, saveContext);
        } else if (hasVisibleEntryInQueue(dragged.getTask(), targetQueue, dragged)) {
            throw new IllegalArgumentException("Эта задача уже есть в целевой очереди");
        } else {
            dragged.setQueue(targetQueue);
            saveContext.saving(dragged);
            insertEntryAtPosition(dragged, targetQueue, targetIndex, saveContext);
        }

        dataManager.save(saveContext);
        normalizeQueuePositions(sourceQueue);
        normalizeQueuePositions(targetQueue);
    }

    private EntryCard toCard(
            QueueEntry entry,
            List<QueueEntry> routeEntries,
            List<TaskAssignment> assignments,
            boolean blockedByPreviousEntry) {
        List<RouteChip> route = routeEntries.stream()
                .filter(routeEntry -> routeEntry.getStatus() != QueueEntryStatus.DONE)
                .sorted(Comparator.comparing(routeEntry -> valueOrZero(routeEntry.getRouteIndex())))
                .map(routeEntry -> new RouteChip(
                        routeEntry.getQueue().getName(),
                        routeEntry.getEntryType() == QueueEntryType.REAL,
                        routeEntry.getStatus()))
                .toList();
        long activeSeconds = currentActiveSeconds(entry);
        int plannedMinutes = entry.getPlannedDurationMinutes() != null
                ? entry.getPlannedDurationMinutes()
                : valueOrZero(entry.getTask().getPlannedDurationMinutes());
        List<AssignmentInfo> assignmentInfos = assignments == null
                ? List.of()
                : assignments.stream().map(this::toAssignmentInfo).toList();
        QueueEntryType effectiveEntryType = blockedByPreviousEntry ? QueueEntryType.SHADOW : entry.getEntryType();
        return new EntryCard(entry, effectiveEntryType, route, assignmentInfos, activeSeconds, plannedMinutes);
    }

    private AssignmentInfo toAssignmentInfo(TaskAssignment assignment) {
        Worker user = assignment.getWorker();
        WorkerGroup group = assignment.getWorkerGroup();
        return new AssignmentInfo(
                user == null ? null : user.getDisplayName(),
                null,
                group == null ? null : group.getName());
    }

    private boolean shouldShowEntry(
            QueueEntry entry,
            List<QueueEntry> routeEntries,
            boolean showShadow,
            boolean blockedByPreviousEntry) {
        if (entry.getStatus() == QueueEntryStatus.DONE) {
            return false;
        }
        boolean shadow = entry.getEntryType() == QueueEntryType.SHADOW || blockedByPreviousEntry;
        if (shadow && !showShadow) {
            return false;
        }
        if (entry.getEntryType() == QueueEntryType.SHADOW && routeEntries != null) {
            Optional<QueueEntry> real = routeEntries.stream()
                    .filter(routeEntry -> routeEntry.getEntryType() == QueueEntryType.REAL)
                    .filter(routeEntry -> routeEntry.getStatus() != QueueEntryStatus.DONE)
                    .findFirst();
            if (real.isPresent()
                    && Boolean.TRUE.equals(real.get().getQueue().getSinkQueue())
                    && valueOrZero(entry.getRouteIndex()) > valueOrZero(real.get().getRouteIndex())) {
                return false;
            }
        }
        return true;
    }

    private Set<UUID> blockedEntryIds(List<QueueEntry> entries) {
        Set<UUID> result = new LinkedHashSet<>();
        for (QueueEntry entry : entries) {
            if (findBlockingPreviousEntry(entry).isPresent()) {
                result.add(entry.getId());
            }
        }
        return result;
    }

    private Optional<QueueEntry> findBlockingPreviousEntry(QueueEntry entry) {
        Optional<QueueEntry> routeBlocker = findBlockingPreviousRouteEntry(entry);
        return routeBlocker.isPresent() ? routeBlocker : findBlockingPreviousProcessEntry(entry);
    }

    private Optional<QueueEntry> findBlockingPreviousRouteEntry(QueueEntry entry) {
        if (entry == null || entry.getTask() == null || valueOrZero(entry.getRouteIndex()) <= 0) {
            return Optional.empty();
        }
        return dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.task = :task
                          and e.status <> 'DONE'
                          and e.routeIndex < :routeIndex
                        order by e.routeIndex, e.position
                        """)
                .parameter("task", entry.getTask())
                .parameter("routeIndex", entry.getRouteIndex())
                .fetchPlan(queueEntryFetchPlan())
                .optional();
    }

    private Optional<QueueEntry> findBlockingPreviousProcessEntry(QueueEntry entry) {
        if (entry == null || entry.getTask() == null || entry.getTask().getRepairProcess() == null || entry.getQueue() == null) {
            return Optional.empty();
        }
        if (valueOrZero(entry.getRouteIndex()) > 0) {
            return dataManager.load(QueueEntry.class)
                    .query("""
                            select e from QueueEntry e
                            where e.task.repairProcess = :process
                              and e.status <> 'DONE'
                              and e <> :entry
                              and e.routeIndex < :routeIndex
                            order by e.routeIndex, e.position
                            """)
                    .parameter("process", entry.getTask().getRepairProcess())
                    .parameter("entry", entry)
                    .parameter("routeIndex", entry.getRouteIndex())
                    .fetchPlan(queueEntryFetchPlan())
                    .optional();
        }
        return dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.task.repairProcess = :process
                          and e.status <> 'DONE'
                          and e <> :entry
                          and e.queue.sortOrder < :sortOrder
                        order by e.queue.sortOrder, e.position
                        """)
                .parameter("process", entry.getTask().getRepairProcess())
                .parameter("entry", entry)
                .parameter("sortOrder", valueOrZero(entry.getQueue().getSortOrder()))
                .fetchPlan(queueEntryFetchPlan())
                .optional();
    }

    private List<String> boundGroupNames(WorkQueue queue) {
        return loadWorkerClassesForQueue(queue).stream()
                .map(WorkerClass::getName)
                .toList();
    }

    private List<Worker> resolveAssignedWorkers(WorkerGroup workerGroup, Worker worker) {
        if (worker != null) {
            return List.of(worker);
        }
        if (workerGroup == null) {
            return List.of();
        }
        return dataManager.load(WorkerGroupMember.class)
                .query("""
                        select e from WorkerGroupMember e
                        where e.workerGroup = :workerGroup
                          and e.active = true
                          and e.worker is not null
                          and e.worker.active = true
                        order by e.worker.displayName
                        """)
                .parameter("workerGroup", workerGroup)
                .fetchPlan(workerGroupMemberFetchPlan())
                .list()
                .stream()
                .map(WorkerGroupMember::getWorker)
                .filter(Objects::nonNull)
                .toList();
    }

    private TaskAssignment createAssignment(QueueEntry entry, WorkerGroup workerGroup, Worker worker, OffsetDateTime now) {
        TaskAssignment assignment = dataManager.create(TaskAssignment.class);
        assignment.setQueueEntry(entry);
        assignment.setWorkerGroup(workerGroup);
        assignment.setWorker(worker);
        assignment.setAssignedAt(now);
        assignment.setStartedAt(now);
        assignment.setStatus(TaskAssignmentStatus.ACTIVE);
        return assignment;
    }

    private void pauseActiveEntriesForWorkers(List<Worker> workers, QueueEntry newEntry, OffsetDateTime now, SaveContext saveContext) {
        Set<UUID> pausedEntryIds = new LinkedHashSet<>();
        for (Worker worker : workers) {
            pauseActiveEntriesForWorker(worker, newEntry, now, saveContext, pausedEntryIds);
        }
    }

    private void resumePausedEntriesForWorkers(List<Worker> workers, QueueEntry completedEntry, OffsetDateTime now, SaveContext saveContext) {
        Set<UUID> resumedEntryIds = new LinkedHashSet<>();
        for (Worker worker : workers) {
            resumePausedEntriesForWorker(worker, completedEntry, now, saveContext, resumedEntryIds);
        }
    }

    private boolean isWorkerMemberOfGroup(WorkerGroup workerGroup, Worker worker) {
        if (workerGroup == null || workerGroup.getId() == null || worker == null || worker.getId() == null) {
            return false;
        }
        return dataManager.loadValue("""
                        select count(e) from WorkerGroupMember e
                        where e.workerGroup = :workerGroup
                          and e.worker = :worker
                          and e.active = true
                        """, Long.class)
                .parameter("workerGroup", workerGroup)
                .parameter("worker", worker)
                .one() > 0;
    }

    private void pauseActiveEntriesForWorker(Worker worker, QueueEntry newEntry, OffsetDateTime now,
                                             SaveContext saveContext, Set<UUID> pausedEntryIds) {
        List<TaskAssignment> assignments = dataManager.load(TaskAssignment.class)
                .query("""
                        select e from TaskAssignment e
                        where e.worker = :worker
                          and e.status = 'ACTIVE'
                          and e.queueEntry <> :newEntry
                        """)
                .parameter("worker", worker)
                .parameter("newEntry", newEntry)
                .fetchPlan(taskAssignmentFetchPlan())
                .list();

        for (TaskAssignment assignment : assignments) {
            QueueEntry oldEntry = assignment.getQueueEntry();
            if (oldEntry.getStatus() == QueueEntryStatus.IN_PROGRESS && pausedEntryIds.add(oldEntry.getId())) {
                pauseEntry(oldEntry, now, "Рабочий переключен на другую задачу", saveContext);
            }
        }
    }

    private void resumePausedEntriesForWorker(Worker worker, QueueEntry completedEntry, OffsetDateTime now,
                                              SaveContext saveContext, Set<UUID> resumedEntryIds) {
        List<TaskAssignment> assignments = dataManager.load(TaskAssignment.class)
                .query("""
                        select e from TaskAssignment e
                        where e.worker = :worker
                          and e.status = 'PAUSED'
                          and e.queueEntry <> :completedEntry
                        """)
                .parameter("worker", worker)
                .parameter("completedEntry", completedEntry)
                .fetchPlan(taskAssignmentFetchPlan())
                .list();

        for (TaskAssignment assignment : assignments) {
            QueueEntry pausedEntry = assignment.getQueueEntry();
            if (pausedEntry.getStatus() == QueueEntryStatus.PAUSED && resumedEntryIds.add(pausedEntry.getId())) {
                resumeEntry(pausedEntry, now, "Переключение завершено, таймер возобновлен", saveContext);
            }
        }
    }

    private boolean shouldResumeInterruptedTasks(WorkQueue queue, List<TaskAssignment> assignments) {
        return assignments.stream()
                .map(TaskAssignment::getWorkerGroup)
                .filter(Objects::nonNull)
                .anyMatch(workerGroup -> shouldStopCurrentTaskOnTake(queue, workerGroup));
    }

    private boolean hasVisibleEntryInQueue(BoardTask task, WorkQueue queue, QueueEntry excluded) {
        return dataManager.loadValue("""
                        select count(e) from QueueEntry e
                        where e.task = :task
                          and e.queue = :queue
                          and e.status <> 'DONE'
                          and e <> :excluded
                        """, Long.class)
                .parameter("task", task)
                .parameter("queue", queue)
                .parameter("excluded", excluded)
                .one() > 0;
    }

    private void insertEntryAtPosition(QueueEntry entry, WorkQueue targetQueue, int targetIndex, SaveContext saveContext) {
        List<QueueEntry> entries = dataManager.load(QueueEntry.class)
                .query("select e from QueueEntry e where e.queue = :queue and e.status <> 'DONE' and e <> :entry order by e.position")
                .parameter("queue", targetQueue)
                .parameter("entry", entry)
                .fetchPlan(queueEntryFetchPlan())
                .list()
                .stream()
                .collect(Collectors.toCollection(ArrayList::new));
        int safeIndex = Math.max(0, Math.min(targetIndex, entries.size()));
        entries.add(safeIndex, entry);
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setPosition(i);
            saveContext.saving(entries.get(i));
        }
    }

    private void pauseEntry(QueueEntry entry, OffsetDateTime now, String reason, SaveContext saveContext) {
        stopTimer(entry, now);
        entry.setStatus(QueueEntryStatus.PAUSED);
        entry.setPausedAt(now);
        saveContext.saving(entry);
        for (TaskAssignment assignment : activeAssignments(entry)) {
            assignment.setStatus(TaskAssignmentStatus.PAUSED);
            assignment.setPausedAt(now);
            saveContext.saving(assignment, timeEvent(entry, assignment.getWorker(), TaskTimeEventType.PAUSED, reason, now));
        }
    }

    private void resumeEntry(QueueEntry entry, OffsetDateTime now, String reason, SaveContext saveContext) {
        entry.setStatus(QueueEntryStatus.IN_PROGRESS);
        entry.setActiveStartedAt(now);
        entry.setPausedAt(null);
        saveContext.saving(entry);
        for (TaskAssignment assignment : activeAssignments(entry)) {
            assignment.setStatus(TaskAssignmentStatus.ACTIVE);
            assignment.setPausedAt(null);
            saveContext.saving(assignment, timeEvent(entry, assignment.getWorker(), TaskTimeEventType.RESUMED, reason, now));
        }
    }

    private void stopTimer(QueueEntry entry, OffsetDateTime now) {
        if (entry.getActiveStartedAt() != null) {
            long delta = Math.max(0, Duration.between(entry.getActiveStartedAt(), now).getSeconds());
            entry.setActiveWorkSeconds(valueOrZero(entry.getActiveWorkSeconds()) + delta);
        }
        entry.setActiveStartedAt(null);
    }

    private List<TaskAssignment> activeAssignments(QueueEntry entry) {
        return dataManager.load(TaskAssignment.class)
                .query("select e from TaskAssignment e where e.queueEntry = :entry and e.status <> 'DONE'")
                .parameter("entry", entry)
                .fetchPlan(taskAssignmentFetchPlan())
                .list();
    }

    private Optional<QueueEntry> nextShadow(BoardTask task) {
        return dataManager.load(QueueEntry.class)
                .query("""
                        select e from QueueEntry e
                        where e.task = :task
                          and e.entryType = 'SHADOW'
                          and e.status = 'WAITING'
                        order by e.routeIndex
                        """)
                .parameter("task", task)
                .fetchPlan(queueEntryFetchPlan())
                .optional();
    }

    private void applyMovementCompletionFlag(BoardTask task, SaveContext saveContext) {
        if (task == null || task.getRepairProcess() == null || task.getTaskKind() == null) {
            return;
        }
        RepairProcess process = task.getRepairProcess();
        if (task.getTaskKind() == RepairProcessTaskKind.MOVE_TO_REPAIR) {
            process.setMoveToRepairDone(true);
            process.setMoveToRepairCancelled(false);
            saveContext.saving(process);
        } else if (task.getTaskKind() == RepairProcessTaskKind.MOVE_FROM_REPAIR) {
            process.setMoveFromRepairDone(true);
            process.setMoveFromRepairCancelled(false);
            saveContext.saving(process);
        }
    }

    private void normalizeQueuePositions(WorkQueue queue) {
        List<QueueEntry> entries = dataManager.load(QueueEntry.class)
                .query("select e from QueueEntry e where e.queue = :queue and e.status <> 'DONE' order by e.position")
                .parameter("queue", queue)
                .fetchPlan("_base")
                .list();
        SaveContext saveContext = new SaveContext();
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setPosition(i);
            saveContext.saving(entries.get(i));
        }
        dataManager.save(saveContext);
    }

    private void moveQueue(UUID queueId, int direction) {
        WorkQueue reloaded = reloadQueue(queueId);
        List<WorkQueue> queues = loadQueues(reloaded.getWarehouse());
        int index = -1;
        for (int i = 0; i < queues.size(); i++) {
            if (Objects.equals(queues.get(i).getId(), queueId)) {
                index = i;
                break;
            }
        }
        int target = index + direction;
        if (index < 0 || target < 0 || target >= queues.size()) {
            return;
        }
        WorkQueue current = queues.get(index);
        WorkQueue other = queues.get(target);
        Integer currentOrder = current.getSortOrder();
        current.setSortOrder(other.getSortOrder());
        other.setSortOrder(currentOrder);
        dataManager.save(new SaveContext().saving(current, other));
    }

    private Comparator<WorkQueue> queueComparator() {
        return Comparator.comparing(WorkQueue::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkQueue::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    private boolean isSinkQueue(WorkQueue queue) {
        return queue != null
                && (Boolean.TRUE.equals(queue.getSinkQueue()) || queue.getQueueKind() == WorkQueueKind.HOLDING);
    }

    private int nextQueueSortOrder(Warehouse warehouse) {
        Integer max = dataManager.loadValue("select max(e.sortOrder) from WorkQueue e where e.warehouse = :warehouse", Integer.class)
                .parameter("warehouse", warehouse)
                .optional()
                .orElse(0);
        return valueOrZero(max) + 10;
    }

    private int nextPosition(WorkQueue queue) {
        Integer max = dataManager.loadValue("select max(e.position) from QueueEntry e where e.queue = :queue and e.status <> 'DONE'", Integer.class)
                .parameter("queue", queue)
                .optional()
                .orElse(-1);
        return valueOrZero(max) + 1;
    }

    private String nextQueueCode(Warehouse warehouse, String name) {
        String base = trimToNull(name) == null ? "QUEUE" : name.toUpperCase()
                .replaceAll("[^A-ZА-Я0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isBlank()) {
            base = "QUEUE";
        }
        String candidate = base;
        int index = 1;
        while (dataManager.load(WorkQueue.class)
                .query("select e from WorkQueue e where e.warehouse = :warehouse and e.code = :code")
                .parameter("warehouse", warehouse)
                .parameter("code", candidate)
                .optional()
                .isPresent()) {
            candidate = base + "_" + index++;
        }
        return candidate;
    }

    private WorkQueue reloadQueue(UUID id) {
        return dataManager.load(WorkQueue.class)
                .id(id)
                .fetchPlan(workQueueFetchPlan())
                .one();
    }

    private QueueEntry reloadEntry(UUID id) {
        return dataManager.load(QueueEntry.class).id(id).fetchPlan(queueEntryFetchPlan()).one();
    }

    private TaskTimeEvent timeEvent(QueueEntry entry, Worker worker, TaskTimeEventType type, String reason, OffsetDateTime createdAt) {
        TaskTimeEvent event = dataManager.create(TaskTimeEvent.class);
        event.setQueueEntry(entry);
        event.setWorker(worker);
        event.setEventType(type);
        event.setReason(reason);
        event.setCreatedAt(createdAt);
        return event;
    }

    private long currentActiveSeconds(QueueEntry entry) {
        long seconds = valueOrZero(entry.getActiveWorkSeconds());
        if (entry.getStatus() == QueueEntryStatus.IN_PROGRESS && entry.getActiveStartedAt() != null) {
            seconds += Math.max(0, Duration.between(entry.getActiveStartedAt(), OffsetDateTime.now()).getSeconds());
        }
        return seconds;
    }

    private Integer totalPlannedDurationMinutes(List<QueueTaskStep> route) {
        if (route == null || route.isEmpty()) {
            return null;
        }
        int total = route.stream()
                .map(QueueTaskStep::plannedDurationMinutes)
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .mapToInt(Integer::intValue)
                .sum();
        return total > 0 ? total : null;
    }

    private Set<WorkerClass> safeWorkerClasses(Set<WorkerClass> workerClasses) {
        if (workerClasses == null) {
            return Set.of();
        }
        return workerClasses.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Warehouse> safeWarehouses(Set<Warehouse> warehouses) {
        if (warehouses == null) {
            return List.of();
        }
        LinkedHashMap<UUID, Warehouse> unique = new LinkedHashMap<>();
        for (Warehouse warehouse : warehouses) {
            if (warehouse == null || warehouse.getId() == null) {
                continue;
            }
            unique.putIfAbsent(warehouse.getId(), warehouse);
        }
        return new ArrayList<>(unique.values());
    }

    private Optional<WorkQueue> findExistingQueueByCode(Warehouse warehouse, String code) {
        if (warehouse == null || warehouse.getId() == null || code == null || code.isBlank()) {
            return Optional.empty();
        }
        return dataManager.load(WorkQueue.class)
                .query("select e from WorkQueue e where e.warehouse = :warehouse and e.code = :code")
                .parameter("warehouse", warehouse)
                .parameter("code", code)
                .fetchPlan(workQueueFetchPlan())
                .optional();
    }

    private void saveQueueWithBindings(WorkQueue queue, List<QueueWorkerClassBinding> bindings) {
        SaveContext saveContext = new SaveContext().saving(queue);
        List<QueueWorkerClassBinding> desiredBindings = safeBindings(bindings);
        Set<UUID> desiredClassIds = desiredBindings.stream()
                .map(binding -> binding.workerClass().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, WorkQueueWorkerGroup> existingByClassId = new LinkedHashMap<>();
        if (queue.getId() != null) {
            List<WorkQueueWorkerGroup> oldBindings = dataManager.load(WorkQueueWorkerGroup.class)
                    .query("select e from WorkQueueWorkerGroup e where e.queue = :queue")
                    .parameter("queue", queue)
                    .list();
            for (WorkQueueWorkerGroup existing : oldBindings) {
                WorkerClass workerClass = existing.getWorkerClass();
                if (workerClass == null || workerClass.getId() == null) {
                    saveContext.removing(existing);
                    continue;
                }
                existingByClassId.putIfAbsent(workerClass.getId(), existing);
                if (!desiredClassIds.contains(workerClass.getId())) {
                    saveContext.removing(existing);
                }
            }
        }
        for (QueueWorkerClassBinding bindingOption : desiredBindings) {
            WorkQueueWorkerGroup binding = existingByClassId.get(bindingOption.workerClass().getId());
            if (binding == null) {
                binding = dataManager.create(WorkQueueWorkerGroup.class);
                binding.setQueue(queue);
                binding.setWorkerClass(bindingOption.workerClass());
            }
            binding.setStopTaskOnTake(bindingOption.stopTaskOnTake());
            saveContext.saving(binding);
        }
        dataManager.save(saveContext);
    }

    private WorkQueue configureQueue(WorkQueue queue, String name, String description, WorkQueueKind queueKind, Integer holdingPeriodMinutes,
                                     Integer threshold, boolean notifyWhenThresholdReached, List<QueueWorkerClassBinding> bindings) {
        WorkQueueKind kind = queueKind == null ? WorkQueueKind.REPAIR : queueKind;
        boolean sinkQueue = kind == WorkQueueKind.HOLDING;
        queue.setName(required(name, "Введите название очереди"));
        queue.setDescription(trimToNull(description));
        queue.setSinkQueue(sinkQueue);
        queue.setQueueKind(kind);
        queue.setNotificationThreshold(sinkQueue ? threshold : null);
        queue.setNotifyWhenThresholdReached(sinkQueue && notifyWhenThresholdReached);
        queue.setHoldingPeriodMinutes(sinkQueue ? holdingPeriodMinutes : null);
        saveQueueWithBindings(queue, bindings);
        return queue;
    }

    private boolean shouldStopCurrentTaskOnTake(WorkQueue queue, WorkerGroup workerGroup) {
        if (queue == null || queue.getId() == null || workerGroup == null || workerGroup.getWorkerClass() == null) {
            return false;
        }
        return dataManager.load(WorkQueueWorkerGroup.class)
                .query("""
                        select e from WorkQueueWorkerGroup e
                        where e.queue = :queue
                          and e.workerClass = :workerClass
                        """)
                .parameter("queue", queue)
                .parameter("workerClass", workerGroup.getWorkerClass())
                .optional()
                .map(binding -> Boolean.TRUE.equals(binding.getStopTaskOnTake()))
                .orElse(false);
    }

    private List<QueueWorkerClassBinding> safeBindings(List<QueueWorkerClassBinding> bindings) {
        if (bindings == null) {
            return List.of();
        }
        LinkedHashMap<UUID, QueueWorkerClassBinding> unique = new LinkedHashMap<>();
        for (QueueWorkerClassBinding binding : bindings) {
            if (binding == null || binding.workerClass() == null || binding.workerClass().getId() == null) {
                continue;
            }
            unique.put(binding.workerClass().getId(), new QueueWorkerClassBinding(binding.workerClass(), binding.stopTaskOnTake()));
        }
        return new ArrayList<>(unique.values());
    }

    private String normalizeQueueCode(String code) {
        return required(code, "Введите код очереди").trim().toUpperCase(Locale.ROOT);
    }

    private Warehouse requireWarehouse(Warehouse warehouse) {
        if (warehouse == null || warehouse.getId() == null) {
            throw new IllegalArgumentException("Выберите склад");
        }
        return dataManager.load(Warehouse.class).id(warehouse.getId()).fetchPlan("_base").one();
    }

    private boolean sameWarehouse(Warehouse left, Warehouse right) {
        return left != null && right != null && Objects.equals(left.getId(), right.getId());
    }

    private boolean sameId(WorkQueue left, WorkQueue right) {
        return left != null && right != null && Objects.equals(left.getId(), right.getId());
    }

    private String required(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0 : value;
    }

    private io.jmix.core.FetchPlan queueEntryFetchPlan() {
        return fetchPlans.builder(QueueEntry.class)
                .addFetchPlan("_base")
                .add("task", builder -> builder
                        .addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("rentalItem", builder1 -> builder1.addFetchPlan("_base")
                                .add("warehouse", "_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")
                                .add("type", "_base")
                                .add("condition", "_base"))
                        .add("repairProcess", builder1 -> builder1.addFetchPlan("_base")
                                .add("estimate", builder2 -> builder2.addFetchPlan("_base")
                                        .add("rentalItem", "_base")
                                        .add("warehouse", "_base"))
                                .add("rentalItem", builder2 -> builder2.addFetchPlan("_base")
                                        .add("warehouse", "_base")
                                        .add("category", "_base")
                                        .add("subcategory", "_base")
                                        .add("type", "_base")
                                        .add("condition", "_base"))
                                .add("warehouse", "_base")))
                .add("queue", builder -> builder
                        .addFetchPlan("_base")
                        .add("warehouse", "_base"))
                .build();
    }

    private io.jmix.core.FetchPlan workQueueFetchPlan() {
        return fetchPlans.builder(WorkQueue.class)
                .addFetchPlan("_base")
                .add("warehouse", "_base")
                .build();
    }

    private io.jmix.core.FetchPlan rentalItemFetchPlan() {
        return fetchPlans.builder(RentalItem.class)
                .addFetchPlan("_base")
                .add("warehouse", "_base")
                .build();
    }

    private io.jmix.core.FetchPlan taskAssignmentFetchPlan() {
        return fetchPlans.builder(TaskAssignment.class)
                .addFetchPlan("_base")
                .add("queueEntry", builder -> builder
                        .addFetchPlan("_base")
                        .add("task", "_base")
                        .add("queue", queueBuilder -> queueBuilder
                                .addFetchPlan("_base")
                                .add("warehouse", "_base")))
                .add("workerGroup", builder -> builder
                        .addFetchPlan("_base")
                        .add("workerClass", "_base"))
                .add("worker", "_base")
                .build();
    }

    private io.jmix.core.FetchPlan workerGroupFetchPlan() {
        return fetchPlans.builder(WorkerGroup.class)
                .addFetchPlan("_base")
                .add("warehouse", "_base")
                .add("workerClass", "_base")
                .build();
    }

    private io.jmix.core.FetchPlan workQueueWorkerGroupFetchPlan() {
        return fetchPlans.builder(WorkQueueWorkerGroup.class)
                .addFetchPlan("_base")
                .add("queue", builder -> builder
                        .addFetchPlan("_base")
                        .add("warehouse", "_base"))
                .add("workerClass", "_base")
                .build();
    }

    private io.jmix.core.FetchPlan workerGroupMemberFetchPlan() {
        return fetchPlans.builder(WorkerGroupMember.class)
                .addFetchPlan("_base")
                .add("workerGroup", builder -> builder
                        .addFetchPlan("_base")
                        .add("workerClass", "_base"))
                .add("worker", builder -> builder
                        .addFetchPlan("_base")
                        .add("warehouse", "_base"))
                .build();
    }

    public record BoardState(List<QueueColumn> columns) {
    }

    public record QueueColumn(WorkQueue queue, List<EntryCard> cards, List<String> workerGroups) {
    }

    public record EntryCard(QueueEntry entry, QueueEntryType effectiveEntryType, List<RouteChip> route, List<AssignmentInfo> assignments,
                            long activeSeconds, int plannedMinutes) {
    }

    public record RouteChip(String queueName, boolean current, QueueEntryStatus status) {
    }

    public record AssignmentInfo(String workerName, String avatarUrl, String groupName) {
    }

    public record CreateTaskCommand(RentalItem rentalItem, String description, OffsetDateTime deadlineAt,
                                    List<QueueTaskStep> routeSteps, RepairProcess repairProcess,
                                    RepairProcessTaskKind taskKind) {
    }

    public record QueueTaskStep(WorkQueue queue, String taskText, Integer plannedDurationMinutes) {
    }

    public record QueueWorkerClassBinding(WorkerClass workerClass, boolean stopTaskOnTake) {
    }
}
