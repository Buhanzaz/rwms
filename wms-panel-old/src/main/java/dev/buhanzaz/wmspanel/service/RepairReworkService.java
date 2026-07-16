package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessKind;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.WorkerGroupMember;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RepairReworkService {

    private final DataManager dataManager;
    private final QueueBoardService queueBoardService;
    private final RepairProcessService repairProcessService;

    public RepairReworkService(DataManager dataManager,
                               QueueBoardService queueBoardService,
                               RepairProcessService repairProcessService) {
        this.dataManager = dataManager;
        this.queueBoardService = queueBoardService;
        this.repairProcessService = repairProcessService;
    }

    @Transactional
    public CreateReworkResult createRework(CreateReworkCommand command) {
        if (command == null || command.sourceProcessId() == null) {
            throw new IllegalArgumentException("Не выбран исходный ремонтный процесс");
        }
        if (command.queueId() == null) {
            throw new IllegalArgumentException("Не выбрана очередь для доработки");
        }

        RepairProcess currentSource = loadProcess(command.sourceProcessId());
        if (currentSource.getStatus() != RepairProcessStatus.AFTER_REPAIR
                && currentSource.getStatus() != RepairProcessStatus.REWORK) {
            throw new IllegalArgumentException("Доработку можно открыть только из процесса на приемке");
        }
        RepairProcess rootSource = rootSource(currentSource);
        ensureNoActiveRework(rootSource.getId());

        WorkQueue queue = dataManager.load(WorkQueue.class)
                .id(command.queueId())
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .one();
        if (queue.getWarehouse() == null || currentSource.getWarehouse() == null
                || !Objects.equals(queue.getWarehouse().getId(), currentSource.getWarehouse().getId())) {
            throw new IllegalArgumentException("Очередь доработки должна относиться к тому же складу");
        }

        WorkerGroup workerGroup = loadWorkerGroup(command.workerGroupId(), currentSource);
        Worker worker = loadWorker(command.workerId(), currentSource);
        validateMembership(workerGroup, worker);

        rootSource.setStatus(RepairProcessStatus.REWORK);
        dataManager.save(rootSource);

        RepairProcess reworkProcess = dataManager.create(RepairProcess.class);
        reworkProcess.setEstimate(null);
        reworkProcess.setRentalItem(currentSource.getRentalItem());
        reworkProcess.setWarehouse(currentSource.getWarehouse());
        reworkProcess.setStatus(RepairProcessStatus.ACTIVE);
        reworkProcess.setProcessKind(RepairProcessKind.REWORK);
        reworkProcess.setSourceProcess(rootSource);
        reworkProcess.setRequestedWorkerGroup(workerGroup);
        reworkProcess.setRequestedWorker(worker);
        reworkProcess.setComment(trimToNull(command.comment()));
        RepairProcess savedProcess = dataManager.save(reworkProcess);

        String description = buildTaskDescription(currentSource, command.comment(), workerGroup, worker);
        BoardTask boardTask = queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                currentSource.getRentalItem(),
                description,
                null,
                List.of(new QueueBoardService.QueueTaskStep(queue, description, null)),
                savedProcess,
                RepairProcessTaskKind.REWORK));

        repairProcessService.markRentalItemInRepair(savedProcess);
        return new CreateReworkResult(savedProcess, boardTask);
    }

    private RepairProcess loadProcess(UUID processId) {
        return dataManager.load(RepairProcess.class)
                .id(processId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", builder1 -> builder1.addFetchPlan("_base").add("warehouse", "_base"))
                        .add("warehouse", "_base")
                        .add("sourceProcess", "_base"))
                .one();
    }

    private RepairProcess rootSource(RepairProcess process) {
        if (process.getProcessKind() == RepairProcessKind.REWORK
                && process.getSourceProcess() != null
                && process.getSourceProcess().getId() != null) {
            return dataManager.load(RepairProcess.class)
                    .id(process.getSourceProcess().getId())
                    .fetchPlan(builder -> builder.addFetchPlan("_base")
                            .add("rentalItem", "_base")
                            .add("warehouse", "_base"))
                    .one();
        }
        return process;
    }

    private void ensureNoActiveRework(UUID sourceProcessId) {
        Long count = dataManager.loadValue("""
                        select count(e) from RepairProcess e
                        where e.sourceProcess.id = :sourceProcessId
                          and e.processKind = dev.buhanzaz.wmspanel.entity.RepairProcessKind.REWORK
                          and e.status = dev.buhanzaz.wmspanel.entity.RepairProcessStatus.ACTIVE
                        """, Long.class)
                .parameter("sourceProcessId", sourceProcessId)
                .one();
        if (count != null && count > 0) {
            throw new IllegalArgumentException("Для этого ремонта уже есть незавершенная доработка");
        }
    }

    private WorkerGroup loadWorkerGroup(UUID workerGroupId, RepairProcess process) {
        if (workerGroupId == null) {
            return null;
        }
        WorkerGroup workerGroup = dataManager.load(WorkerGroup.class)
                .id(workerGroupId)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .one();
        if (workerGroup.getWarehouse() == null || process.getWarehouse() == null
                || !Objects.equals(workerGroup.getWarehouse().getId(), process.getWarehouse().getId())) {
            throw new IllegalArgumentException("Бригада должна относиться к тому же складу, что и ремонт");
        }
        return workerGroup;
    }

    private Worker loadWorker(UUID workerId, RepairProcess process) {
        if (workerId == null) {
            return null;
        }
        Worker worker = dataManager.load(Worker.class)
                .id(workerId)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .one();
        if (worker.getWarehouse() == null || process.getWarehouse() == null
                || !Objects.equals(worker.getWarehouse().getId(), process.getWarehouse().getId())) {
            throw new IllegalArgumentException("Рабочий должен относиться к тому же складу, что и ремонт");
        }
        return worker;
    }

    private void validateMembership(WorkerGroup workerGroup, Worker worker) {
        if (workerGroup == null || worker == null) {
            return;
        }
        Long count = dataManager.loadValue("""
                        select count(e) from WorkerGroupMember e
                        where e.workerGroup = :workerGroup
                          and e.worker = :worker
                          and e.active = true
                        """, Long.class)
                .parameter("workerGroup", workerGroup)
                .parameter("worker", worker)
                .one();
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Выбранный рабочий не состоит в указанной бригаде");
        }
    }

    private String buildTaskDescription(RepairProcess sourceProcess,
                                        String comment,
                                        WorkerGroup workerGroup,
                                        Worker worker) {
        StringBuilder description = new StringBuilder("Доработка после ремонта");
        if (sourceProcess.getRentalItem() != null && sourceProcess.getRentalItem().getNumber() != null) {
            description.append(": ").append(sourceProcess.getRentalItem().getNumber());
        }
        String trimmedComment = trimToNull(comment);
        if (trimmedComment != null) {
            description.append(" | ").append(trimmedComment);
        }
        if (workerGroup != null && workerGroup.getName() != null && !workerGroup.getName().isBlank()) {
            description.append(" | Бригада: ").append(workerGroup.getName().trim());
        }
        if (worker != null && worker.getDisplayName() != null && !worker.getDisplayName().isBlank()) {
            description.append(" | Исполнитель: ").append(worker.getDisplayName().trim());
        }
        return description.toString();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CreateReworkCommand(UUID sourceProcessId,
                                      UUID queueId,
                                      UUID workerGroupId,
                                      UUID workerId,
                                      String comment) {
    }

    public record CreateReworkResult(RepairProcess process, BoardTask boardTask) {
    }
}
